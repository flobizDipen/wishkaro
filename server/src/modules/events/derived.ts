import type { EventType, Sentiment, TraitKind } from '@prisma/client';
import { type CivilDate, type EventDateSpec, occursOnIn } from '../../common/date/occurrence';

/**
 * Every value in this file is DERIVED on the server and never accepted from a client
 * (docs/BUILD_PROMPT_M1.md, "Engineering rules"). Pure functions, no Prisma, no NestJS, no
 * I/O — so the rules that carry the most product risk are testable without a database.
 *
 * The write schemas in `sync.schemas.ts` are `.strict()`, so a client that *tries* to send
 * one of these gets a 400 naming the field rather than having it silently stripped.
 */

// ---------------------------------------------------------------------------------------
// Sentiment (FR-EVT-01a, FR-EVT-04, FR-EVT-05)
// ---------------------------------------------------------------------------------------

/**
 * Sentiment is derived from the event type AND whether the person is deceased.
 *
 * FR-EVT-01a reads as though `type` alone decides it, and M0 implemented exactly that. It is
 * not sufficient. FR-EVT-05 allows a deceased person's birthday to be kept as an opt-in
 * "remembrance birthday", and that event's type is still BIRTHDAY — so under a type-only
 * derivation it came out CELEBRATORY and the notification copy was
 * "🎂 … is today / They turn 39 today." for someone who has died.
 *
 * REQUIREMENTS.md §7.3 calls that the worst failure this product can have and asks for it to
 * be impossible by construction rather than fixed in copy. Hence two inputs, one function,
 * and no caller anywhere that decides this for itself:
 *
 *     anything owned by a deceased person is SOLEMN, whatever its type.
 *
 * The second argument is REQUIRED rather than defaulted. A default would let a new call site
 * silently opt out of the rule; a required argument makes the compiler ask the question.
 */
export function sentimentFor(type: EventType, personIsDeceased: boolean): Sentiment {
  if (type === 'DEATH_ANNIVERSARY') return 'SOLEMN';
  if (personIsDeceased) return 'SOLEMN';
  return 'CELEBRATORY';
}

/** Creating one of these marks the person deceased (FR-EVT-04). */
export function marksPersonDeceased(type: EventType): boolean {
  return type === 'DEATH_ANNIVERSARY';
}

/** `label` is meaningful only on CUSTOM events (FR-EVT-06). */
export function acceptsLabel(type: EventType): boolean {
  return type === 'CUSTOM';
}

// ---------------------------------------------------------------------------------------
// Milestone labels (FR-EVT-03)
// ---------------------------------------------------------------------------------------

/**
 * The celebratory milestones. Years not listed here get no label at all — a 7th anniversary
 * is not a milestone and inventing one would cheapen the ones that are.
 *
 * Adding 5th or 60th later is a line in this table and nothing else.
 */
const CELEBRATORY_MILESTONES: ReadonlyMap<number, string> = new Map([
  [1, '1st'],
  [10, '10th'],
  [25, '25th Silver'],
  [50, '50th Golden'],
]);

/** Event types that can carry a celebratory milestone at all (FR-EVT-03 names these two). */
const MILESTONE_TYPES: ReadonlySet<EventType> = new Set<EventType>([
  'WEDDING_ANNIVERSARY',
  'ENGAGEMENT_ANNIVERSARY',
]);

/**
 * The milestone label for an occurrence, or null.
 *
 * Three rules, in this order, and the order matters:
 *
 *   1. **Year unknown means no label, ever.** `years` is null and there is nothing to count.
 *      FR-EVT-02 requires this everywhere, not just in the UI.
 *   2. **A death anniversary gets a plain year count** — "12 years", "1 year". Never Silver,
 *      never Golden, never an ordinal that reads as an achievement (FR-EVT-03).
 *   3. **Anything else owned by a deceased person gets nothing.** A remembrance birthday is
 *      not an occasion to label "50th Golden", and "they would have been 50" is a sentence
 *      this product does not put in a notification.
 */
export function milestoneLabelFor(
  type: EventType,
  years: number | null,
  personIsDeceased: boolean,
): string | null {
  if (years === null || years < 0) return null;

  if (type === 'DEATH_ANNIVERSARY') {
    if (years === 0) return null;
    return years === 1 ? '1 year' : `${years} years`;
  }

  if (personIsDeceased) return null;
  if (!MILESTONE_TYPES.has(type)) return null;

  return CELEBRATORY_MILESTONES.get(years) ?? null;
}

// ---------------------------------------------------------------------------------------
// Minor flag (FR-PPL-09)
// ---------------------------------------------------------------------------------------

/**
 * Exact age on a given date, honouring the leap-day rule: someone born on 29 February is a
 * year older on 28 February in a non-leap year, because that is the date this app observes
 * their birthday on (FR-EVT-09). Treating the two inconsistently would let a person be a
 * minor on their own birthday.
 */
