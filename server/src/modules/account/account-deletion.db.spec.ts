import { randomUUID } from 'node:crypto';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { PrismaClient } from '@prisma/client';
import IORedis from 'ioredis';
import { JwtService } from '@nestjs/jwt';
import type { AppEnv } from '../../config/env';
import type { PrismaService } from '../../prisma/prisma.service';
import { AuthService } from '../auth/auth.service';
import { blobKeyFor } from '../media/blob-store';
import { LocalBlobStore } from '../media/local-blob-store';
import { AccountDeletionService } from './account-deletion.service';
import { DELETION_INVENTORY } from './deletion-inventory';

/**
 * FR-SET-04, against real Postgres, a real blob store and real Redis.
 *
 * `docs/BUILD_PROMPT_M9.md` is explicit about what this suite has to do:
 *
 * > *"Make the test assert on the **absence** of each one rather than on the endpoint
 * > returning 204."*
 *
 * So there is no 204 anywhere below. Every assertion counts rows in a named table, asks the
 * blob store whether an object exists, or reads a Redis key. The table list is **iterated from
 * `DELETION_INVENTORY`** rather than typed out, so a table added to the inventory is a table
 * this suite starts checking without anybody remembering to add an assertion —
 * `deletion-coverage.spec.ts` is the other half, and makes adding it compulsory.
 *
 * Every person below is invented. The repo is public (`MILESTONE_CHECKLIST.md` §F).
 */

/**
 * A marker unique to THIS run of THIS suite.
 *
 * Jest runs suites in parallel workers against **one** database, and the first version of this
 * file used `pricingVersion: 'spec'` — which `poster-pipeline.db.spec.ts` also writes. Two
 * consequences, and the second is the worse one:
 *
 * 1. `findMany({ where: { pricingVersion: 'spec' } })` here returned the other suite's rows too,
 *    so `expect(usage).toHaveLength(1)` failed whenever the two overlapped.
 * 2. The `afterEach` cleanup `deleteMany({ where: { pricingVersion: 'spec' } })` **deleted the
 *    other suite's ledger rows mid-test** — which also moved the global spend total that
 *    `drafting.db.spec.ts`'s cap tests read, and made those flake too.
 *
 * One marker bug, two flaky suites, two tests failing in five runs. Everything this file writes
 * to a shared table now carries an id nothing else can collide with, and every query and every
 * cleanup is scoped to it.
 */
const MARKER = `deletion-spec-${randomUUID()}`;

const prisma = new PrismaClient();
const db = prisma as unknown as PrismaService;
const redis = new IORedis(process.env.REDIS_URL ?? 'redis://localhost:6379', {
  maxRetriesPerRequest: null,
  enableReadyCheck: false,
});

let root: string;
let blobs: LocalBlobStore;
let service: AccountDeletionService;

/** The account under test, and a second one that must be untouched by any of it. */
interface Seeded {
  userId: string;
  personId: string;
  photoKey: string;
  posterKey: string;
  fcmToken: string;
  generationId: string;
}

