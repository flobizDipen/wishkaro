/**
 * What a model call costs, in USD per million tokens.
 *
 * VERSIONED, and the version goes on every ledger row. Prices change; a ledger that
 * re-derived historical cost from today's table would quietly rewrite what last month cost,
 * and the question this whole table exists to answer — "what does a user cost per year", the
 * one M7's pricing is built on — needs rows that stay true.
 *
 * Rates as published for the Claude API, first-party. Not Bedrock or Vertex, which are
 * partner-operated and priced separately; this app calls the first-party API.
 */

export const PRICING_VERSION = 'anthropic-2026-11';

export interface ModelRates {
  /** Fresh input tokens. */
  inputPerMTok: number;
  outputPerMTok: number;
  /** A cache read, roughly a twentieth of fresh input — the whole point of caching. */
  cacheReadPerMTok: number;
  /** Writing to the cache costs about 1.25x fresh input. Paid once per prefix per TTL. */
  cacheWritePerMTok: number;
}

/**
 * Only the models this app can actually be configured to use.
 *
 * Deliberately not the whole catalogue: an entry here is a claim that we have checked the
 * price, and a stale row for a model nobody runs is a lie waiting to be read as a fact.
 */
const RATES: Record<string, ModelRates> = {
  'claude-opus-5-5': {
    inputPerMTok: 4.0,
    outputPerMTok: 20.0,
    cacheReadPerMTok: 0.2,
    cacheWritePerMTok: 5.0,
  },
  'claude-sonnet-5-5': {
    inputPerMTok: 2.0,
    outputPerMTok: 10.0,
    cacheReadPerMTok: 0.1,
    cacheWritePerMTok: 2.5,
  },
  'claude-haiku-5-5': {
    inputPerMTok: 0.1,
    outputPerMTok: 0.5,
    cacheReadPerMTok: 0.01,
    cacheWritePerMTok: 0.125,
  },
};

/**
 * The fake provider's rates: zero.
 *
 * Not "no row". A call served by the fake still writes a ledger row, at zero cost, because a
 * ledger with gaps in it is a ledger nobody can reason about — and "how many drafts were
 * served without a model" is itself a question worth being able to ask.
 */
const FREE: ModelRates = {
  inputPerMTok: 0,
  outputPerMTok: 0,
  cacheReadPerMTok: 0,
  cacheWritePerMTok: 0,
};

/**
 * A model we have no price for.
 *
 * Returns zeros, and [isPriced] returns false so the caller can say so. The alternative —
 * guessing from a sibling model — would put a number in the ledger that looks measured and
 * is not, which is worse than a zero that is obviously a gap. A server-side fallback can
 * legitimately land here, which is exactly when somebody will be reading the table.
 */
const UNKNOWN: ModelRates = FREE;

export function ratesFor(model: string): ModelRates {
  return RATES[model] ?? (model.startsWith('fake') ? FREE : UNKNOWN);
}

export function isPriced(model: string): boolean {
  return model in RATES || model.startsWith('fake');
}

export interface Cost {
  usd: number;
  inr: number;
}

/**
 * Cost of one call, in USD and INR.
 *
 * [usdInrRate] is passed in rather than read from the environment here so that the value
 * stored on the row and the value used in the arithmetic are provably the same one.
 */
export function costOf(
  model: string,
  usage: {
    inputTokens: number;
    outputTokens: number;
    cacheReadTokens: number;
    cacheWriteTokens: number;
  },
  usdInrRate: number,
): Cost {
  const r = ratesFor(model);
  const usd =
    (usage.inputTokens * r.inputPerMTok +
      usage.outputTokens * r.outputPerMTok +
      usage.cacheReadTokens * r.cacheReadPerMTok +
      usage.cacheWriteTokens * r.cacheWritePerMTok) /
    1_000_000;

  // Six and four decimals, matching the Decimal(12,6) and Decimal(12,4) columns. Rounded
  // here rather than by Postgres so that what the code believes it wrote is what is there.
  return {
    usd: round(usd, 6),
    inr: round(usd * usdInrRate, 4),
  };
}

function round(value: number, places: number): number {
  const factor = 10 ** places;
  return Math.round(value * factor) / factor;
}
