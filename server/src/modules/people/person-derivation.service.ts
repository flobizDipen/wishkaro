import { Inject, Injectable, Logger } from '@nestjs/common';
import type { Prisma } from '@prisma/client';
import { APP_ENV, type AppEnv } from '../../config/env';
import { PrismaService } from '../../prisma/prisma.service';
import { todayIn, type EventDateSpec } from '../../common/date/occurrence';
import { completenessScore, isMinorFor, sentimentFor } from '../events/derived';

/** Works against either the root client or a transaction client. */
type Db = PrismaService | Prisma.TransactionClient;

/**
 * What the caller knew before it wrote.
 *
 * `recompute` re-reads the person, so by the time it runs, a caller that has already written
 * `is_deceased` has destroyed the evidence of a transition. Every caller that writes that
 * flag before calling therefore has to hand back the value it overwrote.
 */
export interface RecomputeContext {
  /**
   * The person's `is_deceased` as it stood BEFORE this write path touched them.
   *
   * Omit it only when the caller has not written the flag itself — then the value still in
   * the database IS the previous value, and that is what the default uses.
   */
  wasDeceased?: boolean;
}

/**
 * Recomputes every server-derived value that hangs off a person.
 *
 * This exists because the derived values are not independent of each other. Marking someone
 * deceased changes the sentiment of events that were written months earlier; adding a
 * birthday with a year changes whether they are a minor; adding a trait changes their
 * completeness score. Scattering those recalculations across the services that happen to
 * perform the write is how one of them gets missed, and the one that matters if it is missed
 * is sentiment.
 *
 * So: every write path that touches a person, their events or their traits finishes by
 * calling `recompute(personId)`. It is idempotent and cheap, and it is the only place any of
 * these four values is ever written.
 */
@Injectable()
export class PersonDerivationService {
  private readonly logger = new Logger(PersonDerivationService.name);

  constructor(
    private readonly prisma: PrismaService,
    @Inject(APP_ENV) private readonly env: AppEnv,
  ) {}

