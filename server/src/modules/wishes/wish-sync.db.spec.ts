import { randomUUID } from 'node:crypto';
import { PrismaClient } from '@prisma/client';
import { PersonDerivationService } from '../people/person-derivation.service';
import { SyncService } from '../sync/sync.service';
import { decodeCursor } from '../sync/sync-cursor';
import { WishHistoryService } from './wish-history.service';
import type { PrismaService } from '../../prisma/prisma.service';

/**
 * The wish entity against a REAL Postgres, through the real sync service.
 *
 * Why this exists rather than a fake-backed unit test: `MILESTONE_CHECKLIST.md` §D, and M3
 * §6.3 in particular — "a fake store cannot catch a disagreement between the code and the
 * schema, because it agrees with whatever the code does to it." Everything this suite is
 * about is a disagreement of that kind: a derived column the service forgets to set, a
 * cursor that does not advance, a tombstone that does not come back down, a `.strict()`
 * schema that accepts a field it should refuse.
 *
 * It runs in CI: `.github/workflows/server.yml` already stands up a `postgres:17-alpine`
 * service and exports `DATABASE_URL`, because `prisma migrate deploy` needed one. Locally it
 * runs against `infra/docker-compose.yml`.
 *
 * It creates its own user and cascade-deletes it afterwards, so it touches nothing else in
 * the database it is pointed at.
 */

const prisma = new PrismaClient();
// SyncService takes a PrismaService (a PrismaClient subclass) and uses only client methods.
const db = prisma as unknown as PrismaService;

let userId: string;
let personId: string;
let deceasedPersonId: string;
let sync: SyncService;
let history: WishHistoryService;

const iso = (s: string) => new Date(s).toISOString();

beforeAll(async () => {
  try {
    await prisma.$connect();
  } catch (cause) {
    // Deliberately NOT a skip. A suite that quietly passes when the database is absent is
    // one that stops being run, and this is the only suite in the server that can catch a
    // disagreement between the service and the schema. Fail, and say what to do.
    throw new Error(
      'This suite needs Postgres. Locally: docker compose -f infra/docker-compose.yml up db. ' +
        `DATABASE_URL=${process.env.DATABASE_URL ?? '(unset)'}. Cause: ${String(cause)}`,
    );
  }
  // The real derivation service, not a stub: the point of this suite is that nothing in the
  // push path is standing in for something. It reads one field of the env.
  const env = { DEFAULT_TIMEZONE: 'Asia/Kolkata' } as never;
  sync = new SyncService(db, new PersonDerivationService(db, env));
  history = new WishHistoryService(db);
});

afterAll(async () => {
  await prisma.$disconnect();
});

beforeEach(async () => {
  const user = await prisma.user.create({
    data: {
      googleSub: `wish-sync-spec-${randomUUID()}`,
      email: 'nobody@example.invalid',
      name: 'Wish Sync Spec',
      timezone: 'Asia/Kolkata',
    },
  });
  userId = user.id;

  // Invented people. The repo is public — MILESTONE_CHECKLIST.md §F.
  const alive = await prisma.person.create({
    data: { userId, displayName: 'Anika Pillai', relationToOwner: 'Sister' },
  });
  personId = alive.id;

  const gone = await prisma.person.create({
    data: {
      userId,
      displayName: 'Rohan Deshmukh',
      relationToOwner: 'Grandparent',
      isDeceased: true,
    },
  });
  deceasedPersonId = gone.id;
});

afterEach(async () => {
  // Cascades through person → wish and ai_usage. Nothing else in the database is touched.
  await prisma.user.delete({ where: { id: userId } }).catch(() => undefined);
});

function wishPush(over: Partial<Record<string, unknown>> = {}) {
  return {
    id: randomUUID(),
    personId,
    type: 'BIRTHDAY' as const,
    occurrenceYear: 2026,
    messageText: 'Many happy returns, and may this year be kinder than the last.',
    tone: 'WARM' as const,
    language: 'ENGLISH' as const,
    channel: 'WHATSAPP' as const,
    source: 'AI' as const,
    sentAt: iso('2026-03-14T04:30:00Z'),
    updatedAt: iso('2026-03-14T04:30:00Z'),
    ...over,
  };
}

