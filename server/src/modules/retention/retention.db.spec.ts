import { randomUUID } from 'node:crypto';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { PrismaClient } from '@prisma/client';
import type { PrismaService } from '../../prisma/prisma.service';
import { LocalBlobStore } from '../media/local-blob-store';
import { blobKeyFor } from '../media/blob-store';
import { RetentionService } from './retention.service';
import { DAY_MS, RETENTION_WARNING_DAYS } from './retention.rules';

/**
 * FR-SET-06's sweep, against **real Postgres and a real blob store**.
 *
 * `retention.rules.spec.ts` proves the decision. This proves the thing the decision is
 * attached to, which is where this codebase actually breaks (`MILESTONE_CHECKLIST.md` §D:
 * *"the test drives the seam, not only the pure function — the pure functions are not where
 * this codebase breaks"*). Specifically it proves the four things a pure rule cannot:
 *
 * 1. The candidate **query** finds what the rules would act on. A `where` clause that
 *    narrowed one case too far would leave a rule correct and never consulted.
 * 2. The **bytes** go, not just the row. The build prompt's first named failure: *"a
 *    deletion that leaves photographs in a blob store is not a deletion; it is a database
 *    change."*
 * 3. `theme_cache` is **untouched** (§10 rule 5, `docs/M6_PLAN.md` §3.6).
 * 4. The **attachment** rule reads `poster.source_asset_id`, which is a column that did not
 *    exist until M9 and whose absence is the whole of `docs/M9_PLAN.md` C3.
 *
 * Every person named below is invented. The repo is public (`MILESTONE_CHECKLIST.md` §F).
 */

const prisma = new PrismaClient();
const db = prisma as unknown as PrismaService;

let root: string;
let blobs: LocalBlobStore;
let service: RetentionService;
let userId: string;
let personId: string;

const NOW = new Date('2026-11-27T10:00:00.000Z');

/** An asset row AND its bytes, so "the row is gone" and "the bytes are gone" are separable. */
async function seedAsset(input: {
  kind: 'POSTER' | 'SOURCE_PHOTO';
  expiresAt: Date | null;
  retention?: 'AUTO' | 'KEEP';
  purgeWarnedAt?: Date | null;
  createdAt?: Date;
}): Promise<{ id: string; blobKey: string }> {
  const id = randomUUID().replace(/-/g, '');
  const blobKey = blobKeyFor(input.kind === 'POSTER' ? 'posters' : 'photos', id, 'png');
  await blobs.put(blobKey, Buffer.from(`bytes-for-${id}`), 'image/png');

  const asset = await prisma.asset.create({
    data: {
      userId,
      personId,
      kind: input.kind,
      blobKey,
      contentType: 'image/png',
      bytes: 16,
      expiresAt: input.expiresAt,
      retention: input.retention ?? 'AUTO',
      purgeWarnedAt: input.purgeWarnedAt ?? null,
      ...(input.createdAt ? { createdAt: input.createdAt } : {}),
    },
  });
  return { id: asset.id, blobKey };
}

async function seedPoster(assetId: string, sourceAssetId: string | null): Promise<string> {
  const row = await prisma.poster.create({
    data: {
      userId,
      personId,
      themeId: 'spec-theme',
      family: 'CELEBRATORY',
      sentiment: 'CELEBRATORY',
      eventType: 'BIRTHDAY',
      format: 'STATUS',
      assetId,
      sourceAssetId,
    },
  });
  return row.id;
}

beforeAll(async () => {
  try {
    await prisma.$connect();
  } catch (cause) {
    throw new Error(`This suite needs Postgres. Cause: ${String(cause)}`);
  }
});

afterAll(async () => {
  await prisma.$disconnect();
});

beforeEach(async () => {
  root = await mkdtemp(join(tmpdir(), 'wishkaro-retention-spec-'));
  blobs = new LocalBlobStore(root, 'spec-signing-secret-at-least-32-characters');
  service = new RetentionService(db, blobs);

  const user = await prisma.user.create({
    data: { googleSub: `retention-spec-${randomUUID()}`, email: 'nobody@example.invalid' },
  });
  userId = user.id;
  const person = await prisma.person.create({
    data: { userId, displayName: 'Meera Iyer', relationToOwner: 'Friend' },
  });
  personId = person.id;
});

afterEach(async () => {
  await prisma.user.deleteMany({ where: { id: userId } });
  await prisma.themeCache.deleteMany({ where: { themeId: 'retention-spec-theme' } });
  await rm(root, { recursive: true, force: true });
});