export function exactAgeOn(spec: EventDateSpec, today: CivilDate): number | null {
  if (spec.year === null) return null;

  const observed = occursOnIn(spec, today.year);
  const hadBirthday =
    today.month > observed.month || (today.month === observed.month && today.day >= observed.day);

  return today.year - spec.year - (hadBirthday ? 0 : 1);
}

/**
 * Whether a person is a minor, derived from a KNOWN birth year (FR-PPL-09).
 *
 * An unknown year is not "probably an adult" and not "probably a child" — it is unknown, and
 * the flag stays false. The flag exists to EXCLUDE records from profiling, so the failure
 * that matters is flagging too few, not too many; this is recorded as a deliberate limit
 * rather than an oversight. A child whose birth year nobody entered is indistinguishable
 * from an adult whose birth year nobody entered.
 */
export function isMinorFor(birthday: EventDateSpec | null, today: CivilDate): boolean {
  if (!birthday) return false;
  const age = exactAgeOn(birthday, today);
  if (age === null) return false;
  return age < 18;
}

// ---------------------------------------------------------------------------------------
// Profile completeness (FR-PPL-05)
// ---------------------------------------------------------------------------------------

/**
 * The weights, in ONE place, as docs/BUILD_PROMPT_M1.md requires.
 *
 * They are ordered by value to gift suggestion quality (M8), which is the only reason this
 * score exists: a profile that is 100% full of fields nobody can buy a present from is not
 * complete in any sense worth showing. Hobbies and likes dominate; an email address barely
 * registers.
 *
 * They sum to exactly 100 and a test asserts it, so adding a field forces the rebalance to
 * be deliberate rather than letting the maximum quietly drift to 108.
 */
export const COMPLETENESS_WEIGHTS = {
  relation: 15,
  birthYear: 10,
  gender: 5,
  phone: 5,
  email: 3,
  notes: 7,
  hobby: 20,
  like: 15,
  dislike: 10,
  budgetBand: 10,
} as const;

export type CompletenessField = keyof typeof COMPLETENESS_WEIGHTS;

export const COMPLETENESS_TOTAL = Object.values(COMPLETENESS_WEIGHTS).reduce((a, b) => a + b, 0);

/** The trait kind that satisfies each trait-backed weight. */
const TRAIT_FIELDS: ReadonlyMap<CompletenessField, TraitKind> = new Map<
  CompletenessField,
  TraitKind
>([
  ['hobby', 'HOBBY'],
  ['like', 'LIKE'],
  ['dislike', 'DISLIKE'],
  ['budgetBand', 'BUDGET_BAND'],
]);

export interface CompletenessInput {
  relationToOwner: string | null;
  gender: string | null;
  phone: string | null;
  email: string | null;
  notes: string | null;
  /** The person's birthday spec, or null if they have no birthday event. */
  birthday: EventDateSpec | null;
  /** Live traits only — tombstoned ones must be filtered out before calling. */
  traitKinds: readonly TraitKind[];
  isMinor: boolean;
}

/** A field that is present and not just whitespace. */
function filled(value: string | null): boolean {
  return typeof value === 'string' && value.trim().length > 0;
}

/** Which weighted fields this profile already satisfies. */
export function completenessSatisfied(input: CompletenessInput): Set<CompletenessField> {
  const have = new Set<CompletenessField>();

  if (filled(input.relationToOwner)) have.add('relation');
  if (input.birthday?.year != null) have.add('birthYear');
  if (filled(input.gender)) have.add('gender');
  if (filled(input.phone)) have.add('phone');
  if (filled(input.email)) have.add('email');
  if (filled(input.notes)) have.add('notes');

  const kinds = new Set(input.traitKinds);
  for (const [field, kind] of TRAIT_FIELDS) {
    if (kinds.has(kind)) have.add(field);
  }

  return have;
}

/** 0..100, rounded. Always an integer, because it is stored in an INTEGER column. */
export function completenessScore(input: CompletenessInput): number {
  const have = completenessSatisfied(input);
  let earned = 0;
  for (const field of have) earned += COMPLETENESS_WEIGHTS[field];
  return Math.round((earned / COMPLETENESS_TOTAL) * 100);
}

/**
 * What to ask for next, heaviest first — the "one-tap improve" affordance (FR-PPL-05).
 *
 * Empty for a minor. FR-PPL-09 excludes flagged records from profiling and from
 * recommendation personalisation, so prompting their owner to fill in a child's hobbies and
 * budget band is precisely the behaviour the flag exists to prevent. The score is still
 * computed — it is only a number — but the app never asks for more.
 */
export function completenessSuggestions(input: CompletenessInput): CompletenessField[] {
  if (input.isMinor) return [];
  const have = completenessSatisfied(input);
  return (Object.keys(COMPLETENESS_WEIGHTS) as CompletenessField[])
    .filter((field) => !have.has(field))
    .sort((a, b) => COMPLETENESS_WEIGHTS[b] - COMPLETENESS_WEIGHTS[a]);
}
