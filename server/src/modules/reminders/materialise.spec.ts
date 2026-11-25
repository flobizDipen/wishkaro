import { RemindersService } from './reminders.service';
import type { PrismaService } from '../../prisma/prisma.service';
import type { FirebaseService } from '../../firebase/firebase.service';
import type { PersonDerivationService } from '../people/person-derivation.service';

/**
 * The materialiser, tested as WIRING rather than as a collection of pure functions.
 *
 * M1's PR was reviewed and three real bugs were found in it. All three lived in stateful code
 * that composed well-tested pure functions — a transition the caller had already overwritten,
 * an outbox cleared without reading the server's `skipped` list, and a sweep filtered to the
 * very rows it existed to catch. The pure functions were green throughout.
 *
 * M2 has more of that shape than M1 did: quiet hours layered over timezone, the ladder layered
 * over the solemn rules, digest suppression layered over both. `ladder.spec.ts`,
 * `quiet-hours.spec.ts` and `dedupe-key.spec.ts` prove the pieces; nothing proved the
 * composition except running it by hand against a live server, which is not a thing the next
 * person gets to re-run on a pull request.
 *
 * So this drives `materialise()` against a fake database — enough of one to be honest about
 * ordering and uniqueness, and no more.
 */

interface FakeEvent {
  id: string;
  userId: string;
  personId: string;
  type: string;
  sentiment: 'CELEBRATORY' | 'SOLEMN';
  day: number;
  month: number;
  year: number | null;
  isMuted: boolean;
  deletedAt: Date | null;
}

interface FakeReminder {
  id: string;
  eventOccurrenceId: string | null;
  userId: string;
  offset: string;
  scheduledAtUtc: Date;
  channel: string;
  status: string;
  dedupeKey: string;
  sentAt: Date | null;
}

interface FakeUser {
  id: string;
  timezone: string;
  reminderHour: number;
  ladderD7: boolean;
  ladderD1: boolean;
  ladderD0: boolean;
  quietHoursStart: number | null;
  quietHoursEnd: number | null;
  digestEnabled: boolean;
  digestHour: number;
}

const USER: FakeUser = {
  id: 'u1',
  timezone: 'Asia/Kolkata',
  reminderHour: 9,
  ladderD7: true,
  ladderD1: true,
  ladderD0: true,
  quietHoursStart: null,
  quietHoursEnd: null,
  digestEnabled: false,
  digestHour: 9,
};

function birthday(overrides: Partial<FakeEvent> = {}): FakeEvent {
  return {
    id: 'e-birthday',
    userId: 'u1',
    personId: 'p1',
    type: 'BIRTHDAY',
    sentiment: 'CELEBRATORY',
    day: 15,
    month: 6,
    year: 1990,
    isMuted: false,
    deletedAt: null,
    ...overrides,
  };
}