  async recompute(
    personId: string,
    db: Db = this.prisma,
    now: Date = new Date(),
    context: RecomputeContext = {},
  ): Promise<void> {
    const person = await db.person.findUnique({
      where: { id: personId },
      include: {
        user: { select: { timezone: true } },
        events: { where: { deletedAt: null } },
        traits: { where: { deletedAt: null }, select: { kind: true } },
      },
    });
    if (!person) return;

    // --- is_deceased (FR-EVT-04) -------------------------------------------------------
    //
    // A live DEATH_ANNIVERSARY forces the flag true. The flag is ALSO directly settable as a
    // profile field (FR-PPL-04), so it is not purely derived — hence the OR rather than an
    // assignment. Deleting a death anniversary deliberately does NOT clear it: silently
    // flipping a person back to celebratory because a row was removed is the exact failure
    // FR-EVT-05 exists to prevent. Clearing it is an explicit edit, and the people service
    // refuses that edit while a death anniversary is still live.
    const hasDeathAnniversary = person.events.some((e) => e.type === 'DEATH_ANNIVERSARY');
    const isDeceased = person.isDeceased || hasDeathAnniversary;

    // NOT `person.isDeceased` — that is what the row says NOW, and a caller that set the flag
    // itself has already written it, so the comparison would be `!true && true` and the
    // transition would never be seen. Ticking the deceased box on a profile went down exactly
    // that path and left the birthday unmuted. Callers that write the flag pass back what
    // they overwrote; callers that do not write it leave the database as the authority.
    const wasDeceased = context.wasDeceased ?? person.isDeceased;
    const becameDeceased = !wasDeceased && isDeceased;

    // --- the remembrance birthday opt-in (FR-EVT-05) -----------------------------------
    //
    // "A deceased person's birthday reminder is disabled by default, with an opt-in
    // remembrance birthday that behaves as SOLEMN."
    //
    // Expressed as a mute rather than a new column. `is_muted` already means exactly
    // "this event exists but does not generate reminders", the materialiser already honours
    // it, and un-muting is then literally the opt-in — at which point the reminder fires
    // with SOLEMN copy, because sentiment is derived from is_deceased rather than stored.
    //
    // Only on the TRANSITION, never on every recompute: otherwise opting in would be undone
    // by the next write that touched this person.
    if (becameDeceased) {
      const birthdays = person.events.filter((e) => e.type === 'BIRTHDAY' && !e.isMuted);
      for (const birthday of birthdays) {
        await db.event.update({
          where: { id: birthday.id },
          data: { isMuted: true, updatedAt: bumped(birthday.updatedAt, now) },
        });
        this.logger.log(
          `Muted birthday ${birthday.id}: person ${personId} is now deceased. ` +
            'Un-muting it opts in to a remembrance birthday (FR-EVT-05).',
        );
      }
    }

    // --- is_minor (FR-PPL-09) ----------------------------------------------------------
    const birthdayEvent = person.events.find((e) => e.type === 'BIRTHDAY');
    const birthday: EventDateSpec | null = birthdayEvent
      ? { day: birthdayEvent.day, month: birthdayEvent.month, year: birthdayEvent.year }
      : null;
    const zone = person.user.timezone || this.env.DEFAULT_TIMEZONE;
    const isMinor = isMinorFor(birthday, todayIn(zone, now));

    // --- completeness_score (FR-PPL-05) ------------------------------------------------
    const score = completenessScore({
      relationToOwner: person.relationToOwner,
      gender: person.gender,
      phone: person.phone,
      email: person.email,
      notes: person.notes,
      birthday,
      traitKinds: person.traits.map((t) => t.kind),
      isMinor,
    });

    const personChanged =
      person.isDeceased !== isDeceased ||
      person.isMinor !== isMinor ||
      person.completenessScore !== score;

    if (personChanged) {
      await db.person.update({
        where: { id: personId },
        data: { isDeceased, isMinor, completenessScore: score, updatedAt: bumped(person.updatedAt, now) },
      });
    }

    // --- sentiment on every live event (FR-EVT-01a, FR-EVT-05) -------------------------
    //
    // Rewritten here rather than only at insert time, because `isDeceased` can change long
    // after an event was created. `updatedAt` is bumped so the correction reaches every
    // client on the next sync pull rather than sitting on the server.
    for (const event of person.events) {
      const correct = sentimentFor(event.type, isDeceased);
      if (event.sentiment !== correct) {
        await db.event.update({
          where: { id: event.id },
          data: { sentiment: correct, updatedAt: bumped(event.updatedAt, now) },
        });
        this.logger.log(
          `Re-derived sentiment for event ${event.id} (${event.type}): ` +
            `${event.sentiment} -> ${correct}`,
        );
      }
    }
  }

  /**
   * Convenience for write paths that have just touched several people.
   *
   * `contexts` carries the pre-write `is_deceased` per person, for the same reason the single
   * form takes one: a sync batch writes the flag and then recomputes, so without it every
   * transition in the batch is invisible.
   */
  async recomputeMany(
    personIds: Iterable<string>,
    db: Db = this.prisma,
    contexts?: ReadonlyMap<string, RecomputeContext>,
  ): Promise<void> {
    const now = new Date();
    for (const id of new Set(personIds)) {
      await this.recompute(id, db, now, contexts?.get(id) ?? {});
    }
  }
}

/**
 * A timestamp for a re-derivation that is never OLDER than the row it is re-deriving.
 *
 * `updatedAt` on a synced row is whatever the CLIENT stamped it with, because that is what
 * drives last-write-wins. A device whose clock runs fast therefore writes a timestamp in the
 * server's future — and `new Date()` at derivation time is then earlier than the row's own
 * `updatedAt`, so writing it moves the row's version BACKWARDS.
 *
 * Two things go wrong when it does. The client's last-write-wins sees the corrected copy as
 * older and discards it, which is how an entire handset's completeness scores were lost in
 * M3 (docs/M3_VERIFY.md §6.3). And `updatedAt` is the sync cursor's ordering key, so a row
 * that moves backwards can fall behind a cursor that has already passed it and never be
 * delivered again.
 *
 * One millisecond past, not more: Postgres stores `timestamp(3)` and the API formats with
 * `toISOString()`, so anything finer is invisible on the wire. The client's own retry stamp
 * uses exactly the same trick for exactly the same reason — see `SyncEngine.retryStamp`.
 */
export function bumped(current: Date | null | undefined, now: Date): Date {
  // No recorded timestamp means no constraint: there is nothing to move backwards from.
  if (!current) return now;
  return now.getTime() > current.getTime() ? now : new Date(current.getTime() + 1);
}
