import { Injectable, Logger } from '@nestjs/common';
import type { Prisma, ReminderOffset } from '@prisma/client';
import { PrismaService } from '../../prisma/prisma.service';
import { FirebaseService } from '../../firebase/firebase.service';
import {
  civilFromDbDate,
  dbDateFromCivil,
  effectiveDate,
  nextOccurrence,
  reminderInstantUtc,
  shiftCivil,
  yearsAt,
} from '../../common/date/occurrence';
import { buildDigestCopy, buildReminderCopy, buildTestCopy } from './reminder-copy';
import { dedupeKeyFor, digestDedupeKey, testDedupeKey } from './dedupe-key';
import { LADDER_ORDER, OFFSET_DAYS, ladderFor, reminderHourFor } from './ladder';
import { applyQuietHours, localDateKey, type QuietWindow } from './quiet-hours';
import { DateTime } from 'luxon';
import { milestoneLabelFor } from '../events/derived';
import { PersonDerivationService } from '../people/person-derivation.service';

/**
 * A reminder whose moment has already passed by more than this is recorded as SUPPRESSED
 * rather than fired. Firing a "morning-of" reminder at 4pm is worse than not firing it, but
 * a reminder that is ten minutes late is still useful — hence a grace window rather than a
 * hard cutoff at zero.
 */
const LATE_GRACE_MS = 2 * 60 * 60 * 1000;

const TEST_REMINDER_DELAY_MS = 2 * 60 * 1000;

export interface MaterialiseSummary {
  eventsConsidered: number;
  occurrencesWritten: number;
  remindersCreated: number;
  remindersRescheduled: number;
  remindersSuppressed: number;
}

export interface SendSummary {
  claimed: number;
  delivered: number;
  failed: number;
  tokensRemoved: number;
}

@Injectable()
export class RemindersService {
  private readonly logger = new Logger(RemindersService.name);

  /**
   * No `AppEnv` any more. It was here for DEFAULT_REMINDER_HOUR, which M2 replaced with a
   * per-user `reminder_hour` and a per-person override (FR-REM-03). The env var still seeds
   * a brand-new user in AuthService; nothing in the send path reads it, so changing it on the
   * server cannot move the reminders of someone who has already chosen their own hour.
   */
  constructor(
    private readonly prisma: PrismaService,
    private readonly firebase: FirebaseService,
    private readonly derivation: PersonDerivationService,
  ) {}

  // -------------------------------------------------------------------------
  // Materialisation
  // -------------------------------------------------------------------------

  /**
   * Materialises the next occurrence of every live event and creates its morning-of reminder.
   *
   * Idempotent by construction: occurrences are keyed on (event, year) and reminders on a
   * unique dedupe key, so running this every ten minutes converges rather than accumulating.
   */
  async materialise(options: { userId?: string; now?: Date } = {}): Promise<MaterialiseSummary> {
    const now = options.now ?? new Date();
    const summary: MaterialiseSummary = {
      eventsConsidered: 0,
      occurrencesWritten: 0,
      remindersCreated: 0,
      remindersRescheduled: 0,
      remindersSuppressed: 0,
    };

    // Suppress first, then schedule. A reminder whose event was muted or deleted since the
    // last tick must stop even if nothing new needs materialising.
    summary.remindersSuppressed += await this.suppressOrphanedPending(options.userId);

    const events = await this.prisma.event.findMany({
      where: {
        deletedAt: null,
        // Muted events are not scheduled at all (FR-EVT-05's remembrance-birthday opt-out is
        // expressed as a mute, so this one condition covers both cases).
        isMuted: false,
        ...(options.userId ? { userId: options.userId } : {}),
        person: { deletedAt: null },
        user: { deletedAt: null },
      },
      include: {
        user: {
          select: {
            id: true,
            timezone: true,
            reminderHour: true,
            ladderD7: true,
            ladderD1: true,
            ladderD0: true,
            quietHoursStart: true,
            quietHoursEnd: true,
            digestEnabled: true,
            digestHour: true,
          },
        },
        person: { select: { displayName: true, isDeceased: true, reminderHourOverride: true } },
      },
    });

    /**
     * Which local days each user has something to say on, collected as the events are walked.
     *
     * Built from the OCCURRENCES rather than from the reminder rows, because when digest mode
     * is on the per-event rows are suppressed — so reading the digest's contents back off them
     * would work on the first tick and find nothing on the second.
     */
    const digestDays = new Map<string, Set<string>>();

    for (const event of events) {
      summary.eventsConsidered += 1;
      try {
        await this.materialiseOne(event, now, summary, digestDays);
      } catch (cause) {
        // One bad row must not stop the scheduler for every other user.
        this.logger.error(
          `Failed to materialise event ${event.id}: ${cause instanceof Error ? cause.message : cause}`,
        );
      }
    }

    // The digest (FR-REM-08). Runs after every event so the day's contents are known, and
    // before the people sweep because that sweep is about derived profile values and has
    // nothing to do with scheduling.
    await this.materialiseDigests(options.userId, digestDays, now, summary);

    /**
     * Re-derive every person this pass touched.
     *
     * Not an optimisation — a correctness requirement, because two of the derived values
     * change WITHOUT a write:
     *
     *   - `is_minor` flips on the person's eighteenth birthday (FR-PPL-09). Derived only at
     *     write time, a child flagged at thirteen stays flagged until somebody happens to
     *     edit them, and the profiling exclusions stay on for years after they should lift.
     *   - `completeness_score` was introduced in M1, so every row written before it exists
     *     reads 0 until it is next touched. That is visible in the app as a profile which
     *     claims to be 0% complete while showing a relation and a birth year.
     *
     * The materialiser already walks every live event on a schedule, which makes it the one
     * place that notices the passage of time at all.
     *
     * Queried over PEOPLE, deliberately, and not derived from `events` above — that query is
     * filtered to `isMuted: false`, so building the set from it skips the two populations the
     * sweep exists for: the self person created at sign-in, who has no events at all and
     * whose completeness score would read 0 forever, and a deceased person whose only live
     * event is a muted remembrance birthday.
     */
    const people = await this.prisma.person.findMany({
      where: {
        deletedAt: null,
        ...(options.userId ? { userId: options.userId } : {}),
        user: { deletedAt: null },
      },
      select: { id: true },
    });

    for (const { id: personId } of people) {
      try {
        await this.derivation.recompute(personId, this.prisma, now);
      } catch (cause) {
        this.logger.error(
          `Failed to re-derive person ${personId}: ${cause instanceof Error ? cause.message : cause}`,
        );
      }
    }

    return summary;
  }

