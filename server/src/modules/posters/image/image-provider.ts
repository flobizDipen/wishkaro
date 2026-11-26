/**
 * The one seam between this app and an image model (D21, `REQUIREMENTS.md` §10).
 *
 * §10 already names it: "OpenAI `gpt-image-1` for background artwork and the optional
 * AI-blend edit path, **behind `ImageProvider`**." The shape mirrors `LlmProvider`
 * deliberately, because the two have the same job — let the mechanism tests run in CI with
 * no key and no money — and because a second seam that worked differently would be a second
 * set of rules to remember.
 *
 * Three things are NOT the same as `LlmProvider`, and each is a cost-shape difference rather
 * than a style one:
 *
 *  1. **Billing is per IMAGE, not per token.** `TokenUsage` does not describe it, so the
 *     result carries an image count, a size and a quality tier instead. `pricing.ts` has a
 *     separate table and a separate `imageCostOf`.
 *  2. **There is no structured output to validate.** §10 rule 3 — "a malformed response is
 *     retried once, then falls back to a template" — applies to bytes rather than to JSON,
 *     so the adapter's equivalent is checking that what came back decodes as an image of the
 *     size that was asked for, retrying once, then degrading to the library.
 *  3. **The thing it generates is the BACKGROUND and nothing else** (FR-POS-01b). No face
 *     is ever sent to it. FR-POS-02 makes that a likeness and personality-rights rule with
 *     no configuration that turns it off; the one exception is FR-POS-07's AI blend, which
 *     is explicitly not in M6, and which is why this interface has no image INPUT at all.
 *     A port that cannot accept a photo cannot be made to send one by a later edit.
 */

export type ImageOutcome = 'OK' | 'REFUSED' | 'ERROR' | 'TIMEOUT';

/** What a background is asked for. Text in, bytes out — never an image in. */
export interface ImagePrompt {
  /** The art-direction prompt. Built by `poster-prompt.ts`, versioned. */
  prompt: string;
  /** "1024x1536" — the API's own size vocabulary, chosen for the poster's aspect ratio. */
  size: string;
  /** "low" | "medium" | "high". The tier that decides the per-image price. */
  quality: string;
}

export interface ImageResult {
  outcome: ImageOutcome;
  /** The PNG, on OK. Null on every other outcome — never a partial. */
  png: Buffer | null;
  model: string;
  /** How many images were actually billed. Not assumed to be one. */
  imageCount: number;
  size: string;
  quality: string;
  refusalCategory?: string | null;
  errorMessage?: string;
}

export interface ImageProvider {
  /** For the ledger: "openai" or "fake". */
  readonly providerName: string;
  readonly modelName: string;

  /**
   * True when this provider can actually reach a model.
   *
   * The fake answers false, which is what lets a `Generation` row record honestly that no
   * model was involved — the same honesty `LlmProvider.isLive` buys for `WishSource`.
   */
  readonly isLive: boolean;

  /** Never throws. Every outcome is a value, so the ledger write cannot become a `catch`. */
  generate(prompt: ImagePrompt): Promise<ImageResult>;
}

export const IMAGE_PROVIDER = Symbol('IMAGE_PROVIDER');
