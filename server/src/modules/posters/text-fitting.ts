import sharp from 'sharp';

/**
 * Making the headline fit the canvas.
 *
 * ## Why this file exists
 *
 * `Layout.textWidth` was declared in all three layouts and **never read**. The headline was
 * drawn at a fixed `font-size` with `text-anchor="middle"`, so a name longer than the
 * canvas simply ran off both edges: "Remembering Rohan Deshpande" at 101px rendered as
 * "mbering Rohan Deshp". The poster was unsendable and the suite was green, because every
 * compositor test asserted geometry — photo placement, canvas size, band overlap — and
 * nothing asserted that the words were inside the picture. It was found by looking at a
 * PNG, which is what `docs/BUILD_PROMPT_M6.md` means by "verify by doing, not by reading".
 *
 * Indian names are long. "Remembering Venkataraman Subramanian" is not an edge case, and
 * the no-photo path (FR-POS-06, OD-11) sets the headline 30% LARGER, so the format most
 * likely to overflow is the one most users will see.
 *
 * ## It MEASURES. The first version estimated, and CI caught it on the wrong platform
 *
 * `sharp` has no text-metrics API, so the first version estimated advance widths from a
 * table of per-character ratios. It passed on macOS and **failed 11 of 42 cases on CI**,
 * because ubuntu-latest resolves the font stack to DejaVu Sans where macOS resolves it to
 * something narrower. The production target is `node:22-alpine` with fonts installed —
 * Linux — so the estimate was tuned against the one platform the server never runs on, and
 * every poster with a long name would still have been clipped in production while the
 * developer's machine said it was fine.
 *
 * That is not a thing to fix by widening a safety factor until CI goes quiet. The numbers
 * would still be a guess about a font nobody has pinned, and the next base-image change
 * would move them again.
 *
 * So the width comes from `sharp` itself: render the text once at a reference size on a
 * transparent canvas, `trim()` to the drawn ink, and divide. Text width scales linearly
 * with font size, so one render gives the em-width and everything after it is arithmetic.
 * It is exact on whatever font is actually installed, which is the only question that
 * matters.
 *
 * **Cost.** One render per distinct string, memoised. A measure canvas is small and
 * monochrome; against a ~150ms poster the two measurements a headline and a subline need
 * are not the expensive part, and `composePoster` already awaits several `sharp` pipelines.
 */

/**
 * Advance widths as a fraction of the font size, for a bold humanist sans.
 *
 * **Kept only to choose where to break a line**, never to decide a font size. A bad guess
 * here moves a word between lines; it cannot cause an overflow, because the size is
 * computed afterwards from a real measurement of the lines this produced.
 */