  private async materialiseOne(
    event: Prisma.EventGetPayload<{
      include: {
        user: {
          select: {
            id: true;
            timezone: true;
            reminderHour: true;
            ladderD7: true;
            ladderD1: true;
            ladderD0: true;
            quietHoursStart: true;
            quietHoursEnd: true;
            digestEnabled: true;
            digestHour: true;
          };
        };
        person: {
          select: { displayName: true; isDeceased: true; reminderHourOverride: true };
        };
      };
    }>,
    now: Date,
    summary: MaterialiseSummary,
    digestDays: Map<string, Set<string>>,
  ): Promise<void> {
    const zone = event.user.timezone;
    const occ = nextOccurrence({ day: event.day, month: event.month, year: event.year }, zone, now);

    // Derived here and stored, so the send worker does not recompute it per push (FR-EVT-03).
    const milestoneLabel = milestoneLabelFor(
      event.type,
      yearsAt({ day: event.day, month: event.month, year: event.year }, occ.occurrenceYear),
      event.person.isDeceased,
    );

    const occurrence = await this.prisma.eventOccurrence.upsert({
      where: { eventId_occurrenceYear: { eventId: event.id, occurrenceYear: occ.occurrenceYear } },
      create: {
        eventId: event.id,
        occurrenceYear: occ.occurrenceYear,
        occursOn: dbDateFromCivil(occ.occursOn),
        milestoneLabel,
      },
      // An edited event date must move the existing row, not orphan it.
      update: { occursOn: dbDateFromCivil(occ.occursOn), milestoneLabel, computedAt: new Date() },
    });
    summary.occurrencesWritten += 1;

    const effective = effectiveDate(
      civilFromDbDate(occurrence.occursOn),
      occurrence.overrideDate ? civilFromDbDate(occurrence.overrideDate) : null,
    );
    const hour = reminderHourFor(event.person.reminderHourOverride, event.user.reminderHour);

    // The rungs this occurrence is ENTITLED to. A SOLEMN event never gets D7 whatever the
    // user's toggles say — see ladderFor(). The rung is not created rather than created and
    // suppressed, because a suppressed row is still a row that a revive or a settings change
    // could turn back on, and FR-REM-11 means there is nothing to turn on.
    const wanted = ladderFor(event.sentiment, event.user);

    for (const offset of wanted) {
      const fireAt = await this.upsertRung(
        event,
        occurrence.id,
        occ.occurrenceYear,
        effective,
        offset,
        hour,
        zone,
        now,
        summary,
        event.user,
        event.user.digestEnabled,
      );
      // Record the day even when the rung itself is suppressed by digest mode — the digest
      // is precisely the thing that will speak for it.
      if (fireAt && fireAt.getTime() >= now.getTime() - LATE_GRACE_MS) {
        const days = digestDays.get(event.userId) ?? new Set<string>();
        days.add(localDateKey(fireAt, zone));
        digestDays.set(event.userId, days);
      }
    }

    // Reconcile the other way: a rung the user has just switched off — or D7 on an event that
    // has just become SOLEMN because the person was marked deceased — has a PENDING row that
    // must stop. Expressed as "whatever is not wanted", so a rung added to the ladder later
    // cannot be forgotten here.
    const unwanted = LADDER_ORDER.filter((o) => !wanted.includes(o));
    if (unwanted.length > 0) {
      const stopped = await this.prisma.reminder.updateMany({
        where: {
          status: 'PENDING',
          dedupeKey: { in: unwanted.map((o) => dedupeKeyFor(event.id, occ.occurrenceYear, o)) },
        },
        data: { status: 'SUPPRESSED' },
      });
      summary.remindersSuppressed += stopped.count;
    }
  }