describe('the sweep removes the bytes, not only the row', () => {
  it('purges an overdue source photo and the object goes with it', async () => {
    const { id, blobKey } = await seedAsset({
      kind: 'SOURCE_PHOTO',
      expiresAt: new Date(NOW.getTime() - DAY_MS),
    });
    await prisma.person.update({ where: { id: personId }, data: { photoKey: blobKey } });

    expect(await blobs.exists(blobKey)).toBe(true);

    const summary = await service.sweep(NOW);

    expect(summary.purged).toBe(1);
    expect(summary.purgedPhotos).toBe(1);
    expect(summary.failed).toBe(0);

    /**
     * The assertion the build prompt names first. A `purged_at` with the object still sitting
     * there is exactly the outcome it calls "a database change" rather than a deletion.
     */
    expect(await blobs.exists(blobKey)).toBe(false);

    const asset = await prisma.asset.findUniqueOrThrow({ where: { id } });
    expect(asset.purgedAt).not.toBeNull();
    // Tombstoned as well, because every read path in the app filters on `deleted_at`.
    expect(asset.deletedAt).not.toBeNull();

    // `person.photo_key` is a denormalised copy and would otherwise point at nothing.
    const person = await prisma.person.findUniqueOrThrow({ where: { id: personId } });
    expect(person.photoKey).toBeNull();
  });

  it('leaves photo_key alone when it points at a NEWER photo than the one being swept', async () => {
    /**
     * The guard that `updateMany ... where: { photoKey: row.blobKey }` exists for.
     *
     * Replacing a photo tombstones the old row and repoints `photo_key` at the new object.
     * Ninety days later the OLD row is swept — and an `update` by person id would blank the
     * key for a photo that is still there, leaving the person with no picture and the bytes
     * orphaned with nothing pointing at them.
     */
    const old = await seedAsset({ kind: 'SOURCE_PHOTO', expiresAt: new Date(NOW.getTime() - DAY_MS) });
    await prisma.asset.update({ where: { id: old.id }, data: { deletedAt: new Date() } });

    const current = await seedAsset({
      kind: 'SOURCE_PHOTO',
      expiresAt: new Date(NOW.getTime() + 80 * DAY_MS),
    });
    await prisma.person.update({ where: { id: personId }, data: { photoKey: current.blobKey } });

    await service.sweep(NOW);

    expect(await blobs.exists(old.blobKey)).toBe(false);
    expect(await blobs.exists(current.blobKey)).toBe(true);

    const person = await prisma.person.findUniqueOrThrow({ where: { id: personId } });
    expect(person.photoKey).toBe(current.blobKey);
  });

  it('is idempotent — a second sweep does nothing and costs nothing', async () => {
    await seedAsset({ kind: 'SOURCE_PHOTO', expiresAt: new Date(NOW.getTime() - DAY_MS) });

    expect((await service.sweep(NOW)).purged).toBe(1);

    const second = await service.sweep(NOW);
    expect(second.purged).toBe(0);
    expect(second.considered).toBe(0);
  });
});

describe('FR-SET-06 — unless attached to a saved poster', () => {
  it('keeps an overdue photo that a saved poster records as its source', async () => {
    const photo = await seedAsset({
      kind: 'SOURCE_PHOTO',
      expiresAt: new Date(NOW.getTime() - 100 * DAY_MS),
    });
    const output = await seedAsset({ kind: 'POSTER', expiresAt: new Date(NOW.getTime() + 100 * DAY_MS) });
    await seedPoster(output.id, photo.id);

    const summary = await service.sweep(NOW);

    expect(summary.purged).toBe(0);
    expect(await blobs.exists(photo.blobKey)).toBe(true);

    const asset = await prisma.asset.findUniqueOrThrow({ where: { id: photo.id } });
    expect(asset.purgedAt).toBeNull();
  });

  it('purges it once the poster holding it is gone', async () => {
    const photo = await seedAsset({
      kind: 'SOURCE_PHOTO',
      expiresAt: new Date(NOW.getTime() - 100 * DAY_MS),
    });
    const output = await seedAsset({ kind: 'POSTER', expiresAt: new Date(NOW.getTime() + 100 * DAY_MS) });
    const posterId = await seedPoster(output.id, photo.id);

    // "A SAVED poster" — one the user still has. A tombstoned poster is not protecting
    // anything, and a photo kept alive by a deleted poster would never be reclaimed.
    await prisma.poster.update({ where: { id: posterId }, data: { deletedAt: new Date() } });

    expect((await service.sweep(NOW)).purged).toBe(1);
    expect(await blobs.exists(photo.blobKey)).toBe(false);
  });

  it('does not let one person\'s poster protect another photo of the same person', async () => {
    /**
     * The precise failure the C3 migration exists to prevent. Two photos of one person; the
     * poster records the SECOND. Before `source_asset_id` the only available rule was "this
     * person has a poster", which would have protected both — and would have meant that one
     * poster froze every photo a person ever had, for ever.
     */
    const stale = await seedAsset({
      kind: 'SOURCE_PHOTO',
      expiresAt: new Date(NOW.getTime() - DAY_MS),
      createdAt: new Date(NOW.getTime() - 200 * DAY_MS),
    });
    const used = await seedAsset({
      kind: 'SOURCE_PHOTO',
      expiresAt: new Date(NOW.getTime() - DAY_MS),
      createdAt: new Date(NOW.getTime() - 150 * DAY_MS),
    });
    const output = await seedAsset({ kind: 'POSTER', expiresAt: new Date(NOW.getTime() + 100 * DAY_MS) });
    await seedPoster(output.id, used.id);

    await service.sweep(NOW);

    expect(await blobs.exists(stale.blobKey)).toBe(false);
    expect(await blobs.exists(used.blobKey)).toBe(true);
  });
});

