import sharp, { type OverlayOptions } from 'sharp';
import type { PosterFormat } from '@prisma/client';
import { FORMATS, type PosterTheme } from './theme-library';
import { fitText } from './text-fitting';

/**
 * FR-POS-01(c): "the server composites the person's **unmodified** photo, the copy, and
 * frame elements over that background using `sharp`."
 *
 * ## What "unmodified" means here, stated because the word is doing real work
 *
 * FR-POS-02 is the requirement this serves, and it says why: "The person's face is **never**
 * passed through a generative model in the default path. This guarantees likeness fidelity
 * and avoids personality-rights exposure." It is a likeness and personality-rights rule, not
 * an aesthetic one.
 *
 * So "unmodified" means **no generative step, no filter, no recolour, and no distortion of
 * the face's proportions**. It cannot mean "not resampled at all": a poster is a fixed
 * canvas, a photo arrives at whatever size a phone's camera produced, and something has to
 * put one inside the other.
 *
 * Three rules follow, and they are what the tests assert:
 *
 *  1. **Fit by covering and cropping, never by stretching.** `fit: 'cover'` preserves the
 *     aspect ratio and trims the overflow. Stretching to fill a box would make a face wider
 *     or narrower than the person's, which is a likeness change — the exact thing FR-POS-02
 *     exists to prevent, arrived at by arithmetic instead of by a model.
 *  2. **Nothing is drawn on top of the photo.** The copy and the frame go above and below
 *     it, never over it. A caption band across somebody's chin is the sort of thing that
 *     turns into a complaint.
 *  3. **A photo that already fits its box is passed through byte-for-byte**, which is what
 *     makes rule 1 checkable rather than asserted. `compositor.spec.ts` builds a photo at
 *     the exact box size and compares the composited region pixel by pixel.
 *
 * `docs/M6_PLAN.md` §11 is why the test fixtures are generated colour patterns rather than
 * photographs: this repository is public, and a known pixel pattern makes "unmodified" an
 * exact comparison where a photograph would only make it an eyeball.
 *
 * ## Text
 *
 * Rendered as SVG and composited, which is how `sharp` draws text at all — it has no text
 * API of its own. That puts a hard dependency on **fonts being installed in the runtime**,
 * which `node:22-alpine` does not have by default; see `server/Dockerfile`, where they are
 * now installed, and `docs/M6_VERIFY.md` for how that was checked rather than assumed.
 */

/** Everything the compositor needs. Assembled by the caller; this module does no I/O. */
export interface CompositeRequest {
  /** The background artwork: from the theme cache, the library, or a live generation. */
  background: Buffer;
  /**
   * The person's photo, or null.
   *
   * **Null is a first-class case, not an error** (FR-POS-06, OD-11): "No photo available →
   * typographic/illustrated template, still high quality." Most people in a 600-person
   * contact list have no photo, so the no-photo path is not an edge — for many users it is
   * the only path.
   */
  photo: Buffer | null;
  /** The headline. One line; the caller has already decided the words. */
  headline: string;
  /** The second line, or null. A year count, a milestone, or nothing. */
  subline: string | null;
  theme: PosterTheme;
  format: PosterFormat;
  /**
   * FR-POS-08 / OD-9. **Defaults to true at every call site**, and M7 is what can make it
   * false. M6 builds the switch and one function that answers "is this user premium" with
   * `false`, so that the attribution cannot be removed by accident before there is anything
   * to pay for.
   */
  attributed: boolean;
}

export interface CompositeResult {
  png: Buffer;
  width: number;
  height: number;
  /** Where the photo was placed, or null when there was none. The spec reads this. */
  photoBox: PhotoBox | null;
}

export interface PhotoBox {
  left: number;
  top: number;
  width: number;
  height: number;
}

