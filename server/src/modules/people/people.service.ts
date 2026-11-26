import { BadRequestException, Inject, Injectable, NotFoundException } from '@nestjs/common';
import type { EventType, TraitKind } from '@prisma/client';
import { APP_ENV, type AppEnv } from '../../config/env';
import { PrismaService } from '../../prisma/prisma.service';
import { nameTokens, tokensOverlap } from './name-tokens';
import {
  daysUntil,
  formatCivil,
  nextOccurrence,
  yearsAt,
  type CivilDate,
} from '../../common/date/occurrence';
import {
  completenessSuggestions,
  defaultMutedFor,
  marksPersonDeceased,
  milestoneLabelFor,
  sentimentFor,
  type CompletenessField,
} from '../events/derived';
import { RemindersService } from '../reminders/reminders.service';
import { PersonDerivationService } from './person-derivation.service';

/**
 * The curated relation list (FR-PPL-02). The client renders these as chips and may send any
 * other string as free text, so this is a suggestion list rather than a validation set — an
 * enum here would make "Mama" or "Chachi" unrepresentable, which is the opposite of what an
 * India-first product wants.
 */
export const CURATED_RELATIONS = [
  'Mother',
  'Father',
  'Brother',
  'Sister',
  'Spouse',
  'Son',
  'Daughter',
  'Grandparent',
  'Uncle',
  'Aunt',
  'Cousin',
  'Friend',
  'Best Friend',
  'Colleague',
  'Boss',
  'Neighbour',
  'Other',
] as const;

/** How long a soft-deleted person can be restored (FR-PPL-08). */
export const RESTORE_WINDOW_DAYS = 30;

export interface PersonEventView {
  id: string;
  type: EventType;
  sentiment: string;
  label: string | null;
  isMuted: boolean;
  day: number;
  month: number;
  year: number | null;
  /** Next occurrence in the owner's timezone, ISO date. */
  nextOccurrenceOn: string;
  daysUntil: number;
  /** Null when the year is unknown — never 0 (FR-EVT-02). */
  turningAge: number | null;
  /** Derived (FR-EVT-03). Null for most years, and for anything a deceased person owns. */
  milestoneLabel: string | null;
}

export interface PersonTraitView {
  id: string;
  kind: TraitKind;
  value: string;
}

export interface PersonView {
  id: string;
  displayName: string;
  relationToOwner: string | null;
  isSelf: boolean;
  gender: string | null;
  phone: string | null;
  email: string | null;
  notes: string | null;
  isDeceased: boolean;
  isMinor: boolean;
  completenessScore: number;
  /** Heaviest missing field first; empty for a minor (FR-PPL-09). */
  completenessSuggestions: CompletenessField[];
  updatedAt: string;
  deletedAt: string | null;
  /** Days left to restore, when deleted. Null otherwise (FR-PPL-08). */
  restorableForDays: number | null;
  events: PersonEventView[];
  traits: PersonTraitView[];
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
        /** CUSTOM events only (FR-EVT-06). */
        label?: string | null | undefined;
      }
    | undefined;
}

/**
 * Every field here is optional, and `undefined` means "leave it alone" while `null` means
 * "clear it". The distinction matters for a PATCH: without it, an app that only wanted to
 * change a phone number would wipe the notes.
 */
export interface UpdatePersonInput {
  displayName?: string | undefined;
  relationToOwner?: string | null | undefined;
  gender?: string | null | undefined;
  phone?: string | null | undefined;
  email?: string | null | undefined;
  notes?: string | null | undefined;
  isDeceased?: boolean | undefined;
}

export interface PersonSearch {
  /** Case-insensitive substring of the display name. */
  q?: string | undefined;
  relation?: string | undefined;
  /** 1..12, matching any event of theirs falling in that month (FR-PPL-10). */
  month?: number | undefined;
  /** Only people at or below this completeness — "who still needs filling in". */
  maxCompleteness?: number | undefined;
}

const PERSON_INCLUDE = {
  events: { where: { deletedAt: null } },
  traits: { where: { deletedAt: null } },
} as const;

@Injectable()
export class PeopleService {
  constructor(
    @Inject(APP_ENV) private readonly env: AppEnv,
    private readonly prisma: PrismaService,
    private readonly derivation: PersonDerivationService,
    private readonly reminders: RemindersService,
  ) {}

  // -------------------------------------------------------------------------------------
  // Reads
  // -------------------------------------------------------------------------------------

