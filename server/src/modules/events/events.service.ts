import { Inject, Injectable, Logger, NotFoundException } from '@nestjs/common';
import type { EventType } from '@prisma/client';
import { APP_ENV, type AppEnv } from '../../config/env';
import { PrismaService } from '../../prisma/prisma.service';
import {
  compareCivil,
  daysUntil,
  formatCivil,
  nextOccurrence,
  yearsAt,
  type CivilDate,
} from '../../common/date/occurrence';
import { marksPersonDeceased, sentimentFor } from './derived';
import { PersonDerivationService } from '../people/person-derivation.service';

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

  async softDelete(userId: string, eventId: string): Promise<void> {
    const result = await this.prisma.event.updateMany({
      where: { id: eventId, userId, deletedAt: null },
      data: { deletedAt: new Date(), updatedAt: new Date() },
    });
    if (result.count === 0) {
      throw new NotFoundException({ code: 'EVENT_NOT_FOUND', message: 'No such event' });
    }
  }
}
