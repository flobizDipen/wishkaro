import type { EventType, PosterFormat } from '@prisma/client';
import { FORMATS } from './theme-library';

/**
 * FR-POS-03's cache key, composed in exactly ONE place.
 *
 * > "Generated background artwork is **cached and reused**. Cache key = (theme, event type,
 * > milestone, palette, aspect ratio). A popular theme is generated once and served
 * > thousands of times, driving marginal cost toward zero."
 *
 * §8's indexing note adds the constraint that makes it work: "`theme_cache(theme_key)` must
 * be unique or caching silently fails." A key composed in two places is a uniqueness
 * constraint protecting two different strings — the lookup misses, a second row is inserted,
 * and the hit rate the whole economic thesis rests on quietly goes to zero while every graph
 * looks fine. So: one function, one separator, and a shared table of cases
 * (`docs/fixtures/poster-themes.json`, `key_cases`) that both languages are held to.
 *
 * ## The two choices in here that are not obvious
 *
 * **`-` for an absent milestone, not an empty string and not `undefined`.** FR-EVT-02 makes
 * "year unknown" a first-class case, so a birthday with no known year genuinely has no
 * milestone, and that is the COMMON case rather than an edge. An empty string would make
 * `a||b` and `a| |b` collide on a trimmed input; interpolating `undefined` would put the
 * word in the key and make it depend on a language's stringification rules.
 *
 * **The aspect RATIO, not the format name.** FR-POS-03 says "aspect ratio" and means it:
 * artwork generated for 9:16 is reusable at any pixel size with that shape, so keying on
 * `STATUS` rather than `9:16` would make a future 720×1280 variant a cache miss against
 * artwork that fits it perfectly. The key is about the picture's proportions, not about our
 * enum.
 */

/** Separator chosen because no component can contain it: ids, enums, palettes and the */
/** milestone labels in `derived.ts` are all alphanumerics, spaces, dashes and colons. */
const SEP = '|';

/** What an absent milestone looks like in the key. Never '' and never 'undefined'. */
export const NO_MILESTONE = '-';

export interface ThemeKeyParts {
  themeId: string;
  eventType: EventType;
  /** `milestoneLabelFor()`'s output: "25th Silver", "12 years", or null. */
  milestone: string | null;
  palette: string;
  /** "9:16" — the ratio, not the format name. */
  aspectRatio: string;
}

export function themeKeyFor(parts: ThemeKeyParts): string {
  return [
    parts.themeId,
    parts.eventType,
    parts.milestone ?? NO_MILESTONE,
    parts.palette,
    parts.aspectRatio,
  ].join(SEP);
}

/** The ratio string for a format, so callers never hand-write "9:16". */
export function aspectRatioOf(format: PosterFormat): string {
  return FORMATS[format].ratio;
}
