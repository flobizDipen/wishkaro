import { Injectable, Logger, NotFoundException } from '@nestjs/common';
import { PrismaService } from '../../prisma/prisma.service';
import { toSettingsDto, type SettingsDto } from '../settings/settings.schemas';
import {
  completenessSuggestions,
  defaultMutedFor,
  marksPersonDeceased,
  sentimentFor,
} from '../events/derived';
import {
  PersonDerivationService,
  type RecomputeContext,
} from '../people/person-derivation.service';
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
  /**
   * The ordered "what to add next" list behind FR-PPL-05's one-tap improve affordance,
   * heaviest field first and empty for a minor.
   *
   * Sent down rather than recomputed on the client, so COMPLETENESS_WEIGHTS stays the single
   * place the ordering is decided — the client would otherwise need its own copy of the
   * weights, which is the duplication BUILD_PROMPT_M1 explicitly rules out.
   */
  completenessSuggestions: string[];
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
  /**
   * The user's reminder settings (FR-REM-03, FR-REM-09). Always sent, not paged and not
   * behind the cursor: there is one row, it is tiny, and a client that has just reinstalled
   * must get its quiet hours back on the first pull rather than after the cursor catches up.
   */
  settings: SettingsDto;
  cursor: string;
  hasMore: boolean;
  serverTime: string;
}

export type SkipReason = 'STALE' | 'NOT_OWNED' | 'UNKNOWN_PERSON';
export type SyncEntity = 'person' | 'event' | 'trait';

export interface SyncPushResult {
  applied: { people: number; events: number; traits: number };
  skipped: { id: string; entity: SyncEntity; reason: SkipReason }[];
  /**
   * The settings as they stand AFTER the push, whether or not the client's copy won.
   *
   * Always returned when the client sent any, so a device whose offline change lost to a
   * newer one from somewhere else learns that in the same round trip instead of pushing the
   * same stale blob on every sync for ever.
   */
  settings?: SettingsDto;
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

    const [owner, people, events, traits] = await Promise.all([
      this.prisma.user.findFirst({ where: { id: userId, deletedAt: null } }),
      this.prisma.person.findMany({
        where: { userId, ...cursorWhere(cursor.person) },
        orderBy: [{ updatedAt: 'asc' }, { id: 'asc' }],
        take: limit,
        // Needed to derive the suggestion list; the rows themselves go down separately.
        include: {
          events: {
            where: { deletedAt: null },
            select: { type: true, day: true, month: true, year: true },
          },
          traits: { where: { deletedAt: null }, select: { kind: true } },
        },
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

    if (!owner) {
      throw new NotFoundException({ code: 'USER_NOT_FOUND', message: 'No such user' });
    }

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
        completenessSuggestions: completenessSuggestions({
          relationToOwner: p.relationToOwner,
          gender: p.gender,
          phone: p.phone,
          email: p.email,
          notes: p.notes,
          birthday: (() => {
            const b = p.events.find((e) => e.type === 'BIRTHDAY');
            return b ? { day: b.day, month: b.month, year: b.year } : null;
          })(),
          traitKinds: p.traits.map((t) => t.kind),
          isMinor: p.isMinor,
        }),
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
      settings: toSettingsDto(owner),
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

    // Pre-write `is_deceased` per person, for the recompute at the end. A sync batch writes
    // the flag and then derives, so by then the row no longer remembers what it was.
    const contexts = new Map<string, RecomputeContext>();

    for (const person of batch.people) {
      const outcome = await this.upsertPerson(userId, person, contexts);
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
    await this.derivation.recomputeMany(touched, this.prisma, contexts);

    // Settings last. They are one row and they do not interact with the records above, so
    // ordering them after the recompute keeps a settings-only push from paying for anything
    // and a records-only push from touching them at all.
    const settings = batch.settings
      ? await this.applySettings(userId, batch.settings)
      : undefined;

    if (skipped.length > 0) {
      this.logger.debug(`Sync push for ${userId}: ${skipped.length} record(s) skipped`);
    }

    return {
      applied: { people: peopleApplied, events: eventsApplied, traits: traitsApplied },
      skipped,
      ...(settings ? { settings } : {}),
      serverTime: new Date().toISOString(),
    };
  }

  /**
   * Last-write-wins on `settings_updated_at`, the same rule the records use.
   *
   * Strictly newer, not newer-or-equal: the client pushes whenever its row is flagged
   * pending and clears the flag from the response, so re-pushing the blob it was just handed
   * has to be a no-op rather than a write that bumps the timestamp and starts the cycle
   * again.
   */
  private async applySettings(
    userId: string,
    incoming: NonNullable<SyncPush['settings']>,
  ): Promise<SettingsDto> {
    const current = await this.prisma.user.findFirst({ where: { id: userId, deletedAt: null } });
    if (!current) {
      throw new NotFoundException({ code: 'USER_NOT_FOUND', message: 'No such user' });
    }

    const incomingAt = new Date(incoming.updatedAt);
    if (incomingAt.getTime() <= current.settingsUpdatedAt.getTime()) {
      return toSettingsDto(current);
    }

    const { updatedAt: _clientClock, ...fields } = incoming;
    const after = await this.prisma.user.update({
      where: { id: userId },
      data: { ...fields, settingsUpdatedAt: incomingAt },
    });
    this.logger.log(`Settings updated from a client push for user ${userId}`);
    return toSettingsDto(after);
  }

  private async upsertPerson(
    userId: string,
    input: PersonUpsert,
    contexts: Map<string, RecomputeContext>,
  ): Promise<'APPLIED' | SkipReason> {
    const updatedAt = new Date(input.updatedAt);
    const deletedAt = input.deletedAt ? new Date(input.deletedAt) : null;
    const existing = await this.prisma.person.findUnique({ where: { id: input.id } });

    if (existing && existing.userId !== userId) return 'NOT_OWNED';
    if (existing && existing.updatedAt.getTime() >= updatedAt.getTime()) return 'STALE';

    // Recorded before the write below, not after: this is the only moment the previous
    // value still exists. A person arriving for the first time was not deceased.
    contexts.set(input.id, { wasDeceased: existing?.isDeceased ?? false });

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

      /**
       * An un-delete arriving through sync has to bring back the rows that delete
       * tombstoned, exactly as POST /v1/people/:id/restore does.
       *
       * Without this the person returns as an empty husk — no dates, no reminders — because
       * the REST restore path and the sync path were applying different rules to the same
       * user action. Matched on the exact timestamp, so an event deleted a week earlier
       * stays deleted.
       */
      if (existing.deletedAt && !deletedAt) {
        const tombstonedAt = existing.deletedAt;
        await this.prisma.$transaction([
          this.prisma.event.updateMany({
            where: { personId: input.id, userId, deletedAt: tombstonedAt },
            data: { deletedAt: null, updatedAt },
          }),
          this.prisma.personTrait.updateMany({
            where: { personId: input.id, userId, deletedAt: tombstonedAt },
            data: { deletedAt: null, updatedAt },
          }),
        ]);
      }
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
