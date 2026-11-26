import type { EventType, Sentiment, WishTone } from '@prisma/client';

/**
 * Which tones are legal on which sentiment (FR-WSH-03, FR-WSH-09).
 *
 * Pure, no Prisma client, no NestJS, no I/O — the same shape as `events/derived.ts`, and for
 * the same reason: the rules that carry the most product risk should be testable without a
 * database.
 *
 * This rule exists TWICE, here and in Kotlin, because the server validates it and the client
 * hides the chips. `MILESTONE_CHECKLIST.md` §C is explicit about the price of that, so both
 * halves are held to `docs/fixtures/wish-tones.json` — `tone-rules.spec.ts` and
 * `WishToneRulesTest.kt` read the same file, and if the two drift one of them goes red.
 */

/**
 * Separate SETS, chosen before anything is built — not one list with a conditional in it.
 *
 * `domain/Tone.kt` already makes this argument for the app's static copy and it applies
 * exactly as well here: a conditional is a thing someone can forget; a set you have to pick
 * is a thing the compiler makes you pick.
 */
const CELEBRATORY_TONES: readonly WishTone[] = ['WARM', 'FUNNY', 'RESPECTFUL', 'SHORT', 'POETIC'];

/**
 * FR-WSH-09. No FUNNY, no POETIC, and `HEARTFELT` exists only here — FR-WSH-03's list does
 * not contain it, so neither set is a subset of the other and the union is six.
 */
const SOLEMN_TONES: readonly WishTone[] = ['RESPECTFUL', 'SHORT', 'HEARTFELT'];

/** The one place a tone list is produced. Everything that offers or validates tones reads this. */
export function tonesFor(sentiment: Sentiment): readonly WishTone[] {
  return sentiment === 'SOLEMN' ? SOLEMN_TONES : CELEBRATORY_TONES;
}

/**
 * The default the composer opens on.
 *
 * SOLEMN defaults to RESPECTFUL rather than to the first of some shared list. A default that
 * leaked across the two sets would be M1's failure again: the wrong set chosen because
 * nobody chose.
 */
export function defaultToneFor(sentiment: Sentiment): WishTone {
  return sentiment === 'SOLEMN' ? 'RESPECTFUL' : 'WARM';
}

export function isToneAllowed(sentiment: Sentiment, tone: WishTone): boolean {
  return tonesFor(sentiment).includes(tone);
}

/**
 * Thrown rather than returned, and caught at the controller into a 400 naming the tone.
 *
 * A request asking for FUNNY on a death anniversary is REJECTED, not quietly coerced to
 * RESPECTFUL. Coercion would mean a client with that bug keeps shipping it and nobody ever
 * finds out — the same argument `refineEventLabel` makes about a label on a BIRTHDAY.
 */
export class ToneNotAllowedError extends Error {
  constructor(
    readonly sentiment: Sentiment,
    readonly tone: WishTone,
  ) {
    super(`${tone} is not available on a ${sentiment} occasion`);
    this.name = 'ToneNotAllowedError';
  }
}

export function assertToneAllowed(sentiment: Sentiment, tone: WishTone): void {
  if (!isToneAllowed(sentiment, tone)) throw new ToneNotAllowedError(sentiment, tone);
}

/**
 * Re-exported from `events/derived.ts` rather than re-implemented, deliberately.
 *
 * A wish's sentiment has to be the SAME function the event's sentiment came from. Two
 * implementations of "is this solemn" is how a remembrance birthday ends up celebratory in
 * one place and solemn in another, and the whole point of FR-EVT-01a is that there is one
 * answer. This file re-exports it so that a reader of the wish module can see where the
 * answer comes from without going looking.
 */
export { sentimentFor } from '../events/derived';

/**
 * Whether this event type can legitimately occur twice for one person in one year.
 *
 * Only CUSTOM can. It is the known ambiguity in the `(personId, eventType, occurrenceYear)`
 * key — see `schema.prisma`'s Wish model and docs/M5_PLAN.md §2.3. Not used to reject
 * anything; used by the history query to say "there may be more than one of these" rather
 * than silently treating two different occasions as one.
 */
export function occasionKeyIsAmbiguous(type: EventType): boolean {
  return type === 'CUSTOM';
}