async function seedAccount(label: string): Promise<Seeded> {
  const user = await prisma.user.create({
    data: { googleSub: `deletion-spec-${label}-${randomUUID()}`, email: `${label}@example.invalid` },
  });
  const person = await prisma.person.create({
    data: { userId: user.id, displayName: 'Meera Iyer', relationToOwner: 'Friend', phone: '+91 98200 11111' },
  });
  await prisma.personTrait.create({
    data: { userId: user.id, personId: person.id, kind: 'HOBBY', value: 'tabla' },
  });
  const event = await prisma.event.create({
    data: {
      userId: user.id,
      personId: person.id,
      type: 'BIRTHDAY',
      sentiment: 'CELEBRATORY',
      day: 3,
      month: 5,
      year: 1990,
    },
  });
  // `event_occurrence` has NO user_id. It is reached only through `event`, which is exactly
  // why it gets seeded and asserted on directly rather than assumed to follow the pattern.
  const occurrence = await prisma.eventOccurrence.create({
    data: { eventId: event.id, occurrenceYear: 2027, occursOn: new Date('2027-05-03') },
  });
  await prisma.reminder.create({
    data: {
      userId: user.id,
      eventOccurrenceId: occurrence.id,
      offset: 'D0',
      scheduledAtUtc: new Date('2027-05-03T03:30:00Z'),
      dedupeKey: `deletion-spec-${randomUUID()}`,
    },
  });

  const fcmToken = `fcm-${randomUUID()}`;
  await prisma.device.create({ data: { userId: user.id, fcmToken, platform: 'ANDROID' } });
  await prisma.refreshToken.create({
    data: { userId: user.id, tokenHash: `hash-${randomUUID()}`, expiresAt: new Date(Date.now() + 86_400_000) },
  });
  await prisma.wish.create({
    data: {
      userId: user.id,
      personId: person.id,
      eventType: 'BIRTHDAY',
      occurrenceYear: 2026,
      sentiment: 'CELEBRATORY',
      messageText: 'Many happy returns.',
      tone: 'WARM',
      language: 'ENGLISH',
      channel: 'WHATSAPP',
      source: 'AI',
      sentAt: new Date(),
    },
  });

  const generation = await prisma.generation.create({
    data: {
      userId: user.id,
      feature: 'poster',
      status: 'SUCCEEDED',
      costInr: 0.0089,
      promptVersion: MARKER,
    },
  });
  await prisma.aiUsage.create({
    data: {
      userId: user.id,
      generationId: generation.id,
      feature: 'poster',
      provider: 'openai',
      model: 'gpt-image-1',
      outcome: 'ok',
      inputTokens: 100,
      outputTokens: 20,
      cacheReadTokens: 0,
      cacheWriteTokens: 0,
      /**
       * Deliberately near zero.
       *
       * §10 rule 6's cap is GLOBAL — `SpendCapService.spentToday()` sums every row in this
       * table for the day — and jest runs suites in parallel workers against one database. The
       * first version of this suite seeded ₹7.12 a row and pushed `drafting.db.spec.ts`'s
       * "lets a draft through while there is budget left" over its ceiling in CI, while
       * passing locally. That suite now computes its headroom from the live baseline; this
       * stays small anyway, because a fixture that can move another suite's result is a
       * fixture that will.
       */
      costUsd: '0.0001',
      usdInrRate: '89',
      costInr: '0.0089',
      pricingVersion: MARKER,
    },
  });
  await prisma.contentReport.create({
    data: { userId: user.id, feature: 'poster', reason: 'OTHER', detail: MARKER },
  });

  // Two objects with real bytes: a source photo and a poster.
  const photoKey = blobKeyFor('photos', randomUUID().replace(/-/g, ''), 'jpg');
  await blobs.put(photoKey, Buffer.from('photo-bytes'), 'image/jpeg');
  const photoAsset = await prisma.asset.create({
    data: {
      userId: user.id,
      personId: person.id,
      kind: 'SOURCE_PHOTO',
      blobKey: photoKey,
      contentType: 'image/jpeg',
      bytes: 11,
    },
  });
  await prisma.person.update({ where: { id: person.id }, data: { photoKey } });

  const posterKey = blobKeyFor('posters', randomUUID().replace(/-/g, ''), 'png');
  await blobs.put(posterKey, Buffer.from('poster-bytes'), 'image/png');
  const posterAsset = await prisma.asset.create({
    data: {
      userId: user.id,
      personId: person.id,
      kind: 'POSTER',
      blobKey: posterKey,
      contentType: 'image/png',
      bytes: 12,
    },
  });
  await prisma.poster.create({
    data: {
      userId: user.id,
      personId: person.id,
      generationId: generation.id,
      themeId: 'spec-theme',
      family: 'CELEBRATORY',
      sentiment: 'CELEBRATORY',
      eventType: 'BIRTHDAY',
      format: 'STATUS',
      assetId: posterAsset.id,
      sourceAssetId: photoAsset.id,
    },
  });

  return { userId: user.id, personId: person.id, photoKey, posterKey, fcmToken, generationId: generation.id };
}

