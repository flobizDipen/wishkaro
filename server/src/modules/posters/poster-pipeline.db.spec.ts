import { randomUUID } from 'node:crypto';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { PrismaClient } from '@prisma/client';
import IORedis from 'ioredis';
import type { AppEnv } from '../../config/env';
import type { PrismaService } from '../../prisma/prisma.service';
import { LocalBlobStore } from '../media/local-blob-store';
import { FakeModerationProvider } from '../moderation/fake.moderation-provider';
import { ModerationService } from '../moderation/moderation.service';
import { AiUsageService } from '../wishes/ledger/ai-usage.service';
import { SpendCapService } from '../wishes/ledger/spend-cap.service';
import { FakeLlmProvider } from '../wishes/llm/fake.provider';
import { GenerationService } from './generation.service';
import { FakeImageProvider } from './image/fake.image-provider';
import type { ImageProvider } from './image/image-provider';
import { PosterPipelineService, type PosterJob } from './poster-pipeline.service';
import { PosterRateLimitService } from './rate-limit/poster-rate-limit.service';
import { ThemeCacheService } from './theme-cache.service';
import { themesFor } from './theme-library';

/**
 * The poster pipeline against a real Postgres, a real Redis and a real blob store.
 *
 * ## What this suite is for
 *
 * The three claims `docs/BUILD_PROMPT_M6.md` makes measurable, and one it says will
 * otherwise be discovered by a user:
 *
 *  - **Definition-of-done item 5**, in its strongest form: a cache hit makes no model call,
 *    writes no `ai_usage` row and consumes no rate-limit slot **while the provider is
 *    live**. The curl proof was taken with `IMAGE_PROVIDER=fake`, where no call would have
 *    happened anyway, so it could not distinguish "the cache worked" from "nothing was
 *    configured". These tests can.
 *  - **Item 8**: the global spend cap degrading a poster request to cached themes.
 *  - **The stale window**, on purpose. `occasion.spec.ts` asserts the pure function; this
 *    builds the row state that produced `454ea75` and `cb5919d` and runs the whole pipeline
 *    over it. No fixture in this repository builds that shape on its own, which is
 *    precisely why it survived twice.
 *
 * Real Postgres rather than a fake store, per `MILESTONE_CHECKLIST.md` §D: a fake agrees
 * with whatever the code does to it, and everything here is looking for a disagreement —
 * a unique constraint that does not hold, a row written when none should be, a counter
 * that moved.
 *
 * Nothing here asserts on what a poster LOOKS like. That is TBD-5's and a person's.
 */

const prisma = new PrismaClient();
const db = prisma as unknown as PrismaService;
const redis = new IORedis(process.env.REDIS_URL ?? 'redis://localhost:6379', {
  maxRetriesPerRequest: null,
  enableReadyCheck: false,
});

function envWith(over: Partial<Record<string, unknown>> = {}): AppEnv {
  return {
    USD_INR_RATE: 89,
    POSTER_LIMIT_HOUR: 3,
    POSTER_LIMIT_DAY: 5,
    AI_DAILY_SPEND_CAP_INR: 1_000_000,
    ...over,
  } as unknown as AppEnv;
}

/**
 * The fake image provider, wrapped so it reports `isLive`.
 *
 * This is half the reason the suite exists. `FakeImageProvider.isLive` is `false`, and the
 * pipeline short-circuits on it BEFORE the spend cap and the limiter — so "a cache hit
 * consumed no slot" would be true for the wrong reason, and the cap could not be shown to
 * bite at all.
 *
 * A wrapper rather than a subclass with the field overridden: the fake declares
 * `readonly isLive = false`, which TypeScript narrows to the literal, and widening it to
 * `boolean` just so a test can flip it would weaken the one property that keeps a
 * `Generation` row honest about whether a model was involved. The test bends; the
 * production type does not.
 */
function asLive(fake: FakeImageProvider): ImageProvider {
  return {
    providerName: fake.providerName,
    modelName: fake.modelName,
    isLive: true,
    generate: (prompt) => fake.generate(prompt),
  };
}

let root: string;
let store: LocalBlobStore;
let userId: string;
let livingId: string;
let staleId: string;

beforeAll(async () => {
  try {
    await prisma.$connect();
    await redis.ping();
  } catch (cause) {
    throw new Error(
      'This suite needs Postgres and Redis. Locally: ' +
        'docker compose -f infra/docker-compose.yml up db redis. ' +
        `Cause: ${String(cause)}`,
    );
  }
});

afterAll(async () => {
  await prisma.$disconnect();
  redis.disconnect();
});