  async list(
    userId: string,
    search: PersonSearch = {},
    now: Date = new Date(),
  ): Promise<PersonView[]> {
    const [user, people] = await Promise.all([
      this.prisma.user.findUnique({ where: { id: userId } }),
      this.prisma.person.findMany({
        where: {
          userId,
          deletedAt: null,
          ...(search.q
            ? { displayName: { contains: search.q, mode: 'insensitive' as const } }
            : {}),
          ...(search.relation
            ? { relationToOwner: { equals: search.relation, mode: 'insensitive' as const } }
            : {}),
          // "Has an event in this month" — a filter on the person, driven by their events.
          ...(search.month ? { events: { some: { month: search.month, deletedAt: null } } } : {}),
          ...(search.maxCompleteness !== undefined
            ? { completenessScore: { lte: search.maxCompleteness } }
            : {}),
        },
        include: PERSON_INCLUDE,
        orderBy: { displayName: 'asc' },
      }),
    ]);
    const zone = user?.timezone ?? this.env.DEFAULT_TIMEZONE;
    return people.map((p) => this.toView(p, zone, now));
  }

  /** Soft-deleted people still inside the restore window (FR-PPL-08). */
  async listDeleted(userId: string, now: Date = new Date()): Promise<PersonView[]> {
    const cutoff = new Date(now.getTime() - RESTORE_WINDOW_DAYS * 86_400_000);
    const [user, people] = await Promise.all([
      this.prisma.user.findUnique({ where: { id: userId } }),
      this.prisma.person.findMany({
        where: { userId, deletedAt: { not: null, gte: cutoff } },
        // Deleted people keep their deleted events, or the list shows an empty husk.
        include: { events: true, traits: true },
        orderBy: { deletedAt: 'desc' },
      }),
    ]);
    const zone = user?.timezone ?? this.env.DEFAULT_TIMEZONE;
    return people.map((p) => this.toView(p, zone, now));
  }

  async get(userId: string, personId: string, now: Date = new Date()): Promise<PersonView> {
    const person = await this.prisma.person.findFirst({
      where: { id: personId, userId, deletedAt: null },
      include: PERSON_INCLUDE,
    });
    if (!person)
      throw new NotFoundException({ code: 'PERSON_NOT_FOUND', message: 'No such person' });

    const user = await this.prisma.user.findUnique({ where: { id: userId } });
    return this.toView(person, user?.timezone ?? this.env.DEFAULT_TIMEZONE, now);
  }

  /**
   * Possible duplicates of a person about to be created (FR-PPL-06).
   *
   * "Fuzzy name plus same date". The name comparison is a normalised-token match rather than
   * an edit distance: "Jansari Jaydatt" and "Jaydatt Jansari" are the same human and no
   * sensible edit distance says so, while "Aarav" and "Aarti" are different humans and a
   * loose edit distance says they are not. Import-time dedup is M3; this is create-time only.
   */
  async findDuplicates(
    userId: string,
    displayName: string,
    date: { day: number; month: number } | null,
    now: Date = new Date(),
  ): Promise<PersonView[]> {
    const wanted = nameTokens(displayName);
    if (wanted.size === 0) return [];

    const [user, candidates] = await Promise.all([
      this.prisma.user.findUnique({ where: { id: userId } }),
      this.prisma.person.findMany({ where: { userId, deletedAt: null }, include: PERSON_INCLUDE }),
    ]);

    const matches = candidates.filter((person) => {
      if (!tokensOverlap(wanted, nameTokens(person.displayName))) return false;
      if (!date) return true;
      return person.events.some((e) => e.day === date.day && e.month === date.month);
    });

    const zone = user?.timezone ?? this.env.DEFAULT_TIMEZONE;
    return matches.map((p) => this.toView(p, zone, now));
  }