/** Rows still pointing at this user, per inventory table. */
async function remainingRows(userId: string): Promise<Record<string, number>> {
  const counts: Record<string, number> = {};
  counts['user'] = await prisma.user.count({ where: { id: userId } });
  counts['person'] = await prisma.person.count({ where: { userId } });
  counts['person_trait'] = await prisma.personTrait.count({ where: { userId } });
  counts['event'] = await prisma.event.count({ where: { userId } });
  /**
   * Counted through its parent, because it has no `user_id` of its own.
   *
   * It was missing from this map in the first version, which made `counts['event_occurrence']`
   * undefined and the inventory loop blow up with "received value must be a number" rather
   * than asserting anything — caught by CI. The table that is hardest to find is the one
   * easiest to leave out of the thing that looks for it.
   */
  counts['event_occurrence'] = await prisma.eventOccurrence.count({ where: { event: { userId } } });
  counts['reminder'] = await prisma.reminder.count({ where: { userId } });
  counts['device'] = await prisma.device.count({ where: { userId } });
  counts['refresh_token'] = await prisma.refreshToken.count({ where: { userId } });
  counts['wish'] = await prisma.wish.count({ where: { userId } });
  counts['poster'] = await prisma.poster.count({ where: { userId } });
  counts['asset'] = await prisma.asset.count({ where: { userId } });
  counts['generation'] = await prisma.generation.count({ where: { userId } });
  counts['ai_usage'] = await prisma.aiUsage.count({ where: { userId } });
  counts['content_report'] = await prisma.contentReport.count({ where: { userId } });
  return counts;
}

beforeAll(async () => {
  try {
    await prisma.$connect();
    await redis.ping();
  } catch (cause) {
    throw new Error(`This suite needs Postgres and Redis. Cause: ${String(cause)}`);
  }
});

afterAll(async () => {
  await prisma.$disconnect();
  redis.disconnect();
});

beforeEach(async () => {
  root = await mkdtemp(join(tmpdir(), 'wishkaro-deletion-spec-'));
  blobs = new LocalBlobStore(root, 'spec-signing-secret-at-least-32-characters');
  service = new AccountDeletionService(db, blobs, redis);
});

afterEach(async () => {
  await rm(root, { recursive: true, force: true });
  /**
   * Anonymised rows survive the user and would otherwise accumulate across runs.
   *
   * **Scoped to THIS suite's marker.** A cleanup wider than the thing it seeded is a cleanup
   * that deletes another suite's rows — which is how this file made two other suites flaky.
   */
  await prisma.aiUsage.deleteMany({ where: { pricingVersion: MARKER } });
  await prisma.generation.deleteMany({ where: { promptVersion: MARKER } });
  await prisma.contentReport.deleteMany({ where: { detail: MARKER } });
});