function build(options: {
  user?: Partial<FakeUser>;
  events: FakeEvent[];
  reminders?: FakeReminder[];
  personOverrides?: Record<string, number | null>;
}) {
  const user: FakeUser = { ...USER, ...options.user };
  const reminders: FakeReminder[] = options.reminders ?? [];
  const occurrences = new Map<string, { id: string; eventId: string; occurrenceYear: number }>();
  let seq = 0;

  const prisma = {
    event: {
      findMany: async () =>
        options.events
          .filter((e) => !e.deletedAt && !e.isMuted)
          .map((e) => ({
            ...e,
            user,
            person: {
              displayName: 'Someone',
              isDeceased: e.sentiment === 'SOLEMN',
              reminderHourOverride: options.personOverrides?.[e.personId] ?? null,
            },
          })),
    },
    person: { findMany: async () => [] },
    user: {
      findMany: async () => [user],
      findUnique: async () => user,
    },
    eventOccurrence: {
      upsert: async ({ where, create }: any) => {
        const key = `${where.eventId_occurrenceYear.eventId}:${where.eventId_occurrenceYear.occurrenceYear}`;
        const existing = occurrences.get(key);
        if (existing) return { ...existing, occursOn: create.occursOn, overrideDate: null };
        const row = {
          id: `occ-${(seq += 1)}`,
          eventId: create.eventId,
          occurrenceYear: create.occurrenceYear,
        };
        occurrences.set(key, row);
        return { ...row, occursOn: create.occursOn, overrideDate: null };
      },
    },
    reminder: {
      findUnique: async ({ where }: any) =>
        reminders.find((r) => r.dedupeKey === where.dedupeKey) ?? null,
      create: async ({ data }: any) => {
        if (reminders.some((r) => r.dedupeKey === data.dedupeKey)) {
          // The real UNIQUE index. Without it, a double-create bug looks like a pass.
          throw new Error(`duplicate dedupe_key ${data.dedupeKey}`);
        }
        const row: FakeReminder = { id: `r-${(seq += 1)}`, sentAt: null, ...data };
        reminders.push(row);
        return row;
      },
      update: async ({ where, data }: any) => {
        const row = reminders.find((r) => r.id === where.id);
        if (!row) throw new Error(`no reminder ${where.id}`);
        Object.assign(row, data);
        return row;
      },
      updateMany: async ({ where, data }: any) => {
        const matches = reminders.filter((r) => {
          if (where.status && r.status !== where.status) return false;
          if (where.userId && r.userId !== where.userId) return false;
          if (where.offset && r.offset !== where.offset) return false;
          if (where.dedupeKey?.in && !where.dedupeKey.in.includes(r.dedupeKey)) return false;
          // suppressOrphanedPending's nested event filter: nothing in these fixtures is
          // deleted or muted at the moment the sweep runs, so it matches nothing.
          if (where.eventOccurrence) return false;
          return true;
        });
        matches.forEach((r) => Object.assign(r, data));
        return { count: matches.length };
      },
      findMany: async () => [],
    },
  } as unknown as PrismaService;

  const service = new RemindersService(
    prisma,
    {} as FirebaseService,
    { recompute: async () => undefined } as unknown as PersonDerivationService,
  );
  return { service, reminders };
}

/** Rows as (offset, status, UTC instant), sorted, for readable assertions. */
const summarise = (rows: FakeReminder[]) =>
  rows
    .map((r) => `${r.offset} ${r.status} ${r.scheduledAtUtc.toISOString()}`)
    .sort();

const NOW = new Date('2026-11-25T06:00:00.000Z');

describe('materialise — the ladder over the clock', () => {
  it('builds three rungs at the user’s hour, seven, one and zero days out', async () => {
    const { service, reminders } = build({ events: [birthday()] });

    await service.materialise({ userId: 'u1', now: NOW });

    // 09:00 Asia/Kolkata is 03:30 UTC.
    expect(summarise(reminders)).toEqual([
      'D0 PENDING 2027-06-15T03:30:00.000Z',
      'D1 PENDING 2027-06-14T03:30:00.000Z',
      'D7 PENDING 2027-06-08T03:30:00.000Z',
    ]);
  });

  it('is idempotent — a second pass creates nothing and moves nothing', async () => {
    const { service, reminders } = build({ events: [birthday()] });

    await service.materialise({ userId: 'u1', now: NOW });
    const first = summarise(reminders);
    const second = await service.materialise({ userId: 'u1', now: NOW });

    expect(summarise(reminders)).toEqual(first);
    expect(second.remindersCreated).toBe(0);
    expect(second.remindersRescheduled).toBe(0);
  });

  it('gives a solemn occurrence two rungs and never mints the D7 key', async () => {
    const { service, reminders } = build({
      events: [birthday({ id: 'e-death', type: 'DEATH_ANNIVERSARY', sentiment: 'SOLEMN' })],
    });

    await service.materialise({ userId: 'u1', now: NOW });

    expect(reminders.map((r) => r.offset).sort()).toEqual(['D0', 'D1']);
    expect(reminders.some((r) => r.dedupeKey.endsWith(':D7'))).toBe(false);
  });

  it('honours a per-person hour, including midnight', async () => {
    const { service, reminders } = build({
      events: [birthday()],
      personOverrides: { p1: 0 },
    });

    await service.materialise({ userId: 'u1', now: NOW });

    // 00:00 IST on the 15th is 18:30 UTC on the 14th. `?? ` not `||`, or 0 becomes 9.
    expect(reminders.find((r) => r.offset === 'D0')?.scheduledAtUtc.toISOString()).toBe(
      '2027-06-14T18:30:00.000Z',
    );
  });
});

