import { BadRequestException, Inject, Injectable, Logger, NotFoundException } from '@nestjs/common';
import type { EventType } from '@prisma/client';
import { APP_ENV, type AppEnv } from '../../config/env';
import { PrismaService } from '../../prisma/prisma.service';
import {
  compareCivil,
  daysUntil,
  dbDateFromCivil,
  formatCivil,
  nextOccurrence,
  yearsAt,
  type CivilDate,
} from '../../common/date/occurrence';
import { acceptsLabel, marksPersonDeceased, sentimentFor } from './derived';
import { PersonDerivationService } from '../people/person-derivation.service';
import { RemindersService } from '../reminders/reminders.service';

export interface UpcomingEventView {
  eventId: string;
  personId: string;
  personName: string;
  type: EventType;
  sentiment: string;
  occursOn: string;
  daysUntil: number;
  turningAge: number | null;
}

/** `undefined` leaves a field alone; `null` clears it. `sentiment` is absent on purpose. */
export interface UpdateEventInput {
  type?: EventType | undefined;
  day?: number | undefined;
  month?: number | undefined;
  year?: number | null | undefined;
  label?: string | null | undefined;
  isMuted?: boolean | undefined;
}

export interface CreateEventInput {
  id?: string | undefined;
  personId: string;
  type: EventType;
  day: number;
  month: number;
  year?: number | null | undefined;
  /** CUSTOM events only (FR-EVT-06); the schema rejects it on any other type. */
  label?: string | null | undefined;
}

@Injectable()
export class EventsService {
  private readonly logger = new Logger(EventsService.name);

  constructor(
    @Inject(APP_ENV) private readonly env: AppEnv,
    private readonly prisma: PrismaService,
    private readonly derivation: PersonDerivationService,
    private readonly reminders: RemindersService,
  ) {}

  /**
   * Upcoming events in a window, computed in the owner's timezone.
   *
   * Deliberately computed from the event rows rather than read out of `event_occurrence`:
   * the materialised table is the scheduler's business, and a read path that depends on a
   * background job having run is a read path that is empty right after you add someone.
   */
  async upcoming(
    userId: string,
    options: { from?: CivilDate; to?: CivilDate; now?: Date } = {},
  ): Promise<UpcomingEventView[]> {
    const now = options.now ?? new Date();
    const user = await this.prisma.user.findUnique({ where: { id: userId } });
    const zone = user?.timezone ?? this.env.DEFAULT_TIMEZONE;

    const events = await this.prisma.event.findMany({
      where: { userId, deletedAt: null, person: { deletedAt: null } },
      include: { person: { select: { id: true, displayName: true } } },
    });

    return events
      .flatMap((e) => {
        let occ;
        try {
          occ = nextOccurrence({ day: e.day, month: e.month, year: e.year }, zone, now);
        } catch (cause) {
          // One unrepresentable row must not take down the whole list. Validation keeps
          // these out at write time; this is the belt to that pair of braces.
          this.logger.error(
            `Skipping event ${e.id} (${e.day}/${e.month}): ` +
              `${cause instanceof Error ? cause.message : cause}`,
          );
          return [];
        }
        return [
          {
            eventId: e.id,
            personId: e.person.id,
            personName: e.person.displayName,
            type: e.type,
            sentiment: e.sentiment,
            occursOn: formatCivil(occ.occursOn),
            daysUntil: daysUntil(occ.occursOn, zone, now),
            turningAge: yearsAt({ day: e.day, month: e.month, year: e.year }, occ.occurrenceYear),
            _civil: occ.occursOn,
          },
        ];
      })
      .filter((row) => {
        if (options.from && compareCivil(row._civil, options.from) < 0) return false;
        if (options.to && compareCivil(row._civil, options.to) > 0) return false;
        return true;
      })
      .sort((a, b) => a.daysUntil - b.daysUntil)
      .map(({ _civil, ...view }) => view);
  }

  async create(userId: string, input: CreateEventInput): Promise<{ id: string }> {
    const person = await this.prisma.person.findFirst({
      where: { id: input.personId, userId, deletedAt: null },
    });
    if (!person)
      throw new NotFoundException({ code: 'PERSON_NOT_FOUND', message: 'No such person' });

    // Creating a death anniversary makes the person deceased (FR-EVT-04), and that is true
    // of the event being written right now — not only from the next write onwards.
    const deceased = person.isDeceased || marksPersonDeceased(input.type);

    const event = await this.prisma.event.create({
      data: {
        ...(input.id ? { id: input.id } : {}),
        personId: input.personId,
        userId,
        type: input.type,
        sentiment: sentimentFor(input.type, deceased),
        day: input.day,
        month: input.month,
        year: input.year ?? null,
        label: input.label ?? null,
      },
    });

    // Re-derives is_deceased, is_minor, the completeness score and the sentiment of every
    // OTHER event this person owns — a death anniversary added today changes the tone of a
    // birthday written months ago.
    await this.derivation.recompute(input.personId);

    return { id: event.id };
  }

