import { Logger } from '@nestjs/common';
import OpenAI from 'openai';
import type { ImagesResponse } from 'openai/resources/images';
import sharp from 'sharp';
import type { ImagePrompt, ImageProvider, ImageResult } from './image-provider';

/**
 * `gpt-image-1`, for background artwork and nothing else (FR-POS-01b, §10, D21).
 *
 * ## Three things are different from the text adapter, and each is a cost-shape difference
 *
 * **Billing is per image.** `pricing.ts` has a separate table keyed by `(model, quality,
 * size)`, and `imageCount` on the result is what it multiplies. Not assumed to be one: a
 * provider that returned two for one request would otherwise be under-reported by half,
 * exactly when somebody was spending most.
 *
 * **There is no JSON to be strict about.** §10 rule 3 — "structured outputs only; a
 * malformed response is retried once, then falls back to a template" — does not obviously
 * apply to bytes, and skipping it quietly would be the easy mistake. The equivalent here is
 * validating that what came back **decodes as an image of the size that was asked for**,
 * one retry on a malformed body, then the caller falls back to the library. `sharp` does
 * the decoding, so "it is a PNG" is a fact rather than a content-type header's claim.
 *
 * **No image ever goes IN.** `ImagePrompt` has no image field, which is FR-POS-02 expressed
 * as a type: the person's face is never passed through a generative model in the default
 * path, there is no configuration that turns that off, and a port that cannot accept a
 * photo cannot be made to send one by a later edit. FR-POS-07's AI blend is the one
 * exception and is explicitly not in M6.
 *
 * ## Nothing here has been run
 *
 * There is no `OPENAI_API_KEY` on this machine. The shapes are the installed SDK's own
 * types, so they are not invented from memory, but a type is a claim about a shape rather
 * than a recording of one. `M6_VERIFY.md` records definition-of-done items 6 and 7's
 * measured half as **BLOCKED**, not partial.
 */
export class OpenAiImageProvider implements ImageProvider {
  readonly providerName = 'openai';
  readonly isLive = true;

  private readonly logger = new Logger(OpenAiImageProvider.name);
  private readonly client: OpenAI;

  constructor(
    apiKey: string,
    readonly modelName: string,
    private readonly quality: 'low' | 'medium' | 'high',
    /** Injectable so a test can drive the mapping without a network. */
    client?: OpenAI,
  ) {
    this.client =
      client ??
      new OpenAI({
        apiKey,
        /**
         * Longer than the text adapter's twenty seconds, because an image takes longer and
         * the user is watching a progress state rather than a composer — FR-POS-09 budgets
         * p95 under 15 seconds end to end, and a timeout below that would fire before the
         * target it is supposed to protect.
         */
        timeout: 45_000,
        /**
         * **Zero, not one.** The SDK retries 429s and 5xx by default, and every retry of an
         * image request is another billed image. §10's cost-control section exists for
         * exactly this shape: a retry loop with a bug in it is a bill rather than a log
         * line. The one retry this adapter does make is for a malformed BODY, where nothing
         * usable was produced, and it is made deliberately below.
         */
        maxRetries: 0,
      });
  }

  async generate(prompt: ImagePrompt): Promise<ImageResult> {
    const first = await this.attempt(prompt);
    if (first.outcome !== 'MALFORMED') return first.result;

    this.logger.warn('gpt-image-1 returned bytes that did not decode; retrying once');
    const second = await this.attempt(prompt);
    // Both attempts billed, so both are counted. A ledger that recorded only the successful
    // one would under-report precisely the calls that cost double.
    const imageCount = first.result.imageCount + second.result.imageCount;

    if (second.outcome !== 'MALFORMED') return { ...second.result, imageCount };

    return {
      outcome: 'ERROR',
      png: null,
      model: this.modelName,
      imageCount,
      size: prompt.size,
      quality: prompt.quality,
      errorMessage: 'image did not decode twice',
    };
  }

  private async attempt(
    prompt: ImagePrompt,
  ): Promise<{ outcome: 'DONE' | 'MALFORMED'; result: ImageResult }> {
    const base = { model: this.modelName, size: prompt.size, quality: prompt.quality };
    const done = (result: ImageResult) => ({ outcome: 'DONE' as const, result });

    let response: ImagesResponse;
    try {
      response = await this.client.images.generate({
        model: this.modelName,
        prompt: prompt.prompt,
        size: prompt.size as 'auto',
        quality: (prompt.quality || this.quality) as 'low',
        n: 1,
      });
    } catch (cause) {
      const timedOut =
        cause instanceof OpenAI.APIConnectionTimeoutError ||
        (cause instanceof Error && /timed?\s*out/i.test(cause.message));

      /**
       * A refusal is distinguished from an error, because the two mean different things to
       * the ledger and to whoever reads it later. OpenAI returns 400 with a
       * moderation-blocked code for a prompt its safety system declines.
       */
      const refused =
        cause instanceof OpenAI.APIError &&
        (cause.status === 400 || cause.status === 403) &&
        /moderation|safety|content_policy/i.test(String(cause.message));

      this.logger.warn(`gpt-image-1 failed: ${describe(cause)}`);
      return done({
        ...base,
        outcome: refused ? 'REFUSED' : timedOut ? 'TIMEOUT' : 'ERROR',
        png: null,
        // Nothing was produced. Whether a refusal BILLS is provider-specific and unknown
        // without a key, so this is zero and `M6_VERIFY.md` records that as an open
        // question rather than a measured fact.
        imageCount: 0,
        refusalCategory: refused ? 'content_policy' : null,
        errorMessage: describe(cause),
      });
    }

    const image = response.data?.[0];
    const b64 = image?.b64_json;
    if (!b64) {
      // A 200 with nothing in it. One image was requested and the request was accepted, so
      // it is counted as billed — assuming otherwise would under-report.
      return {
        outcome: 'MALFORMED',
        result: {
          ...base,
          outcome: 'ERROR',
          png: null,
          imageCount: 1,
          errorMessage: 'no image in the response',
        },
      };
    }

    const bytes = Buffer.from(b64, 'base64');

    /**
     * Our half of §10 rule 3, for bytes.
     *
     * Decoded rather than trusted. A truncated base64 payload is still a valid string and
     * would reach `composePoster` as a background, where `sharp` would throw inside the
     * compositor — turning a provider problem into a pipeline crash two layers away from
     * its cause.
     */
    try {
      const meta = await sharp(bytes).metadata();
      if (!meta.width || !meta.height) throw new Error('no dimensions');
    } catch (cause) {
      return {
        outcome: 'MALFORMED',
        result: {
          ...base,
          outcome: 'ERROR',
          png: null,
          imageCount: 1,
          errorMessage: `returned bytes did not decode: ${describe(cause)}`,
        },
      };
    }

    return done({ ...base, outcome: 'OK', png: bytes, imageCount: 1 });
  }
}

function describe(cause: unknown): string {
  if (cause instanceof Error) return `${cause.name}: ${cause.message}`;
  return String(cause);
}
