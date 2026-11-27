import type { PosterFormat } from '@prisma/client';
import sharp from 'sharp';
import { FORMATS, THEMES, type PosterTheme } from './theme-library';
import { renderTextLayer, type CompositeRequest } from './compositor';
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

  it('does not shrink a headline that already fits', async () => {
    // The fix must not quietly make every poster smaller. A short name keeps the
    // layout's own size.
    const fitted = fitText('Happy Birthday, Asha', { maxWidth: 920, nominalSize: 78, maxLines: 2 });
    expect(fitted.fontSize).toBe(78);
    expect(fitted.lines).toHaveLength(1);
  });

  it('wraps before it shrinks', async () => {
    // Two lines at 78px reads better than one at 38px on a phone held at arm's length.
    const fitted = fitText('Remembering Rohan Deshpande', {
      maxWidth: 920,
      nominalSize: 101,
      maxLines: 2,
    });
    expect(fitted.lines.length).toBe(2);
    expect(fitted.fontSize).toBe(101);
  });

  it('shrinks a single word that cannot be broken', async () => {
    const fitted = fitText('Venkataramanujacharya', {
      maxWidth: 400,
      nominalSize: 101,
      maxLines: 2,
    });
    expect(fitted.lines).toHaveLength(1);
    expect(fitted.fontSize).toBeLessThan(101);
  });

  it('never returns a font size above nominal', () => {
    for (const headline of ['A', 'Hi', ...LONG_HEADLINES]) {
      const fitted = fitText(headline, { maxWidth: 920, nominalSize: 78, maxLines: 2 });
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