describe('what a deletion must leave behind: nothing', () => {
  it('removes every row in the inventory that is marked CASCADE', async () => {
    const me = await seedAccount('cascade');

    // Precondition, or the assertions below pass for the wrong reason — the vacuous-green
    // shape this project has shipped twice.
    const before = await remainingRows(me.userId);
    for (const entry of DELETION_INVENTORY) {
      expect(before[entry.table]).toBeGreaterThan(0);
    }

    await service.deleteAccount(me.userId);

    const after = await remainingRows(me.userId);
    for (const entry of DELETION_INVENTORY) {
      // Both dispositions end with no row pointing at this user: CASCADE because the row is
      // gone, ANONYMISE because the id is null. The difference is asserted separately below.
      expect({ table: entry.table, rows: after[entry.table] }).toEqual({ table: entry.table, rows: 0 });
    }
  });

  /**
   * `event_occurrence` has **no `user_id` at all**. It is reached only through `event`, so it
   * is the one table in the schema that a `WHERE user_id = ?` sweep cannot see and the easiest
   * to lose. Asserted by id rather than by owner for exactly that reason.
   */
  it('removes event_occurrence, which has no user_id to find it by', async () => {
    const me = await seedAccount('occurrence');
    const occurrences = await prisma.eventOccurrence.findMany({
      where: { event: { userId: me.userId } },
      select: { id: true },
    });
    expect(occurrences).toHaveLength(1);

    await service.deleteAccount(me.userId);

    expect(await prisma.eventOccurrence.count({ where: { id: { in: occurrences.map((o) => o.id) } } })).toBe(0);
  });

  /**
   * **The bytes.** `docs/BUILD_PROMPT_M9.md`: *"a deletion that leaves photographs in a blob
   * store is not a deletion; it is a database change."*
   */
  it('removes the objects, not only the rows that point at them', async () => {
    const me = await seedAccount('blobs');
    expect(await blobs.exists(me.photoKey)).toBe(true);
    expect(await blobs.exists(me.posterKey)).toBe(true);

    const summary = await service.deleteAccount(me.userId);

    expect(summary.blobsDeleted).toBe(2);
    expect(summary.blobsFailed).toBe(0);
    expect(await blobs.exists(me.photoKey)).toBe(false);
    expect(await blobs.exists(me.posterKey)).toBe(false);
  });

  it('removes a TOMBSTONED asset’s bytes too — a deleted photo is still a photograph', async () => {
    const me = await seedAccount('tombstoned');
    await prisma.asset.updateMany({ where: { userId: me.userId }, data: { deletedAt: new Date() } });

    const summary = await service.deleteAccount(me.userId);

    expect(summary.blobsDeleted).toBe(2);
    expect(await blobs.exists(me.photoKey)).toBe(false);
  });

  /**
   * **The FCM token.** The build prompt names the failure in full: *"a reminder about
   * somebody's late father, pushed to a phone whose owner deleted their account last week."*
   *
   * Asserted on the token itself, not on the user id — `fcm_token` is UNIQUE across the whole
   * table, so this says no row anywhere can be used to push to that device.
   */
  it('leaves no device row anywhere holding that FCM token', async () => {
    const me = await seedAccount('fcm');
    expect(await prisma.device.count({ where: { fcmToken: me.fcmToken } })).toBe(1);

    await service.deleteAccount(me.userId);

    expect(await prisma.device.count({ where: { fcmToken: me.fcmToken } })).toBe(0);
    // And the send loop reads devices by user, so there is nothing for it to find either.
    expect(await prisma.device.count({ where: { userId: me.userId } })).toBe(0);
  });

  it('removes the Redis rate-limit counters that carry the user id', async () => {
    const me = await seedAccount('redis');
    const hour = new Date().toISOString().slice(0, 13);
    const day = new Date().toISOString().slice(0, 10);
    const keys = [
      `wishdraft:h:${me.userId}:${hour}`,
      `wishdraft:d:${me.userId}:${day}`,
      `poster:h:${me.userId}:${hour}`,
      `postercopy:d:${me.userId}:${day}`,
      `photo:h:${me.userId}:${hour}`,
    ];
    for (const key of keys) await redis.set(key, '3', 'EX', 3600);

    const summary = await service.deleteAccount(me.userId);

    expect(summary.redisKeysDeleted).toBe(keys.length);
    for (const key of keys) {
      expect(await redis.exists(key)).toBe(0);
    }
  });
});

