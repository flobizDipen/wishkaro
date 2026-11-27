import {
  BadRequestException,
  HttpException,
  HttpStatus,
  Inject,
  Injectable,
  Logger,
  NotFoundException,
} from '@nestjs/common';
import { randomUUID } from 'node:crypto';
import sharp from 'sharp';
import { PrismaService } from '../../prisma/prisma.service';
import { expiryFor } from '../retention/retention.rules';
import { BLOB_STORE, SIGNED_URL_TTL_SECONDS, blobKeyFor, type BlobStore } from './blob-store';
import { PhotoRateLimitService } from './photo-rate-limit.service';

/**
 * A person's photo: upload, store, and hand to the compositor.
 *
 * ## This is work the build prompt did not scope, and that is recorded rather than hidden
 *
 * `docs/M6_PLAN.md` §3.1 raised it as collision C1. FR-POS-01 composites "the person's
 * unmodified photo" and definition-of-done item 1 is "a celebratory poster, composited with
 * a **real photo**" — but there is no photo anywhere in this app. `person.photo_key` has
 * been reserved and unwritten since M1, `AndroidContacts` reads a contact's `photoUri` for
 * display only and says so, and the build prompt's Scope section lists the theme library,
 * the compositor, the solemn set, moderation, async generation, the ledger and attribution,
 * and no photo pipeline at all.
 *
 * So this is the MINIMUM that makes item 1 reachable: one photo per person, uploaded,
 * stored, served to the compositor. Not cropping, not face detection, not multiple photos,
 * not a gallery, not re-upload on sync. `M6_VERIFY.md` says this was unscoped work absorbed
 * to make a definition-of-done item reachable, so the decision is visible rather than
 * discovered in a diff.
 *
 * ## Re-encoded on the way in, and EXIF is why
 *
 * A photograph off a phone carries EXIF, and EXIF carries **GPS coordinates**, the device
 * model and the exact timestamp. Storing that would mean this app held the location where a
 * picture of somebody's late father was taken, in a blob store, for ninety days, for no
 * product reason whatsoever — and §12's DPDP obligations are about exactly this kind of
 * quiet collection.
 *
 * `sharp` drops metadata by default on re-encode, so the fix is to re-encode rather than to
 * strip. `.rotate()` first, because the orientation lives in the EXIF that is about to be
 * discarded: without it a portrait photo taken on a phone comes out sideways, on the one
 * path where the subject is somebody's face.
 *
 * ## It does not contradict FR-POS-02
 *
 * "Unmodified" is a likeness and personality-rights rule: no generative step, no filter, no
 * recolour, no distortion of the face's proportions (see `compositor.ts`). A resize that
 * preserves the aspect ratio is none of those, and the alternative — compositing a 12-
 * megapixel original into a 760px box on every render — is the same resampling done more
 * often and more slowly.
 */

/** Long edge. Comfortably above the largest photo box any format uses (760px). */
const MAX_EDGE = 1600;

/** See the `limitInputPixels` comment below. 50 MP is above any phone camera. */
const MAX_INPUT_PIXELS = 50_000_000;

@Injectable()
export class PhotoService {
  private readonly logger = new Logger(PhotoService.name);

  constructor(
    private readonly prisma: PrismaService,
    private readonly rateLimit: PhotoRateLimitService,
    @Inject(BLOB_STORE) private readonly blobs: BlobStore,
  ) {}