function advanceRatio(ch: string): number {
  if (ch === ' ') return 0.28;
  if (/[iljtfI.,:;'`!|()[\]]/.test(ch)) return 0.33;
  if (/[mwMW@%]/.test(ch)) return 0.95;
  if (/[A-Z0-9]/.test(ch)) return 0.68;
  // Devanagari and anything else non-Latin.
  if (ch.codePointAt(0)! > 0x0370) return 0.78;
  return 0.56;
}

/** Rough width, for choosing a line break only. See [advanceRatio]. */
export function estimateWidth(text: string, fontSize: number): number {
  let em = 0;
  for (const ch of text) em += advanceRatio(ch);
  return em * fontSize;
}

/**
 * The size the text is measured at. Large enough that hinting and rounding are a fraction
 * of a percent, small enough that the measure canvas is cheap.
 */
const REF_SIZE = 200;

/**
 * Memoised, because the same name is measured for the headline of every format and every
 * regeneration. Bounded so a server that has seen a million names does not hold them all —
 * these are PEOPLE'S NAMES, and an unbounded cache of them is both a leak and a pile of
 * personal data sitting in memory for no reason (§11).
 */
const MEASURE_CACHE = new Map<string, number>();
const MEASURE_CACHE_MAX = 500;

/**
 * The width of one line at font size 1, measured by rendering it.
 *
 * Returns the ESTIMATE if the render fails or the string draws no ink at all — a measuring
 * step that can throw must not be the thing that stops a poster being made, and the
 * estimate is what the first version shipped, so falling back to it is no worse than that.
 */
export async function measureEm(text: string, fontStack: string): Promise<number> {
  const key = `${fontStack}\u0000${text}`;
  const cached = MEASURE_CACHE.get(key);
  if (cached !== undefined) return cached;

  let em: number;
  try {
    // Generous canvas: wide enough that nothing is clipped before it is measured, which
    // would make an over-long line measure as exactly the canvas width and "fit".
    const width = Math.ceil(estimateWidth(text, REF_SIZE) * 2 + REF_SIZE * 4);
    const height = REF_SIZE * 3;
    const svg =
      `<svg xmlns="http://www.w3.org/2000/svg" width="${width}" height="${height}">` +
      `<text x="${REF_SIZE}" y="${REF_SIZE * 2}" font-family="${fontStack}" ` +
      `font-size="${REF_SIZE}" font-weight="700" fill="#000">${escapeXml(text)}</text></svg>`;

    const { info } = await sharp(Buffer.from(svg))
      .png()
      .trim({ threshold: 1 })
      .toBuffer({ resolveWithObject: true });

    em = info.width / REF_SIZE;
    // A string that draws nothing — all spaces, or a glyph the font lacks entirely.
    if (!Number.isFinite(em) || em <= 0) em = estimateWidth(text, 1);
  } catch {
    em = estimateWidth(text, 1);
  }

  if (MEASURE_CACHE.size >= MEASURE_CACHE_MAX) MEASURE_CACHE.clear();
  MEASURE_CACHE.set(key, em);
  return em;
}

/** The same escaping `compositor.ts` uses; duplicated rather than imported to avoid a cycle. */
function escapeXml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');
}

export interface FittedText {
  lines: string[];
  fontSize: number;
}

export interface FitOptions {
  maxWidth: number;
  /** The size the layout wants. Never exceeded — fitting only ever shrinks. */
  nominalSize: number;
  maxLines: number;
  /**
   * The font the text will actually be drawn in — the same stack `compositor.ts` puts in
   * the SVG. Passed in rather than imported, because measuring in one font and drawing in
   * another is the whole bug this file now exists to prevent.
   */
  fontStack: string;
  /**
   * The smallest the text may become, in pixels.
   *
   * **The floor truncates; it never lets text overflow.** The first version of this let a
   * line stay slightly wide once it hit the floor, on the reasoning that "a headline at
   * 30% is not a headline" — and the pixel-measuring spec immediately failed on
   * "Happy Anniversary, Priyadarshini Raghunathan", which is a name, not a pathological
   * input. Running off the canvas is strictly worse than being small: one is a poster
   * somebody can send and the other is the bug this file exists for. So below the floor
   * the text is shortened with an ellipsis, which is visibly imperfect and still inside
   * the picture.
   */
  minSize?: number;
}

/**
 * Fit text to a box by wrapping onto whole words first, then shrinking.
 *
 * Wrapping before shrinking, deliberately: two lines at 78px reads better than one line at
 * 38px, and a poster is read at arm's length on a phone. Shrinking is the fallback for a
 * single word that cannot be broken — which is most Indian surnames, and the case that
 * produced the bug.
 */
export async function fitText(text: string, options: FitOptions): Promise<FittedText> {
  const { maxWidth, nominalSize, maxLines, fontStack } = options;
  const minSize = options.minSize ?? 28;
  const trimmed = text.trim();
  if (trimmed === '') return { lines: [''], fontSize: nominalSize };

  for (let lineCount = 1; lineCount <= maxLines; lineCount++) {
    const lines = wrapInto(trimmed, lineCount, nominalSize, maxWidth);
    if (!lines) continue;

    // MEASURED, not estimated. The break point above is a guess; this is not.
    const widestEm = await widestEmOf(lines, fontStack);
    if (widestEm * nominalSize <= maxWidth) return { lines, fontSize: nominalSize };
  }

  // Nothing fits at the nominal size. Wrap as far as allowed, then take the size straight
  // from the measurement. `floor`, because rounding up is how a fit becomes a crop.
  const lines = wrapInto(trimmed, maxLines, nominalSize, maxWidth) ?? [trimmed];
  const widestEm = await widestEmOf(lines, fontStack);
  const needed = Math.floor(maxWidth / widestEm);

  if (needed >= minSize) {
    return { lines, fontSize: Math.min(needed, nominalSize) };
  }

  // Smaller than is worth reading. Hold the floor and shorten instead, so the result is
  // always inside the canvas.
  const fontSize = Math.min(minSize, nominalSize);
  const shortened: string[] = [];
  for (const line of lines) shortened.push(await truncateTo(line, maxWidth, fontSize, fontStack));
  return { lines: shortened, fontSize };
}

async function widestEmOf(lines: readonly string[], fontStack: string): Promise<number> {
  let widest = 0;
  for (const line of lines) widest = Math.max(widest, await measureEm(line, fontStack));
  // A line of pure whitespace measures zero ink; never divide by it.
  return widest > 0 ? widest : 1;
}

/**
 * Shorten with an ellipsis until it MEASURES as fitting.
 *
 * Drops characters in proportion to how far over it is rather than one at a time, so a
 * wildly long string costs a handful of measurements instead of hundreds.
 */
async function truncateTo(
  line: string,
  maxWidth: number,
  fontSize: number,
  fontStack: string,
): Promise<string> {
  if ((await measureEm(line, fontStack)) * fontSize <= maxWidth) return line;

  let chars = [...line];
  for (let attempt = 0; attempt < 12 && chars.length > 1; attempt++) {
    const candidate = `${chars.join('').trimEnd()}\u2026`;
    const width = (await measureEm(candidate, fontStack)) * fontSize;
    if (width <= maxWidth) return candidate;
    const keep = Math.max(1, Math.floor(chars.length * (maxWidth / width)) - 1);
    chars = chars.slice(0, keep);
  }
  return `${chars.join('').trimEnd()}\u2026`;
}

/**
 * Split into exactly `lineCount` lines on word boundaries, balancing them.
 *
 * Returns null when there are not enough words — the caller then tries fewer lines, or
 * falls through to shrinking. Greedy rather than optimal: a two-line headline has one
 * break, and "Remembering / Rohan Deshpande" is what greedy gives.
 */
function wrapInto(
  text: string,
  lineCount: number,
  fontSize: number,
  maxWidth: number,
): string[] | null {
  if (lineCount === 1) return [text];

  const words = text.split(/\s+/);
  if (words.length < lineCount) return null;

  const lines: string[] = [];
  let current = '';
  for (const word of words) {
    const candidate = current === '' ? word : `${current} ${word}`;
    // The last line takes whatever is left, so a long final word does not start a line
    // that cannot exist.
    const isLastLine = lines.length === lineCount - 1;
    if (!isLastLine && current !== '' && estimateWidth(candidate, fontSize) > maxWidth) {
      lines.push(current);
      current = word;
    } else {
      current = candidate;
    }
  }
  lines.push(current);

  return lines.length === lineCount ? lines : null;
}