/**
 * The layout per format (FR-POS-05).
 *
 * A table rather than arithmetic, because these are design values and TBD-5 is what will
 * replace them. When the art direction arrives, the change is this table and the palettes —
 * not the pipeline. `docs/M6_PLAN.md` §2.1.
 *
 * The photo box never overlaps the text bands, by construction: `photo.top + photo.height`
 * is always less than or equal to `headlineY - headlineSize`. A test asserts it rather than
 * leaving it to whoever edits the numbers next.
 */
interface Layout {
  photo: PhotoBox;
  headlineY: number;
  headlineSize: number;
  sublineY: number;
  sublineSize: number;
  textLeft: number;
  textWidth: number;
  /** Centre for STATUS and SQUARE; left for LINK, whose photo sits beside the text. */
  textAnchor: 'middle' | 'start';
  attributionY: number;
}

const LAYOUTS: Record<PosterFormat, Layout> = {
  // 1080×1920 — the WhatsApp status size, and the default.
  STATUS: {
    photo: { left: 160, top: 360, width: 760, height: 760 },
    headlineY: 1320,
    headlineSize: 78,
    sublineY: 1440,
    sublineSize: 44,
    textLeft: 540,
    textWidth: 920,
    textAnchor: 'middle',
    attributionY: 1856,
  },
  // 1080×1080 — square.
  SQUARE: {
    photo: { left: 290, top: 110, width: 500, height: 500 },
    headlineY: 760,
    headlineSize: 68,
    sublineY: 860,
    sublineSize: 40,
    textLeft: 540,
    textWidth: 920,
    textAnchor: 'middle',
    attributionY: 1020,
  },
  // 1200×630 — link preview. The only landscape format, so the photo sits BESIDE the text
  // rather than above it; a 380px-tall photo over text in a 630px canvas leaves room for
  // one line.
  LINK: {
    photo: { left: 80, top: 125, width: 380, height: 380 },
    headlineY: 290,
    headlineSize: 58,
    sublineY: 370,
    sublineSize: 34,
    textLeft: 530,
    textWidth: 590,
    textAnchor: 'start',
    attributionY: 580,
  },
};

export function layoutFor(format: PosterFormat): Layout {
  return LAYOUTS[format];
}

/** FR-POS-08's "subtle" attribution. Small, low-contrast, never across the photo. */
const ATTRIBUTION = 'made with WishKaro';

/**
 * The gutter the typographic template keeps from the canvas edge.
 *
 * Wider than it looks necessary because WhatsApp re-encodes a status image and some
 * devices crop a few pixels of bleed; text that only just fits is text that is only just
 * readable after somebody else's pipeline has had it.
 */
const CANVAS_MARGIN = 80;

/**
 * Compose one poster.
 *
 * Order matters and is the design: background, then photo, then text. The text layer is one
 * SVG composited last, so nothing can be drawn over the words — and because the photo goes
 * on before it, a layout that accidentally overlapped the two would put text ON the face
 * rather than under it. `assertNoOverlap` is why that cannot happen quietly.
 */
export async function composePoster(request: CompositeRequest): Promise<CompositeResult> {
  const spec = FORMATS[request.format];
  const layout = LAYOUTS[request.format];
  const hasPhoto = request.photo !== null;

  const layers: OverlayOptions[] = [];
  let photoBox: PhotoBox | null = null;

  if (request.photo) {
    /**
     * `fit: 'cover'` — preserve the aspect ratio and crop the overflow.
     *
     * NOT `fill`, which would stretch a face to the box's proportions. That is a likeness
     * change produced by arithmetic rather than by a model, and FR-POS-02's rule is about
     * likeness, not about which component did it.
     *
     * `position: 'top'` rather than `centre`: when a tall phone photo is cropped to a square
     * the head is near the top, and centring takes the top of the head off. This is the one
     * place in the file where a portrait assumption is made, and it is made deliberately.
     */
    const fitted = await sharp(request.photo)
      .resize(layout.photo.width, layout.photo.height, { fit: 'cover', position: 'top' })
      .png()
      .toBuffer();

    layers.push({ input: fitted, left: layout.photo.left, top: layout.photo.top });
    photoBox = { ...layout.photo };
  }

  layers.push({
    input: Buffer.from(await textLayerSvg(request, layout, spec, hasPhoto)),
    left: 0,
    top: 0,
  });

  const png = await sharp(request.background)
    // The background may be any size — a cached 9:16 render, a library asset, or whatever a
    // model returned. `cover` again, for the same reason: a stretched background is merely
    // ugly, but having one rule for both layers is how the rule survives an edit.
    .resize(spec.width, spec.height, { fit: 'cover', position: 'centre' })
    .composite(layers)
    .png()
    .toBuffer();

  return { png, width: spec.width, height: spec.height, photoBox };
}

