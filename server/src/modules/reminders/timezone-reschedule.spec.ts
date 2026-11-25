import { RemindersService } from './reminders.service';
import type { PrismaService } from '../../prisma/prisma.service';
import type { FirebaseService } from '../../firebase/firebase.service';
import type { PersonDerivationService } from '../people/person-derivation.service';
import type { AppEnv } from '../../config/env';

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
  eventOccurrence: { occursOn: Date; overrideDate: Date | null } | null;
}

function buildService(options: { timezone: string; reminders: FakeReminder[] }): {
  service: RemindersService;
  updates: Array<{ id: string; data: Record<string, unknown> }>;
} {
  const updates: Array<{ id: string; data: Record<string, unknown> }> = [];

  const prisma = {
    user: {
      findUnique: async () => ({ id: 'u1', timezone: options.timezone }),
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

  const env = { DEFAULT_REMINDER_HOUR: 9, DEFAULT_TIMEZONE: 'Asia/Kolkata' } as AppEnv;
  const firebase = {} as FirebaseService;
  const derivation = { recompute: async () => undefined } as unknown as PersonDerivationService;

  return { service: new RemindersService(env, prisma, firebase, derivation), updates };
}

function reminder(id: string, occursOn: Date, scheduledAtUtc: Date): FakeReminder {
  return {
    id,
    userId: 'u1',
    status: 'PENDING',
    scheduledAtUtc,
    dedupeKey: `${id}:D0`,
    eventOccurrenceId: `occ-${id}`,
    eventOccurrence: { occursOn, overrideDate: null },
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
    };
    const { service, updates } = buildService({ timezone: 'Asia/Kolkata', reminders: [r] });

    await service.rescheduleForTimezoneChange('u1', now);

    // Same zone, but the override moves the day: 09:00 IST on the 20th, not the 14th.
    expect(updates[0]?.data.scheduledAtUtc).toEqual(new Date('2027-03-20T03:30:00.000Z'));
  });
});