  /**
   * Creates or moves one rung of the ladder. Returns the instant it will fire, or null if
   * there is no live row for it.
   */
  private async upsertRung(
    event: { id: string; userId: string },
    occurrenceId: string,
    occurrenceYear: number,
    effective: ReturnType<typeof civilFromDbDate>,
    offset: Exclude<ReminderOffset, 'DIGEST'>,
    hour: number,
    zone: string,
    now: Date,
    summary: MaterialiseSummary,
    quiet: QuietWindow = { quietHoursStart: null, quietHoursEnd: null },
    digestEnabled = false,
  ): Promise<Date | null> {
    // Calendar arithmetic on the DATE, then the hour in the owner's zone — not "the D0
    // instant minus seven days", which drifts by an hour across a DST boundary and would
    // deliver a 09:00 reminder at 08:00 twice a year.
    const fireOn = shiftCivil(effective, -OFFSET_DAYS[offset]);
    // Quiet hours are applied here, once, so `scheduled_at_utc` is already the deferred
    // instant and BOTH delivery paths inherit the deferral from one implementation. The
    // local alarm is the path that fires when the server cannot reach the phone, and a
    // fallback that wakes someone at 3am is worse than no fallback at all.
    const scheduledAtUtc = applyQuietHours(reminderInstantUtc(fireOn, hour, zone), zone, quiet);
    const tooLate = scheduledAtUtc.getTime() < now.getTime() - LATE_GRACE_MS;

    // In digest mode the rung still exists but says nothing: the day's digest speaks for it
    // (FR-REM-08, and docs/M2_PLAN.md §2.1 — the digest replaces the WHOLE ladder, not just
    // morning-of). Kept as a suppressed row rather than deleted so that turning digest off
    // brings the ladder straight back, and flagged here rather than suppressed afterwards so
    // the revive branch below does not fight the digest pass every ten minutes.
    const wantSuppressed = tooLate || digestEnabled;
    const dedupeKey = dedupeKeyFor(event.id, occurrenceYear, offset);

    const existing = await this.prisma.reminder.findUnique({ where: { dedupeKey } });

    if (!existing) {
      await this.prisma.reminder.create({
        data: {
          eventOccurrenceId: occurrenceId,
          userId: event.userId,
          offset,
          scheduledAtUtc,
          channel: 'FCM',
          // A D7 rung for a birthday three days away is already in the past. Recorded as
          // SUPPRESSED rather than fired late: "buy a present, you have a week" is worse
          // than useless when the day is tomorrow.
          status: wantSuppressed ? 'SUPPRESSED' : 'PENDING',
          dedupeKey,
        },
      });
      if (wantSuppressed) summary.remindersSuppressed += 1;
      else summary.remindersCreated += 1;
      return scheduledAtUtc;
    }

    // A rung the user switched off and has now switched back on. Suppression has to be
    // reversible or the toggle is a one-way door: without this, turning D7 off in the
    // morning and on again in the afternoon leaves the row SUPPRESSED until next year.
    //
    // `sentAt: null` is the guard that keeps it honest — a reminder that actually went out
    // and was later suppressed must never be resurrected and sent a second time.
    if (existing.status === 'SUPPRESSED' && !wantSuppressed && existing.sentAt === null) {
      await this.prisma.reminder.update({
        where: { id: existing.id },
        data: { scheduledAtUtc, status: 'PENDING' },
      });
      summary.remindersCreated += 1;
      return scheduledAtUtc;
    }

    // Digest just went on, or the rung's moment has passed. Either way it stops.
    if (existing.status === 'PENDING' && wantSuppressed) {
      await this.prisma.reminder.update({
        where: { id: existing.id },
        data: { scheduledAtUtc, status: 'SUPPRESSED' },
      });
      summary.remindersSuppressed += 1;
      return scheduledAtUtc;
    }

    // Only a reminder that has not gone out yet can move.
    if (
      existing.status === 'PENDING' &&
      existing.scheduledAtUtc.getTime() !== scheduledAtUtc.getTime()
    ) {
      await this.prisma.reminder.update({
        where: { id: existing.id },
        data: { scheduledAtUtc, status: 'PENDING' },
      });
      summary.remindersRescheduled += 1;
    }
    return scheduledAtUtc;
  }

