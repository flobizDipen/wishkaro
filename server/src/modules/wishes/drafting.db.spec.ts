import { randomUUID } from 'node:crypto';
import { PrismaClient } from '@prisma/client';
import IORedis from 'ioredis';
import { DraftingService } from './drafting.service';
import { AiUsageService } from './ledger/ai-usage.service';
import { FakeLlmProvider } from './llm/fake.provider';
import { DraftRateLimitService } from './rate-limit/draft-rate-limit.service';
import { WishHistoryService } from './wish-history.service';
import type { AppEnv } from '../../config/env';
import type { PrismaService } from '../../prisma/prisma.service';
import type { DraftWishRequest } from './wishes.schemas';

/**
 * The drafting endpoint's mechanism, against a real Postgres and a real Redis.
 *
 * What this suite is for, and what it is deliberately NOT for.
 *
 * It is for the plumbing: that a ledger row exists for every attempt including the failed
 * ones, that the rate limit bites at the configured ceiling and says so in a way the app can
 * show, that a tone the occasion does not allow is refused before anything costs money, and
 * that a refusal and a provider error both come out as the one thing the composer knows how
 * to answer.
 *
 * It is NOT for the words. The provider here is the fake, and
 * `MILESTONE_CHECKLIST.md` §D is explicit that a test asserting on a fake model's output
 * tests the fake. Nothing below reads `text`. The tests that are about words call a real
 * model and live in `live/`, which CI does not run.
 *
 * Real Redis rather than a stub for the same reason the Postgres suite uses real Postgres: a
 * stub agrees with whatever the code does to it, and the things that go wrong in a limiter —
 * a multi whose replies are read at the wrong index, a key that never expires, an off-by-one
 * at the boundary — are all disagreements with the real thing.
 */

const prisma = new PrismaClient();
const db = prisma as unknown as PrismaService;
const redis = new IORedis(process.env.REDIS_URL ?? 'redis://localhost:6379', {
  maxRetriesPerRequest: null,
  enableReadyCheck: false,
});

const env = {
  ANTHROPIC_MODEL: 'claude-opus-5-5',
  USD_INR_RATE: 89,
  WISH_DRAFT_LIMIT_HOUR: 3,
  WISH_DRAFT_LIMIT_DAY: 5,
} as unknown as AppEnv;

let userId: string;
let personId: string;
let deceasedId: string;
let minorId: string;
let service: DraftingService;
let provider: FakeLlmProvider;

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
  const user = await prisma.user.create({
    data: { googleSub: `drafting-spec-${randomUUID()}`, email: 'nobody@example.invalid' },
  });
  userId = user.id;

  // Invented people, as MILESTONE_CHECKLIST.md §F requires. The repo is public.
  const anika = await prisma.person.create({
    data: {
      userId,
      displayName: 'Anika Pillai',
      relationToOwner: 'Sister',
      events: {
        create: {
          userId,
          type: 'BIRTHDAY',
          sentiment: 'CELEBRATORY',
          day: 14,
          month: 3,
          year: 1994,
        },
      },
      traits: { create: [{ userId, kind: 'HOBBY', value: 'long-distance running' }] },
    },
  });
  personId = anika.id;

  const rohan = await prisma.person.create({
    data: {
      userId,
      displayName: 'Rohan Deshmukh',
      relationToOwner: 'Grandparent',
      isDeceased: true,
    },
  });
  deceasedId = rohan.id;

  const ira = await prisma.person.create({
    data: {
      userId,
      displayName: 'Ira Kulkarni',
      relationToOwner: 'Niece',
      isMinor: true,
      traits: { create: [{ userId, kind: 'HOBBY', value: 'skateboarding' }] },
    },
  });
  minorId = ira.id;

  provider = new FakeLlmProvider();
  service = new DraftingService(
    db,
    new WishHistoryService(db),
    new AiUsageService(db, env),
    new DraftRateLimitService(redis, env),
    provider,
    env,
  );
});

afterEach(async () => {
  // Cascades through person -> wish and ai_usage. Nothing else is touched.
  await prisma.user.delete({ where: { id: userId } }).catch(() => undefined);
  // The limiter keys are per user and expire on their own; clearing them keeps a re-run of
  // this suite within the same hour from inheriting the previous run's counts.
  const keys = await redis.keys(`wishdraft:*:${userId}:*`);
  if (keys.length > 0) await redis.del(...keys);
});

