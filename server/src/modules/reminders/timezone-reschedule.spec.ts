import { RemindersService } from './reminders.service';
import type { PrismaService } from '../../prisma/prisma.service';
import type { FirebaseService } from '../../firebase/firebase.service';
import type { PersonDerivationService } from '../people/person-derivation.service';

/**
 * FR-EVT-08: "reminders fire in the owner's *current* timezone, and a timezone change
 * reschedules pending reminders."
 *
 * REQUIREMENTS.md §11 names timezone handling the highest-bug-density area in the product,
 * and BUILD_PROMPT_M1 adds an edge M0 never had: a person with SEVERAL events spanning a
 * timezone change mid-schedule.
 *
 * `rescheduleForTimezoneChange` is the one piece of that logic that is not pure — it reads
 * and writes rows. Rather than leave it untested because it touches a database, it is driven
 * here against a hand-built fake: the branch logic is what carries the risk, not Prisma.
 */

const UTC_MIDNIGHT = (y: number, m: number, d: number) => new Date(Date.UTC(y, m - 1, d));

interface FakeReminder {
  id: string;
  userId: string;
  status: string;
  scheduledAtUtc: Date;
  dedupeKey: string;
  eventOccurrenceId: string | null;
  offset: 'D7' | 'D1' | 'D0' | 'DIGEST';
  eventOccurrence: {
    occursOn: Date;
    overrideDate: Date | null;
    event: { person: { reminderHourOverride: number | null } };
  } | null;
}

function buildService(options: { timezone: string; reminders: FakeReminder[] }): {
  service: RemindersService;
  updates: Array<{ id: string; data: Record<string, unknown> }>;
} {
  const updates: Array<{ id: string; data: Record<string, unknown> }> = [];

  const prisma = {
    user: {
      findUnique: async () => ({
        id: 'u1',
        timezone: options.timezone,
        reminderHour: 9,
        ladderD7: true,
        ladderD1: true,
        ladderD0: true,
      }),
    },
    reminder: {
      findMany: async () => options.reminders.filter((r) => r.status === 'PENDING'),
      update: async ({ where, data }: { where: { id: string }; data: Record<string, unknown> }) => {
        updates.push({ id: where.id, data });
        const row = options.reminders.find((r) => r.id === where.id);
        if (row) {
          if (data.scheduledAtUtc instanceof Date) row.scheduledAtUtc = data.scheduledAtUtc;
          if (typeof data.status === 'string') row.status = data.status;
        }
        return row;
      },
    },
  } as unknown as PrismaService;

  const firebase = {} as FirebaseService;
  const derivation = { recompute: async () => undefined } as unknown as PersonDerivationService;

  return { service: new RemindersService(prisma, firebase, derivation), updates };
}

function reminder(
  id: string,
  occursOn: Date,
  scheduledAtUtc: Date,
  offset: 'D7' | 'D1' | 'D0' = 'D0',
  reminderHourOverride: number | null = null,
): FakeReminder {
  return {
    id,
    userId: 'u1',
    status: 'PENDING',
    scheduledAtUtc,
    offset,
    dedupeKey: `${id}:2027:${offset}`,
    eventOccurrenceId: `occ-${id}`,
    eventOccurrence: { occursOn, overrideDate: null, event: { person: { reminderHourOverride } } },
  };
}