/**
 * The layer that worried the M1 reviewer most: two clocks stacked on each other. Quiet hours
 * are evaluated in the owner's zone on an instant that was itself built in the owner's zone,
 * and the rung offset is applied to the DATE before either.
 */
describe('materialise — quiet hours over the ladder', () => {
  it('defers every rung past a midnight-spanning window', async () => {
    const { service, reminders } = build({
      user: { reminderHour: 23, quietHoursStart: 22, quietHoursEnd: 7 },
      events: [birthday()],
    });

    await service.materialise({ userId: 'u1', now: NOW });

    // 23:00 IST is inside 22:00–07:00, so each rung moves to 07:00 the NEXT morning —
    // 01:30 UTC, one calendar day later than the rung's own date.
    expect(summarise(reminders)).toEqual([
      'D0 PENDING 2027-06-16T01:30:00.000Z',
      'D1 PENDING 2027-06-15T01:30:00.000Z',
      'D7 PENDING 2027-06-09T01:30:00.000Z',
    ]);
  });

  it('leaves a 09:00 reminder alone under a 22:00–07:00 window', async () => {
    const { service, reminders } = build({
      user: { quietHoursStart: 22, quietHoursEnd: 7 },
      events: [birthday()],
    });

    await service.materialise({ userId: 'u1', now: NOW });

    expect(reminders.every((r) => r.scheduledAtUtc.toISOString().endsWith('T03:30:00.000Z')))
      .toBe(true);
  });

  it('defers an early-morning hour to the SAME day', async () => {
    const { service, reminders } = build({
      user: { reminderHour: 3, quietHoursStart: 22, quietHoursEnd: 7 },
      events: [birthday()],
    });

    await service.materialise({ userId: 'u1', now: NOW });

    // 03:00 IST on the 15th -> 07:00 IST on the 15th, which is 01:30 UTC on the 15th.
    expect(reminders.find((r) => r.offset === 'D0')?.scheduledAtUtc.toISOString()).toBe(
      '2027-06-15T01:30:00.000Z',
    );
  });
});

describe('materialise — the ladder toggles, both directions', () => {
  it('does not create a rung the user has switched off', async () => {
    const { service, reminders } = build({
      user: { ladderD7: false },
      events: [birthday()],
    });

    await service.materialise({ userId: 'u1', now: NOW });

    expect(reminders.map((r) => r.offset).sort()).toEqual(['D0', 'D1']);
  });

  /**
   * Found on a device, not here: suppression worked and the reverse did not, so switching a
   * rung off in the morning and on again in the afternoon left it dead until next year.
   */
  it('suppresses a rung when it is switched off, and revives it when it comes back', async () => {
    const on = build({ events: [birthday()] });
    await on.service.materialise({ userId: 'u1', now: NOW });

    const off = build({
      user: { ladderD7: false },
      events: [birthday()],
      reminders: on.reminders,
    });
    await off.service.materialise({ userId: 'u1', now: NOW });
    expect(on.reminders.find((r) => r.offset === 'D7')?.status).toBe('SUPPRESSED');

    const back = build({ events: [birthday()], reminders: on.reminders });
    await back.service.materialise({ userId: 'u1', now: NOW });
    expect(on.reminders.find((r) => r.offset === 'D7')?.status).toBe('PENDING');
  });

  /** A reminder that actually went out must never be resurrected and sent a second time. */
  it('refuses to revive a rung that has already been sent', async () => {
    const seeded = build({ events: [birthday()] });
    await seeded.service.materialise({ userId: 'u1', now: NOW });
    const d7 = seeded.reminders.find((r) => r.offset === 'D7')!;
    d7.status = 'SUPPRESSED';
    d7.sentAt = new Date('2027-06-08T03:30:00.000Z');

    const again = build({ events: [birthday()], reminders: seeded.reminders });
    await again.service.materialise({ userId: 'u1', now: NOW });

    expect(d7.status).toBe('SUPPRESSED');
  });
});