  // -------------------------------------------------------------------------------------
  // Writes
  // -------------------------------------------------------------------------------------

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
        // A person created with a DEATH_ANNIVERSARY is deceased from their first row — the
        // event must never exist for even one transaction with a celebratory sentiment.
        const deceased = marksPersonDeceased(input.event.type);
        await tx.event.create({
          data: {
            ...(input.event.id ? { id: input.event.id } : {}),
            personId: created.id,
            userId,
            type: input.event.type,
            sentiment: sentimentFor(input.event.type, deceased),
            day: input.event.day,
            month: input.event.month,
            year: input.event.year ?? null,
            label: input.event.label ?? null,
            isMuted: defaultMutedFor(input.event.type, deceased),
          },
        });
      }

      await this.derivation.recompute(created.id, tx);

      return tx.person.findUniqueOrThrow({ where: { id: created.id }, include: PERSON_INCLUDE });
    });

    const user = await this.prisma.user.findUnique({ where: { id: userId } });
    return this.toView(person, user?.timezone ?? this.env.DEFAULT_TIMEZONE, new Date());
  }

  async update(userId: string, personId: string, input: UpdatePersonInput): Promise<PersonView> {
    const existing = await this.prisma.person.findFirst({
      where: { id: personId, userId, deletedAt: null },
      include: { events: { where: { deletedAt: null } } },
    });
    if (!existing)
      throw new NotFoundException({ code: 'PERSON_NOT_FOUND', message: 'No such person' });

    // Clearing the deceased flag while a death anniversary is live would put the person back
    // into celebratory copy while the event that made them deceased still exists. Refused
    // with an explanation rather than silently ignored.
    if (input.isDeceased === false && existing.events.some((e) => e.type === 'DEATH_ANNIVERSARY')) {
      throw new BadRequestException({
        code: 'DEATH_ANNIVERSARY_PRESENT',
        message:
          'Cannot un-mark this person as deceased while they still have a death anniversary. ' +
          'Delete that event first.',
      });
    }

    await this.prisma.person.update({
      where: { id: personId },
      data: {
        ...(input.displayName !== undefined ? { displayName: input.displayName } : {}),
        ...(input.relationToOwner !== undefined ? { relationToOwner: input.relationToOwner } : {}),
        ...(input.gender !== undefined ? { gender: input.gender } : {}),
        ...(input.phone !== undefined ? { phone: input.phone } : {}),
        ...(input.email !== undefined ? { email: input.email } : {}),
        ...(input.notes !== undefined ? { notes: input.notes } : {}),
        ...(input.isDeceased !== undefined ? { isDeceased: input.isDeceased } : {}),
        updatedAt: new Date(),
      },
    });

    // Marking someone deceased here re-derives the sentiment of every event they own and
    // mutes their birthday — the same path a DEATH_ANNIVERSARY takes.
    //
    // `existing.isDeceased` is read BEFORE the update above and handed back, because the
    // update has already overwritten it: without this the derivation sees `is_deceased`
    // already true, concludes nothing changed, and never mutes the birthday (FR-EVT-05).
    await this.derivation.recompute(personId, this.prisma, new Date(), {
      wasDeceased: existing.isDeceased,
    });
    await this.reminders.suppressOrphanedPending(userId);

    return this.get(userId, personId);
  }

  /**
   * Soft delete with a 30-day restore window (FR-PPL-08).
   *
   * Reminders stop **immediately**, not at the end of the window — the user deleted someone,
   * and a notification about them an hour later is the single most upsetting bug this app
   * could have, particularly for the deceased case.
   */
  async softDelete(userId: string, personId: string): Promise<void> {
    const now = new Date();
    const result = await this.prisma.person.updateMany({
      where: { id: personId, userId, deletedAt: null },
      data: { deletedAt: now, updatedAt: now },
    });
    if (result.count === 0) {
      throw new NotFoundException({ code: 'PERSON_NOT_FOUND', message: 'No such person' });
    }

    // Tombstone the events and traits too, so the client and the scheduler agree.
    await this.prisma.event.updateMany({
      where: { personId, userId, deletedAt: null },
      data: { deletedAt: now, updatedAt: now },
    });
    await this.prisma.personTrait.updateMany({
      where: { personId, userId, deletedAt: null },
      data: { deletedAt: now, updatedAt: now },
    });

    await this.reminders.suppressOrphanedPending(userId);
  }

  /** Restores a person deleted within the window, with their events and traits. */
  async restore(userId: string, personId: string, now: Date = new Date()): Promise<PersonView> {
    const person = await this.prisma.person.findFirst({ where: { id: personId, userId } });
    if (!person)
      throw new NotFoundException({ code: 'PERSON_NOT_FOUND', message: 'No such person' });
    if (!person.deletedAt) return this.get(userId, personId, now);

    const daysGone = Math.floor((now.getTime() - person.deletedAt.getTime()) / 86_400_000);
    if (daysGone >= RESTORE_WINDOW_DAYS) {
      throw new BadRequestException({
        code: 'RESTORE_WINDOW_EXPIRED',
        message: `Deleted ${daysGone} days ago; the restore window is ${RESTORE_WINDOW_DAYS} days.`,
      });
    }

    // Only rows tombstoned by this delete come back, matched on the exact timestamp. An
    // event the user had already deleted a week earlier stays deleted — restoring a person
    // must not resurrect their whole history.
    const deletedAt = person.deletedAt;
    await this.prisma.$transaction([
      this.prisma.person.update({
        where: { id: personId },
        data: { deletedAt: null, updatedAt: now },
      }),
      this.prisma.event.updateMany({
        where: { personId, userId, deletedAt },
        data: { deletedAt: null, updatedAt: now },
      }),
      this.prisma.personTrait.updateMany({
        where: { personId, userId, deletedAt },
        data: { deletedAt: null, updatedAt: now },
      }),
    ]);

    await this.derivation.recompute(personId);
    await this.reminders.revivePending(userId, now);

    return this.get(userId, personId, now);
  }

  // -------------------------------------------------------------------------------------
  // Traits (FR-PPL-04)
  // -------------------------------------------------------------------------------------

  async addTrait(
    userId: string,
    personId: string,
    input: { id?: string | undefined; kind: TraitKind; value: string },
  ): Promise<PersonView> {
    const person = await this.prisma.person.findFirst({
      where: { id: personId, userId, deletedAt: null },
    });
    if (!person)
      throw new NotFoundException({ code: 'PERSON_NOT_FOUND', message: 'No such person' });

    // FR-PPL-09: flagged records are excluded from profiling and from recommendation
    // personalisation. Collecting a child's likes and budget band is exactly the profiling
    // the flag exists to prevent, so the write is refused rather than quietly dropped.
    if (person.isMinor) {
      throw new BadRequestException({
        code: 'MINOR_PROFILING_BLOCKED',
        message:
          'This person is flagged as a minor, and minors are excluded from profile enrichment.',
      });
    }

    await this.prisma.personTrait.create({
      data: {
        ...(input.id ? { id: input.id } : {}),
        personId,
        userId,
        kind: input.kind,
        value: input.value,
        source: 'USER',
      },
    });

    await this.derivation.recompute(personId);
    return this.get(userId, personId);
  }

  async removeTrait(userId: string, personId: string, traitId: string): Promise<PersonView> {
    const now = new Date();
    const result = await this.prisma.personTrait.updateMany({
      where: { id: traitId, personId, userId, deletedAt: null },
      data: { deletedAt: now, updatedAt: now },
    });
    if (result.count === 0) {
      throw new NotFoundException({ code: 'TRAIT_NOT_FOUND', message: 'No such trait' });
    }
    await this.derivation.recompute(personId);
    return this.get(userId, personId);
  }

  // -------------------------------------------------------------------------------------
  // View
  // -------------------------------------------------------------------------------------

  private toView(
    person: {
      id: string;
      displayName: string;
      relationToOwner: string | null;
      isSelf: boolean;
      gender: string | null;
      phone: string | null;
      email: string | null;
      notes: string | null;
      isDeceased: boolean;
      isMinor: boolean;
      completenessScore: number;
      updatedAt: Date;
      deletedAt: Date | null;
      events: {
        id: string;
        type: EventType;
        sentiment: string;
        label: string | null;
        isMuted: boolean;
        day: number;
        month: number;
        year: number | null;
      }[];
      traits: { id: string; kind: TraitKind; value: string }[];
    },
    zone: string,
    now: Date,
  ): PersonView {
    const birthdayEvent = person.events.find((e) => e.type === 'BIRTHDAY');

    return {
      id: person.id,
      displayName: person.displayName,
      relationToOwner: person.relationToOwner,
      isSelf: person.isSelf,
      gender: person.gender,
      phone: person.phone,
      email: person.email,
      notes: person.notes,
      isDeceased: person.isDeceased,
      isMinor: person.isMinor,
      completenessScore: person.completenessScore,
      completenessSuggestions: completenessSuggestions({
        relationToOwner: person.relationToOwner,
        gender: person.gender,
        phone: person.phone,
        email: person.email,
        notes: person.notes,
        birthday: birthdayEvent
          ? { day: birthdayEvent.day, month: birthdayEvent.month, year: birthdayEvent.year }
          : null,
        traitKinds: person.traits.map((t) => t.kind),
        isMinor: person.isMinor,
      }),
      updatedAt: person.updatedAt.toISOString(),
      deletedAt: person.deletedAt?.toISOString() ?? null,
      restorableForDays: person.deletedAt
        ? Math.max(
            0,
            RESTORE_WINDOW_DAYS -
              Math.floor((now.getTime() - person.deletedAt.getTime()) / 86_400_000),
          )
        : null,
      traits: person.traits.map((t) => ({ id: t.id, kind: t.kind, value: t.value })),
      events: person.events
        .map((e) => {
          const spec = { day: e.day, month: e.month, year: e.year };
          const occ = nextOccurrence(spec, zone, now);
          const on: CivilDate = occ.occursOn;
          const years = yearsAt(spec, occ.occurrenceYear);
          return {
            id: e.id,
            type: e.type,
            sentiment: e.sentiment,
            label: e.label,
            isMuted: e.isMuted,
            day: e.day,
            month: e.month,
            year: e.year,
            nextOccurrenceOn: formatCivil(on),
            daysUntil: daysUntil(on, zone, now),
            // A deceased person is never "turning" an age. The field is the countdown's
            // companion and reads as a celebration, so it goes with the rest of the tone.
            turningAge: person.isDeceased ? null : years,
            milestoneLabel: milestoneLabelFor(e.type, years, person.isDeceased),
          };
        })
        .sort((a, b) => a.daysUntil - b.daysUntil),
    };
  }
}
