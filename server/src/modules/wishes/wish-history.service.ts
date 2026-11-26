import { Injectable } from '@nestjs/common';
import type { EventType, Wish } from '@prisma/client';
import { PrismaService } from '../../prisma/prisma.service';

/**
 * What was sent to this person for this occasion in previous years.
 *
 * This is the input FR-WSH-02's "must not repeat" is built on, and the reason wish history
 * had to land before the composer did (docs/BUILD_PROMPT_M5.md, "Why history goes first").
 */

/** One previous year's message, trimmed to what the prompt actually needs. */
export interface PreviousWish {
  year: number;
  tone: string;
  language: string;
  text: string;
}

/**
 * How many previous years go into a prompt.
 *
 * Three, not all of them. The rule is "do not repeat", not "summarise a decade": every extra
 * year is input tokens on every draft for ever, and a message from six years ago is not one
 * the recipient remembers either. Three also means the conditioning block has a bounded size,
 * which matters for the cache layout in docs/M5_PLAN.md §3.3 — an unbounded block after the
 * cache breakpoint grows the uncached part of every request without limit.
 */
export const NO_REPEAT_YEARS = 3;

@Injectable()
export class WishHistoryService {
  constructor(private readonly prisma: PrismaService) {}

  /**
   * The previous years' wishes for one occasion, newest first.
   *
   * **Strictly earlier years.** `occurrenceYear` is excluded, not included: a wish already
   * recorded for THIS year is not something to avoid repeating, it is the user having
   * already sent one — and feeding it in as "do not write this" would make a redraft of a
   * message they liked impossible.
   *
   * Tombstones are excluded. A deleted history row is the user saying "that is not what I
   * sent"; continuing to avoid it would be acting on a record they have withdrawn.
   *
   * Ownership is asserted here rather than assumed from the person, because this runs on the
   * drafting path where `personId` arrives from a request body.
   */
  async previousWishes(
    userId: string,
    personId: string,
    eventType: EventType,
    occurrenceYear: number,
    limit: number = NO_REPEAT_YEARS,
  ): Promise<PreviousWish[]> {
    const rows = await this.prisma.wish.findMany({
      where: {
        userId,
        personId,
        eventType,
        occurrenceYear: { lt: occurrenceYear },
        deletedAt: null,
      },
      orderBy: [{ occurrenceYear: 'desc' }, { sentAt: 'desc' }],
      take: limit,
    });

    return rows.map(toPreviousWish);
  }

  /**
   * Everything this person was ever sent, newest first — FR-PPL-07's history list.
   *
   * Not filtered by event type: the person screen shows one timeline, not one per occasion.
   * Capped, because a ten-year-old account with four events a year is 40 rows and a person
   * screen does not want all of them at once.
   */
  async historyFor(userId: string, personId: string, limit = 50): Promise<Wish[]> {
    return this.prisma.wish.findMany({
      where: { userId, personId, deletedAt: null },
      orderBy: [{ sentAt: 'desc' }],
      take: limit,
    });
  }

  /**
   * FR-HIS-04's "Last year you sent…", for the composer.
   *
   * The most recent previous year only, or null. Separate from [previousWishes] on purpose:
   * that one feeds the model and this one is shown to the user, and conflating them would
   * mean a change to what the prompt sees silently changing what is on screen.
   */
  async lastYear(
    userId: string,
    personId: string,
    eventType: EventType,
    occurrenceYear: number,
  ): Promise<PreviousWish | null> {
    const [previous] = await this.previousWishes(userId, personId, eventType, occurrenceYear, 1);
    return previous ?? null;
  }
}

function toPreviousWish(row: Wish): PreviousWish {
  return {
    year: row.occurrenceYear,
    tone: row.tone,
    language: row.language,
    text: row.messageText,
  };
}
