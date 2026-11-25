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

    const events = await this.prisma.event.findMany({
      where: {
        deletedAt: null,
        ...(options.userId ? { userId: options.userId } : {}),
        person: { deletedAt: null },
        user: { deletedAt: null },
      },
      include: { user: { select: { id: true, timezone: true } }, person: { select: { displayName: true } } },
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

    return summary;
  }

  private async materialiseOne(
    event: Prisma.EventGetPayload<{
      include: { user: { select: { id: true; timezone: true } }; person: { select: { displayName: true } } };
    }>,
    now: Date,
    summary: MaterialiseSummary,
  ): Promise<void> {
    const zone = event.user.timezone;
    const occ = nextOccurrence({ day: event.day, month: event.month, year: event.year }, zone, now);

    const occurrence = await this.prisma.eventOccurrence.upsert({
      where: { eventId_occurrenceYear: { eventId: event.id, occurrenceYear: occ.occurrenceYear } },
      create: {
        eventId: event.id,
        occurrenceYear: occ.occurrenceYear,
        occursOn: dbDateFromCivil(occ.occursOn),
      },
      // An edited event date must move the existing row, not orphan it.
      update: { occursOn: dbDateFromCivil(occ.occursOn), computedAt: new Date() },
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
    if (existing.status === 'PENDING' && existing.scheduledAtUtc.getTime() !== scheduledAtUtc.getTime()) {
      await this.prisma.reminder.update({
        where: { id: existing.id },
        data: { scheduledAtUtc, status: tooLate ? 'SUPPRESSED' : 'PENDING' },
      });
      summary.remindersRescheduled += 1;
    }
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
      const scheduledAtUtc = reminderInstantUtc(effective, this.env.DEFAULT_REMINDER_HOUR, user.timezone);
      if (scheduledAtUtc.getTime() === reminder.scheduledAtUtc.getTime()) continue;

      const tooLate = scheduledAtUtc.getTime() < now.getTime() - LATE_GRACE_MS;
      await this.prisma.reminder.update({
        where: { id: reminder.id },
        data: { scheduledAtUtc, status: tooLate ? 'SUPPRESSED' : 'PENDING' },
      });
      moved += 1;
    }

    if (moved > 0) {
      this.logger.log(`Rescheduled ${moved} pending reminder(s) for user ${userId} → ${user.timezone}`);
    }
    return moved;
  }

  // -------------------------------------------------------------------------
  // Self-test (FR-REM-07)
  // -------------------------------------------------------------------------

  /** Queues a reminder two minutes out. The fastest honest proof that the chain works. */
  async queueTestReminder(userId: string): Promise<{ reminderId: string; scheduledAtUtc: Date; dedupeKey: string }> {
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

  private async deliver(reminderId: string): Promise<{ delivered: boolean; tokensRemoved: number }> {
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
    const base = { dedupeKey: reminder.dedupeKey, reminderId: reminder.id, offset: reminder.offset };

    const occurrence = reminder.eventOccurrence;
    if (!occurrence) {
      const copy = buildTestCopy();
      return { ...base, type: 'TEST', title: copy.title, body: copy.body, deepLink: 'wishkaro://home' };
    }

    const event = occurrence.event;
    const copy = buildReminderCopy({
      personName: event.person.displayName,
      eventType: event.type,
      sentiment: event.sentiment,
      years: yearsAt({ day: event.day, month: event.month, year: event.year }, occurrence.occurrenceYear),
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
