/**
 * What a model call costs, in USD per million tokens.
 *
 * VERSIONED, and the version goes on every ledger row. Prices change; a ledger that
 * re-derived historical cost from today's table would quietly rewrite what last month cost,
 * and the question this whole table exists to answer — "what does a user cost per year", the
 * one M7's pricing is built on — needs rows that stay true.
 *
 * Rates as published on each provider's own pricing page on 2026-11-26, first-party. Not
 * Bedrock, Vertex or Azure, which are partner-operated and priced separately; this app calls
 * both first-party APIs directly.
 *
 * The OpenAI rows were read off `platform.openai.com/docs/pricing` rather than from a
 * third-party rate-card blog, because four aggregator sites disagreed with each other about
 * the current flagship and two of them were a generation behind. A number in a cost ledger
 * that came from a blog is a number that looks measured and is not.
 */

export const PRICING_VERSION = 'claude+openai-2026-11-26';

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

  // ---- OpenAI (D20 amended; see docs/M5_PLAN.md) ------------------------------------
  //
  // Only the three tiers this app could sensibly be pointed at. The catalogue has forty-odd
  // text models and an entry here is a claim that somebody checked the price — a stale row
  // for a model nobody runs is a lie waiting to be read as a fact.

  /**
   * The cheap, high-volume tier, and M5's default.
   *
   * Identical published rates to Claude Haiku 5.5, which makes the two a clean comparison:
   * the same prompt on both costs the same, so a quality difference is the only difference.
   * D20's own words for this feature are "Haiku for high-volume wish drafting".
   */
  'gpt-6-luna': {
    inputPerMTok: 0.1,
    outputPerMTok: 0.5,
    cacheReadPerMTok: 0.01,
    cacheWritePerMTok: 0.125,
  },
  /** Mid tier, if the cheap one's Hindi or solemn copy does not hold up. */
  'gpt-6.1-terra': {
    inputPerMTok: 1.0,
    outputPerMTok: 5.0,
    cacheReadPerMTok: 0.05,
    cacheWritePerMTok: 1.25,
  },
  /** The current flagship, for a like-for-like comparison against Claude Opus 5.5. */
  'gpt-6.2-astra': {
    inputPerMTok: 8.0,
    outputPerMTok: 40.0,
    cacheReadPerMTok: 0.4,
    cacheWritePerMTok: 10.0,
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