const emptyBatch = { people: [], events: [], traits: [], wishes: [] };

describe('wish — push', () => {
  it('applies a wish and derives its sentiment from the person', async () => {
    const wish = wishPush();
    const result = await sync.push(userId, { ...emptyBatch, wishes: [wish] } as never);

    expect(result.applied.wishes).toBe(1);
    expect(result.skipped).toEqual([]);

    const stored = await prisma.wish.findUniqueOrThrow({ where: { id: wish.id } });
    expect(stored.sentiment).toBe('CELEBRATORY');
    expect(stored.userId).toBe(userId);
    expect(stored.eventType).toBe('BIRTHDAY');
    expect(stored.posterId).toBeNull();
  });

  it('derives SOLEMN for a deceased person’s BIRTHDAY — the case M1 got wrong', async () => {
    // The type is BIRTHDAY. Nothing in the pushed row says solemn. The person does.
    const wish = wishPush({
      personId: deceasedPersonId,
      messageText: 'Thinking of him today, and of how much he is missed.',
      tone: 'RESPECTFUL' as const,
    });
    await sync.push(userId, { ...emptyBatch, wishes: [wish] } as never);

    const stored = await prisma.wish.findUniqueOrThrow({ where: { id: wish.id } });
    expect(stored.sentiment).toBe('SOLEMN');
  });

  it('is idempotent — replaying the same batch changes nothing', async () => {
    const wish = wishPush();
    const first = await sync.push(userId, { ...emptyBatch, wishes: [wish] } as never);
    const second = await sync.push(userId, { ...emptyBatch, wishes: [wish] } as never);

    expect(first.applied.wishes).toBe(1);
    // Equal timestamps are not NEWER, so the replay is STALE rather than a second write.
    expect(second.applied.wishes).toBe(0);
    expect(second.skipped).toEqual([{ id: wish.id, entity: 'wish', reason: 'STALE' }]);
    expect(await prisma.wish.count({ where: { userId } })).toBe(1);
  });

  it('refuses a wish for somebody else’s person', async () => {
    const stranger = await prisma.user.create({
      data: { googleSub: `wish-sync-other-${randomUUID()}`, email: 'other@example.invalid' },
    });
    const theirPerson = await prisma.person.create({
      data: { userId: stranger.id, displayName: 'Kabir Rao' },
    });

    const result = await sync.push(userId, {
      ...emptyBatch,
      wishes: [wishPush({ personId: theirPerson.id })],
    } as never);

    expect(result.applied.wishes).toBe(0);
    expect(result.skipped[0]?.reason).toBe('UNKNOWN_PERSON');
    await prisma.user.delete({ where: { id: stranger.id } });
  });

  it('stores an eventId that no longer resolves, rather than refusing the wish', async () => {
    // The soft reference. A user who deleted and re-added a wrong date must not lose the
    // record of what they said — see the Wish model in schema.prisma.
    const orphanEventId = randomUUID();
    const wish = wishPush({ eventId: orphanEventId });
    const result = await sync.push(userId, { ...emptyBatch, wishes: [wish] } as never);

    expect(result.applied.wishes).toBe(1);
    const stored = await prisma.wish.findUniqueOrThrow({ where: { id: wish.id } });
    expect(stored.eventId).toBe(orphanEventId);
  });

  it('does not move the person’s updated_at — recording a wish must not re-sync the person', async () => {
    // A recompute would bump person.updated_at, which is the sync cursor's ordering key, and
    // every device would re-pull a person whose data did not change. M3 §6.3 bought this.
    const before = await prisma.person.findUniqueOrThrow({ where: { id: personId } });
    await sync.push(userId, { ...emptyBatch, wishes: [wishPush()] } as never);
    const after = await prisma.person.findUniqueOrThrow({ where: { id: personId } });

    expect(after.updatedAt.getTime()).toBe(before.updatedAt.getTime());
  });

  it('carries a tombstone', async () => {
    const wish = wishPush();
    await sync.push(userId, { ...emptyBatch, wishes: [wish] } as never);
    await sync.push(userId, {
      ...emptyBatch,
      wishes: [
        { ...wish, updatedAt: iso('2026-03-15T00:00:00Z'), deletedAt: iso('2026-03-15T00:00:00Z') },
      ],
    } as never);

    const stored = await prisma.wish.findUniqueOrThrow({ where: { id: wish.id } });
    expect(stored.deletedAt).not.toBeNull();
    // Soft, not hard: the row is still there to be pulled as a tombstone.
    expect(stored.messageText).not.toBe('');
  });
});

