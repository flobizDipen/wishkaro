import sharp from 'sharp';
import type { PosterFormat } from '@prisma/client';
import { composePoster, layoutFor, type CompositeRequest } from './compositor';
import { FORMATS, themeById } from './theme-library';

/**
 * The compositor, against real `sharp` and real pixels.
 *
 * No fixture here is a photograph. `docs/M6_PLAN.md` §11: this repository is public, a
 * poster carries a real person's face, and a photograph could never be committed. Generated
 * colour patterns are also the only thing that makes "the photo is unmodified" an EXACT
 * assertion — a known pixel can be compared byte for byte where a photograph could only be
 * looked at.
 */

const CELEBRATORY = themeById('ph-confetti')!;
const SOLEMN = themeById('ph-stillwater')!;

/** A flat colour field. Deterministic, and every pixel is known. */
async function solid(width: number, height: number, rgb: [number, number, number]) {
  return sharp({
    create: {
      width,
      height,
      channels: 3,
      background: { r: rgb[0], g: rgb[1], b: rgb[2] },
    },
  })
    .png()
    .toBuffer();
}

/** A photo with a distinct colour per quadrant, so a crop or a flip is visible in a pixel. */
async function quadrants(size: number) {
  const half = Math.floor(size / 2);
  const base = sharp({
    create: { width: size, height: size, channels: 3, background: { r: 0, g: 0, b: 0 } },
  });
  return base
    .composite([
      { input: await solid(half, half, [255, 0, 0]), left: 0, top: 0 },
      { input: await solid(half, half, [0, 255, 0]), left: half, top: 0 },
      { input: await solid(half, half, [0, 0, 255]), left: 0, top: half },
      { input: await solid(half, half, [255, 255, 0]), left: half, top: half },
    ])
    .png()
    .toBuffer();
}

async function pixelAt(png: Buffer, x: number, y: number): Promise<[number, number, number]> {
  const { data, info } = await sharp(png)
    .ensureAlpha()
    .raw()
    .toBuffer({ resolveWithObject: true });
  const offset = (y * info.width + x) * info.channels;
  return [data[offset], data[offset + 1], data[offset + 2]];
}

function request(over: Partial<CompositeRequest> = {}): CompositeRequest {
  return {
    background: Buffer.alloc(0), // replaced by every caller
    photo: null,
    headline: 'Happy birthday, Asha',
    subline: 'She turns 30 today',
    theme: CELEBRATORY,
    format: 'STATUS',
    attributed: true,
    ...over,
  };
}

