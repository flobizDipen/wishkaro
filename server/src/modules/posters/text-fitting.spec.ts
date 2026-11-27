import type { PosterFormat } from '@prisma/client';
import sharp from 'sharp';
import { FORMATS, THEMES, type PosterTheme } from './theme-library';
import { renderTextLayer, type CompositeRequest } from './compositor';
import { FONT_STACK } from './compositor';
import { estimateWidth, fitText } from './text-fitting';

/**
 * "Are the words inside the picture?"
 *
 * The compositor suite was green while the headline ran off both edges of the canvas —
 * every test asserted geometry (photo placement, canvas size, band overlap) and none
 * asserted that the text was visible. The bug was found by opening a PNG.
 *
 * So this suite does not trust `estimateWidth`. It renders the text layer and measures the
 * **actual drawn ink** with `sharp.trim()`, which reports the bounding box of everything
 * that is not the transparent background. If the estimate is wrong, these fail.
 */

const THEME: PosterTheme = THEMES[0];

function request(headline: string, over: Partial<CompositeRequest> = {}): CompositeRequest {
  return {
    background: Buffer.alloc(0),
    photo: null,
    headline,
    subline: null,
    theme: THEME,
    format: 'STATUS',
    attributed: true,
    ...over,
  };
}

/**
 * The bounding box of the drawn ink, in canvas coordinates.
 *
 * `trim` returns the offsets it removed in `info.trimOffsetLeft` / `trimOffsetTop` (both
 * negative) and the remaining size, which together give the box.
 */
async function inkBox(png: Buffer) {
  const { data, info } = await sharp(png)
    .trim({ threshold: 1 })
    .toBuffer({ resolveWithObject: true });
  void data;
  const left = -(info.trimOffsetLeft ?? 0);
  const top = -(info.trimOffsetTop ?? 0);
  return { left, top, right: left + info.width, bottom: top + info.height };
}

/**
 * Names chosen to be long rather than exotic, and all invented
 * (`MILESTONE_CHECKLIST.md` §F). The first is the one that produced the bug.
 */
const LONG_HEADLINES = [
  'Remembering Rohan Deshpande',
  'Remembering Venkataraman Subramanian',
  'Happy Birthday, Lakshminarayanan',
  'Happy Anniversary, Priyadarshini Raghunathan',
  'जन्मदिन मुबारक, प्रियदर्शिनी',
  'Happy Birthday, Wm',
];

describe('the headline stays inside the canvas — measured, not estimated', () => {
  const formats: PosterFormat[] = ['STATUS', 'SQUARE', 'LINK'];

  for (const format of formats) {
    for (const headline of LONG_HEADLINES) {
      for (const hasPhoto of [true, false]) {
        it(`${format}, photo=${hasPhoto}: "${headline.slice(0, 28)}"`, async () => {
          const png = await renderTextLayer(request(headline, { format }), hasPhoto);
          const box = await inkBox(png);
          const spec = FORMATS[format];

          // Inside the canvas, with a small margin. A headline touching the bleed is one
          // WhatsApp will crop on some devices.
          expect(box.left).toBeGreaterThanOrEqual(8);
          expect(box.right).toBeLessThanOrEqual(spec.width - 8);
          expect(box.top).toBeGreaterThanOrEqual(0);
          expect(box.bottom).toBeLessThanOrEqual(spec.height);
        });
      }
    }
  }

  it('does not shrink a headline that comfortably fits', async () => {
    /**
     * The fix must not quietly make every poster smaller.
     *
     * **The headline here is short on purpose, and the first version's was not.** It said
     * "Happy Birthday, Asha" and asserted exactly 78 — which held on macOS and failed on
     * CI at 76, because on DejaVu that string is a couple of pixels over 920 and shrinking
     * it is the CORRECT answer. The test's premise was false on the font the server
     * actually uses, which is the same mistake as §0.6 one level up: a number pinned to
     * whichever font the machine happens to have.
     *
     * A string with room to spare on any plausible font keeps the assertion exact and
     * makes the premise true everywhere.
     */
    const fitted = await fitText('Happy Birthday', {
      maxWidth: 920,
      nominalSize: 78,
      maxLines: 2,
      fontStack: FONT_STACK,
    });
    expect(fitted.fontSize).toBe(78);
    expect(fitted.lines).toHaveLength(1);
  });

  it('wraps before it shrinks', async () => {
    /**
     * The property, not a pixel count: two lines at a readable size beat one line at a
     * tiny one on a phone held at arm's length.
     *
     * Asserted by comparing against the same text with `maxLines: 1`, so what is checked
     * is that WRAPPING BUYS A BIGGER FONT — which is true on every font — rather than that
     * the font came out at 101, which was true only on this laptop.
     */
    const options = { maxWidth: 920, nominalSize: 101, fontStack: FONT_STACK };
    const wrapped = await fitText('Remembering Rohan Deshpande', { ...options, maxLines: 2 });
    const oneLine = await fitText('Remembering Rohan Deshpande', { ...options, maxLines: 1 });

    expect(wrapped.lines.length).toBe(2);
    expect(oneLine.lines.length).toBe(1);
    expect(wrapped.fontSize).toBeGreaterThan(oneLine.fontSize);
    // And it is a real difference, not a rounding one — the whole point of wrapping.
    expect(wrapped.fontSize).toBeGreaterThan(oneLine.fontSize * 1.3);
  });

  it('shrinks a single word that cannot be broken', async () => {
    const fitted = await fitText('Venkataramanujacharya', {
      maxWidth: 400,
      nominalSize: 101,
      maxLines: 2,
      fontStack: FONT_STACK,
    });
    expect(fitted.lines).toHaveLength(1);
    expect(fitted.fontSize).toBeLessThan(101);
  });

  it('never returns a font size above nominal', async () => {
    for (const headline of ['A', 'Hi', ...LONG_HEADLINES]) {
      const fitted = await fitText(headline, {
        maxWidth: 920,
        nominalSize: 78,
        maxLines: 2,
        fontStack: FONT_STACK,
      });
      expect(fitted.fontSize).toBeLessThanOrEqual(78);
      expect(fitted.fontSize).toBeGreaterThan(0);
    }
  });

  it('estimates width monotonically in the font size', () => {
    expect(estimateWidth('Asha', 100)).toBeGreaterThan(estimateWidth('Asha', 50));
  });
});

describe('the subline stays inside the canvas too', () => {
  it('shrinks a long milestone rather than running off the edge', async () => {
    const png = await renderTextLayer(
      request('Happy Anniversary, Asha', {
        subline: '25th Silver anniversary of a very long kind',
        format: 'LINK',
      }),
      true,
    );
    const box = await inkBox(png);
    expect(box.right).toBeLessThanOrEqual(FORMATS.LINK.width - 8);
  });
});