describe('wish — pull', () => {
  it('comes down on the cursor, and the cursor advances past it', async () => {
    const wish = wishPush();
    await sync.push(userId, { ...emptyBatch, wishes: [wish] } as never);

    const first = await sync.pull(userId, undefined, 200);
    expect(first.wishes.map((w) => w.id)).toEqual([wish.id]);
    expect(first.wishes[0]?.sentiment).toBe('CELEBRATORY');
    expect(decodeCursor(first.cursor).wish?.id).toBe(wish.id);

    // Second pull from that cursor: nothing new.
    const second = await sync.pull(userId, first.cursor, 200);
    expect(second.wishes).toEqual([]);
  });

  it('sends a tombstone down rather than an absence', async () => {
    const wish = wishPush();
    await sync.push(userId, { ...emptyBatch, wishes: [wish] } as never);
    const afterCreate = await sync.pull(userId, undefined, 200);

    await sync.push(userId, {
      ...emptyBatch,
      wishes: [
        { ...wish, updatedAt: iso('2026-03-15T00:00:00Z'), deletedAt: iso('2026-03-15T00:00:00Z') },
      ],
    } as never);

    const delta = await sync.pull(userId, afterCreate.cursor, 200);
    expect(delta.wishes).toHaveLength(1);
    expect(delta.wishes[0]?.deletedAt).not.toBeNull();
  });

  it('an M4-shaped cursor pulls every wish it has never seen', async () => {
    await sync.push(userId, { ...emptyBatch, wishes: [wishPush(), wishPush()] } as never);

    const m4Cursor = Buffer.from(
      JSON.stringify({ person: null, event: null, trait: null }),
    ).toString('base64url');

    const pulled = await sync.pull(userId, m4Cursor, 200);
    expect(pulled.wishes).toHaveLength(2);
  });
});

describe('wish — the no-repeat query (FR-WSH-02)', () => {
  async function seed(year: number, text: string, type: 'BIRTHDAY' | 'CUSTOM' = 'BIRTHDAY') {
    const wish = wishPush({
      occurrenceYear: year,
      type,
      messageText: text,
      sentAt: iso(`${year}-03-14T04:30:00Z`),
      updatedAt: iso(`${year}-03-14T04:30:00Z`),
      ...(type === 'CUSTOM' ? {} : {}),
    });
    await sync.push(userId, { ...emptyBatch, wishes: [wish] } as never);
    return wish;
  }

  it('returns previous years, newest first', async () => {
    await seed(2023, 'Twenty twenty-three.');
    await seed(2024, 'Twenty twenty-four.');
    await seed(2025, 'Twenty twenty-five.');

    const previous = await history.previousWishes(userId, personId, 'BIRTHDAY', 2026);
    expect(previous.map((p) => p.year)).toEqual([2025, 2024, 2023]);
    expect(previous[0]?.text).toBe('Twenty twenty-five.');
  });

  it('EXCLUDES this year — a message already sent is not one to avoid repeating', async () => {
    await seed(2025, 'Last year.');
    await seed(2026, 'This year, already sent.');

    const previous = await history.previousWishes(userId, personId, 'BIRTHDAY', 2026);
    expect(previous.map((p) => p.year)).toEqual([2025]);
  });

  it('ignores a different occasion on the same person', async () => {
    await seed(2025, 'For the birthday.');
    await seed(2025, 'For the other thing.', 'CUSTOM');

    const previous = await history.previousWishes(userId, personId, 'BIRTHDAY', 2026);
    expect(previous.map((p) => p.text)).toEqual(['For the birthday.']);
  });

  it('ignores a withdrawn row', async () => {
    const wish = await seed(2025, 'Deleted afterwards.');
    await sync.push(userId, {
      ...emptyBatch,
      wishes: [
        { ...wish, updatedAt: iso('2026-01-01T00:00:00Z'), deletedAt: iso('2026-01-01T00:00:00Z') },
      ],
    } as never);

    const previous = await history.previousWishes(userId, personId, 'BIRTHDAY', 2026);
    expect(previous).toEqual([]);
  });

  it('is bounded — a long history does not grow the prompt without limit', async () => {
    for (const year of [2019, 2020, 2021, 2022, 2023, 2024, 2025]) {
      await seed(year, `Year ${year}.`);
    }
    const previous = await history.previousWishes(userId, personId, 'BIRTHDAY', 2026);
    expect(previous).toHaveLength(3);
    expect(previous.map((p) => p.year)).toEqual([2025, 2024, 2023]);
  });

  it('lastYear is the most recent previous year, or null', async () => {
    expect(await history.lastYear(userId, personId, 'BIRTHDAY', 2026)).toBeNull();
    await seed(2025, 'Last year.');
    expect((await history.lastYear(userId, personId, 'BIRTHDAY', 2026))?.year).toBe(2025);
  });
});

