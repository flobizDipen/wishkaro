import { randomUUID } from 'node:crypto';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { PrismaClient } from '@prisma/client';
import sharp from 'sharp';
import type { PrismaService } from '../../prisma/prisma.service';
import { LocalBlobStore } from './local-blob-store';
import { PhotoService } from './photo.service';

/**
 * The photo path (FR-PPL-04, FR-POS-01), against real Postgres and a real blob store.
 *
 * Unscoped work — `docs/M6_PLAN.md` §3.1 — so it gets the same treatment as everything
 * else rather than less.
 *
 * The test that matters most here is the EXIF one. A photograph off a phone carries GPS
 * coordinates, and nothing about a birthday poster needs to know where a picture was
 * taken; storing them would be the quiet kind of collection §12's DPDP obligations are
 * about. It is also the kind of thing that is true by accident until somebody changes the
 * pipeline, which is what the assertion is for.
 */

const prisma = new PrismaClient();
const db = prisma as unknown as PrismaService;

let root: string;
let service: PhotoService;
let userId: string;
let personId: string;

/** The same JPEG with no GPS IFD, so the precondition below can be a comparison. */
async function photoWithoutGps(): Promise<Buffer> {
  return sharp({
    create: { width: 400, height: 600, channels: 3, background: { r: 120, g: 90, b: 60 } },
  })
    .withExif({ IFD0: { Make: 'TestPhone', Model: 'TP-1' } })
    .jpeg()
    .toBuffer();
}

/** A photograph-shaped JPEG carrying GPS EXIF, built here rather than committed. */
async function photoWithGps(): Promise<Buffer> {
  return sharp({
    create: { width: 400, height: 600, channels: 3, background: { r: 120, g: 90, b: 60 } },
  })
    .withExif({
      IFD0: { Make: 'TestPhone', Model: 'TP-1' },
      // IFD3 is the GPS IFD in libvips' model, which is what `sharp.withExif` writes
      // through. An invented reading, not a real place and not anybody's.
      IFD3: {
        GPSLatitudeRef: 'N',
        GPSLatitude: '18/1 31/1 1334/100',
        GPSLongitudeRef: 'E',
        GPSLongitude: '73/1 51/1 2412/100',
      },
    })
    .jpeg()
    .toBuffer();
}

beforeAll(async () => {
  try {
    await prisma.$connect();
  } catch (cause) {
    throw new Error(`This suite needs Postgres. Cause: ${String(cause)}`);
  }
});

afterAll(async () => {
  await prisma.$disconnect();
});

beforeEach(async () => {
  root = await mkdtemp(join(tmpdir(), 'wishkaro-photo-spec-'));
  service = new PhotoService(
    db,
    // A limiter that always allows: this suite is about the upload pipeline, and the
    // ceiling has its own behaviour. Real Redis is not needed for either.
    { consume: async () => ({ allowed: true, retryAfterSeconds: 0, window: null }) } as never,
    new LocalBlobStore(root, 'spec-signing-secret'),
  );

  const user = await prisma.user.create({
    data: { googleSub: `photo-spec-${randomUUID()}`, email: 'nobody@example.invalid' },
  });
  userId = user.id;
  // Invented (MILESTONE_CHECKLIST.md §F).
  const person = await prisma.person.create({
    data: { userId, displayName: 'Meera Iyer', relationToOwner: 'Friend' },
  });
  personId = person.id;
});

afterEach(async () => {
  await prisma.user.deleteMany({ where: { id: userId } });
  await rm(root, { recursive: true, force: true });
});