describe('materialise — digest over everything', () => {
  it('suppresses every rung and leaves one digest per day that has something to say', async () => {
    const { service, reminders } = build({
      user: { digestEnabled: true },
      events: [birthday()],
    });

    await service.materialise({ userId: 'u1', now: NOW });

    const rungs = reminders.filter((r) => r.offset !== 'DIGEST');
    const digests = reminders.filter((r) => r.offset === 'DIGEST');

    expect(rungs).toHaveLength(3);
    expect(rungs.every((r) => r.status === 'SUPPRESSED')).toBe(true);
    expect(digests.map((d) => d.scheduledAtUtc.toISOString()).sort()).toEqual([
      '2027-06-08T03:30:00.000Z',
      '2027-06-14T03:30:00.000Z',
      '2027-06-15T03:30:00.000Z',
    ]);
    // Exactly one per local day, which the key makes a constraint rather than a hope.
    expect(new Set(digests.map((d) => d.dedupeKey)).size).toBe(3);
  });

  /**
   * The flip-flop this was written to prevent: upsertRung marks a rung suppressed itself
   * rather than letting a later sweep do it, so the revive branch and the digest pass cannot
   * undo each other every ten minutes.
   */
  it('does not oscillate across repeated passes while digest is on', async () => {
    const { service, reminders } = build({
      user: { digestEnabled: true },
      events: [birthday()],
    });

    await service.materialise({ userId: 'u1', now: NOW });
    const first = summarise(reminders);
    await service.materialise({ userId: 'u1', now: NOW });
    await service.materialise({ userId: 'u1', now: NOW });

    expect(summarise(reminders)).toEqual(first);
  });

  it('puts the ladder back and stops the digests when it is switched off', async () => {
    const on = build({ user: { digestEnabled: true }, events: [birthday()] });
    await on.service.materialise({ userId: 'u1', now: NOW });

    const off = build({ events: [birthday()], reminders: on.reminders });
    await off.service.materialise({ userId: 'u1', now: NOW });

    expect(
      on.reminders.filter((r) => r.offset !== 'DIGEST').every((r) => r.status === 'PENDING'),
    ).toBe(true);
    expect(
      on.reminders.filter((r) => r.offset === 'DIGEST').every((r) => r.status === 'SUPPRESSED'),
    ).toBe(true);
  });

  it('applies quiet hours to the digest too', async () => {
    const { service, reminders } = build({
      user: { digestEnabled: true, digestHour: 6, quietHoursStart: 22, quietHoursEnd: 7 },
      events: [birthday()],
    });

    await service.materialise({ userId: 'u1', now: NOW });

    // 06:00 IST is inside the window; 07:00 IST is 01:30 UTC on the same day.
    expect(
      reminders
        .filter((r) => r.offset === 'DIGEST')
        .every((d) => d.scheduledAtUtc.toISOString().endsWith('T01:30:00.000Z')),
    ).toBe(true);
  });
});

describe('materialise — a rung whose moment has passed', () => {
  it('records it SUPPRESSED rather than firing it late', async () => {
    // Birthday three days away: the T-7 rung is already behind us.
    const { service, reminders } = build({
      events: [birthday({ day: 28, month: 11, year: 1990 })],
    });

    await service.materialise({ userId: 'u1', now: NOW });

    const d7 = reminders.find((r) => r.offset === 'D7')!;
    expect(d7.status).toBe('SUPPRESSED');
    expect(reminders.find((r) => r.offset === 'D0')?.status).toBe('PENDING');
  });
});