/**
 * Sentiment derived in the SAME BATCH that makes the person deceased.
 *
 * Found in review of PR #7. `upsertWish` reads `person.isDeceased`, and the recompute that
 * SETS that flag runs after all four upsert loops — so a wish applied in the same batch as
 * the death anniversary that causes the flag read a value that had not caught up yet.
 *
 * It is permanent, unlike the identical expression in `upsertEvent`: events are re-derived by
 * `recomputeMany` afterwards, and wishes are deliberately excluded from `touched` so that
 * recording one cannot move `person.updated_at`.
 *
 * And the client makes it worse rather than better. `applyRemote` takes the server's
 * `sentiment` as authoritative on the next pull, so a wrong CELEBRATORY here OVERWRITES the
 * phone's own correct SOLEMN placeholder — the one `454ea75` had just made correct. Same bug
 * class as that fix, one layer down: that was a client reading a stale derived flag, this is
 * the server deriving from one.
 */
describe('wish — sentiment when the batch itself makes the person deceased', () => {
  it('is SOLEMN for a BIRTHDAY wish pushed alongside the death anniversary', async () => {
    // Composed offline: a remembrance sent on their birthday, before the death anniversary
    // had ever reached the server. Both leave the phone in one batch.
    const eventId = randomUUID();
    const wish = wishPush({
      type: 'BIRTHDAY',
      messageText: 'Thinking of her today, on the day she was born.',
      tone: 'HEARTFELT' as const,
    });

    const result = await sync.push(userId, {
      ...emptyBatch,
      events: [
        {
          id: eventId,
          personId,
          type: 'DEATH_ANNIVERSARY',
          day: 2,
          month: 8,
          year: 2019,
          updatedAt: iso('2026-03-14T04:00:00Z'),
        },
      ],
      wishes: [wish],
    } as never);

    expect(result.applied.wishes).toBe(1);
    // The person IS deceased by the end of this request — the recompute flips them.
    const after = await prisma.person.findUniqueOrThrow({ where: { id: personId } });
    expect(after.isDeceased).toBe(true);

    const stored = await prisma.wish.findUniqueOrThrow({ where: { id: wish.id } });
    expect(stored.sentiment).toBe('SOLEMN');
  });

  it('is still SOLEMN when the wish is for the death anniversary itself', async () => {
    // The simpler shape, which already worked: marksPersonDeceased(type) catches it without
    // needing the flag. Here so that fixing the case above cannot break this one.
    const wish = wishPush({ type: 'DEATH_ANNIVERSARY', messageText: 'Remembering her.' });
    await sync.push(userId, { ...emptyBatch, wishes: [wish] } as never);

    expect((await prisma.wish.findUniqueOrThrow({ where: { id: wish.id } })).sentiment).toBe(
      'SOLEMN',
    );
  });

  it('a CELEBRATORY wish for a living person is unaffected', async () => {
    // The assertion that stops the fix making everything solemn, which would be a quieter
    // bug of the same size.
    const wish = wishPush();
    await sync.push(userId, { ...emptyBatch, wishes: [wish] } as never);

    expect((await prisma.wish.findUniqueOrThrow({ where: { id: wish.id } })).sentiment).toBe(
      'CELEBRATORY',
    );
  });
});