/**
 * The text and frame layer, as one SVG the size of the whole canvas.
 *
 * One layer rather than one per string, so the z-order is fixed by the document rather than
 * by the order of an array somebody can reorder.
 *
 * **Every string is XML-escaped.** A person's name reaches this function, names contain
 * ampersands and apostrophes, and an unescaped `&` is not a vulnerability here so much as a
 * poster that fails to render at all — `librsvg` rejects the document and `sharp` throws, so
 * the symptom is "posters do not work for anybody called Jones & Co" rather than anything
 * dramatic. Escaped anyway, and tested, because the input is a user's own text.
 */
async function textLayerSvg(
  request: CompositeRequest,
  layout: Layout,
  spec: { width: number; height: number },
  hasPhoto: boolean,
): Promise<string> {
  const { theme } = request;

  /**
   * The no-photo path (FR-POS-06, OD-11): "typographic/illustrated template, still high
   * quality". Not an error, not an empty rectangle where a face should be.
   *
   * The headline moves UP into the space the photo would have occupied and gets larger, so
   * the result is a typographic poster rather than a photo poster with a hole in it. That
   * is the difference between a fallback and a template.
   *
   * **It also re-centres, and LINK is why.** LINK is the one landscape format, so its
   * photo sits BESIDE the text and its text box is a 590px column starting at x=530. With
   * no photo there is no column — the right half of a 1200px canvas is simply where the
   * words used to be squeezed, and a headline set there at 1.3× ran off the right edge
   * while the whole left half sat empty. The pixel-measuring spec caught it; reading the
   * layout table would not have, because the table is correct for the case it describes.
   */
  const anchor = hasPhoto ? layout.textAnchor : 'middle';
  const textLeft = hasPhoto ? layout.textLeft : Math.round(spec.width / 2);
  const textWidth = hasPhoto ? layout.textWidth : spec.width - CANVAS_MARGIN * 2;

  const nominalHeadline = hasPhoto ? layout.headlineSize : Math.round(layout.headlineSize * 1.3);

  /**
   * **Fitted, not assumed.** `layout.textWidth` was declared in all three layouts and read
   * by nothing, so a headline wider than the canvas ran off both edges — see
   * `text-fitting.ts` for the poster that found it. Two lines at most: a third would push
   * a headline into the subline, and `assertNoOverlap` would then be guarding a band the
   * text had already left.
   */
  const headline = await fitText(request.headline, {
    maxWidth: textWidth,
    nominalSize: nominalHeadline,
    maxLines: 2,
    fontStack: FONT_STACK,
  });

  const lineHeight = Math.round(headline.fontSize * 1.12);
  const headlineY = hasPhoto ? layout.headlineY : typographicHeadlineY(spec);

  /**
   * A second line grows the block DOWNWARDS from the first baseline, so the first line
   * stays where the layout put it and the rule above it does not move. The subline is then
   * pushed below whatever the headline actually occupied rather than below where a
   * one-line headline would have ended.
   */
  const headlineBottom = headlineY + lineHeight * (headline.lines.length - 1);
  const sublineY = hasPhoto
    ? Math.max(layout.sublineY, headlineBottom + Math.round(layout.sublineSize * 1.6))
    : headlineBottom + Math.round(headline.fontSize * 1.1);

  const rule = hasPhoto
    ? ''
    : // One quiet rule above the headline, so a typographic poster has a frame element of
      // its own rather than being words floating on a gradient.
      `<rect x="${anchor === 'middle' ? spec.width / 2 - 80 : textLeft}" ` +
      `y="${headlineY - headline.fontSize - 56}" width="160" height="4" ` +
      `fill="${theme.textColor}" opacity="0.5"/>`;

  const headlineTspans = headline.lines
    .map(
      (line, i) =>
        `<tspan x="${textLeft}" ${i === 0 ? '' : `dy="${lineHeight}"`}>${escapeXml(line)}</tspan>`,
    )
    .join('');

  // Shrunk but never wrapped: a subline is a year count or a milestone, and breaking
  // "25th Silver anniversary" across two lines reads worse than setting it smaller.
  const fittedSub = request.subline
    ? await fitText(request.subline, {
        maxWidth: textWidth,
        nominalSize: layout.sublineSize,
        maxLines: 1,
        fontStack: FONT_STACK,
      })
    : null;

  const subline = fittedSub
    ? `<text x="${textLeft}" y="${sublineY}" text-anchor="${anchor}" ` +
      `font-family="${FONT_STACK}" font-size="${fittedSub.fontSize}" ` +
      `fill="${theme.textColor}" opacity="0.82">${escapeXml(fittedSub.lines[0])}</text>`
    : '';

  const attribution = request.attributed
    ? `<text x="${textLeft}" y="${layout.attributionY}" text-anchor="${anchor}" ` +
      `font-family="${FONT_STACK}" font-size="24" fill="${theme.textColor}" ` +
      `opacity="0.55">${ATTRIBUTION}</text>`
    : '';

  return `<svg xmlns="http://www.w3.org/2000/svg" width="${spec.width}" height="${spec.height}">
  ${rule}
  <text x="${textLeft}" y="${headlineY}" text-anchor="${anchor}" font-family="${FONT_STACK}" font-size="${headline.fontSize}" font-weight="700" fill="${theme.textColor}">${headlineTspans}</text>
  ${subline}
  ${attribution}
</svg>`;
}