  /**
   * One digest row per user per local day that has anything to say (FR-REM-08).
   *
   * Digest mode replaces the WHOLE ladder, not just morning-of — docs/M2_PLAN.md §2.1. A user
   * who turns on a digest is asking for one notification a day, and leaving D7 and D1 firing
   * per-event does not give them that. So the rungs are suppressed by `upsertRung` and this
   * creates one row per day in their place; the row's CONTENT is built at send time from
   * whatever is due that day, so nothing has to be stored and nothing can go stale.
   *
   * Keyed `digest:${userId}:${localDate}`, which makes "exactly one digest per day" a
   * UNIQUE constraint rather than a hope — the same trick as the ladder's own keys.
   *
   * Reconciles in both directions: turning digest off suppresses the digest rows, and
   * `upsertRung` brings the ladder back on the same pass.
   */
  private async materialiseDigests(
    userId: string | undefined,
    digestDays: Map<string, Set<string>>,
    now: Date,
    summary: MaterialiseSummary,
  ): Promise<void> {
    const users = await this.prisma.user.findMany({
      where: { deletedAt: null, ...(userId ? { id: userId } : {}) },
      select: {
        id: true,
        timezone: true,
        digestEnabled: true,
        digestHour: true,
        quietHoursStart: true,
        quietHoursEnd: true,
      },
    });

    for (const user of users) {
      if (!user.digestEnabled) {
        // Digest just went off. Stop the digest rows; upsertRung has already restored the
        // ladder on this same pass, so the user never has a tick with neither.
        const stopped = await this.prisma.reminder.updateMany({
          where: { userId: user.id, offset: 'DIGEST', status: 'PENDING' },
          data: { status: 'SUPPRESSED' },
        });
        summary.remindersSuppressed += stopped.count;
        continue;
      }

      for (const localDate of digestDays.get(user.id) ?? []) {
        try {
          await this.upsertDigest(user, localDate, now, summary);
        } catch (cause) {
          this.logger.error(
            `Failed to materialise the ${localDate} digest for ${user.id}: ` +
              (cause instanceof Error ? cause.message : String(cause)),
          );
        }
      }
    }
  }

  private async upsertDigest(
    user: {
      id: string;
      timezone: string;
      digestHour: number;
      quietHoursStart: number | null;
      quietHoursEnd: number | null;
    },
    localDate: string,
    now: Date,
    summary: MaterialiseSummary,
  ): Promise<void> {
    const [year, month, day] = localDate.split('-').map(Number);
    if (!year || !month || !day) return;

    // Quiet hours apply to the digest exactly as they do to a rung. A digest hour of 07:00
    // under a 22:00–07:00 window is outside it and does not move; one of 06:00 does.
    const scheduledAtUtc = applyQuietHours(
      reminderInstantUtc({ year, month, day }, user.digestHour, user.timezone),
      user.timezone,
      user,
    );
    if (scheduledAtUtc.getTime() < now.getTime() - LATE_GRACE_MS) return;

    const dedupeKey = digestDedupeKey(user.id, localDate);
    const existing = await this.prisma.reminder.findUnique({ where: { dedupeKey } });

    if (!existing) {
      await this.prisma.reminder.create({
        data: {
          // No occurrence: a digest belongs to a user and a day, not to one event. The
          // column has been nullable since M0 for the self-test, which has the same shape.
          eventOccurrenceId: null,
          userId: user.id,
          offset: 'DIGEST',
          scheduledAtUtc,
          channel: 'FCM',
          status: 'PENDING',
          dedupeKey,
        },
      });
      summary.remindersCreated += 1;
      return;
    }

    if (existing.status === 'SUPPRESSED' && existing.sentAt === null) {
      await this.prisma.reminder.update({
        where: { id: existing.id },
        data: { scheduledAtUtc, status: 'PENDING' },
      });
      summary.remindersCreated += 1;
      return;
    }

    if (
      existing.status === 'PENDING' &&
      existing.scheduledAtUtc.getTime() !== scheduledAtUtc.getTime()
    ) {
      await this.prisma.reminder.update({
        where: { id: existing.id },
        data: { scheduledAtUtc },
      });
      summary.remindersRescheduled += 1;
    }
  }