  async upload(
    userId: string,
    personId: string,
    body: Buffer | undefined,
  ): Promise<{ assetId: string; url: string | null }> {
    if (!body || body.byteLength === 0) {
      throw new BadRequestException({
        code: 'EMPTY_BODY',
        message: 'Send the image bytes with an image/jpeg, image/png or image/webp content type',
      });
    }

    const person = await this.prisma.person.findFirst({
      where: { id: personId, userId, deletedAt: null },
      select: { id: true },
    });
    if (!person) {
      throw new NotFoundException({ code: 'PERSON_NOT_FOUND', message: 'No such person' });
    }

    /**
     * The ceiling, taken after ownership and before any decoding.
     *
     * After ownership so a 404 costs nothing; before `sharp` touches the bytes, because
     * the decode is the expensive thing being bounded.
     */
    const verdict = await this.rateLimit.consume(userId);
    if (!verdict.allowed) {
      throw new HttpException(
        {
          code: 'RATE_LIMITED',
          message: 'That is a lot of photos at once. Try again shortly.',
          retryAfterSeconds: verdict.retryAfterSeconds,
        },
        HttpStatus.TOO_MANY_REQUESTS,
      );
    }

    /**
     * What the bytes ACTUALLY are, not what the header claimed.
     *
     * `configure-app.ts` gates on the `Content-Type`, which is the client's word for it.
     * Without this check anything libvips can decode is accepted and stored — an SVG sent
     * as `image/png` was verified to be rasterised and kept. Nothing worse followed in
     * this build (librsvg refuses network refs and rejects XXE), but exposing the GIF,
     * TIFF, AVIF and SVG decoders to arbitrary bytes for a feature that wants three
     * formats is surface for nothing.
     *
     * `metadata()` reads the header only, so this costs no decode.
     */
    const ACCEPTED = new Set(['jpeg', 'png', 'webp']);
    try {
      const probed = await sharp(body, { limitInputPixels: MAX_INPUT_PIXELS }).metadata();
      if (!probed.format || !ACCEPTED.has(probed.format)) {
        throw new Error(`decoded as ${probed.format ?? 'nothing'}`);
      }
    } catch (cause) {
      this.logger.warn(`Photo upload refused: ${String(cause)}`);
      throw new BadRequestException({
        code: 'NOT_AN_IMAGE',
        message: "That file isn't a JPEG, PNG or WebP we can read",
      });
    }

    let jpeg: Buffer;
    let width: number;
    let height: number;
    try {
      const pipeline = sharp(body, {
        /**
         * A ceiling on DECODED pixels, well below sharp's default of 268 megapixels.
         *
         * Measured on this machine against the installed sharp: a 0.72 MB solid-colour PNG
         * declaring 16000x16000 = 256 MP sits just under the default limit, decodes
         * happily, and costs ~143 MB of RSS and ~220 ms. Thirty of those in flight from
         * one signed-in account OOMs the container for 20 MB of upstream traffic.
         *
         * 50 MP is above any phone camera this app will meet — a 48 MP sensor is 48 MP —
         * so it costs nothing real and turns the bomb into a 400.
         */
        limitInputPixels: MAX_INPUT_PIXELS,
      })
        // Applies the EXIF orientation while that EXIF still exists. Everything after this
        // line is working with pixels whose top is the top.
        .rotate()
        .resize(MAX_EDGE, MAX_EDGE, { fit: 'inside', withoutEnlargement: true })
        .jpeg({ quality: 88 });

      const { data, info } = await pipeline.toBuffer({ resolveWithObject: true });
      jpeg = data;
      width = info.width;
      height = info.height;
    } catch (cause) {
      // Decoded rather than trusted: a content-type header is a claim, and a file that is
      // not an image must fail here with something the app can show rather than inside the
      // compositor three screens later.
      this.logger.warn(`Photo upload did not decode: ${String(cause)}`);
      throw new BadRequestException({
        code: 'NOT_AN_IMAGE',
        message: "That file isn't an image we can read",
      });
    }

    const blobKey = blobKeyFor('photos', randomUUID().replace(/-/g, ''), 'jpg');
    await this.blobs.put(blobKey, jpeg, 'image/jpeg');

    const asset = await this.prisma.$transaction(async (tx) => {
      /**
       * One photo per person. The previous one is tombstoned rather than deleted, so a
       * poster already composited from it keeps a row explaining where its pixels came
       * from. M9's retention sweep is what removes the bytes.
       */
      await tx.asset.updateMany({
        where: { userId, personId, kind: 'SOURCE_PHOTO', deletedAt: null },
        data: { deletedAt: new Date() },
      });

      const created = await tx.asset.create({
        data: {
          userId,
          personId,
          kind: 'SOURCE_PHOTO',
          blobKey,
          contentType: 'image/jpeg',
          bytes: jpeg.byteLength,
          width,
          height,
          // FR-SET-06: source photos purged after 90 days unless attached to a saved
          // poster. M6 wrote the date; M9's sweep reads it, and the arithmetic is named once
          // in `retention.rules.ts` rather than written out here and again in the poster
          // pipeline. Two copies of "what 90 days means" is how one of them ends up wrong.
          expiresAt: expiryFor('SOURCE_PHOTO', new Date()),
        },
      });

      // The column M1 reserved, written for the first time.
      await tx.person.update({ where: { id: personId }, data: { photoKey: blobKey } });

      return created;
    });

    return { assetId: asset.id, url: await this.signed(blobKey) };
  }

  /** The current photo's signed URL, or null when there is none. */
  async currentUrl(userId: string, personId: string): Promise<string | null> {
    const asset = await this.prisma.asset.findFirst({
      where: { userId, personId, kind: 'SOURCE_PHOTO', deletedAt: null },
      orderBy: { createdAt: 'desc' },
      select: { blobKey: true },
    });
    return asset ? this.signed(asset.blobKey) : null;
  }

  /** Tombstoned, and `photo_key` cleared, so the next poster takes the no-photo path. */
  async remove(userId: string, personId: string): Promise<void> {
    await this.prisma.$transaction(async (tx) => {
      await tx.asset.updateMany({
        where: { userId, personId, kind: 'SOURCE_PHOTO', deletedAt: null },
        data: { deletedAt: new Date() },
      });
      await tx.person.updateMany({ where: { id: personId, userId }, data: { photoKey: null } });
    });
  }

  private async signed(blobKey: string): Promise<string | null> {
    try {
      return (await this.blobs.signedUrl(blobKey, SIGNED_URL_TTL_SECONDS)).url;
    } catch (cause) {
      this.logger.warn(`Could not sign ${blobKey}: ${String(cause)}`);
      return null;
    }
  }
}
