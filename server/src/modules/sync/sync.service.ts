import { Injectable, Logger } from '@nestjs/common';
import { PrismaService } from '../../prisma/prisma.service';
import { sentimentFor } from '../events/event-rules';
import { advance, cursorWhere, decodeCursor, encodeCursor, type SyncCursor } from './sync-cursor';
import type { EventUpsert, PersonUpsert, SyncPush } from './sync.schemas';

export interface SyncPersonDto {
  id: string;
  displayName: string;
  relationToOwner: string | null;
  isSelf: boolean;
  updatedAt: string;
  deletedAt: string | null;
}

export interface SyncEventDto {
  id: string;
  personId: string;
  type: string;
  sentiment: string;
  day: number;
  month: number;
  year: number | null;
  updatedAt: string;
  deletedAt: string | null;
}

export interface SyncPullResult {
  people: SyncPersonDto[];
  events: SyncEventDto[];
  cursor: string;
  hasMore: boolean;
  serverTime: string;
}

export type SkipReason = 'STALE' | 'NOT_OWNED' | 'UNKNOWN_PERSON';

export interface SyncPushResult {
  applied: { people: number; events: number };
  skipped: { id: string; entity: 'person' | 'event'; reason: SkipReason }[];
  serverTime: string;
}

@Injectable()
export class SyncService {
  private readonly logger = new Logger(SyncService.name);

  constructor(private readonly prisma: PrismaService) {}

  /**
   * Delta pull. Tombstones are returned like any other record — a client that was offline
   * when something was deleted learns about it here, not by noticing an absence.
   */
  async pull(userId: string, encodedCursor: string | undefined, limit: number): Promise<SyncPullResult> {
    const cursor = decodeCursor(encodedCursor);

    const [people, events] = await Promise.all([
      this.prisma.person.findMany({
        where: { userId, ...cursorWhere(cursor.person) },
        orderBy: [{ updatedAt: 'asc' }, { id: 'asc' }],
        take: limit,
      }),
      this.prisma.event.findMany({
        where: { userId, ...cursorWhere(cursor.event) },
        orderBy: [{ updatedAt: 'asc' }, { id: 'asc' }],
        take: limit,
      }),
    ]);

    const next: SyncCursor = {
      person: advance(cursor.person, people),
      event: advance(cursor.event, events),
    };

    return {
      people: people.map((p) => ({
        id: p.id,
        displayName: p.displayName,
        relationToOwner: p.relationToOwner,
        isSelf: p.isSelf,
        updatedAt: p.updatedAt.toISOString(),
        deletedAt: p.deletedAt?.toISOString() ?? null,
      })),
      events: events.map((e) => ({
        id: e.id,
        personId: e.personId,
        type: e.type,
        sentiment: e.sentiment,
        day: e.day,
        month: e.month,
        year: e.year,
        updatedAt: e.updatedAt.toISOString(),
        deletedAt: e.deletedAt?.toISOString() ?? null,
      })),
      cursor: encodeCursor(next),
      hasMore: people.length === limit || events.length === limit,
      serverTime: new Date().toISOString(),
    };
  }

  /**
   * Delta push: batched upserts and tombstones, last-write-wins on `updatedAt`.
   *
   * Idempotent by construction — replaying a batch produces timestamps that are equal, not
   * newer, so every record is skipped as STALE and nothing changes. People are applied before
   * events so a person and their birthday can arrive in the same request.
   *
   * NOTE: this is last-write-wins per RECORD. REQUIREMENTS.md §11 asks for per-FIELD, which
   * the v1 schema cannot express — one `updated_at` per row carries no per-field versions.
   * See docs/M0_PLAN.md §2.
   */
  async push(userId: string, batch: SyncPush): Promise<SyncPushResult> {
    const skipped: SyncPushResult['skipped'] = [];
    let peopleApplied = 0;
    let eventsApplied = 0;

    for (const person of batch.people) {
      const outcome = await this.upsertPerson(userId, person);
      if (outcome === 'APPLIED') peopleApplied += 1;
      else skipped.push({ id: person.id, entity: 'person', reason: outcome });
    }

    for (const event of batch.events) {
      const outcome = await this.upsertEvent(userId, event);
      if (outcome === 'APPLIED') eventsApplied += 1;
      else skipped.push({ id: event.id, entity: 'event', reason: outcome });
    }

    if (skipped.length > 0) {
      this.logger.debug(`Sync push for ${userId}: ${skipped.length} record(s) skipped`);
    }

    return {
      applied: { people: peopleApplied, events: eventsApplied },
      skipped,
      serverTime: new Date().toISOString(),
    };
  }

  private async upsertPerson(userId: string, input: PersonUpsert): Promise<'APPLIED' | SkipReason> {
    const updatedAt = new Date(input.updatedAt);
    const deletedAt = input.deletedAt ? new Date(input.deletedAt) : null;
    const existing = await this.prisma.person.findUnique({ where: { id: input.id } });

    if (existing && existing.userId !== userId) return 'NOT_OWNED';
    if (existing && existing.updatedAt.getTime() >= updatedAt.getTime()) return 'STALE';

    const data = {
      displayName: input.displayName,
      relationToOwner: input.relationToOwner ?? null,
      updatedAt,
      deletedAt,
    };

    if (existing) {
      await this.prisma.person.update({ where: { id: input.id }, data });
    } else {
      await this.prisma.person.create({
        data: {
          id: input.id,
          userId,
          // isSelf is owned by the server (set at first sign-in) and never promoted here.
          isSelf: false,
          ...data,
        },
      });
    }
    return 'APPLIED';
  }

  private async upsertEvent(userId: string, input: EventUpsert): Promise<'APPLIED' | SkipReason> {
    const updatedAt = new Date(input.updatedAt);
    const deletedAt = input.deletedAt ? new Date(input.deletedAt) : null;

    const [existing, person] = await Promise.all([
      this.prisma.event.findUnique({ where: { id: input.id } }),
      this.prisma.person.findUnique({ where: { id: input.personId } }),
    ]);

    if (!person || person.userId !== userId) return 'UNKNOWN_PERSON';
    if (existing && existing.userId !== userId) return 'NOT_OWNED';
    if (existing && existing.updatedAt.getTime() >= updatedAt.getTime()) return 'STALE';

    const data = {
      type: input.type,
      // Derived, never taken from the client (FR-EVT-01a).
      sentiment: sentimentFor(input.type),
      day: input.day,
      month: input.month,
      year: input.year ?? null,
      updatedAt,
      deletedAt,
    };

    if (existing) {
      await this.prisma.event.update({ where: { id: input.id }, data });
    } else {
      await this.prisma.event.create({
        data: { id: input.id, personId: input.personId, userId, ...data },
      });
    }
    return 'APPLIED';
  }
}