  /**
   * Partial update. `undefined` leaves a field alone; `null` clears it.
   *
   * `sentiment` is absent from the input type by construction — it is re-derived below from
   * the type and the person, and the write schema rejects a client that sends it.
   */
  async update(userId: string, eventId: string, input: UpdateEventInput): Promise<{ id: string }> {
    const existing = await this.prisma.event.findFirst({
      where: { id: eventId, userId, deletedAt: null },
      include: { person: { select: { id: true, isDeceased: true } } },
    });
    if (!existing)
      throw new NotFoundException({ code: 'EVENT_NOT_FOUND', message: 'No such event' });

    const type = input.type ?? existing.type;

    // A label belongs only to a CUSTOM event. Changing a CUSTOM event into a BIRTHDAY must
    // take its label with it, or the row keeps a name nothing will ever show.
    const label = acceptsLabel(type) ? (input.label ?? existing.label) : null;
    if (acceptsLabel(type) && !label) {
      throw new BadRequestException({
        code: 'LABEL_REQUIRED',
        message: 'A CUSTOM event needs a label — it has no other name.',
      });
    }

    const deceased = existing.person.isDeceased || marksPersonDeceased(type);

    await this.prisma.event.update({
      where: { id: eventId },
      data: {
        type,
        sentiment: sentimentFor(type, deceased),
        ...(input.day !== undefined ? { day: input.day } : {}),
        ...(input.month !== undefined ? { month: input.month } : {}),
        ...(input.year !== undefined ? { year: input.year } : {}),
        ...(input.isMuted !== undefined ? { isMuted: input.isMuted } : {}),
        label,
        updatedAt: new Date(),
      },
    });

    await this.derivation.recompute(existing.person.id);

    // Muting must stop a pending reminder now, not at the next materialiser tick ten minutes
    // from now — a birthday muted at 08:55 would otherwise still arrive at 09:00.
    if (input.isMuted === true) await this.reminders.suppressOrphanedPending(userId);
    if (input.isMuted === false) await this.reminders.revivePending(userId);

    return { id: eventId };
  }

  async softDelete(userId: string, eventId: string): Promise<void> {
    const event = await this.prisma.event.findFirst({
      where: { id: eventId, userId, deletedAt: null },
      select: { personId: true },
    });
    if (!event) throw new NotFoundException({ code: 'EVENT_NOT_FOUND', message: 'No such event' });

    const now = new Date();
    await this.prisma.event.update({
      where: { id: eventId },
      data: { deletedAt: now, updatedAt: now },
    });

    // Deleting a death anniversary does NOT un-mark the person (see PersonDerivationService),
    // but it does re-derive everything else — the completeness score and the minor flag can
    // both change when an event with a year disappears.
    await this.derivation.recompute(event.personId);
    await this.reminders.suppressOrphanedPending(userId);
  }

  /** Undeletes an event. The companion to softDelete for the "deletes are recoverable" rule. */
  async restore(userId: string, eventId: string): Promise<{ id: string }> {
    const event = await this.prisma.event.findFirst({
      where: { id: eventId, userId, deletedAt: { not: null }, person: { deletedAt: null } },
      select: { id: true, personId: true },
    });
    if (!event) {
      throw new NotFoundException({
        code: 'EVENT_NOT_FOUND',
        message:
          'No such deleted event, or its person is deleted too — restore the person instead.',
      });
    }

    const now = new Date();
    await this.prisma.event.update({
      where: { id: eventId },
      data: { deletedAt: null, updatedAt: now },
    });
    await this.derivation.recompute(event.personId);
    await this.reminders.revivePending(userId, now);
    return { id: eventId };
  }

  /**
   * Moves a single year's occurrence without touching the base event (FR-EVT-10, OD-16).
   *
   * The effective date has been `COALESCE(override_date, occurs_on)` since M0's first line of
   * code, so this writes one column and the scheduler already reads it. Passing `null` clears
   * the override; the following year reverts to the base date on its own, because the
   * override lives on the occurrence row and each year gets a new one.
   */
  async setOccurrenceOverride(
    userId: string,
    eventId: string,
    occurrenceYear: number,
    overrideDate: CivilDate | null,
  ): Promise<{ eventId: string; occurrenceYear: number; overrideDate: string | null }> {
    const event = await this.prisma.event.findFirst({
      where: { id: eventId, userId, deletedAt: null },
      select: { id: true, personId: true },
    });
    if (!event) throw new NotFoundException({ code: 'EVENT_NOT_FOUND', message: 'No such event' });

    const occurrence = await this.prisma.eventOccurrence.findUnique({
      where: { eventId_occurrenceYear: { eventId, occurrenceYear } },
    });
    if (!occurrence) {
      throw new NotFoundException({
        code: 'OCCURRENCE_NOT_FOUND',
        message:
          `No materialised occurrence for ${occurrenceYear}. Only the next occurrence is ` +
          'materialised; a future year cannot be overridden before it exists.',
      });
    }

    await this.prisma.eventOccurrence.update({
      where: { id: occurrence.id },
      data: { overrideDate: overrideDate ? dbDateFromCivil(overrideDate) : null },
    });

    return {
      eventId,
      occurrenceYear,
      overrideDate: overrideDate ? formatCivil(overrideDate) : null,
    };
  }
}
