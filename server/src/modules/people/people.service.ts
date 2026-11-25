import { Inject, Injectable, NotFoundException } from '@nestjs/common';
import { APP_ENV, type AppEnv } from '../../config/env';
import { PrismaService } from '../../prisma/prisma.service';
import {
  daysUntil,
  formatCivil,
  nextOccurrence,
  yearsAt,
  type CivilDate,
} from '../../common/date/occurrence';
import { sentimentFor } from '../events/event-rules';
import type { EventType } from '@prisma/client';

export interface PersonEventView {
  id: string;
  type: EventType;
  sentiment: string;
  day: number;
  month: number;
  year: number | null;
  /** Next occurrence in the owner's timezone, ISO date. */
  nextOccurrenceOn: string;
  daysUntil: number;
  /** Null when the year is unknown — never 0 (FR-EVT-02). */
  turningAge: number | null;
}

export interface PersonView {
  id: string;
  displayName: string;
  relationToOwner: string | null;
  isSelf: boolean;
  updatedAt: string;
  events: PersonEventView[];
}

export interface CreatePersonInput {
  /** Client-generated id, so a record created offline keeps its identity forever. */
  id?: string | undefined;
  displayName: string;
  relationToOwner?: string | undefined;
  event?:
    | {
        id?: string | undefined;
        type: EventType;
        day: number;
        month: number;
        year?: number | null | undefined;
      }
    | undefined;
}

@Injectable()
export class PeopleService {
  constructor(
    @Inject(APP_ENV) private readonly env: AppEnv,
    private readonly prisma: PrismaService,
  ) {}

  async list(userId: string, now: Date = new Date()): Promise<PersonView[]> {
    const [user, people] = await Promise.all([
      this.prisma.user.findUnique({ where: { id: userId } }),
      this.prisma.person.findMany({
        where: { userId, deletedAt: null },
        include: { events: { where: { deletedAt: null } } },
        orderBy: { displayName: 'asc' },
      }),
    ]);
    const zone = user?.timezone ?? this.env.DEFAULT_TIMEZONE;
    return people.map((p) => this.toView(p, zone, now));
  }

  async get(userId: string, personId: string, now: Date = new Date()): Promise<PersonView> {
    const person = await this.prisma.person.findFirst({
      where: { id: personId, userId, deletedAt: null },
      include: { events: { where: { deletedAt: null } } },
    });
    if (!person) throw new NotFoundException({ code: 'PERSON_NOT_FOUND', message: 'No such person' });
    const user = await this.prisma.user.findUnique({ where: { id: userId } });
    return this.toView(person, user?.timezone ?? this.env.DEFAULT_TIMEZONE, now);
  }

  /** Creates a person and, optionally, their first event in one transaction. */
  async create(userId: string, input: CreatePersonInput): Promise<PersonView> {
    const person = await this.prisma.$transaction(async (tx) => {
      const created = await tx.person.create({
        data: {
          ...(input.id ? { id: input.id } : {}),
          userId,
          displayName: input.displayName,
          relationToOwner: input.relationToOwner ?? null,
        },
      });

      if (input.event) {
        await tx.event.create({
          data: {
            ...(input.event.id ? { id: input.event.id } : {}),
            personId: created.id,
            userId,
            type: input.event.type,
            sentiment: sentimentFor(input.event.type),
            day: input.event.day,
            month: input.event.month,
            year: input.event.year ?? null,
          },
        });
      }

      return tx.person.findUniqueOrThrow({
        where: { id: created.id },
        include: { events: { where: { deletedAt: null } } },
      });
    });

    const user = await this.prisma.user.findUnique({ where: { id: userId } });
    return this.toView(person, user?.timezone ?? this.env.DEFAULT_TIMEZONE, new Date());
  }

  async softDelete(userId: string, personId: string): Promise<void> {
    const result = await this.prisma.person.updateMany({
      where: { id: personId, userId, deletedAt: null },
      data: { deletedAt: new Date(), updatedAt: new Date() },
    });
    if (result.count === 0) {
      throw new NotFoundException({ code: 'PERSON_NOT_FOUND', message: 'No such person' });
    }
    // Tombstone the events too, so the client and the scheduler agree.
    await this.prisma.event.updateMany({
      where: { personId, userId, deletedAt: null },
      data: { deletedAt: new Date(), updatedAt: new Date() },
    });
  }

  private toView(
    person: {
      id: string;
      displayName: string;
      relationToOwner: string | null;
      isSelf: boolean;
      updatedAt: Date;
      events: {
        id: string;
        type: EventType;
        sentiment: string;
        day: number;
        month: number;
        year: number | null;
      }[];
    },
    zone: string,
    now: Date,
  ): PersonView {
    return {
      id: person.id,
      displayName: person.displayName,
      relationToOwner: person.relationToOwner,
      isSelf: person.isSelf,
      updatedAt: person.updatedAt.toISOString(),
      events: person.events
        .map((e) => {
          const occ = nextOccurrence({ day: e.day, month: e.month, year: e.year }, zone, now);
          const on: CivilDate = occ.occursOn;
          return {
            id: e.id,
            type: e.type,
            sentiment: e.sentiment,
            day: e.day,
            month: e.month,
            year: e.year,
            nextOccurrenceOn: formatCivil(on),
            daysUntil: daysUntil(on, zone, now),
            turningAge: yearsAt({ day: e.day, month: e.month, year: e.year }, occ.occurrenceYear),
          };
        })
        .sort((a, b) => a.daysUntil - b.daysUntil),
    };
  }
}
