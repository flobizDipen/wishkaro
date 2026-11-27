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
 * ## Why the width is estimated rather than measured
 *
 * `sharp` renders SVG through librsvg and gives no text-metrics API, and the alternative —
 * shipping a font parser to measure advances — is a dependency and a font file in a public
 * repository for one layout decision. So this estimates, and then **the spec measures the
 * rendered ink with `sharp.trim()` and asserts it is inside the canvas**. The estimate can
 * be wrong; the assertion is on the actual pixels, which is the only claim worth making.
 *
 * The estimate is deliberately biased to OVER-state width. Too small is a poster that reads
 * slightly quietly; too large is one with the user's own name cut in half.
 */

/**
 * Advance widths as a fraction of the font size, for a bold humanist sans.
 *
 * Buckets rather than a table of every glyph: the error that matters is systematic, not
 * per-character, and five buckets get within a few percent on real names. Devanagari is
 * treated as wide because its conjuncts and matras are, and because under-estimating a
 * Hindi headline is the case nobody here can eyeball.
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

/** Estimated width of a string at a font size, in pixels. */
export function estimateWidth(text: string, fontSize: number): number {
  let em = 0;
  for (const ch of text) em += advanceRatio(ch);
  return em * fontSize;
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
export function fitText(text: string, options: FitOptions): FittedText {
  const { maxWidth, nominalSize, maxLines } = options;
  const minSize = options.minSize ?? 28;
  const trimmed = text.trim();
  if (trimmed === '') return { lines: [''], fontSize: nominalSize };

  for (let lineCount = 1; lineCount <= maxLines; lineCount++) {
    const lines = wrapInto(trimmed, lineCount, nominalSize, maxWidth);
    if (!lines) continue;
    const widest = Math.max(...lines.map((l) => estimateWidth(l, nominalSize)));
    if (widest <= maxWidth) return { lines, fontSize: nominalSize };
  }

  // Nothing fits at the nominal size. Wrap as far as allowed, then shrink to the widest
  // line. `floor` rather than round, because rounding up is how a fit becomes a crop.
  const lines = wrapInto(trimmed, maxLines, nominalSize, maxWidth) ?? [trimmed];
  const widestEm = Math.max(...lines.map((l) => estimateWidth(l, 1)));
  const needed = Math.floor(maxWidth / widestEm);

  if (needed >= minSize) {
    return { lines, fontSize: Math.min(needed, nominalSize) };
  }

  // Smaller than is worth reading. Hold the floor and shorten instead, so the result is
  // always inside the canvas.
  const fontSize = Math.min(minSize, nominalSize);
  return { lines: lines.map((line) => truncateTo(line, maxWidth, fontSize)), fontSize };
}

/** Shorten with an ellipsis until the estimate fits. */
function truncateTo(line: string, maxWidth: number, fontSize: number): string {
  if (estimateWidth(line, fontSize) <= maxWidth) return line;

  const chars = [...line];
  while (chars.length > 1) {
    chars.pop();
    const candidate = `${chars.join('').trimEnd()}\u2026`;
    if (estimateWidth(candidate, fontSize) <= maxWidth) return candidate;
  }
  return '\u2026';
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