describe('what a deletion must keep: the ledger, anonymised', () => {
  /**
   * Dipen's decision 3. DPDP's erasure right says no row may identify the user; §10 rule 2 and
   * FR-POS-10 say the cost rows are non-negotiable. Nulling the id satisfies both — and the
   * schema had already resolved it the other way, by an `onDelete: Cascade` nobody chose.
   */
  it('keeps ai_usage and generation with a null user_id, not deleted', async () => {
    const me = await seedAccount('ledger');

    const summary = await service.deleteAccount(me.userId);

    expect(summary.rowsAnonymised).toBe(3);

    const usage = await prisma.aiUsage.findMany({ where: { pricingVersion: MARKER } });
    expect(usage).toHaveLength(1);
    expect(usage[0]?.userId).toBeNull();
    // The money is the point. It survives intact.
    expect(usage[0]?.costInr.toString()).toBe('0.0089');

    const generation = await prisma.generation.findUnique({ where: { id: me.generationId } });
    expect(generation).not.toBeNull();
    expect(generation?.userId).toBeNull();
  });

  it('keeps the content report, without the reporter', async () => {
    const me = await seedAccount('report');

    await service.deleteAccount(me.userId);

    const reports = await prisma.contentReport.findMany({ where: { detail: MARKER } });
    expect(reports).toHaveLength(1);
    expect(reports[0]?.userId).toBeNull();
    // What was reported is the evidence; it stays.
    expect(reports[0]?.feature).toBe('poster');
  });

  /**
   * The database enforces this, not the service.
   *
   * The migration changed `ON DELETE CASCADE` to `SET NULL` precisely so that a deletion
   * written later which forgot to anonymise first could not take the ledger with it. This
   * deletes the user directly, bypassing `AccountDeletionService` entirely, and the rows must
   * still survive.
   */
  it('survives a user.delete() that never went through the service at all', async () => {
    const me = await seedAccount('raw');

    await prisma.user.delete({ where: { id: me.userId } });

    const usage = await prisma.aiUsage.findMany({ where: { pricingVersion: MARKER } });
    expect(usage).toHaveLength(1);
    expect(usage[0]?.userId).toBeNull();
  });
});

describe('what a deletion must not touch', () => {
  it('leaves another account entirely alone', async () => {
    const me = await seedAccount('mine');
    const other = await seedAccount('theirs');

    await service.deleteAccount(me.userId);

    const after = await remainingRows(other.userId);
    for (const entry of DELETION_INVENTORY) {
      // Named in the failure message, so a regression says WHICH table lost rows rather than
      // "expected 1, received 0" thirteen times over.
      expect({ table: entry.table, intact: after[entry.table]! > 0 }).toEqual({
        table: entry.table,
        intact: true,
      });
    }
    expect(await blobs.exists(other.photoKey)).toBe(true);
    expect(await blobs.exists(other.posterKey)).toBe(true);

    await service.deleteAccount(other.userId);
  });

  it('leaves theme_cache and its shared artwork alone', async () => {
    // §10 rule 5, docs/M6_PLAN.md §3.6. Product art, not a user's media.
    const me = await seedAccount('themes');
    const themeKey = `deletion-spec-${randomUUID()}`;
    const artKey = blobKeyFor('themes', randomUUID().replace(/-/g, ''), 'png');
    await blobs.put(artKey, Buffer.from('shared-artwork'), 'image/png');
    await prisma.themeCache.create({
      data: {
        themeKey,
        themeId: 'deletion-spec-theme',
        family: 'CELEBRATORY',
        blobKey: artKey,
        eventType: 'BIRTHDAY',
        palette: 'warm',
        aspectRatio: '9:16',
        approved: true,
      },
    });

    await service.deleteAccount(me.userId);

    expect(await prisma.themeCache.count({ where: { themeKey } })).toBe(1);
    expect(await blobs.exists(artKey)).toBe(true);

    await prisma.themeCache.deleteMany({ where: { themeKey } });
  });
});