const thisYear = new Date().getUTCFullYear();

function request(over: Partial<DraftWishRequest> = {}): DraftWishRequest {
  return {
    personId,
    eventType: 'BIRTHDAY',
    occurrenceYear: thisYear,
    tone: 'WARM',
    language: 'ENGLISH',
    avoid: [],
    ...over,
  } as DraftWishRequest;
}

const ledgerRows = () =>
  prisma.aiUsage.findMany({ where: { userId }, orderBy: { createdAt: 'asc' } });

describe('drafting — the happy path and what it records', () => {
  it('returns a draft and marks it TEMPLATE when no model served it', async () => {
    const result = await service.draft(userId, request());

    // Not an assertion about the words — an assertion that the server, not the client,
    // decides whether a model wrote this. A client that decided for itself would write AI
    // into the history for something a template produced.
    expect(result.source).toBe('TEMPLATE');
    expect(result.model).toBeNull();
    expect(result.tone).toBe('WARM');
    expect(result.text.length).toBeGreaterThan(0);
  });

  it('writes exactly one ledger row per call, with four token counts', async () => {
    await service.draft(userId, request());
    await service.draft(userId, request());

    const rows = await ledgerRows();
    expect(rows).toHaveLength(2);
    expect(rows[0].feature).toBe('wish_draft');
    expect(rows[0].provider).toBe('fake');
    expect(rows[0].outcome).toBe('OK');
    expect(rows[0].inputTokens).toBeGreaterThan(0);
    expect(rows[0].outputTokens).toBeGreaterThan(0);
    // Cache columns exist and are zero on the fake. The point of four counts rather than two
    // is that the real adapter can report a cache read and the ledger can see it.
    expect(rows[0].cacheReadTokens).toBe(0);
    expect(rows[0].cacheWriteTokens).toBe(0);
  });

  it('stores the exchange rate and the pricing version on the row', async () => {
    await service.draft(userId, request());
    const [row] = await ledgerRows();

    // Per row, so that changing either later cannot re-price what last month cost.
    expect(Number(row.usdInrRate)).toBe(89);
    expect(row.pricingVersion).toMatch(/^anthropic-/);
  });

  it('never logs or stores the drafted text on the ledger row', async () => {
    const result = await service.draft(userId, request());
    const [row] = await ledgerRows();

    // §11 bans PII in logs and a drafted wish is prose about a named person. The ledger
    // records what a call cost, never what it said. Asserted rather than assumed because
    // "add the text, it will help debugging" is a one-line change somebody will propose.
    expect(JSON.stringify(row)).not.toContain(result.text);
    expect(JSON.stringify(row)).not.toContain('Anika');
  });

  it('does not create a wish — a draft nobody sent is not history', async () => {
    await service.draft(userId, request());
    // FR-WSH-07 puts the record after the hand-off. If drafting wrote one, next year's
    // no-repeat rule would be avoiding messages that were never sent.
    expect(await prisma.wish.count({ where: { userId } })).toBe(0);
  });
});

describe('drafting — the tone restriction (FR-WSH-09)', () => {
  it('refuses FUNNY on a death anniversary, and charges nothing for it', async () => {
    await expect(
      service.draft(
        userId,
        request({ personId: deceasedId, eventType: 'DEATH_ANNIVERSARY', tone: 'FUNNY' }),
      ),
    ).rejects.toMatchObject({ response: { code: 'TONE_NOT_ALLOWED' } });

    // Refused BEFORE the rate limit is touched and before anything costs money: a 400 must
    // not spend one of the user's fifteen drafts.
    expect(await ledgerRows()).toHaveLength(0);
  });

  it('refuses FUNNY on a deceased person’s BIRTHDAY — the type is still BIRTHDAY', async () => {
    // The case M1 got wrong. Nothing in the request says solemn; the person does.
    await expect(
      service.draft(
        userId,
        request({ personId: deceasedId, eventType: 'BIRTHDAY', tone: 'FUNNY' }),
      ),
    ).rejects.toMatchObject({ response: { code: 'TONE_NOT_ALLOWED' } });
  });

  it('allows HEARTFELT on a death anniversary and refuses it on a birthday', async () => {
    await expect(
      service.draft(
        userId,
        request({ personId: deceasedId, eventType: 'DEATH_ANNIVERSARY', tone: 'HEARTFELT' }),
      ),
    ).resolves.toBeDefined();

    await expect(service.draft(userId, request({ tone: 'HEARTFELT' }))).rejects.toMatchObject({
      response: { code: 'TONE_NOT_ALLOWED' },
    });
  });
});