describe('rescheduleForTimezoneChange (FR-EVT-08)', () => {
  const now = new Date('2026-11-25T06:00:00.000Z');

  it('moves a pending reminder when the owner changes timezone', async () => {
    // 09:00 on 14 March in Asia/Kolkata is 03:30 UTC.
    const original = new Date('2027-03-14T03:30:00.000Z');
    const { service, updates } = buildService({
      timezone: 'America/New_York',
      reminders: [reminder('r1', UTC_MIDNIGHT(2027, 3, 14), original)],
    });

    const moved = await service.rescheduleForTimezoneChange('u1', now);

    expect(moved).toBe(1);
    // 09:00 on 14 March in New York is 13:00 UTC — EDT, because the US has already sprung
    // forward by the 14th. An offset cached from January would give 14:00 and be wrong.
    expect(updates[0]?.data.scheduledAtUtc).toEqual(new Date('2027-03-14T13:00:00.000Z'));
    expect(updates[0]?.data.status).toBe('PENDING');
  });

  it('does not touch a reminder whose instant is unchanged', async () => {
    const original = new Date('2027-03-14T03:30:00.000Z');
    const { service, updates } = buildService({
      timezone: 'Asia/Kolkata',
      reminders: [reminder('r1', UTC_MIDNIGHT(2027, 3, 14), original)],
    });

    expect(await service.rescheduleForTimezoneChange('u1', now)).toBe(0);
    expect(updates).toEqual([]);
  });

  /**
   * The M1 edge named in the build prompt. One person, several events, one timezone change.
   * Every pending reminder has to move, not merely the next one.
   */
  it("moves ALL of a person's pending reminders, not just the soonest", async () => {
    const { service, updates } = buildService({
      timezone: 'America/New_York',
      reminders: [
        reminder('birthday', UTC_MIDNIGHT(2027, 3, 14), new Date('2027-03-14T03:30:00.000Z')),
        reminder('wedding', UTC_MIDNIGHT(2027, 12, 2), new Date('2027-12-02T03:30:00.000Z')),
        reminder('custom', UTC_MIDNIGHT(2027, 6, 1), new Date('2027-06-01T03:30:00.000Z')),
      ],
    });

    expect(await service.rescheduleForTimezoneChange('u1', now)).toBe(3);
    expect(updates.map((u) => u.id).sort()).toEqual(['birthday', 'custom', 'wedding']);
  });

  /**
   * The subtle half of the same case: the three events straddle a DST boundary in the NEW
   * zone, so they do NOT all shift by the same amount. A single cached offset applied to all
   * three would put one of them an hour out, and nothing downstream would notice.
   */
  it('converts each occurrence in its own season, not by one cached offset', async () => {
    const { service, updates } = buildService({
      timezone: 'America/New_York',
      reminders: [
        // Mid-winter: EST, UTC-5. 09:00 local -> 14:00 UTC.
        reminder('january', UTC_MIDNIGHT(2027, 1, 20), new Date('2027-01-20T03:30:00.000Z')),
        // Mid-summer: EDT, UTC-4. 09:00 local -> 13:00 UTC.
        reminder('july', UTC_MIDNIGHT(2027, 7, 20), new Date('2027-07-20T03:30:00.000Z')),
      ],
    });

    await service.rescheduleForTimezoneChange('u1', now);

    const byId = Object.fromEntries(updates.map((u) => [u.id, u.data.scheduledAtUtc as Date]));
    expect(byId.january).toEqual(new Date('2027-01-20T14:00:00.000Z'));
    expect(byId.july).toEqual(new Date('2027-07-20T13:00:00.000Z'));

    const januaryHour = byId.january.getUTCHours();
    const julyHour = byId.july.getUTCHours();
    expect(januaryHour).not.toBe(julyHour);
  });

  /**
   * The dedupe key is what stops the same reminder arriving twice. Moving a reminder must
   * not mint a new one — a client that has already seen the old schedule would then be
   * unable to recognise the repeat.
   */
  it('never changes a dedupe key while rescheduling', async () => {
    const { service, updates } = buildService({
      timezone: 'America/New_York',
      reminders: [reminder('r1', UTC_MIDNIGHT(2027, 3, 14), new Date('2027-03-14T03:30:00.000Z'))],
    });

    await service.rescheduleForTimezoneChange('u1', now);

    for (const update of updates) {
      expect(Object.keys(update.data)).toEqual(['scheduledAtUtc', 'status']);
      expect(update.data).not.toHaveProperty('dedupeKey');
    }
  });

  /**
   * Travelling west can move a reminder's moment into the past. It must be recorded as
   * SUPPRESSED rather than fired: a "morning of" reminder delivered at midnight after the
   * day has gone is worse than silence.
   */
  it('suppresses rather than fires a reminder the change pushed into the past', async () => {
    const todayNow = new Date('2026-11-25T12:00:00.000Z');
    const { service, updates } = buildService({
      timezone: 'Pacific/Kiritimati', // UTC+14; 09:00 local on the 25th is 19:00 UTC on the 24th.
      reminders: [reminder('r1', UTC_MIDNIGHT(2026, 11, 25), new Date('2026-11-25T03:30:00.000Z'))],
    });

    await service.rescheduleForTimezoneChange('u1', todayNow);

    expect(updates[0]?.data.status).toBe('SUPPRESSED');
    expect((updates[0]?.data.scheduledAtUtc as Date).getTime()).toBeLessThan(todayNow.getTime());
  });

  it('leaves reminders that have already been sent alone', async () => {
    const sent = reminder('r1', UTC_MIDNIGHT(2027, 3, 14), new Date('2027-03-14T03:30:00.000Z'));
    sent.status = 'SENT';
    const { service, updates } = buildService({ timezone: 'America/New_York', reminders: [sent] });

    expect(await service.rescheduleForTimezoneChange('u1', now)).toBe(0);
    expect(updates).toEqual([]);
  });

  /** A per-occurrence override (FR-EVT-10) is the effective date, so it is what moves. */
  it('reschedules from the override date when one is set', async () => {
    const r = reminder('r1', UTC_MIDNIGHT(2027, 3, 14), new Date('2027-03-14T03:30:00.000Z'));
    r.eventOccurrence = {
      occursOn: UTC_MIDNIGHT(2027, 3, 14),
      overrideDate: UTC_MIDNIGHT(2027, 3, 20),
      event: { person: { reminderHourOverride: null } },
    };
    const { service, updates } = buildService({ timezone: 'Asia/Kolkata', reminders: [r] });

    await service.rescheduleForTimezoneChange('u1', now);

    // Same zone, but the override moves the day: 09:00 IST on the 20th, not the 14th.
    expect(updates[0]?.data.scheduledAtUtc).toEqual(new Date('2027-03-20T03:30:00.000Z'));
  });

  /**
   * M2. Each rung keeps its own distance from the occurrence across a timezone change.
   * Rescheduling every pending reminder to the D0 instant would collapse the whole ladder
   * onto the morning of the birthday the first time anyone flew abroad.
   */
  it('keeps each rung its own distance from the occurrence', async () => {
    const occursOn = UTC_MIDNIGHT(2027, 3, 14);
    const { service, updates } = buildService({
      timezone: 'America/New_York',
      reminders: [
        reminder('r7', occursOn, new Date('2027-03-07T03:30:00.000Z'), 'D7'),
        reminder('r1', occursOn, new Date('2027-03-13T03:30:00.000Z'), 'D1'),
        reminder('r0', occursOn, new Date('2027-03-14T03:30:00.000Z'), 'D0'),
      ],
    });

    await service.rescheduleForTimezoneChange('u1', now);

    const byId = new Map(updates.map((u) => [u.id, u.data.scheduledAtUtc]));
    // All three are 09:00 in New York, and the ladder straddles a DST boundary: the US
    // springs forward on 14 March 2027, so D7 and D1 are EST (UTC-5, 14:00Z) while D0 on the
    // day itself is EDT (UTC-4, 13:00Z). "The D0 instant minus seven days" would have put D7
    // an hour out, which is exactly why the shift is done on the DATE and the hour applied
    // afterwards in the owner's zone.
    expect(byId.get('r7')).toEqual(new Date('2027-03-07T14:00:00.000Z'));
    expect(byId.get('r1')).toEqual(new Date('2027-03-13T14:00:00.000Z'));
    expect(byId.get('r0')).toEqual(new Date('2027-03-14T13:00:00.000Z'));
  });

  /** FR-REM-03: a per-person hour wins over the global one, and 0 is a real hour. */
  it('honours a per-person hour override, including midnight', async () => {
    const occursOn = UTC_MIDNIGHT(2027, 3, 14);
    const { service, updates } = buildService({
      timezone: 'Asia/Kolkata',
      reminders: [reminder('r0', occursOn, new Date('2027-03-14T03:30:00.000Z'), 'D0', 0)],
    });

    await service.rescheduleForTimezoneChange('u1', now);

    // 00:00 IST on the 14th is 18:30 UTC on the 13th. `?? ` not `||`, or 0 would silently
    // become the global 09:00.
    expect(updates[0]?.data.scheduledAtUtc).toEqual(new Date('2027-03-13T18:30:00.000Z'));
  });
});