describe('when the objects cannot be removed', () => {
  /**
   * **Bytes before rows, in one direction only.**
   *
   * A row deleted before its object leaves bytes unreachable by anything that will ever run —
   * `BlobStore` has no `list()`. So a failing store must leave the account intact and the
   * request repeatable, which is the opposite of what an endpoint returning 204 would say.
   */
  it('destroys nothing at all, and the retry finishes the job', async () => {
    const me = await seedAccount('failing');

    const failing = new AccountDeletionService(
      db,
      {
        storeName: 'exploding',
        isDurable: false,
        put: blobs.put.bind(blobs),
        get: blobs.get.bind(blobs),
        exists: blobs.exists.bind(blobs),
        delete: async () => {
          throw new Error('store unavailable');
        },
        signedUrl: blobs.signedUrl.bind(blobs),
      },
      redis,
    );

    await expect(failing.deleteAccount(me.userId)).rejects.toThrow(/could not be removed/);

    // Every row is still there to be found by, which is the whole point of the ordering.
    expect(await prisma.person.count({ where: { userId: me.userId } })).toBe(1);
    expect(await prisma.asset.count({ where: { userId: me.userId } })).toBe(2);
    expect(await prisma.user.count({ where: { id: me.userId } })).toBe(1);
    expect(await blobs.exists(me.photoKey)).toBe(true);

    // The door is shut, though: the account is already signed out everywhere.
    const user = await prisma.user.findUniqueOrThrow({ where: { id: me.userId } });
    expect(user.deletedAt).not.toBeNull();
    expect(await prisma.refreshToken.count({ where: { userId: me.userId, revokedAt: null } })).toBe(0);

    const summary = await service.deleteAccount(me.userId);
    expect(summary.blobsDeleted).toBe(2);
    expect(await prisma.user.count({ where: { id: me.userId } })).toBe(0);
  });
});

describe('the sync protocol after a HARD delete', () => {
  /**
   * `docs/M9_PLAN.md` C8, as the assertion that made it worth fixing.
   *
   * Account deletion is a hard delete; a JWT does not know that. Until M9 the guard verified
   * the signature and nothing else, so for up to `JWT_ACCESS_TTL` a phone holding its last
   * token authenticated on every route after its owner had deleted everything — and a sync
   * push would then foreign-key-violate against a `user_id` that no longer exists, surfacing
   * as a 500. Four entities have cursors that assume rows do not vanish; this is what stops
   * them ever being asked.
   */
  it('refuses an access token that was valid when the account existed', async () => {
    const secret = 'a-spec-access-secret-at-least-32-characters';
    const jwt = new JwtService({});
    const auth = new AuthService(
      { JWT_ACCESS_SECRET: secret, JWT_ACCESS_TTL: '15m', JWT_REFRESH_TTL: '60d' } as unknown as AppEnv,
      { verify: async () => ({}) as never },
      db,
      jwt,
    );

    const me = await seedAccount('token');
    const token = await jwt.signAsync({ sub: me.userId, email: 'token@example.invalid' }, { secret });

    // Still a real session while the account exists.
    await expect(auth.verifyAccessToken(token)).resolves.toMatchObject({ sub: me.userId });

    await service.deleteAccount(me.userId);

    // The signature is still perfectly valid. The account is not.
    await expect(auth.verifyAccessToken(token)).rejects.toMatchObject({ status: 401 });
  });

  it('refuses it from the moment the deletion starts, not only when it finishes', async () => {
    // The deletion soft-deletes before it hard-deletes, so a request arriving mid-deletion is
    // refused rather than racing it. Simulated by stopping at the soft-delete, which is what a
    // failed blob removal leaves behind.
    const secret = 'a-spec-access-secret-at-least-32-characters';
    const jwt = new JwtService({});
    const auth = new AuthService(
      { JWT_ACCESS_SECRET: secret, JWT_ACCESS_TTL: '15m', JWT_REFRESH_TTL: '60d' } as unknown as AppEnv,
      { verify: async () => ({}) as never },
      db,
      jwt,
    );

    const me = await seedAccount('midway');
    const token = await jwt.signAsync({ sub: me.userId, email: 'midway@example.invalid' }, { secret });
    await prisma.user.update({ where: { id: me.userId }, data: { deletedAt: new Date() } });

    await expect(auth.verifyAccessToken(token)).rejects.toMatchObject({ status: 401 });

    await service.deleteAccount(me.userId);
  });
});
