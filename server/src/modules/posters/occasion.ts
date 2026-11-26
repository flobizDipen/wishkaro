import type { EventType, Sentiment } from '@prisma/client';
import { isDeceasedConsidering, milestoneLabelFor, sentimentFor } from '../events/derived';

/**
 * What a poster is *of* — resolved in one place, from rows, before anything is drawn.
 *
 * ## Why this file exists at all
 *
 * `docs/BUILD_PROMPT_M6.md` names the failure this milestone is most likely to produce:
 * **a celebratory theme on a death anniversary.** It is not a guess. M5 shipped two bugs of
 * exactly that shape in one milestone (`454ea75`, `cb5919d`), both "derived state read one
 * step too early", and both invisible to a full passing suite because every fixture in this
 * repository builds a person the way the server returns one — with the flag already set.
 *
 * So the resolution is a pure function over the rows that were actually read, it is called
 * from exactly one place in the pipeline, and `occasion.db.spec.ts` constructs the stale
 * window on purpose against real Postgres rather than hoping a fixture happens to.
 *
 * ## The three rules it encodes, none of them new
 *
 *  1. **The sentiment comes from `sentimentFor()`** — the one implementation, in
 *     `events/derived.ts`, mirrored on the client by `Person.isSolemn` and nowhere else.
 *     M6 adds no third.
 *  2. **`person.is_deceased` is never read on its own.** It is derived, and it lags the row
 *     that implies it by however long `recompute` takes to run. `isDeceasedConsidering`
 *     reads the person's live event rows in the same query and ORs their types in, so a
 *     death anniversary that exists in any form decides this read.
 *  3. **The milestone label comes from `milestoneLabelFor()`**, which already refuses to
 *     call a remembrance "25th Silver" and already turns a death anniversary into a plain
 *     year count. It is part of the cache key, so getting it from anywhere else would also
 *     split the cache.
 */

/** The shape this needs from a person row. A subset, so a test can build one by hand. */
export interface PersonFacts {
  isDeceased: boolean;
  /** The types of the person's LIVE events. Tombstoned rows must be filtered out first. */
  liveEventTypes: readonly EventType[];
}

export interface OccasionInput {
  person: PersonFacts;
  eventType: EventType;
  /** The year the occasion falls in, as the client asked for it. */
  occurrenceYear: number;
  /** The year the event started — a birth year, a wedding year — or null (FR-EVT-02). */
  eventYear: number | null;
}

export interface Occasion {
  sentiment: Sentiment;
  /** What `is_deceased` should be read as right now, lag included. */
  deceased: boolean;
  /** Elapsed years, or null when the start year was never known. */
  years: number | null;
  /** "25th Silver", "12 years", or null. Part of the cache key. */
  milestone: string | null;
}

export function resolveOccasion(input: OccasionInput): Occasion {
  const deceased = isDeceasedConsidering(input.person.isDeceased, input.person.liveEventTypes);
  const sentiment = sentimentFor(input.eventType, deceased);

  const years = input.eventYear != null ? input.occurrenceYear - input.eventYear : null;

  return {
    sentiment,
    deceased,
    years,
    milestone: milestoneLabelFor(input.eventType, years, deceased),
  };
}
