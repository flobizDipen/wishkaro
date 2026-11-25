import { Inject, Injectable, Logger } from '@nestjs/common';
import type { Prisma } from '@prisma/client';
import { APP_ENV, type AppEnv } from '../../config/env';
import { PrismaService } from '../../prisma/prisma.service';
import { FirebaseService } from '../../firebase/firebase.service';
import {
  civilFromDbDate,
  dbDateFromCivil,
  effectiveDate,
  nextOccurrence,
  reminderInstantUtc,
  yearsAt,
} from '../../common/date/occurrence';
import { buildReminderCopy, buildTestCopy } from './reminder-copy';
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

  constructor(
    @Inject(APP_ENV) private readonly env: AppEnv,
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
        user: { select: { id: true, timezone: true } },
        person: { select: { displayName: true, isDeceased: true } },
      },
    });

    for (const event of events) {
      summary.eventsConsidered += 1;
      try {
        await this.materialiseOne(event, now, summary);
      } catch (cause) {
        // One bad row must not stop the scheduler for every other user.
        this.logger.error(
          `Failed to materialise event ${event.id}: ${cause instanceof Error ? cause.message : cause}`,
        );
      }
    }

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
     */
    const touched = new Set(events.map((e) => e.personId));
    for (const personId of touched) {
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
        user: { select: { id: true; timezone: true } };
        person: { select: { displayName: true; isDeceased: true } };
      };
    }>,
    now: Date,
    summary: MaterialiseSummary,
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
    const scheduledAtUtc = reminderInstantUtc(effective, this.env.DEFAULT_REMINDER_HOUR, zone);
    const tooLate = scheduledAtUtc.getTime() < now.getTime() - LATE_GRACE_MS;
    const dedupeKey = `${occurrence.id}:D0`;

    const existing = await this.prisma.reminder.findUnique({ where: { dedupeKey } });

    if (!existing) {
      await this.prisma.reminder.create({
        data: {
          eventOccurrenceId: occurrence.id,
          userId: event.userId,
          offset: 'D0',
          scheduledAtUtc,
          channel: 'FCM',
          status: tooLate ? 'SUPPRESSED' : 'PENDING',
          dedupeKey,
        },
      });
      if (tooLate) summary.remindersSuppressed += 1;
      else summary.remindersCreated += 1;
      return;
    }

    // Only a reminder that has not gone out yet can move.
    if (
      existing.status === 'PENDING' &&
      existing.scheduledAtUtc.getTime() !== scheduledAtUtc.getTime()
    ) {
      await this.prisma.reminder.update({
        where: { id: existing.id },
        data: { scheduledAtUtc, status: tooLate ? 'SUPPRESSED' : 'PENDING' },
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
   */
  async revivePending(userId: string, now: Date = new Date()): Promise<number> {
    const candidates = await this.prisma.reminder.findMany({
      where: {
        userId,
        status: 'SUPPRESSED',
        sentAt: null,
        eventOccurrence: {
          event: { deletedAt: null, isMuted: false, person: { deletedAt: null } },
        },
      },
      select: { id: true, scheduledAtUtc: true },
    });

    const revivable = candidates
      .filter((r) => r.scheduledAtUtc.getTime() >= now.getTime() - LATE_GRACE_MS)
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
      include: { eventOccurrence: true },
    });

    let moved = 0;
    for (const reminder of pending) {
      if (!reminder.eventOccurrence) continue;
      const effective = effectiveDate(
        civilFromDbDate(reminder.eventOccurrence.occursOn),
        reminder.eventOccurrence.overrideDate
          ? civilFromDbDate(reminder.eventOccurrence.overrideDate)
          : null,
      );
      const scheduledAtUtc = reminderInstantUtc(
        effective,
        this.env.DEFAULT_REMINDER_HOUR,
        user.timezone,
      );
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
    const dedupeKey = `test:${userId}:${scheduledAtUtc.getTime()}`;

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

    const data = this.buildPayload(reminder);
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