describe('FR-SET-06 — generated media is warned before it is purged', () => {
  it('warns rather than purges on the first sweep, and purges on a later one', async () => {
    const output = await seedAsset({ kind: 'POSTER', expiresAt: new Date(NOW.getTime() - DAY_MS) });
    const posterId = await seedPoster(output.id, null);

    const first = await service.sweep(NOW);
    expect(first.warned).toBe(1);
    expect(first.purged).toBe(0);
    expect(await blobs.exists(output.blobKey)).toBe(true);

    const warnedAsset = await prisma.asset.findUniqueOrThrow({ where: { id: output.id } });
    expect(warnedAsset.purgeWarnedAt).toEqual(NOW);

    const later = new Date(NOW.getTime() + RETENTION_WARNING_DAYS * DAY_MS);
    const second = await service.sweep(later);
    expect(second.purged).toBe(1);
    expect(second.purgedPosters).toBe(1);
    expect(await blobs.exists(output.blobKey)).toBe(false);

    /**
     * The poster row goes with its image. `PostersService.forPerson` filters on
     * `poster.deleted_at` and then signs the asset's key for whatever survives — it never
     * looks at the asset's state — so without this the person's history would keep showing a
     * card whose image 404s.
     */
    const poster = await prisma.poster.findUniqueOrThrow({ where: { id: posterId } });
    expect(poster.deletedAt).not.toBeNull();
  });

  it('surfaces what was warned, so the user can press Keep while there is still time', async () => {
    const output = await seedAsset({
      kind: 'POSTER',
      expiresAt: new Date(NOW.getTime() + (RETENTION_WARNING_DAYS - 1) * DAY_MS),
    });
    await seedPoster(output.id, null);

    await service.sweep(NOW);

    const { items } = await service.warned(userId);
    expect(items).toHaveLength(1);
    expect(items[0]?.assetId).toBe(output.id);
    expect(items[0]?.warnedAt).toBe(NOW.toISOString());
  });
});

describe('FR-SET-06 — both user-overridable to keep', () => {
  it('never purges a kept asset, and purges it once released', async () => {
    const createdAt = new Date(NOW.getTime() - 200 * DAY_MS);
    const photo = await seedAsset({
      kind: 'SOURCE_PHOTO',
      expiresAt: new Date(NOW.getTime() - 110 * DAY_MS),
      retention: 'KEEP',
      createdAt,
    });

    expect((await service.sweep(NOW)).purged).toBe(0);
    expect(await blobs.exists(photo.blobKey)).toBe(true);

    await service.setRetention(userId, photo.id, 'AUTO');

    expect((await service.sweep(NOW)).purged).toBe(1);
    expect(await blobs.exists(photo.blobKey)).toBe(false);
  });

  it('restores the ORIGINAL deadline on release rather than granting another 90 days', async () => {
    /**
     * Otherwise keep-then-unkeep is an unlimited extension, and the policy page's "90 days"
     * would be a number nothing enforces. The deadline is re-derived from `created_at`, which
     * is the same arithmetic the upload path used.
     */
    const createdAt = new Date(NOW.getTime() - 200 * DAY_MS);
    const photo = await seedAsset({ kind: 'SOURCE_PHOTO', expiresAt: null, retention: 'KEEP', createdAt });

    const result = await service.setRetention(userId, photo.id, 'AUTO');

    expect(result.expiresAt).toBe(new Date(createdAt.getTime() + 90 * DAY_MS).toISOString());
    // Which is already in the past, so it is swept immediately — not 90 days from today.
    expect((await service.sweep(NOW)).purged).toBe(1);
  });

  it("refuses to set retention on somebody else's asset, with the same answer as a missing one", async () => {
    const other = await prisma.user.create({
      data: { googleSub: `retention-spec-other-${randomUUID()}`, email: 'nobody2@example.invalid' },
    });
    const photo = await seedAsset({ kind: 'SOURCE_PHOTO', expiresAt: NOW });

    await expect(service.setRetention(other.id, photo.id, 'KEEP')).rejects.toMatchObject({
      status: 404,
    });
    await expect(service.setRetention(userId, randomUUID(), 'KEEP')).rejects.toMatchObject({
      status: 404,
    });

    await prisma.user.delete({ where: { id: other.id } });
  });
});

