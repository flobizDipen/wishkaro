import { Logger } from '@nestjs/common';
import OpenAI from 'openai';
import type { Moderation, ModerationCreateResponse } from 'openai/resources/moderations';
import type { ModerationCheck, ModerationProvider } from './moderation-provider';
import { ALLOWED, UNCHECKED, blocked } from './moderation-provider';

/**
 * The live classifier (REQUIREMENTS.md §10 rule 7).
 *
 * `omni-moderation-latest` takes text and images in one endpoint, which is why one port
 * covers both surfaces rather than two. It is not billed per call, so unlike every other
 * model call in this app it writes **no ledger row** — §10 rule 2's "every call is wrapped
 * in a generation record with measured INR cost" is about calls that cost money, and a row
 * of zeroes for a free endpoint would dilute the table the spend cap reads.
 *
 * ## Nothing here has been run
 *
 * There is no `OPENAI_API_KEY` on this machine, so this adapter has never made a request.
 * The response shapes below are the installed SDK's own types — `tsc` rejects a field that
 * does not exist on `Moderation` — so they are not invented from memory, but a type is a
 * claim about a shape rather than a recording of one. `M6_VERIFY.md` records a live
 * moderation verdict as **BLOCKED**, not as passing, for exactly this reason. The adapter
 * exists so that `MODERATION_PROVIDER=openai` is a real option rather than an env value
 * that throws, and so that the day a key arrives is a config change and not a milestone.
 */

/** `blocked()` needs a category list; the SDK gives a flag per category. */
function flaggedCategories(result: Moderation): string[] {
  return Object.entries(result.categories)
    .filter(([, isFlagged]) => isFlagged === true)
    .map(([name]) => name);
}

export class OpenAiModerationProvider implements ModerationProvider {
  readonly providerName = 'openai';
  readonly isLive = true;

  private readonly logger = new Logger(OpenAiModerationProvider.name);
  private readonly client: OpenAI;

  constructor(
    apiKey: string,
    private readonly model: string = 'omni-moderation-latest',
    /** Injectable so a test can drive the mapping without a network. */
    client?: OpenAI,
  ) {
    this.client =
      client ??
      new OpenAI({
        apiKey,
        /**
         * Shorter than the drafting adapter's twenty seconds, deliberately.
         *
         * This runs *in addition to* the generation it guards, so its latency is added to
         * FR-POS-09's p95 budget of 15 seconds on a cache miss rather than spent inside it.
         * A classifier that takes longer than five seconds has already cost more than the
         * check is worth, and `safely()` in `ModerationService` turns a timeout into
         * UNCHECKED rather than into a failed poster.
         */
        timeout: 5_000,
        maxRetries: 1,
      });
  }

  async checkText(text: string): Promise<ModerationCheck> {
    return this.check({ model: this.model, input: text });
  }

  /**
   * Images are sent as a base64 data URL rather than by reference.
   *
   * At the moment this runs the poster has not been stored anywhere. Uploading it first in
   * order to hand the classifier a URL would mean a poster that might be blocked briefly
   * existing at a fetchable key — and a signed URL we then have to hope expires before
   * anybody follows it. Sending the bytes costs bandwidth and keeps the ordering honest.
   */
  async checkImage(png: Buffer): Promise<ModerationCheck> {
    const dataUrl = `data:image/png;base64,${png.toString('base64')}`;
    return this.check({
      model: this.model,
      input: [{ type: 'image_url', image_url: { url: dataUrl } }],
    });
  }

  private async check(
    params: Parameters<OpenAI['moderations']['create']>[0],
  ): Promise<ModerationCheck> {
    let response: ModerationCreateResponse;
    try {
      response = await this.client.moderations.create(params);
    } catch (cause) {
      // Never throws, per the port's contract. UNCHECKED rather than BLOCKED: see
      // `ModerationService.safely` for why this one rail fails open.
      this.logger.warn(`Moderation call failed: ${String(cause)}`);
      return UNCHECKED;
    }

    const result = response.results[0];
    if (!result) {
      // A 200 with no results is a shape the type permits and nothing documents. Recording
      // ALLOWED here would be the quiet kind of wrong this codebase keeps refusing.
      this.logger.warn('Moderation returned no results; recording UNCHECKED.');
      return UNCHECKED;
    }

    if (!result.flagged) return ALLOWED;

    const categories = flaggedCategories(result);
    this.logger.warn(`Moderation flagged: ${categories.join(', ')}`);
    return blocked(categories, 'MODERATION_FLAGGED');
  }
}
