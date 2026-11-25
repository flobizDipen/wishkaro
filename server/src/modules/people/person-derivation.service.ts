import { Inject, Injectable, Logger } from '@nestjs/common';
import type { Prisma } from '@prisma/client';
import { APP_ENV, type AppEnv } from '../../config/env';
import { PrismaService } from '../../prisma/prisma.service';
import { todayIn, type EventDateSpec } from '../../common/date/occurrence';
import { completenessScore, isMinorFor, sentimentFor } from '../events/derived';

/** Works against either the root client or a transaction client. */
type Db = PrismaService | Prisma.TransactionClient;

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

  async recompute(personId: string, db: Db = this.prisma, now: Date = new Date()): Promise<void> {
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
        data: { isDeceased, isMinor, completenessScore: score, updatedAt: now },
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
          data: { sentiment: correct, updatedAt: now },
        });
        this.logger.log(
          `Re-derived sentiment for event ${event.id} (${event.type}): ` +
            `${event.sentiment} -> ${correct}`,
        );
      }
    }
  }

  /** Convenience for write paths that have just touched several people. */
  async recomputeMany(personIds: Iterable<string>, db: Db = this.prisma): Promise<void> {
    const now = new Date();
    for (const id of new Set(personIds)) {
      await this.recompute(id, db, now);
    }
  }
}