describe('drafting — what the request is not allowed to decide', () => {
  it('404s on somebody else’s person', async () => {
    const stranger = await prisma.user.create({
      data: { googleSub: `drafting-other-${randomUUID()}`, email: 'other@example.invalid' },
    });
    const theirs = await prisma.person.create({
      data: { userId: stranger.id, displayName: 'Kabir Rao' },
    });

    await expect(service.draft(userId, request({ personId: theirs.id }))).rejects.toMatchObject({
      response: { code: 'PERSON_NOT_FOUND' },
    });
    expect(await ledgerRows()).toHaveLength(0);
    await prisma.user.delete({ where: { id: stranger.id } });
  });

  it('rejects a year that is not within one of now', async () => {
    // The composer is legitimately opened for NEXT year from a T-7 reminder in December, so
    // ±1. Wider than that is nobody doing anything, and an unbounded year poisons the
    // no-repeat query's ordering for ever.
    await expect(
      service.draft(userId, request({ occurrenceYear: thisYear + 1 })),
    ).resolves.toBeDefined();
    await expect(
      service.draft(userId, request({ occurrenceYear: thisYear + 5 })),
    ).rejects.toMatchObject({ response: { code: 'VALIDATION_FAILED' } });
  });
});

describe('drafting — the rate limit (FR-WSH-08)', () => {
  it('bites at the hourly ceiling and says how long to wait', async () => {
    for (let i = 0; i < env.WISH_DRAFT_LIMIT_HOUR; i += 1) {
      await service.draft(userId, request());
    }

    await expect(service.draft(userId, request())).rejects.toMatchObject({
      response: {
        code: 'RATE_LIMITED',
        // Definition-of-done item 8: something the app can SHOW, not a dead button.
        message: expect.stringContaining('yourself'),
      },
      status: 429,
    });
  });

  it('counts refusals and errors against the ceiling, because they cost money', async () => {
    provider.nextOutcome = 'REFUSED';
    for (let i = 0; i < env.WISH_DRAFT_LIMIT_HOUR; i += 1) {
      await expect(service.draft(userId, request())).rejects.toMatchObject({
        response: { code: 'DRAFT_UNAVAILABLE' },
      });
    }

    provider.nextOutcome = 'OK';
    await expect(service.draft(userId, request())).rejects.toMatchObject({ status: 429 });
  });

  it('a 429 writes no ledger row — nothing was called', async () => {
    for (let i = 0; i < env.WISH_DRAFT_LIMIT_HOUR; i += 1) {
      await service.draft(userId, request());
    }
    await expect(service.draft(userId, request())).rejects.toMatchObject({ status: 429 });

    expect(await ledgerRows()).toHaveLength(env.WISH_DRAFT_LIMIT_HOUR);
  });
});

describe('drafting — the three failures that look the same from the composer', () => {
  it.each(['REFUSED', 'ERROR', 'TIMEOUT'] as const)(
    '%s becomes DRAFT_UNAVAILABLE with a ledger row recording what really happened',
    async (outcome) => {
      provider.nextOutcome = outcome;

      await expect(service.draft(userId, request())).rejects.toMatchObject({
        response: {
          code: 'DRAFT_UNAVAILABLE',
          // No category, no provider message, no stack. The screen where somebody is writing
          // about a death is the worst place in this app to show a raw error.
          message: "We couldn't write this one.",
        },
        status: 503,
      });

      const rows = await ledgerRows();
      expect(rows).toHaveLength(1);
      // The difference between the three belongs in the ledger, not on the screen.
      expect(rows[0].outcome).toBe(outcome);
    },
  );

  it('a refusal that still cost input tokens is charged for', async () => {
    provider.nextOutcome = 'REFUSED';
    await expect(service.draft(userId, request())).rejects.toBeDefined();

    const [row] = await ledgerRows();
    expect(row.inputTokens).toBeGreaterThan(0);
    expect(row.outcome).toBe('REFUSED');
  });
});