describe('uploading a photo', () => {
  it('writes the asset, sets person.photo_key, and dates it for FR-SET-06', async () => {
    const { assetId } = await service.upload(userId, personId, await photoWithGps());

    const asset = await prisma.asset.findUniqueOrThrow({ where: { id: assetId } });
    expect(asset.kind).toBe('SOURCE_PHOTO');
    expect(asset.contentType).toBe('image/jpeg');
    expect(asset.bytes).toBeGreaterThan(0);
    expect(asset.expiresAt).not.toBeNull();

    // 90 days, not 180: a source photo and a generated poster have different lifetimes.
    const days = (asset.expiresAt!.getTime() - Date.now()) / 86_400_000;
    expect(days).toBeGreaterThan(88);
    expect(days).toBeLessThan(91);

    // The column M1 reserved and nothing wrote until now.
    const person = await prisma.person.findUniqueOrThrow({ where: { id: personId } });
    expect(person.photoKey).toBe(asset.blobKey);
  });

  it('does not store the GPS coordinates the camera put in the file', async () => {
    const original = await photoWithGps();

    /**
     * The fixture really does carry them, or the assertion below proves nothing — this is
     * the shape of green revert-check `MILESTONE_CHECKLIST.md` warns about: a test that
     * passes because the input never had what it is checking for the absence of.
     */
    const before = (await sharp(original).metadata()).exif;
    const withoutGps = (await sharp(await photoWithoutGps()).metadata()).exif;
    expect(before).toBeDefined();
    expect(withoutGps).toBeDefined();

    /**
     * EXIF tag NAMES are not stored as text — the block is binary, keyed by numeric tag —
     * so "contains the string GPSLatitude" would never have matched and the assertion
     * would have been green for the wrong reason. Comparing against the same image
     * written WITHOUT a GPS IFD is what actually shows the coordinates are in there.
     */
    expect(before!.length).toBeGreaterThan(withoutGps!.length);

    const { assetId } = await service.upload(userId, personId, original);
    const asset = await prisma.asset.findUniqueOrThrow({ where: { id: assetId } });

    const stored = await new LocalBlobStore(root, 'spec-signing-secret').get(asset.blobKey);
    expect(stored).not.toBeNull();

    const meta = await sharp(stored!).metadata();
    expect(meta.exif).toBeUndefined();
  });

  it('shrinks a large photo and keeps its aspect ratio', async () => {
    const big = await sharp({
      create: { width: 4000, height: 3000, channels: 3, background: { r: 10, g: 20, b: 30 } },
    })
      .jpeg()
      .toBuffer();

    const { assetId } = await service.upload(userId, personId, big);
    const asset = await prisma.asset.findUniqueOrThrow({ where: { id: assetId } });

    expect(asset.width).toBe(1600);
    expect(asset.height).toBe(1200);
    // FR-POS-02 is about likeness: a resize that preserves the ratio is not a change to it.
    expect(asset.width! / asset.height!).toBeCloseTo(4 / 3, 5);
  });

  it('keeps one live photo per person and tombstones the previous one', async () => {
    const first = await service.upload(userId, personId, await photoWithGps());
    const second = await service.upload(userId, personId, await photoWithGps());

    const live = await prisma.asset.findMany({
      where: { personId, kind: 'SOURCE_PHOTO', deletedAt: null },
    });
    expect(live).toHaveLength(1);
    expect(live[0].id).toBe(second.assetId);

    // Tombstoned, not deleted: a poster already composited from it keeps a row explaining
    // where its pixels came from.
    const old = await prisma.asset.findUniqueOrThrow({ where: { id: first.assetId } });
    expect(old.deletedAt).not.toBeNull();
  });

  it('refuses something that is not an image, rather than failing in the compositor', async () => {
    await expect(
      service.upload(userId, personId, Buffer.from('this is not a photograph', 'utf8')),
    ).rejects.toThrow(/image/i);
  });

  it('refuses an empty body with a message naming the content types', async () => {
    await expect(service.upload(userId, personId, undefined)).rejects.toThrow(/image\/jpeg/);
  });

  it('refuses a person who is not yours', async () => {
    const other = await prisma.user.create({
      data: { googleSub: `photo-spec-other-${randomUUID()}`, email: 'nobody@example.invalid' },
    });
    await expect(service.upload(other.id, personId, await photoWithGps())).rejects.toThrow(
      /No such person/,
    );
    await prisma.user.delete({ where: { id: other.id } });
  });

  it('removing it clears photo_key, so the next poster takes the no-photo path', async () => {
    await service.upload(userId, personId, await photoWithGps());
    await service.remove(userId, personId);

    const person = await prisma.person.findUniqueOrThrow({ where: { id: personId } });
    expect(person.photoKey).toBeNull();
    expect(await service.currentUrl(userId, personId)).toBeNull();
  });
});
