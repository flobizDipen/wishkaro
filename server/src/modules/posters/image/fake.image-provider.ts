import { Injectable } from '@nestjs/common';
import sharp from 'sharp';
import type { ImageOutcome, ImagePrompt, ImageProvider, ImageResult } from './image-provider';

/**
 * A provider that never calls anything, and never pretends it did.
 *
 * The committed default, for the same two reasons `FakeLlmProvider` is:
 *
 *  1. **CI and local development with no key.** Every mechanism around the call — the
 *     `Generation` row, the ledger, the rate limit, the cache, the degradation path, the
 *     error shapes — is exercised against this.
 *  2. **Forcing an outcome on demand**, so a refusal and a provider error can be
 *     *demonstrated* rather than argued.
 *
 * What it is NOT for: anything about how a poster LOOKS. `isLive` is false, so a
 * `Generation` row records that no model was involved, and `pricing.ts` prices it at zero —
 * a ₹0 row rather than no row, because a ledger with gaps cannot be reasoned about and "how
 * many posters were served without a model" is itself worth being able to ask.
 *
 * **It is also not art direction.** TBD-5 is open and the backgrounds below are flat
 * gradients that look like what they are. docs/M6_PLAN.md §2.1.
 */
@Injectable()
export class FakeImageProvider implements ImageProvider {
  readonly providerName = 'fake';
  readonly modelName = 'fake-image';
  readonly isLive = false;

  /**
   * Forces the next call's outcome. Set by a test, so that "a refusal degrades to the
   * library" is a demonstration rather than an argument — definition-of-done item 8's
   * cousin.
   */
  nextOutcome: ImageOutcome = 'OK';

  async generate(prompt: ImagePrompt): Promise<ImageResult> {
    const base = {
      model: this.modelName,
      size: prompt.size,
      quality: prompt.quality,
    };

    switch (this.nextOutcome) {
      case 'REFUSED':
        // An image the model declined to produce still consumed a request. Whether it
        // BILLED is provider-specific and unknown without a key, so the count is zero and
        // `M6_VERIFY.md` records that as an open question rather than a measured fact.
        return {
          ...base,
          outcome: 'REFUSED',
          png: null,
          imageCount: 0,
          refusalCategory: 'simulated',
        };
      case 'ERROR':
        return {
          ...base,
          outcome: 'ERROR',
          png: null,
          imageCount: 0,
          errorMessage: 'simulated provider error',
        };
      case 'TIMEOUT':
        return {
          ...base,
          outcome: 'TIMEOUT',
          png: null,
          imageCount: 0,
          errorMessage: 'simulated timeout',
        };
      case 'OK': {
        const [width, height] = parseSize(prompt.size);
        return {
          ...base,
          outcome: 'OK',
          png: await placeholder(width, height),
          imageCount: 1,
        };
      }
    }
  }
}

function parseSize(size: string): [number, number] {
  const [w, h] = size.split('x').map(Number);
  // Falls back to the portrait default rather than throwing: a provider is contracted never
  // to throw, and a malformed size is a caller's bug that should surface as a wrong-looking
  // poster in a test, not as the one path that skips the ledger.
  return [Number.isFinite(w) ? w : 1024, Number.isFinite(h) ? h : 1536];
}

/**
 * A flat diagonal gradient. Deterministic for a given size, so a test that renders twice
 * gets the same bytes and a cache test can tell a hit from a miss by comparing them.
 */
async function placeholder(width: number, height: number): Promise<Buffer> {
  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}">` +
    `<defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">` +
    `<stop offset="0%" stop-color="#6B7A8F"/><stop offset="100%" stop-color="#2E3440"/>` +
    `</linearGradient></defs>` +
    `<rect width="${width}" height="${height}" fill="url(#g)"/></svg>`;
  return sharp(Buffer.from(svg)).png().toBuffer();
}