  /**
   * Suppresses every PENDING reminder that should no longer fire.
   *
   * FR-PPL-08 requires a delete to stop reminders **immediately**, not at the end of the
   * 30-day restore window, and `is_muted` has to behave the same way or muting an event is a
   * promise the app does not keep until the next materialiser tick. The materialiser runs
   * every ten minutes; a birthday muted at 08:55 would otherwise still arrive at 09:00.
   *
   * Expressed as a reconciliation over current state rather than as a list of ids passed in
   * from the caller. That way a write path added later cannot forget to call it with the
   * right arguments — the worst it can do is not call it at all, and then the next tick
   * catches up.
   */
  async suppressOrphanedPending(userId?: string): Promise<number> {
    const result = await this.prisma.reminder.updateMany({
      where: {
        status: 'PENDING',
        ...(userId ? { userId } : {}),
        eventOccurrenceId: { not: null },
        eventOccurrence: {
          event: {
            OR: [
              { deletedAt: { not: null } },
              { isMuted: true },
              { person: { deletedAt: { not: null } } },
            ],
          },
        },
      },
      data: { status: 'SUPPRESSED' },
    });

    if (result.count > 0) {
      this.logger.log(
        `Suppressed ${result.count} pending reminder(s) for deleted or muted events` +
          (userId ? ` (user ${userId})` : ''),
      );
    }
    return result.count;
  }

  /**
   * Returns reminders to PENDING after a restore or an un-mute, so long as their moment has
   * not already passed. A restored person whose birthday is next March gets their reminder
   * back; one whose birthday was last week does not suddenly receive a late notification.
   *
   * M2 adds a second filter that is not optional. This query's job is "bring back what was
   * only suppressed because the event was away", and after the ladder exists a row can also
   * be suppressed because the ladder does not want it — a rung the user switched off, or D7
   * on an occurrence that became SOLEMN when the person was marked deceased. Reviving those
   * would restore a T-7 gifting reminder for a death anniversary through the back door, which
   * REQUIREMENTS.md §11 lists as a correctness failure rather than a content one.
   *
   * So the ladder is consulted here too, from the same function the materialiser uses.
   */
  async revivePending(userId: string, now: Date = new Date()): Promise<number> {
    const user = await this.prisma.user.findUnique({
      where: { id: userId },
      select: { ladderD7: true, ladderD1: true, ladderD0: true },
    });
    if (!user) return 0;

    const candidates = await this.prisma.reminder.findMany({
      where: {
        userId,
        status: 'SUPPRESSED',
        sentAt: null,
        eventOccurrence: {
          event: { deletedAt: null, isMuted: false, person: { deletedAt: null } },
        },
      },
      select: {
        id: true,
        scheduledAtUtc: true,
        offset: true,
        eventOccurrence: { select: { event: { select: { sentiment: true } } } },
      },
    });

    const revivable = candidates
      .filter((r) => r.scheduledAtUtc.getTime() >= now.getTime() - LATE_GRACE_MS)
      .filter((r) => {
        const sentiment = r.eventOccurrence?.event.sentiment;
        if (!sentiment) return true; // self-test and digest rows have no ladder to consult
        return (ladderFor(sentiment, user) as ReminderOffset[]).includes(r.offset);
      })
      .map((r) => r.id);
    if (revivable.length === 0) return 0;

    const result = await this.prisma.reminder.updateMany({
      where: { id: { in: revivable } },
      data: { status: 'PENDING' },
    });
    this.logger.log(`Revived ${result.count} reminder(s) for user ${userId}`);
    return result.count;
  }

