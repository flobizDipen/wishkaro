import type { ModerationVerdict } from '@prisma/client';

/**
 * The seam between this app and a content classifier (REQUIREMENTS.md §10 rule 7).
 *
 * > "Input and output moderation on every generation, with user reporting."
 *
 * That rule has **no FR- id**, which is exactly how M5 missed it: M5's plan quoted
 * requirements by id and §10's seven hard rules carry none. It is recorded as a knowingly
 * unmet hard rule in `docs/M5_VERIFY.md` §4.6 against code that is already in users' hands,
 * and `docs/M6_PLAN.md` §5 is explicit that M6 does not open believing it was handed over —
 * it was *offered*, twice, and never decided. So M6 builds it for **both** surfaces, posters
 * and the wish drafting path that already shipped.
 *
 * ## The honest limit, stated here rather than discovered later
 *
 * The classifier this would use for real is OpenAI's, and it needs the same
 * `OPENAI_API_KEY` that does not exist on this machine. So the **mechanism** — the port, the
 * deterministic input check, the stored verdict, the block path, the report endpoint and the
 * UI — is built and tested, and a **live verdict is not reached**. `ModerationVerdict` has
 * an `UNCHECKED` value for precisely this reason: a generation served by the fake records
 * what actually happened rather than claiming an approval nobody gave. `M6_VERIFY.md`
 * records rule 7 as structurally met and not actually exercised, which are two different
 * claims.
 */

export interface ModerationCheck {
  verdict: ModerationVerdict;
  /** Which categories fired, for the log and the error code. Never shown to a user. */
  categories: readonly string[];
  /** A short code a developer can act on. Null when nothing fired. */
  reasonCode: string | null;
}

export const UNCHECKED: ModerationCheck = Object.freeze({
  verdict: 'UNCHECKED' as ModerationVerdict,
  categories: Object.freeze([]),
  reasonCode: null,
});

export const ALLOWED: ModerationCheck = Object.freeze({
  verdict: 'ALLOWED' as ModerationVerdict,
  categories: Object.freeze([]),
  reasonCode: null,
});

export function blocked(categories: readonly string[], reasonCode: string): ModerationCheck {
  return { verdict: 'BLOCKED', categories, reasonCode };
}

export interface ModerationProvider {
  /** For the log and for `generation.error_code`: "openai" or "fake". */
  readonly providerName: string;

  /**
   * True when this provider can actually reach a classifier.
   *
   * The fake answers false, which is what lets a `Generation` row record `UNCHECKED`
   * honestly — the same honesty `LlmProvider.isLive` buys for `WishSource` and
   * `ImageProvider.isLive` buys for a ₹0 ledger row.
   */
  readonly isLive: boolean;

  /** Never throws. A classifier that is down must not be the thing that fails a request. */
  checkText(text: string): Promise<ModerationCheck>;

  /**
   * Output moderation for a generated image (FR-POS-12).
   *
   * Takes PNG bytes rather than a URL: at the moment this runs the poster has not been
   * stored anywhere, and storing it first in order to moderate it would mean a blocked
   * poster that briefly existed at a fetchable key.
   */
  checkImage(png: Buffer): Promise<ModerationCheck>;
}

export const MODERATION_PROVIDER = Symbol('MODERATION_PROVIDER');
