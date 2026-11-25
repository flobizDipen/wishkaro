import { Injectable, Logger } from '@nestjs/common';
import { PrismaService } from '../../prisma/prisma.service';
import { defaultMutedFor, marksPersonDeceased, sentimentFor } from '../events/derived';
import { PersonDerivationService } from '../people/person-derivation.service';
import { advance, cursorWhere, decodeCursor, encodeCursor, type SyncCursor } from './sync-cursor';
import type { EventUpsert, PersonUpsert, SyncPush, TraitUpsert } from './sync.schemas';

export interface SyncPersonDto {
  id: string;
  displayName: string;
  relationToOwner: string | null;
  isSelf: boolean;
  gender: string | null;
  phone: string | null;
  email: string | null;
  notes: string | null;
  isDeceased: boolean;
  /** Derived. Sent down so the client can render it; refused on the way up. */
  isMinor: boolean;
  completenessScore: number;
  updatedAt: string;
  deletedAt: string | null;
}

export interface SyncEventDto {
  id: string;
  personId: string;
  type: string;
  /** Derived. Sent down so the client can branch its tone; refused on the way up. */
  sentiment: string;
  label: string | null;
  isMuted: boolean;
  day: number;
  month: number;
  year: number | null;
  updatedAt: string;
  deletedAt: string | null;
}

export interface SyncTraitDto {
  id: string;
  personId: string;
  kind: string;
  value: string;
  source: string;
  updatedAt: string;
  deletedAt: string | null;
}

export interface SyncPullResult {
  people: SyncPersonDto[];
  events: SyncEventDto[];
  traits: SyncTraitDto[];
  cursor: string;
  hasMore: boolean;
  serverTime: string;
}

export type SkipReason = 'STALE' | 'NOT_OWNED' | 'UNKNOWN_PERSON';
export type SyncEntity = 'person' | 'event' | 'trait';

export interface SyncPushResult {
  applied: { people: number; events: number; traits: number };
  skipped: { id: string; entity: SyncEntity; reason: SkipReason }[];
  serverTime: string;
}

@Injectable()
export class SyncService {
  private readonly logger = new Logger(SyncService.name);

  constructor(
    private readonly prisma: PrismaService,
    private readonly derivation: PersonDerivationService,
  ) {}