  /**
   * Recomputes every pending reminder for a user after their timezone changes (FR-EVT-08).
   *
   * The dedupe key deliberately does not change, so a client that has already seen the old
   * schedule cannot be shown the same reminder twice.
   */
  async rescheduleForTimezoneChange(userId: string, now: Date = new Date()): Promise<number> {
    const user = await this.prisma.user.findUnique({ where: { id: userId } });
    if (!user) return 0;

    const pending = await this.prisma.reminder.findMany({
      where: { userId, status: 'PENDING', eventOccurrenceId: { not: null } },
      include: {
        eventOccurrence: {
          include: { event: { include: { person: { select: { reminderHourOverride: true } } } } },
        },
      },
    });

    let moved = 0;
    for (const reminder of pending) {
      if (!reminder.eventOccurrence) continue;
      // A digest belongs to a day rather than to an occurrence, so it is rescheduled by the
      // materialiser's own digest pass, not here. Skipped explicitly: falling through would
      // compute it as if it were a rung of some event's ladder.
      if (reminder.offset === 'DIGEST') continue;

      const effective = effectiveDate(
        civilFromDbDate(reminder.eventOccurrence.occursOn),
        reminder.eventOccurrence.overrideDate
          ? civilFromDbDate(reminder.eventOccurrence.overrideDate)
          : null,
      );
      // Each rung keeps its own distance from the occurrence. Rescheduling every pending
      // reminder to the D0 instant — which is what a single hour and no offset would do —
      // would quietly collapse the whole ladder onto the morning of the birthday the first
      // time anyone flew abroad.
      const fireOn = shiftCivil(effective, -OFFSET_DAYS[reminder.offset]);
      const hour = reminderHourFor(
        reminder.eventOccurrence.event.person.reminderHourOverride,
        user.reminderHour,
      );
      const scheduledAtUtc = reminderInstantUtc(fireOn, hour, user.timezone);
      if (scheduledAtUtc.getTime() === reminder.scheduledAtUtc.getTime()) continue;

      const tooLate = scheduledAtUtc.getTime() < now.getTime() - LATE_GRACE_MS;
      await this.prisma.reminder.update({
        where: { id: reminder.id },
        data: { scheduledAtUtc, status: tooLate ? 'SUPPRESSED' : 'PENDING' },
      });
      moved += 1;
    }

    if (moved > 0) {
      this.logger.log(
        `Rescheduled ${moved} pending reminder(s) for user ${userId} → ${user.timezone}`,
      );
    }
    return moved;
  }

  // -------------------------------------------------------------------------
  // Self-test (FR-REM-07)
  // -------------------------------------------------------------------------

  /** Queues a reminder two minutes out. The fastest honest proof that the chain works. */
  async queueTestReminder(
    userId: string,
  ): Promise<{ reminderId: string; scheduledAtUtc: Date; dedupeKey: string }> {
    const scheduledAtUtc = new Date(Date.now() + TEST_REMINDER_DELAY_MS);
    const dedupeKey = testDedupeKey(userId, scheduledAtUtc);

    const reminder = await this.prisma.reminder.create({
      data: {
        // Null by design: a self-test must work before any event exists.
        eventOccurrenceId: null,
        userId,
        offset: 'D0',
        scheduledAtUtc,
        channel: 'FCM',
        status: 'PENDING',
        dedupeKey,
      },
    });

    this.logger.log(`Test reminder ${reminder.id} queued for ${scheduledAtUtc.toISOString()}`);
    return { reminderId: reminder.id, scheduledAtUtc, dedupeKey };
  }

  // -------------------------------------------------------------------------
  // Delivery reporting (FR-REM-10)
  // -------------------------------------------------------------------------

  /**
   * Records what the device says happened to a batch of reminders.
   *
   * Three things make this more than a status setter:
   *
   * 1. **It is scoped to the caller.** `userId` is in the WHERE clause, not just the body,
   *    so a report can only ever touch the reporter's own reminders.
   * 2. **An unknown key is accepted, not rejected.** The client arms a local alarm for an
   *    event created offline that the server has never materialised (docs/M2_PLAN.md §1.3),
   *    and that alarm's report names a key with no row behind it. That is a real delivery
   *    the G1 metric should count, and 400-ing it would hide exactly the case this exists to
   *    measure. Counted and logged instead.
   * 3. **`channel` records who WON.** A reminder created as FCM and delivered by the phone's
   *    own alarm flips to LOCAL, so `count(channel = LOCAL) / count(*)` is the FCM failure
   *    rate rather than a restatement of how we scheduled things (docs/M2_PLAN.md §2.2).
   *
   * Idempotent: the same report arriving twice — which it will, because the client keeps a
   * report in its outbox until the server confirms — writes the same row twice and changes
   * nothing. `SHOWN` is sticky: once a notification has been drawn, a later
   * SUPPRESSED_DUPLICATE from the other path must not downgrade it, or every successful
   * de-duplication would erase the delivery it de-duplicated.
   */
  async recordDelivery(
    userId: string,
    reports: readonly {
      dedupeKey: string;
      outcome: string;
      channel: 'FCM' | 'LOCAL';
      occurredAtUtc: string;
    }[],
  ): Promise<{ recorded: number; unknown: number }> {
    let recorded = 0;
    let unknown = 0;

    for (const report of reports) {
      const occurredAt = new Date(report.occurredAtUtc);

      const existing = await this.prisma.reminder.findFirst({
        where: { dedupeKey: report.dedupeKey, userId },
        select: { id: true, status: true, deliveryOutcome: true },
      });

      if (!existing) {
        unknown += 1;
        this.logger.log(
          `Delivery report for an unknown key ${report.dedupeKey} (user ${userId}, ` +
            `${report.outcome} via ${report.channel}) — a local alarm for an event this ` +
            `server has not materialised yet`,
        );
        continue;
      }

      // Never downgrade a delivery that already happened.
      if (existing.deliveryOutcome === 'SHOWN' && report.outcome !== 'SHOWN') {
        recorded += 1;
        continue;
      }

      await this.prisma.reminder.update({
        where: { id: existing.id },
        data: {
          deliveryOutcome: report.outcome,
          deliveredAt: occurredAt,
          channel: report.channel,
          ...(report.outcome === 'SHOWN' ? { status: 'DELIVERED' as const } : {}),
        },
      });
      recorded += 1;
    }

    return { recorded, unknown };
  }