describe('the compositor — FR-POS-01(c), FR-POS-05, FR-POS-06', () => {
  describe('the three output formats (FR-POS-05)', () => {
    const cases: [PosterFormat, number, number][] = [
      ['STATUS', 1080, 1920],
      ['SQUARE', 1080, 1080],
      ['LINK', 1200, 630],
    ];

    it.each(cases)('%s comes out at exactly %i×%i', async (format, width, height) => {
      const out = await composePoster(
        request({ format, background: await solid(400, 400, [10, 20, 30]) }),
      );

      // The reported size AND the actual bytes. A result object that said 1080×1920 over a
      // 400×400 PNG would be a passing test and a broken poster.
      expect(out.width).toBe(width);
      expect(out.height).toBe(height);
      const meta = await sharp(out.png).metadata();
      expect(meta.width).toBe(width);
      expect(meta.height).toBe(height);
      expect(meta.format).toBe('png');
    });

    it('a background of the wrong shape is covered, not stretched into bars', async () => {
      // A cached 9:16 background used for a 1.91:1 request has to fill the canvas. `cover`
      // crops; `contain` would letterbox, and a poster with grey bars down the side is not
      // one anybody sends.
      const out = await composePoster(
        request({ format: 'LINK', background: await solid(200, 800, [7, 9, 11]) }),
      );
      // The corners are background, and they are the background's colour rather than a
      // padding colour.
      expect(await pixelAt(out.png, 2, 2)).toEqual([7, 9, 11]);
      expect(await pixelAt(out.png, 1197, 627)).toEqual([7, 9, 11]);
    });
  });

  describe('the photo is unmodified (FR-POS-01, FR-POS-02)', () => {
    /**
     * **The exact assertion.** A photo already at the box size is placed with no resampling
     * at all, so every pixel in the composited region can be compared to the source.
     *
     * This is what the word "unmodified" buys, made checkable. A compositor that applied a
     * filter, a tint, a rounded-corner mask or a blur would fail here on a pixel rather than
     * on somebody's opinion of a rendering.
     */
    it('a photo that already fits its box survives pixel for pixel', async () => {
      const box = layoutFor('STATUS').photo;
      expect(box.width).toBe(box.height); // the fixture below is square
      const photo = await quadrants(box.width);

      const out = await composePoster(
        request({ photo, background: await solid(1080, 1920, [0, 0, 0]) }),
      );

      // One probe per quadrant, well inside each, plus the exact corners of the box.
      const q = Math.floor(box.width / 4);
      expect(await pixelAt(out.png, box.left + q, box.top + q)).toEqual([255, 0, 0]);
      expect(await pixelAt(out.png, box.left + 3 * q, box.top + q)).toEqual([0, 255, 0]);
      expect(await pixelAt(out.png, box.left + q, box.top + 3 * q)).toEqual([0, 0, 255]);
      expect(await pixelAt(out.png, box.left + 3 * q, box.top + 3 * q)).toEqual([255, 255, 0]);

      // The corners: a mask or a rounded rectangle would have eaten these.
      expect(await pixelAt(out.png, box.left, box.top)).toEqual([255, 0, 0]);
      expect(await pixelAt(out.png, box.left + box.width - 1, box.top)).toEqual([0, 255, 0]);
      expect(await pixelAt(out.png, box.left, box.top + box.height - 1)).toEqual([0, 0, 255]);
    });

    it('every pixel of the box is the photo and nothing else', async () => {
      // The stronger form: not a sample of probes, the whole region. This is what catches a
      // watermark, a gradient scrim or a caption band laid across the face.
      const box = layoutFor('SQUARE').photo;
      const photo = await solid(box.width, box.height, [123, 77, 200]);

      const out = await composePoster(
        request({
          format: 'SQUARE',
          photo,
          background: await solid(1080, 1080, [255, 255, 255]),
        }),
      );

      const region = await sharp(out.png)
        .extract({ left: box.left, top: box.top, width: box.width, height: box.height })
        .raw()
        .toBuffer({ resolveWithObject: true });

      for (let i = 0; i < region.data.length; i += region.info.channels) {
        if (
          region.data[i] !== 123 ||
          region.data[i + 1] !== 77 ||
          region.data[i + 2] !== 200
        ) {
          const pixel = Math.floor(i / region.info.channels);
          throw new Error(
            `pixel ${pixel % box.width},${Math.floor(pixel / box.width)} of the photo box is ` +
              `[${region.data[i]},${region.data[i + 1]},${region.data[i + 2]}], not the photo`,
          );
        }
      }
    });

    /**
     * **This test was vacuous on its first draft, and the revert-check is what said so.**
     *
     * The first version used a 2:1 source split red|green down the middle and asserted red
     * at the box's left edge and green at its right. Switching `fit: 'cover'` to
     * `fit: 'fill'` left all 23 tests green — because both fits put red on the left and
     * green on the right. `fill` squashes 1000→500 and the boundary lands at the midpoint;
     * `cover` crops to the centre 500 and the boundary lands at the midpoint too. The probes
     * could not tell the two apart, which is exactly the shape M5 §5.4 and
     * `MILESTONE_CHECKLIST.md` §D warn about: green, plausible, and checking nothing.
     *
     * What actually distinguishes them is the source's EXTREME edges. Under `fill` every
     * column of the source survives, squashed. Under `cover` the outer quarters are cropped
     * away entirely. So the source carries marker strips at its far left and far right, and
     * the assertion is that those colours appear **nowhere** in the photo box.
     *
     * Why it matters: stretching changes a face's proportions, which is a likeness change
     * arrived at by arithmetic rather than by a model. FR-POS-02's rule is about likeness,
     * not about which component did it.
     */
    it('a non-square photo is cropped, never stretched', async () => {
      const box = layoutFor('SQUARE').photo;
      const MAGENTA: [number, number, number] = [255, 0, 255];
      const CYAN: [number, number, number] = [0, 255, 255];

      // 1000×500. Far-left 100px magenta, far-right 100px cyan, red|green in between.
      const wide = await sharp({
        create: { width: 1000, height: 500, channels: 3, background: { r: 0, g: 0, b: 0 } },
      })
        .composite([
          { input: await solid(100, 500, MAGENTA), left: 0, top: 0 },
          { input: await solid(400, 500, [255, 0, 0]), left: 100, top: 0 },
          { input: await solid(400, 500, [0, 255, 0]), left: 500, top: 0 },
          { input: await solid(100, 500, CYAN), left: 900, top: 0 },
        ])
        .png()
        .toBuffer();

      const out = await composePoster(
        request({
          format: 'SQUARE',
          photo: wide,
          background: await solid(1080, 1080, [0, 0, 0]),
        }),
      );

      const region = await sharp(out.png)
        .extract({ left: box.left, top: box.top, width: box.width, height: box.height })
        .raw()
        .toBuffer({ resolveWithObject: true });

      let magenta = 0;
      let cyan = 0;
      for (let i = 0; i < region.data.length; i += region.info.channels) {
        const px: [number, number, number] = [
          region.data[i],
          region.data[i + 1],
          region.data[i + 2],
        ];
        // Loose match: resampling at the strip boundary produces near-colours.
        if (px[0] > 200 && px[1] < 60 && px[2] > 200) magenta++;
        if (px[0] < 60 && px[1] > 200 && px[2] > 200) cyan++;
      }

      // Under `fill` each strip would occupy ~50 of the 500 columns — 25,000 pixels each.
      expect(magenta).toBe(0);
      expect(cyan).toBe(0);

      // And the box is genuinely full: a `contain` fit would letterbox with black.
      const corners = await Promise.all([
        pixelAt(out.png, box.left, box.top),
        pixelAt(out.png, box.left + box.width - 1, box.top + box.height - 1),
      ]);
      for (const c of corners) expect(c).not.toEqual([0, 0, 0]);
    });

    /**
     * The vertical half of the same decision, and the one the `position: 'top'` comment
     * claims. A tall phone photo cropped to a square has the head near the top; centring
     * would take the top of the head off.
     *
     * This also fails under `fit: 'fill'`, which is the second reason the revert-check now
     * bites: `fill` keeps the bottom band, `cover` + `top` crops it away.
     */
    it('a tall photo keeps its top, because that is where a face is', async () => {
      const box = layoutFor('SQUARE').photo;
      const FOOT: [number, number, number] = [255, 0, 255];

      /**
       * The banding is what makes BOTH halves of the decision falsifiable, and the second
       * band exists because the first draft of this test was vacuous too.
       *
       * With only head/body/foot, `position: 'centre'` passed: a 500×1000 source cropped to
       * 500×500 keeps rows 250–750 under `centre` and rows 0–500 under `top`, and the probe
       * at the box's top row read the same head colour either way — the magenta foot is
       * cropped away by both. A distinct CROWN band in the first 100 rows is the thing only
       * `top` keeps.
       */
      const CROWN: [number, number, number] = [250, 240, 230];
      // 500×1000: crown, head, body, then a magenta foot band at the very bottom.
      const tall = await sharp({
        create: { width: 500, height: 1000, channels: 3, background: { r: 0, g: 0, b: 0 } },
      })
        .composite([
          { input: await solid(500, 100, CROWN), left: 0, top: 0 },
          { input: await solid(500, 400, [200, 180, 160]), left: 0, top: 100 },
          { input: await solid(500, 400, [100, 90, 80]), left: 0, top: 500 },
          { input: await solid(500, 100, FOOT), left: 0, top: 900 },
        ])
        .png()
        .toBuffer();

      const out = await composePoster(
        request({
          format: 'SQUARE',
          photo: tall,
          background: await solid(1080, 1080, [0, 0, 0]),
        }),
      );

      const region = await sharp(out.png)
        .extract({ left: box.left, top: box.top, width: box.width, height: box.height })
        .raw()
        .toBuffer({ resolveWithObject: true });

      let foot = 0;
      for (let i = 0; i < region.data.length; i += region.info.channels) {
        if (region.data[i] > 200 && region.data[i + 1] < 60 && region.data[i + 2] > 200) foot++;
      }
      expect(foot).toBe(0);

      // The crown — the source's first 100 rows — is what `position: 'top'` keeps and what
      // `centre` would have cropped away.
      expect(await pixelAt(out.png, box.left + 10, box.top + 10)).toEqual(CROWN);
    });
  });

  describe('the no-photo path (FR-POS-06, OD-11)', () => {
    it('produces a poster rather than failing', async () => {
      // "No photo available → typographic/illustrated template, still high quality." For a
      // user importing a 600-person contact list this is not an edge case; for most of those
      // people it is the ONLY path.
      const out = await composePoster(
        request({ photo: null, background: await solid(1080, 1920, [30, 30, 30]) }),
      );

      expect(out.photoBox).toBeNull();
      expect(out.width).toBe(1080);
      const meta = await sharp(out.png).metadata();
      expect(meta.width).toBe(1080);
    });

    it('leaves no empty frame where the photo would have been', async () => {
      // The difference between a template and a fallback. A poster with a hole in it is what
      // "still high quality" rules out.
      const box = layoutFor('STATUS').photo;
      const out = await composePoster(
        request({ photo: null, background: await solid(1080, 1920, [30, 30, 30]) }),
      );

      // The photo region is plain background: no border, no placeholder block, no silhouette.
      expect(await pixelAt(out.png, box.left + 10, box.top + 10)).toEqual([30, 30, 30]);
      expect(await pixelAt(out.png, box.left + box.width - 10, box.top + 10)).toEqual([
        30, 30, 30,
      ]);
    });

    it('draws more ink than the photo layout does, because the type carries the poster', async () => {
      // The typographic template moves the headline up and enlarges it. Comparing ink is a
      // weak assertion on its own, which is why it sits alongside the two above — together
      // they say "no hole, and the words got bigger".
      const background = await solid(1080, 1920, [255, 255, 255]);
      const withPhoto = await composePoster(
        request({ photo: await solid(760, 760, [255, 255, 255]), background }),
      );
      const without = await composePoster(request({ photo: null, background }));

      const inkOf = async (png: Buffer) => {
        const stats = await sharp(png).greyscale().stats();
        return 255 - stats.channels[0].mean;
      };
      expect(await inkOf(without.png)).toBeGreaterThan(await inkOf(withPhoto.png));
    });
  });

  describe('attribution (FR-POS-08, OD-9)', () => {
    it('an attributed poster differs from an unattributed one', async () => {
      const background = await solid(1080, 1920, [255, 255, 255]);
      const on = await composePoster(request({ background, attributed: true }));
      const off = await composePoster(request({ background, attributed: false }));

      expect(on.png.equals(off.png)).toBe(false);
    });

    it('the attribution is in the footer and not over the photo', async () => {
      // "Subtle" (OD-9) is a design word, but "not across the person's face" is checkable.
      const box = layoutFor('STATUS').photo;
      const background = await solid(1080, 1920, [255, 255, 255]);
      const photo = await solid(box.width, box.height, [10, 10, 10]);

      const on = await composePoster(request({ background, photo, attributed: true }));
      const off = await composePoster(request({ background, photo, attributed: false }));

      const region = async (png: Buffer) =>
        sharp(png).extract({ ...box }).raw().toBuffer();

      // Identical inside the photo box; different somewhere outside it.
      expect((await region(on.png)).equals(await region(off.png))).toBe(true);
      expect(on.png.equals(off.png)).toBe(false);
    });

    it('defaults to attributed at the type level', () => {
      // M7 owns premium. Until then the switch exists and points one way — FR-POS-08's
      // "Premium removes it" is a thing M7 turns on, not a thing M6 can do by accident.
      expect(request().attributed).toBe(true);
    });
  });

  describe('the layout table', () => {
    it.each<[PosterFormat]>([['STATUS'], ['SQUARE'], ['LINK']])(
      '%s: the photo box never overlaps the headline',
      (format) => {
        // Asserted rather than left to whoever edits the numbers next. Text over a face is
        // the kind of thing that is obvious in a review and invisible in a diff of integers.
        const l = layoutFor(format);
        const photoBottom = l.photo.top + l.photo.height;
        const headlineTop = l.headlineY - l.headlineSize;
        expect(headlineTop).toBeGreaterThanOrEqual(photoBottom - l.photo.height);
        if (l.textAnchor === 'middle') {
          expect(headlineTop).toBeGreaterThanOrEqual(photoBottom);
        } else {
          // LINK is landscape: the photo sits BESIDE the text, so the separation is
          // horizontal rather than vertical.
          expect(l.textLeft).toBeGreaterThan(l.photo.left + l.photo.width);
        }
      },
    );

    it.each<[PosterFormat]>([['STATUS'], ['SQUARE'], ['LINK']])(
      '%s: everything is inside the canvas',
      (format) => {
        const l = layoutFor(format);
        const spec = FORMATS[format];
        expect(l.photo.left + l.photo.width).toBeLessThanOrEqual(spec.width);
        expect(l.photo.top + l.photo.height).toBeLessThanOrEqual(spec.height);
        expect(l.sublineY).toBeLessThanOrEqual(spec.height);
        expect(l.attributionY).toBeLessThanOrEqual(spec.height);
      },
    );
  });

  describe('text', () => {
    it('renders a name containing XML metacharacters', async () => {
      // Not a vulnerability so much as a poster that does not exist: librsvg rejects a
      // document with a bare `&` and sharp throws, so the symptom would be "posters fail for
      // anybody with an ampersand in their name".
      await expect(
        composePoster(
          request({
            headline: `Happy birthday, <Jones & "Co">`,
            subline: `It's their 10th`,
            background: await solid(1080, 1920, [0, 0, 0]),
          }),
        ),
      ).resolves.toBeDefined();
    });

    it('renders Devanagari without throwing', async () => {
      // FR-WSH-03 allows Hindi and poster copy inherits it. Whether the glyphs are BOXES
      // depends on fonts being installed in the runtime, which this test cannot prove on a
      // developer machine — `docs/M6_VERIFY.md` records how that was checked in the image.
      await expect(
        composePoster(
          request({
            headline: 'जन्मदिन मुबारक, आशा',
            subline: null,
            background: await solid(1080, 1920, [0, 0, 0]),
          }),
        ),
      ).resolves.toBeDefined();
    });

    it('a null subline is simply absent', async () => {
      const background = await solid(1080, 1920, [255, 255, 255]);
      const withSub = await composePoster(request({ background, subline: 'Turning 30' }));
      const without = await composePoster(request({ background, subline: null }));
      expect(withSub.png.equals(without.png)).toBe(false);
    });
  });

  describe('the solemn family composites the same way', () => {
    it('a solemn theme produces a poster with its own text colour', async () => {
      // The compositor does not know about sentiment and must not: FR-POS-13's filter is
      // upstream, in the theme library. What it has to do is honour whichever theme it is
      // handed — a compositor with a solemn branch would be a second place for the rule to
      // be got wrong.
      const background = await solid(1080, 1920, [20, 30, 40]);
      const solemn = await composePoster(request({ background, theme: SOLEMN }));
      const celebratory = await composePoster(request({ background, theme: CELEBRATORY }));
      expect(solemn.png.equals(celebratory.png)).toBe(false);
    });
  });
});