beforeEach(async () => {
  root = await mkdtemp(join(tmpdir(), 'wishkaro-poster-spec-'));
  store = new LocalBlobStore(root, 'spec-signing-secret');

  // The theme cache is global product art, not a user's media, so it is shared across
  // every test in the database. Cleared here so a hit in one test is not a hit in the next.
  await prisma.themeCache.deleteMany({});

  const user = await prisma.user.create({
    data: { googleSub: `poster-spec-${randomUUID()}`, email: 'nobody@example.invalid' },
  });
  userId = user.id;

  // Invented people (MILESTONE_CHECKLIST.md §F). The repo is public.
  const asha = await prisma.person.create({
    data: {
      userId,
      displayName: 'Asha Kulkarni',
      relationToOwner: 'Sister',
      events: {
        create: {
          userId,
          type: 'BIRTHDAY',
          sentiment: 'CELEBRATORY',
          day: 14,
          month: 3,
          year: 1991,
        },
      },
    },
  });
  livingId = asha.id;

  /**
   * **The stale window, built on purpose.**
   *
   * A death anniversary row EXISTS and `is_deceased` is deliberately left FALSE — which is
   * exactly the state a sync push leaves behind between `upsertEvent` and `recomputeMany`,
   * and the state `cb5919d` read one step too early. The birthday's stored sentiment is
   * left CELEBRATORY for the same reason: `recompute` has not run.
   *
   * Every other fixture in this repository builds a person the way the server returns one,
   * with the flag already set. That is why this bug survived twice with a green suite.
   */
  const rohan = await prisma.person.create({
    data: {
      userId,
      displayName: 'Rohan Deshpande',
      relationToOwner: 'Uncle',
      isDeceased: false,
      events: {
        create: [
          { userId, type: 'DEATH_ANNIVERSARY', sentiment: 'SOLEMN', day: 2, month: 11, year: 2014 },
          { userId, type: 'BIRTHDAY', sentiment: 'CELEBRATORY', day: 9, month: 6, year: 1952 },
        ],
      },
    },
  });
  staleId = rohan.id;
});

afterEach(async () => {
  await prisma.user.deleteMany({ where: { id: userId } });
  await rm(root, { recursive: true, force: true });
});

interface Harness {
  pipeline: PosterPipelineService;
  generations: GenerationService;
  images: FakeImageProvider;
  moderator: FakeModerationProvider;
}

function harness(over: Partial<Record<string, unknown>> = {}, live = true): Harness {
  const env = envWith(over);
  const generations = new GenerationService(db);
  const images = new FakeImageProvider();
  const moderator = new FakeModerationProvider();

  const pipeline = new PosterPipelineService(
    db,
    generations,
    new ThemeCacheService(db, store),
    new AiUsageService(db, env),
    new SpendCapService(db, env),
    new PosterRateLimitService(redis, env),
    new ModerationService(moderator),
    store,
    live ? asLive(images) : images,
    new FakeLlmProvider(),
  );

  return { pipeline, generations, images, moderator };
}

async function runFor(
  h: Harness,
  personId: string,
  over: Partial<PosterJob> = {},
): Promise<{ generationId: string; result: Awaited<ReturnType<PosterPipelineService['run']>> }> {
  const generation = await h.generations.open(userId, personId);
  const job: PosterJob = {
    generationId: generation.id,
    userId,
    personId,
    eventId: null,
    eventType: 'BIRTHDAY',
    occurrenceYear: 2026,
    themeId: null,
    format: 'STATUS',
    ...over,
  };
  const result = await h.pipeline.run(job, generation.createdAt);
  return { generationId: generation.id, result };
}

/** Poster rate-limit keys for this user, so "no slot consumed" is a measurement. */
async function slotsUsed(): Promise<number> {
  const keys = await redis.keys(`poster:*:${userId}:*`);
  if (keys.length === 0) return 0;
  const values = await redis.mget(...keys);
  return Math.max(...values.map((v) => Number(v ?? 0)));
}

// ---------------------------------------------------------------------------------------
// The thing most likely to go wrong
// ---------------------------------------------------------------------------------------