  // -------------------------------------------------------------------------
  // Sending
  // -------------------------------------------------------------------------

  /**
   * Claims and sends every reminder that is due.
   *
   * The claim is a compare-and-swap (`updateMany` filtered on `status = PENDING`), so two
   * workers racing on the same row produce exactly one send. A crash between claim and send
   * loses that one reminder rather than sending it twice — and the client de-duplicates on
   * `dedupe_key` regardless.
   */
  async sendDue(limit = 50, now: Date = new Date()): Promise<SendSummary> {
    const summary: SendSummary = { claimed: 0, delivered: 0, failed: 0, tokensRemoved: 0 };

    const due = await this.prisma.reminder.findMany({
      where: { status: 'PENDING', channel: 'FCM', scheduledAtUtc: { lte: now } },
      orderBy: { scheduledAtUtc: 'asc' },
      take: limit,
      select: { id: true },
    });

    for (const { id } of due) {
      const claim = await this.prisma.reminder.updateMany({
        where: { id, status: 'PENDING' },
        data: { status: 'SENT', sentAt: new Date() },
      });
      if (claim.count !== 1) continue; // another worker got there first
      summary.claimed += 1;

      try {
        const result = await this.deliver(id);
        if (result.delivered) summary.delivered += 1;
        else {
          summary.failed += 1;
          await this.prisma.reminder.update({ where: { id }, data: { status: 'FAILED' } });
        }
        summary.tokensRemoved += result.tokensRemoved;
      } catch (cause) {
        summary.failed += 1;
        this.logger.error(
          `Delivery failed for reminder ${id}: ${cause instanceof Error ? cause.message : cause}`,
        );
        await this.prisma.reminder.update({ where: { id }, data: { status: 'FAILED' } });
      }
    }

    return summary;
  }

  private async deliver(
    reminderId: string,
  ): Promise<{ delivered: boolean; tokensRemoved: number }> {
    const reminder = await this.prisma.reminder.findUnique({
      where: { id: reminderId },
      include: {
        eventOccurrence: { include: { event: { include: { person: true } } } },
        user: { include: { devices: true } },
      },
    });
    if (!reminder) return { delivered: false, tokensRemoved: 0 };

    const tokens = reminder.user.devices.map((d) => d.fcmToken);
    if (tokens.length === 0) {
      this.logger.warn(`Reminder ${reminderId}: user ${reminder.userId} has no registered device`);
      return { delivered: false, tokensRemoved: 0 };
    }

    const data =
      reminder.offset === 'DIGEST'
        ? await this.buildDigestPayload(reminder)
        : this.buildPayload(reminder);
    const results = await this.firebase.sendData(tokens, data);

    const dead = results.filter((r) => r.unregistered).map((r) => r.token);
    if (dead.length > 0) {
      await this.prisma.device.deleteMany({ where: { fcmToken: { in: dead } } });
      this.logger.log(`Removed ${dead.length} dead FCM token(s)`);
    }

    for (const failure of results.filter((r) => !r.success)) {
      this.logger.warn(`FCM rejected a token for reminder ${reminderId}: ${failure.error}`);
    }

    return { delivered: results.some((r) => r.success), tokensRemoved: dead.length };
  }