/**
 * The text layer on its own, transparent.
 *
 * Exported so `compositor.spec.ts` can render it and measure the drawn ink with
 * `sharp.trim()`. That is the only way to assert "the words are inside the picture"
 * against the actual pixels rather than against the estimate that decided the font size —
 * and the estimate being wrong is exactly the failure this is guarding.
 */
export async function renderTextLayer(
  request: CompositeRequest,
  hasPhoto: boolean,
): Promise<Buffer> {
  const spec = FORMATS[request.format];
  const svg = await textLayerSvg(request, LAYOUTS[request.format], spec, hasPhoto);
  return sharp(Buffer.from(svg)).png().toBuffer();
}

/**
 * Where the headline sits when there is no photo: the optical centre of the canvas.
 *
 * A fraction of the height rather than an entry in [LAYOUTS], because the typographic
 * template is one layout for all three formats — there is no photo box to work around, so
 * the only thing that differs between them is the canvas.
 */
function typographicHeadlineY(spec: { height: number }): number {
  return Math.round(spec.height * 0.46);
}

/**
 * A stack rather than one family.
 *
 * `Noto Sans Devanagari` first because FR-WSH-03 allows Hindi, and poster copy inherits
 * that: a headline reading "जन्मदिन मुबारक" in a font with no Devanagari coverage renders as
 * boxes. The generic `sans-serif` is last so that a runtime with neither named font still
 * draws something.
 *
 * **This depends on fonts existing in the runtime**, which `node:22-alpine` does not have.
 * `server/Dockerfile` installs them; `docs/M6_VERIFY.md` records whether that was checked
 * inside the image or only read.
 */
export const FONT_STACK = 'Noto Sans Devanagari, Noto Sans, DejaVu Sans, sans-serif';

function escapeXml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');
}