  /**
   * Delta pull. Tombstones are returned like any other record — a client that was offline
   * when something was deleted learns about it here, not by noticing an absence.
   */
  async pull(
    userId: string,
    encodedCursor: string | undefined,
    limit: number,
  ): Promise<SyncPullResult> {
    const cursor = decodeCursor(encodedCursor);

    const [people, events, traits] = await Promise.all([
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
      this.prisma.personTrait.findMany({
        where: { userId, ...cursorWhere(cursor.trait) },
        orderBy: [{ updatedAt: 'asc' }, { id: 'asc' }],
        take: limit,
      }),
    ]);

    const next: SyncCursor = {
      person: advance(cursor.person, people),
      event: advance(cursor.event, events),
      trait: advance(cursor.trait, traits),
    };

    return {
      people: people.map((p) => ({
        id: p.id,
        displayName: p.displayName,
        relationToOwner: p.relationToOwner,
        isSelf: p.isSelf,
        gender: p.gender,
        phone: p.phone,
        email: p.email,
        notes: p.notes,
        isDeceased: p.isDeceased,
        isMinor: p.isMinor,
        completenessScore: p.completenessScore,
        updatedAt: p.updatedAt.toISOString(),
        deletedAt: p.deletedAt?.toISOString() ?? null,
      })),
      events: events.map((e) => ({
        id: e.id,
        personId: e.personId,
        type: e.type,
        sentiment: e.sentiment,
        label: e.label,
        isMuted: e.isMuted,
        day: e.day,
        month: e.month,
        year: e.year,
        updatedAt: e.updatedAt.toISOString(),
        deletedAt: e.deletedAt?.toISOString() ?? null,
      })),
      traits: traits.map((t) => ({
        id: t.id,
        personId: t.personId,
        kind: t.kind,
        value: t.value,
        source: t.source,
        updatedAt: t.updatedAt.toISOString(),
        deletedAt: t.deletedAt?.toISOString() ?? null,
      })),
      cursor: encodeCursor(next),
      hasMore: people.length === limit || events.length === limit || traits.length === limit,
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
    let traitsApplied = 0;
    const touched = new Set<string>();

    for (const person of batch.people) {
      const outcome = await this.upsertPerson(userId, person);
      if (outcome === 'APPLIED') {
        peopleApplied += 1;
        touched.add(person.id);
      } else skipped.push({ id: person.id, entity: 'person', reason: outcome });
    }

    for (const event of batch.events) {
      const outcome = await this.upsertEvent(userId, event);
      if (outcome === 'APPLIED') {
        eventsApplied += 1;
        touched.add(event.personId);
      } else skipped.push({ id: event.id, entity: 'event', reason: outcome });
    }

    for (const trait of batch.traits) {
      const outcome = await this.upsertTrait(userId, trait);
      if (outcome === 'APPLIED') {
        traitsApplied += 1;
        touched.add(trait.personId);
      } else skipped.push({ id: trait.id, entity: 'trait', reason: outcome });
    }

    // Once, at the end, rather than per record: a batch carrying a person, their death
    // anniversary and three traits would otherwise recompute the same person five times, and
    // the first four would each see a half-applied batch.
    await this.derivation.recomputeMany(touched);

    if (skipped.length > 0) {
      this.logger.debug(`Sync push for ${userId}: ${skipped.length} record(s) skipped`);
    }

    return {
      applied: { people: peopleApplied, events: eventsApplied, traits: traitsApplied },
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

    // `undefined` means the client did not mention the field, so the server's value stands.
    // Writing `null` for an absent key would let an older client wipe a profile field it has
    // never heard of — and `isDeceased` is the one that must never be cleared by accident.
    const data = {
      displayName: input.displayName,
      relationToOwner: input.relationToOwner ?? null,
      ...(input.gender !== undefined ? { gender: input.gender } : {}),
      ...(input.phone !== undefined ? { phone: input.phone } : {}),
      ...(input.email !== undefined ? { email: input.email } : {}),
      ...(input.notes !== undefined ? { notes: input.notes } : {}),
      ...(input.isDeceased !== undefined ? { isDeceased: input.isDeceased } : {}),
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

    // Derived, never taken from the client (FR-EVT-01a). The write schema is .strict(), so
    // a client that sends `sentiment` is rejected rather than having it quietly dropped.
    const deceased = person.isDeceased || marksPersonDeceased(input.type);

    const data = {
      type: input.type,
      sentiment: sentimentFor(input.type, deceased),
      day: input.day,
      month: input.month,
      year: input.year ?? null,
      label: input.label ?? null,
      // Absent means "no opinion", not "false". A client that pushed an event back without
      // this field would otherwise un-mute the birthday the server muted when the person was
      // marked deceased — silently re-enabling exactly the reminder FR-EVT-05 turns off.
      ...(input.isMuted !== undefined ? { isMuted: input.isMuted } : {}),
      updatedAt,
      deletedAt,
    };

    if (existing) {
      await this.prisma.event.update({ where: { id: input.id }, data });
    } else {
      await this.prisma.event.create({
        data: {
          id: input.id,
          personId: input.personId,
          userId,
          // The creation-time half of FR-EVT-05's default. Applied here and not on the
          // update branch above, so re-saving an event cannot re-mute a remembrance
          // birthday the user has opted into.
          isMuted: input.isMuted ?? defaultMutedFor(input.type, deceased),
          ...data,
        },
      });
    }

    return 'APPLIED';
  }

  private async upsertTrait(userId: string, input: TraitUpsert): Promise<'APPLIED' | SkipReason> {
    const updatedAt = new Date(input.updatedAt);
    const deletedAt = input.deletedAt ? new Date(input.deletedAt) : null;

    const [existing, person] = await Promise.all([
      this.prisma.personTrait.findUnique({ where: { id: input.id } }),
      this.prisma.person.findUnique({ where: { id: input.personId } }),
    ]);

    if (!person || person.userId !== userId) return 'UNKNOWN_PERSON';
    if (existing && existing.userId !== userId) return 'NOT_OWNED';
    if (existing && existing.updatedAt.getTime() >= updatedAt.getTime()) return 'STALE';

    const data = { kind: input.kind, value: input.value, updatedAt, deletedAt };

    if (existing) {
      await this.prisma.personTrait.update({ where: { id: input.id }, data });
    } else {
      await this.prisma.personTrait.create({
        data: {
          id: input.id,
          personId: input.personId,
          userId,
          // Always USER on this path. The schema does not accept `source`, so a client
          // cannot push a trait in claiming the app inferred it.
          source: 'USER',
          ...data,
        },
      });
    }
    return 'APPLIED';
  }
}