  /**
   * The day's digest, assembled at SEND time from whatever is due (FR-REM-08).
   *
   * Nothing about the contents is stored on the digest row. The day's events can change
   * between materialisation and 09:00 — a person deleted, an event muted, a date edited —
   * and a list frozen at materialisation time would announce a birthday the user has since
   * removed. Reading it fresh costs one query and cannot go stale.
   *
   * The rows it reads are SUPPRESSED, because that is what digest mode does to the ladder.
   * Their schedule is still the truth about WHEN each rung would have fired, which is what
   * decides whether an item is today's, tomorrow's or next week's.
   */
  private async buildDigestPayload(
    reminder: Prisma.ReminderGetPayload<{
      include: {
        eventOccurrence: { include: { event: { include: { person: true } } } };
        user: { include: { devices: true } };
      };
    }>,
  ): Promise<Record<string, string>> {
    const zone = reminder.user.timezone;
    const localDate = localDateKey(reminder.scheduledAtUtc, zone);
    const { start, end } = localDayBounds(localDate, zone);

    const rungs = await this.prisma.reminder.findMany({
      where: {
        userId: reminder.userId,
        offset: { in: ['D7', 'D1', 'D0'] },
        scheduledAtUtc: { gte: start, lt: end },
        eventOccurrence: {
          event: { deletedAt: null, isMuted: false, person: { deletedAt: null } },
        },
      },
      include: { eventOccurrence: { include: { event: { include: { person: true } } } } },
      orderBy: { scheduledAtUtc: 'asc' },
    });

    const items = rungs
      .filter((r) => r.eventOccurrence !== null)
      // The third place the ladder has to be consulted, and the least obvious.
      //
      // These rows are read by schedule, not by status, because digest mode suppresses them
      // all — so a rung the ladder no longer wants is indistinguishable from one it does. A
      // stale D7 row on an event that has since become SOLEMN (the person was marked
      // deceased after it was created) would otherwise reappear as a "in 7 days" line in the
      // digest: the T-7 gifting nudge FR-REM-11 forbids, smuggled in as a list item.
      .filter((r) =>
        (ladderFor(r.eventOccurrence!.event.sentiment, reminder.user) as ReminderOffset[]).includes(
          r.offset,
        ),
      )
      .map((r) => ({
        personName: r.eventOccurrence!.event.person.displayName,
        eventType: r.eventOccurrence!.event.type,
        sentiment: r.eventOccurrence!.event.sentiment,
        offset: r.offset,
      }));

    const copy = buildDigestCopy(items);
    return {
      dedupeKey: reminder.dedupeKey,
      reminderId: reminder.id,
      offset: 'DIGEST',
      type: 'DIGEST',
      title: copy.title,
      body: copy.body,
      itemCount: String(items.length),
      deepLink: 'wishkaro://home',
    };
  }

  private buildPayload(
    reminder: Prisma.ReminderGetPayload<{
      include: {
        eventOccurrence: { include: { event: { include: { person: true } } } };
        user: { include: { devices: true } };
      };
    }>,
  ): Record<string, string> {
    const base = {
      dedupeKey: reminder.dedupeKey,
      reminderId: reminder.id,
      offset: reminder.offset,
    };

    const occurrence = reminder.eventOccurrence;
    if (!occurrence) {
      const copy = buildTestCopy();
      return {
        ...base,
        type: 'TEST',
        title: copy.title,
        body: copy.body,
        deepLink: 'wishkaro://home',
      };
    }

    const event = occurrence.event;
    const copy = buildReminderCopy({
      personName: event.person.displayName,
      eventType: event.type,
      sentiment: event.sentiment,
      years: yearsAt(
        { day: event.day, month: event.month, year: event.year },
        occurrence.occurrenceYear,
      ),
      // The rung changes the tense, never the register — buildReminderCopy picks the SOLEMN
      // string set before it looks at this.
      offset: reminder.offset,
    });

    return {
      ...base,
      type: 'REMINDER',
      title: copy.title,
      body: copy.body,
      personId: event.personId,
      personName: event.person.displayName,
      eventId: event.id,
      eventType: event.type,
      sentiment: event.sentiment,
      occurrenceId: occurrence.id,
      deepLink: `wishkaro://person/${event.personId}`,
    };
  }
}

/** The UTC half-open interval covering one of the user's calendar days. */
function localDayBounds(localDate: string, zone: string): { start: Date; end: Date } {
  const startLocal = DateTime.fromISO(localDate, { zone }).startOf('day');
  return {
    start: startLocal.toUTC().toJSDate(),
    end: startLocal.plus({ days: 1 }).toUTC().toJSDate(),
  };
}
