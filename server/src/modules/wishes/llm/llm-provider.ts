/**
 * The one seam between this app and a model.
 *
 * Narrow on purpose, and it is not speculative generality. It exists so the MECHANISM tests
 * — the ledger writes a row, the rate limit bites, a refusal becomes a template, a network
 * error becomes a template — run in CI with no key and no money, against a deterministic
 * fake. The PROMPT tests do not use it: `MILESTONE_CHECKLIST.md` §D and
 * docs/BUILD_PROMPT_M5.md are both explicit that a test asserting on a fake model's output
 * tests the fake, so the tests that are about words call the real thing and live in `live/`.
 *
 * The fake is therefore never asked a question about words. It is asked whether the plumbing
 * around the call is correct, which is a question it can answer.
 */

export type DraftOutcome = 'OK' | 'REFUSED' | 'ERROR' | 'TIMEOUT';

/** What the provider was asked for. Already built; the provider does not assemble prompts. */
export interface DraftPrompt {
  /** Stable across every request with the same tone and language. The cache prefix. */
  system: string;
  /** Volatile: this person, this occasion, this history. After the cache breakpoint. */
  user: string;
}

/**
 * Token counts, NORMALISED.
 *
 * Four, not two. Cache reads cost roughly a twentieth of fresh input, so a ledger that
 * cannot tell them apart cannot answer whether prompt caching is working — which is the one
 * question caching has to be checked against rather than assumed.
 *
 * **The four counts are DISJOINT, and this is a contract every adapter has to honour.**
 * `inputTokens` means input tokens that were neither read from the cache nor written to it,
 * so that `costOf` can add all four at their own rates without counting anything twice.
 *
 * It is a contract because the two providers disagree. Anthropic reports `input_tokens`
 * already excluding cached tokens; OpenAI reports `input_tokens` as the TOTAL, with
 * `cached_tokens` and `cache_write_tokens` as subsets of it. An adapter that passed OpenAI's
 * numbers straight through would bill the cached tokens twice — once at the full input rate
 * inside the total, and again at the cache rate — and the ledger would read high by exactly
 * the amount caching was supposed to save. A cost table that is wrong in the direction of
 * "caching looks useless" is worse than no cost table, because somebody acts on it.
 */
export interface TokenUsage {
  inputTokens: number;
  outputTokens: number;
  cacheReadTokens: number;
  cacheWriteTokens: number;
}

export const NO_TOKENS: TokenUsage = {
  inputTokens: 0,
  outputTokens: 0,
  cacheReadTokens: 0,
  cacheWriteTokens: 0,
};

/**
 * Always returned, on every outcome — there is no throwing path out of a provider.
 *
 * That is deliberate. Every call has to write a ledger row, including the ones that failed,
 * because a call that produced tokens cost money whether or not the user got anything. An
 * exception would make the ledger write an afterthought in a `catch`; a result type makes it
 * the only way out.
 */
export interface DraftResult {
  outcome: DraftOutcome;
  /** The message, on OK. Empty on every other outcome — never a partial. */
  text: string;
  /**
   * The model that ACTUALLY served it. A server-side fallback changes this, and the ledger
   * recording it is the only way to find out afterwards that one happened.
   */
  model: string;
  usage: TokenUsage;
  /**
   * On REFUSED, the category the API gave (`stop_details.category`): an open set, and
   * sometimes null. Logged and stored; never shown to the user. The screen where somebody is
   * writing about a death is the worst place in this app to surface a classifier's opinion.
   */
  refusalCategory?: string | null;
  /** On ERROR or TIMEOUT, something a developer can act on. Never shown to the user either. */
  errorMessage?: string;
}

export interface LlmProvider {
  /** A short name for the ledger: "anthropic", "openai", or "fake". */
  readonly providerName: string;

  /**
   * The model this provider is configured to call.
   *
   * On the provider rather than read from the environment by the caller: `DraftingService`
   * needs a model name for the ledger row it writes when a provider throws before returning
   * one, and reaching into `env.ANTHROPIC_MODEL` for that — which is what it used to do —
   * is a provider-specific name leaking into provider-neutral code. It would have gone on
   * quietly recording "claude-opus-5-5" against failed OpenAI calls.
   */
  readonly modelName: string;

  /**
   * True when this provider can actually reach a model.
   *
   * The fake answers false, which is what lets the composer mark its output as TEMPLATE
   * rather than claiming a model wrote it (FR-HIS-01's `source` column). An honest history
   * row is worth one boolean.
   */
  readonly isLive: boolean;

  draft(prompt: DraftPrompt): Promise<DraftResult>;
}

/** Koin-style DI token. Nest needs a symbol for an interface-typed provider. */
export const LLM_PROVIDER = Symbol('LLM_PROVIDER');