describe('a celebratory theme on a death anniversary — the stale window, end to end', () => {
  it('produces a SOLEMN poster from the solemn family while is_deceased is still false', async () => {
    const h = harness();

    // Precondition, asserted rather than assumed: the bug only exists in this state, so a
    // fixture that quietly recomputed would make the test pass for the wrong reason.
    const before = await prisma.person.findUniqueOrThrow({ where: { id: staleId } });
    expect(before.isDeceased).toBe(false);

    const { generationId } = await runFor(h, staleId, { eventType: 'BIRTHDAY' });

    const poster = await prisma.poster.findFirstOrThrow({ where: { generationId } });
    expect(poster.sentiment).toBe('SOLEMN');
    expect(poster.family).toBe('SOLEMN');

    const solemnIds = themesFor('SOLEMN').map((t) => t.id);
    expect(solemnIds).toContain(poster.themeId);
  });

  it('REQUIREMENTS §11 as a query: no solemn poster is ever celebratory', async () => {
    const h = harness();
    await runFor(h, staleId, { eventType: 'BIRTHDAY' });
    await runFor(h, staleId, { eventType: 'DEATH_ANNIVERSARY' });
    await runFor(h, livingId, { eventType: 'BIRTHDAY' });

    // The index `@@index([sentiment, family])` exists so this is a query rather than an
    // inspection of every image.
    const wrong = await prisma.poster.count({
      where: { userId, sentiment: 'SOLEMN', family: 'CELEBRATORY' },
    });
    expect(wrong).toBe(0);

    // And the living person still gets a celebratory one, so the gate is not simply
    // answering SOLEMN to everything.
    const living = await prisma.poster.findFirstOrThrow({ where: { userId, personId: livingId } });
    expect(living.sentiment).toBe('CELEBRATORY');
    expect(living.family).toBe('CELEBRATORY');
  });

  it('refuses a celebratory theme asked for by id, rather than coercing it', async () => {
    const h = harness();
    const celebratory = themesFor('CELEBRATORY')[0].id;
    await expect(runFor(h, staleId, { themeId: celebratory })).rejects.toThrow(
      /not available for a SOLEMN/,
    );
  });
});

// ---------------------------------------------------------------------------------------
// Definition-of-done item 5
// ---------------------------------------------------------------------------------------

describe('a cache hit costs nothing and consumes nothing', () => {
  it('second request: no model call, no ai_usage row, no rate-limit slot', async () => {
    const h = harness();

    const first = await runFor(h, livingId);
    const firstRow = await prisma.generation.findUniqueOrThrow({
      where: { id: first.generationId },
    });
    expect(firstRow.cacheHit).toBe(false);

    // The miss DID call the provider and DID take a slot — otherwise the hit's zero below
    // would prove nothing.
    const usageAfterMiss = await prisma.aiUsage.count({ where: { userId } });
    const slotsAfterMiss = await slotsUsed();
    expect(usageAfterMiss).toBe(1);
    expect(slotsAfterMiss).toBe(1);

    const second = await runFor(h, livingId);
    const secondRow = await prisma.generation.findUniqueOrThrow({
      where: { id: second.generationId },
    });

    expect(secondRow.cacheHit).toBe(true);
    expect(secondRow.degraded).toBe(false);
    expect(secondRow.status).toBe('SUCCEEDED');
    // Zero cost, and it is a fact rather than a default: no call was made.
    expect(Number(secondRow.costInr)).toBe(0);

    // Nothing new on the ledger, and no further slot.
    expect(await prisma.aiUsage.count({ where: { userId } })).toBe(usageAfterMiss);
    expect(await slotsUsed()).toBe(slotsAfterMiss);

    // And a poster still came out of it.
    expect(second.result.posterId).not.toBeNull();
  });

  it('keeps one theme_cache row per key and counts the hits', async () => {
    const h = harness();
    await runFor(h, livingId);
    await runFor(h, livingId);
    await runFor(h, livingId);

    const rows = await prisma.themeCache.findMany();
    expect(rows).toHaveLength(1);
    // §13's health metric: generated once, served three times.
    expect(rows[0].hitCount).toBe(2);
    // TBD-5 is open, so nothing in M6 can honestly approve artwork.
    expect(rows[0].approved).toBe(false);
  });

  it('treats a different format as a different key, because the aspect ratio differs', async () => {
    const h = harness();
    await runFor(h, livingId, { format: 'STATUS' });
    await runFor(h, livingId, { format: 'SQUARE' });

    const rows = await prisma.themeCache.findMany();
    expect(rows).toHaveLength(2);
    expect(new Set(rows.map((r) => r.aspectRatio))).toEqual(new Set(['9:16', '1:1']));
  });
});

// ---------------------------------------------------------------------------------------
// Definition-of-done items 7 and 8
// ---------------------------------------------------------------------------------------