describe('drafting — what the prompt is allowed to know', () => {
  it('a minor’s traits and notes do not reach the model', async () => {
    // REQUIREMENTS.md §12: the minor flag suppresses profiling and personalisation;
    // FR-WSH-02 says condition on hobbies. docs/M5_PLAN.md §6.2 raises the collision and
    // this is the narrowing — relation and occasion only.
    const seen: string[] = [];
    provider.draft = async (prompt) => {
      seen.push(prompt.user);
      return {
        outcome: 'OK' as const,
        text: 'x',
        model: 'fake-model',
        usage: { inputTokens: 1, outputTokens: 1, cacheReadTokens: 0, cacheWriteTokens: 0 },
      };
    };

    await service.draft(userId, request({ personId: minorId }));
    expect(seen[0]).toContain('Niece');
    expect(seen[0]).not.toContain('skateboarding');

    await service.draft(userId, request({ personId }));
    expect(seen[1]).toContain('long-distance running');
  });

  it('the history block carries previous years in full', async () => {
    await prisma.wish.create({
      data: {
        userId,
        personId,
        eventType: 'BIRTHDAY',
        occurrenceYear: thisYear - 1,
        sentiment: 'CELEBRATORY',
        messageText: 'Last year I wrote about the marathon.',
        tone: 'WARM',
        language: 'ENGLISH',
        channel: 'WHATSAPP',
        source: 'AI',
        sentAt: new Date(),
      },
    });

    let captured = '';
    provider.draft = async (prompt) => {
      captured = prompt.user;
      return {
        outcome: 'OK' as const,
        text: 'x',
        model: 'fake-model',
        usage: { inputTokens: 1, outputTokens: 1, cacheReadTokens: 0, cacheWriteTokens: 0 },
      };
    };

    await service.draft(userId, request());

    // In full, not summarised. "Do not repeat last year's message" is unenforceable without
    // the message, and a summary is exactly what a model satisfies by rewording.
    expect(captured).toContain('Last year I wrote about the marathon.');
    expect(captured).toContain(String(thisYear - 1));
  });

  it('the system prompt is byte-identical across two different people', async () => {
    // The cache prefix. Any volatile byte — a name, a timestamp, a request id — crossing
    // into the system prompt makes nearly every call a cache miss, silently.
    const systems: string[] = [];
    provider.draft = async (prompt) => {
      systems.push(prompt.system);
      return {
        outcome: 'OK' as const,
        text: 'x',
        model: 'fake-model',
        usage: { inputTokens: 1, outputTokens: 1, cacheReadTokens: 0, cacheWriteTokens: 0 },
      };
    };

    await service.draft(userId, request());
    await service.draft(userId, request({ personId: minorId }));
    await service.draft(userId, request({ tone: 'POETIC', language: 'HINDI' }));

    expect(new Set(systems).size).toBe(1);
    expect(systems[0]).not.toContain('Anika');
  });

  it('a solemn occasion gets a different system prompt, with no celebratory word in it', async () => {
    const systems: string[] = [];
    provider.draft = async (prompt) => {
      systems.push(prompt.system);
      return {
        outcome: 'OK' as const,
        text: 'x',
        model: 'fake-model',
        usage: { inputTokens: 1, outputTokens: 1, cacheReadTokens: 0, cacheWriteTokens: 0 },
      };
    };

    await service.draft(userId, request());
    await service.draft(
      userId,
      request({ personId: deceasedId, eventType: 'DEATH_ANNIVERSARY', tone: 'RESPECTFUL' }),
    );

    expect(systems[0]).not.toBe(systems[1]);

    // Two prompts chosen before anything is built, not one with a conditional inside it.
    //
    // NOT a banned-lexicon check. An earlier version of this test asserted that the solemn
    // prompt contains no celebratory word, copying what `ToneTest.kt` does to
    // `SolemnStrings`, and it failed on "never congratulate, never celebrate" — the rule
    // itself. A prompt has to name what it forbids, so the lexicon assertion belongs on the
    // model's OUTPUT and lives in `live/`. Asserting it here would be asserting that the
    // rule is absent rather than that it is obeyed.
    expect(systems[1]).toContain('never celebrate');
    expect(systems[1]).toContain('No emoji');
    // And the celebratory prompt must not have quietly acquired the solemn rules, which is
    // what a single merged template drifts into.
    expect(systems[0]).not.toContain('grieving');
  });
});