describe('what the sweep must never touch', () => {
  /**
   * §10 rule 5 says background artwork is cached and "reused indefinitely"; FR-SET-06 purges
   * generated media at 180 days. `docs/M6_PLAN.md` §3.6 resolved that by keeping them in
   * separate tables, and spelled out the failure: a sweep that cannot tell them apart deletes
   * the cache, every later poster becomes a live generation, and the bill goes up with nothing
   * on screen having changed. It is quiet, expensive, and this is the only thing that would
   * catch it.
   */
  it('leaves theme_cache and its artwork entirely alone', async () => {
    const themeKey = `retention-spec-${randomUUID()}`;
    const artKey = blobKeyFor('themes', randomUUID().replace(/-/g, ''), 'png');
    await blobs.put(artKey, Buffer.from('shared-product-artwork'), 'image/png');
    await prisma.themeCache.create({
      data: {
        themeKey,
        themeId: 'retention-spec-theme',
        family: 'CELEBRATORY',
        blobKey: artKey,
        eventType: 'BIRTHDAY',
        palette: 'warm',
        aspectRatio: '9:16',
        approved: true,
      },
    });

    // Something overdue alongside it, so the sweep genuinely runs rather than finding nothing.
    await seedAsset({ kind: 'SOURCE_PHOTO', expiresAt: new Date(NOW.getTime() - DAY_MS) });

    await service.sweep(NOW);

    expect(await blobs.exists(artKey)).toBe(true);
    expect(await prisma.themeCache.count({ where: { themeKey } })).toBe(1);
  });

  it('leaves another user\'s overdue media alone — the sweep is global but the rules are per asset', async () => {
    // Not a scoping test of the query (the sweep deliberately has no user filter — it is a
    // maintenance job) but a check that "global" never means "indiscriminate": a second
    // user's KEEP must hold even on a sweep run for nobody in particular.
    const other = await prisma.user.create({
      data: { googleSub: `retention-spec-keep-${randomUUID()}`, email: 'nobody3@example.invalid' },
    });
    const otherPerson = await prisma.person.create({
      data: { userId: other.id, displayName: 'Arjun Nair', relationToOwner: 'Cousin' },
    });
    const keptKey = blobKeyFor('photos', randomUUID().replace(/-/g, ''), 'png');
    await blobs.put(keptKey, Buffer.from('kept'), 'image/png');
    await prisma.asset.create({
      data: {
        userId: other.id,
        personId: otherPerson.id,
        kind: 'SOURCE_PHOTO',
        blobKey: keptKey,
        contentType: 'image/png',
        bytes: 4,
        expiresAt: new Date(NOW.getTime() - 500 * DAY_MS),
        retention: 'KEEP',
      },
    });

    await service.sweep(NOW);

    expect(await blobs.exists(keptKey)).toBe(true);
    await prisma.user.delete({ where: { id: other.id } });
  });
});

describe('a blob that will not delete', () => {
  it('leaves the row untouched so the next sweep tries again, rather than orphaning the bytes', async () => {
    /**
     * **Bytes before rows, in one direction only.**
     *
     * `BlobStore` has no `list`, so a row written before its object is removed leaves those
     * bytes unreachable by anything that will ever run — undiscoverable AND undeleted. The
     * survivable failure is the other one, and this is the test that says which of the two
     * this code produces.
     */
    const photo = await seedAsset({ kind: 'SOURCE_PHOTO', expiresAt: new Date(NOW.getTime() - DAY_MS) });

    const failing = new RetentionService(
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
    );

    const summary = await failing.sweep(NOW);

    expect(summary.failed).toBe(1);
    expect(summary.purged).toBe(0);

    const asset = await prisma.asset.findUniqueOrThrow({ where: { id: photo.id } });
    expect(asset.purgedAt).toBeNull();
    expect(asset.deletedAt).toBeNull();

    // And the working store finishes the job on the next pass.
    expect((await service.sweep(NOW)).purged).toBe(1);
    expect(await blobs.exists(photo.blobKey)).toBe(false);
  });
});