describe('the ledger and the rails', () => {
  it('writes one ai_usage row per image call, tied to its generation', async () => {
    const h = harness();
    const { generationId } = await runFor(h, livingId);

    const rows = await prisma.aiUsage.findMany({ where: { generationId } });
    expect(rows).toHaveLength(1);
    expect(rows[0].feature).toBe('poster');
    expect(rows[0].outcome).toBe('OK');
    // The fake is priced at zero on purpose — a ₹0 row rather than no row. A LIVE
    // non-zero figure is definition-of-done item 7's measured half and is BLOCKED on the
    // key; `M6_VERIFY.md` says so rather than rounding it up.
    expect(Number(rows[0].costInr)).toBe(0);
    expect(rows[0].promptVersion).toMatch(/^poster-art-/);
  });

  it('writes a row for a refused image too, because the call still happened', async () => {
    const h = harness();
    h.images.nextOutcome = 'REFUSED';

    const { generationId } = await runFor(h, livingId);

    const rows = await prisma.aiUsage.findMany({ where: { generationId } });
    expect(rows).toHaveLength(1);
    expect(rows[0].outcome).toBe('REFUSED');

    // §10 rule 3: it falls back to the library rather than failing.
    const row = await prisma.generation.findUniqueOrThrow({ where: { id: generationId } });
    expect(row.status).toBe('SUCCEEDED');
    expect(row.degraded).toBe(true);
  });

  it('degrades to the theme library when the global spend cap is reached (item 8)', async () => {
    // Seed the ledger past a low cap. Summed across every feature, as §10 rule 6 requires.
    await prisma.aiUsage.create({
      data: {
        userId,
        feature: 'wish_draft',
        provider: 'fake',
        model: 'fake-model',
        outcome: 'OK',
        inputTokens: 0,
        outputTokens: 0,
        cacheReadTokens: 0,
        cacheWriteTokens: 0,
        costUsd: 1,
        usdInrRate: 89,
        costInr: 250,
        pricingVersion: 'spec',
        promptVersion: 'spec',
      },
    });

    const h = harness({ AI_DAILY_SPEND_CAP_INR: 200 });
    const { generationId, result } = await runFor(h, livingId);

    const row = await prisma.generation.findUniqueOrThrow({ where: { id: generationId } });
    expect(row.status).toBe('SUCCEEDED');
    expect(row.degraded).toBe(true);
    expect(row.cacheHit).toBe(false);
    // A poster still came out — degradation is a cheaper picture, not an error.
    expect(result.posterId).not.toBeNull();

    // No image was called, so no poster row joined the ledger.
    expect(await prisma.aiUsage.count({ where: { generationId } })).toBe(0);
    // And being capped did not also cost the user one of their five.
    expect(await slotsUsed()).toBe(0);
  });

  it('degrades once the per-user ceiling is reached, and does not fail', async () => {
    const h = harness({ POSTER_LIMIT_HOUR: 2 });

    // Three distinct keys, so every one is a miss and reaches the limiter.
    await runFor(h, livingId, { format: 'STATUS' });
    await runFor(h, livingId, { format: 'SQUARE' });
    const third = await runFor(h, livingId, { format: 'LINK' });

    const row = await prisma.generation.findUniqueOrThrow({ where: { id: third.generationId } });
    expect(row.degraded).toBe(true);
    expect(row.status).toBe('SUCCEEDED');
    expect(await prisma.aiUsage.count({ where: { generationId: third.generationId } })).toBe(0);
  });

  it('does not mark the ordinary no-key path as degraded', async () => {
    // FR-POS-04's main path is the library. Calling that a degradation would make the
    // metric read 100% on a healthy deployment.
    const h = harness({}, false);
    const { generationId } = await runFor(h, livingId);

    const row = await prisma.generation.findUniqueOrThrow({ where: { id: generationId } });
    expect(row.degraded).toBe(false);
    expect(row.cacheHit).toBe(false);
    expect(await prisma.aiUsage.count({ where: { generationId } })).toBe(0);
  });
});

// ---------------------------------------------------------------------------------------
// §10 rule 7
// ---------------------------------------------------------------------------------------

describe('moderation', () => {
  it('records UNCHECKED rather than ALLOWED when no classifier ran', async () => {
    const h = harness();
    const { generationId } = await runFor(h, livingId);

    const row = await prisma.generation.findUniqueOrThrow({ where: { id: generationId } });
    // The honest value. Rule 7 is structurally met and not actually exercised.
    expect(row.moderation).toBe('UNCHECKED');
  });

  it('blocks a generation and writes no poster when the classifier refuses', async () => {
    const h = harness();
    h.moderator.nextVerdict = 'block';

    const { generationId, result } = await runFor(h, livingId);

    expect(result.status).toBe('BLOCKED');
    expect(result.posterId).toBeNull();

    const row = await prisma.generation.findUniqueOrThrow({ where: { id: generationId } });
    // BLOCKED, not FAILED: the user-facing answer differs, and "how often does moderation
    // bite" is a question worth being able to ask of this table.
    expect(row.status).toBe('BLOCKED');
    expect(row.moderation).toBe('BLOCKED');
    expect(row.errorCode).toBeTruthy();

    expect(await prisma.poster.count({ where: { userId } })).toBe(0);
  });
});
