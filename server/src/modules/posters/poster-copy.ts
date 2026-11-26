import type { EventType, Sentiment } from '@prisma/client';

/**
 * The words on a poster when no model wrote them (FR-POS-01a, FR-POS-04).
 *
 * FR-POS-01 describes step (a) as "a text model picks or writes the words and selects a
 * theme", and FR-POS-04 then says the common path makes **no live call at all**. Both are
 * true at once only if the no-model path has words of its own, so this is it: a deterministic
 * table, the same shape `reminder-copy.ts` already uses, and the thing the live path degrades
 * to when the spend cap bites or the provider declines (§10 rule 3, "falls back to a
 * template").
 *
 * ## The branch is taken first, and that is the whole design
 *
 * `reminder-copy.ts` says why in its own comment and it holds here with more force: a poster
 * is what gets sent, seen and screenshotted. The SOLEMN branch is chosen **before** the event
 * type, the milestone or anything else is consulted, so there is no combination of inputs
 * that can route a solemn occasion into the celebratory strings. REQUIREMENTS.md §11 makes
 * this a correctness requirement rather than a content preference.
 *
 * ## Why it is not "the drafted wish, shrunk"
 *
 * M5 writes a message. A message is two or three sentences and belongs in WhatsApp's text
 * field; a poster headline is three or four words at 64–96px. Putting the former on the
 * latter produces a wall of type over somebody's face, so the poster carries its own short
 * copy and the message travels beside it (FR-POS-01: "instead of, or alongside").
 *
 * TBD-5 is open, so these strings are plain rather than styled. They are not placeholders in
 * the way the artwork is — "Happy Birthday, Asha" is what the poster should say — but the
 * typography, the casing and the register are art direction and will change.
 */

export interface PosterCopyInput {
  personName: string;
  eventType: EventType;
  sentiment: Sentiment;
  /** "25th Silver", "12 years", or null. From `milestoneLabelFor()`, never re-derived. */
  milestone: string | null;
}

export interface PosterCopy {
  headline: string;
  subline: string | null;
}

export function buildPosterCopy(input: PosterCopyInput): PosterCopy {
  const name = input.personName.trim();

  // Taken first. Nothing below this line can be reached by a solemn occasion.
  if (input.sentiment === 'SOLEMN') {
    return {
      headline: `Remembering ${name}`,
      // A plain year count, never an ordinal that reads as an achievement — the rule
      // `milestoneLabelFor()` already enforces, repeated here only as a pass-through.
      subline: input.milestone,
    };
  }

  switch (input.eventType) {
    case 'BIRTHDAY':
      return { headline: `Happy Birthday, ${name}`, subline: input.milestone };
    case 'WEDDING_ANNIVERSARY':
      return {
        headline: `Happy Anniversary, ${name}`,
        subline: input.milestone ? `${input.milestone} anniversary` : null,
      };
    case 'ENGAGEMENT_ANNIVERSARY':
      return {
        headline: `Happy Engagement Anniversary, ${name}`,
        subline: input.milestone ? `${input.milestone} year` : null,
      };
    case 'CUSTOM':
      // No invented occasion wording. A CUSTOM event's label is the user's own and the
      // server does not know what it means, so the poster greets the person and says
      // nothing it cannot stand behind.
      return { headline: name, subline: null };
    case 'DEATH_ANNIVERSARY':
      /**
       * Unreachable: `sentimentFor()` makes a DEATH_ANNIVERSARY SOLEMN unconditionally, so
       * the branch above has already returned. It is written out rather than left to
       * `default:` because an exhaustive switch is what makes the compiler object when a
       * sixth event type is added, and the one thing that must never happen on a new type
       * is a silent fall-through into the celebratory strings.
       */
      return { headline: `Remembering ${name}`, subline: input.milestone };
  }
}
