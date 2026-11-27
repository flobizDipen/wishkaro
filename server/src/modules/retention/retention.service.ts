import { Inject, Injectable, Logger, NotFoundException } from '@nestjs/common';
import { PrismaService } from '../../prisma/prisma.service';
import { BLOB_STORE, type BlobStore } from '../media/blob-store';
import {
  DAY_MS,
  RETENTION_WARNING_DAYS,
  decide,
  expiryFor,
  type RetentionCandidate,
} from './retention.rules';

/**
 * How far ahead the candidate query looks.
 *
 * Exactly the warning window, because nothing earlier than that can produce an action: a
 * POSTER is warned at `expires_at - RETENTION_WARNING_DAYS` and a SOURCE_PHOTO does nothing at
 * all until `expires_at`. Derived from the constant the rules use rather than written as a
 * number here, so widening the window cannot leave the query behind looking at a narrower one.
 */
const WARNING_HORIZON_MS = RETENTION_WARNING_DAYS * DAY_MS;

/**
 * FR-SET-06's scheduled job — §12 insists on a job rather than a sentence in a policy, in as
 * many words: *"Retention limits enforced by a scheduled job, not just stated in policy."*
 *
 * ## Why this is the first thing M9 builds
 *
 * It is the only requirement in the milestone with a clock already running. M6 began storing
 * **photographs of real people** — uploaded, EXIF-stripped, composited — and wrote
 * `asset.expires_at` on every one of them. Nothing has ever read it. Every photo and every
 * poster has been accumulating on a personal deployment since M6 merged, and every day it is
 * not built the pile is larger. Export and deletion are requirements; this one is a
 * requirement with interest accruing.
 *
 * ## Bytes before rows, in one direction only
 *
 * `BlobStore` has `put/get/exists/delete/signedUrl` and **no `list`** (see `blob-store.ts`).
 * So there is no way to ask a store what it is holding, and a row deleted before its bytes
 * leaves those bytes unreachable by anything that will ever run — undiscoverable *and*
 * undeleted, which is the worst of the two. Every purge here therefore deletes the object
 * first and only then writes the row, so the one survivable failure is the one that happens:
 * a row still pointing at an object that is already gone, which the next sweep fixes and
 * which `photoFor` already tolerates (`poster-pipeline.service.ts` treats missing bytes as
 * no photo).
 *
 * ## What it must never touch
 *
 * `theme_cache`. §10 rule 5 says background artwork is "cached by theme key and reused
 * indefinitely"; FR-SET-06 purges generated media at 180 days. Those collide only if the two
 * kinds of image are one kind, and they are not — a `theme_cache` row is product art, owned by
 * us and shared across every user, while a composited poster is a named person's media.
 * `docs/M6_PLAN.md` §3.6 decided this and spelled out the failure mode: a sweep that cannot
 * tell them apart deletes the cache, every subsequent poster becomes a live generation, and
 * the bill goes up with nothing on screen having changed. There is a test for it.
 *
 * ## Logging
 *
 * **Counts and durations. Never a key, an id, a filename or a user.** §11 and the build
 * prompt: *"Nothing in a log, ever. Not an export path, not a deletion confirmation, not a
 * filename."* A sweep that logs nothing at all is unoperable — the first question anybody asks
 * is what it did last night — so it answers that question with numbers and nothing else.
 * `docs/M9_PLAN.md` C7.
 */

export interface SweepSummary {
  considered: number;
  warned: number;
  purged: number;
  /** Bytes whose `delete` threw. The rows are left alone, so the next sweep tries again. */
  failed: number;
  /** Purged rows broken out, because the two have very different meanings to a user. */
  purgedPhotos: number;
  purgedPosters: number;
  durationMs: number;
}

/**
 * How many assets one sweep will deal with.
 *
 * A bound rather than "all of them", because this runs on a container that is also serving
 * requests, and the first sweep of a deployment that has been accumulating since M6 is the
 * largest one it will ever do. The remainder is picked up on the next tick — the ordering is
 * oldest-expiry-first, so nothing starves.
 */
const SWEEP_BATCH = 500;

@Injectable()
export class RetentionService {
  private readonly logger = new Logger(RetentionService.name);

  constructor(
    private readonly prisma: PrismaService,
    @Inject(BLOB_STORE) private readonly blobs: BlobStore,
  ) {}

  /**
   * One pass. Idempotent: an asset already purged is skipped on `purged_at`, so re-running
   * this — on a restart, on an overlapping tick, by hand — does nothing twice.
   */
  async sweep(now: Date = new Date()): Promise<SweepSummary> {
    const startedAt = Date.now();
    const summary: SweepSummary = {
      considered: 0,
      warned: 0,
      purged: 0,
      failed: 0,
      purgedPhotos: 0,
      purgedPosters: 0,
      durationMs: 0,
    };

    /**
     * Candidates, narrowed in SQL to what could possibly be actionable.
     *
     * `retention: 'AUTO'` and `purgedAt: null` are the rules' own first two checks, pushed
     * into the query so a deployment with a million kept assets does not read them every
     * night. The DATE is deliberately NOT narrowed here beyond the warning window: the rules
     * decide what is due, and a query that pre-decided it would be a second implementation of
     * FR-SET-06 living in a `where` clause.
     */
    const horizon = new Date(now.getTime() + WARNING_HORIZON_MS);
    const candidates = await this.prisma.asset.findMany({
      where: {
        purgedAt: null,
        retention: 'AUTO',
        expiresAt: { not: null, lte: horizon },
      },
      orderBy: [{ expiresAt: 'asc' }, { id: 'asc' }],
      take: SWEEP_BATCH,
      select: {
        id: true,
        userId: true,
        personId: true,
        kind: true,
        blobKey: true,
        retention: true,
        expiresAt: true,
        purgeWarnedAt: true,
        purgedAt: true,
        /**
         * FR-SET-06's "unless attached to a saved poster", as a recorded link.
         *
         * `sourceFor` is `poster.source_asset_id`, added and backfilled by M9's migration
         * because `poster.asset_id` points at the OUTPUT png and nothing recorded the input.
         * Tombstoned posters do not count — "a saved poster" is one the user still has.
         */
        sourceFor: { where: { deletedAt: null }, select: { id: true }, take: 1 },
      },
    });

    for (const row of candidates) {
      summary.considered += 1;

      const candidate: RetentionCandidate = {
        kind: row.kind,
        retention: row.retention,
        expiresAt: row.expiresAt,
        purgeWarnedAt: row.purgeWarnedAt,
        purgedAt: row.purgedAt,
        attachedToSavedPoster: row.sourceFor.length > 0,
      };

      const verdict = decide(candidate, now);
      if (verdict.action === 'SKIP') continue;

      if (verdict.action === 'WARN') {
        await this.prisma.asset.update({
          where: { id: row.id },
          data: { purgeWarnedAt: now },
        });
        summary.warned += 1;
        continue;
      }

      const purged = await this.purge(row, now);
      if (purged) {
        summary.purged += 1;
        if (row.kind === 'POSTER') summary.purgedPosters += 1;
        else summary.purgedPhotos += 1;
      } else {
        summary.failed += 1;
      }
    }

    summary.durationMs = Date.now() - startedAt;
    return summary;
  }

  /**
   * One asset's bytes, then one asset's row. Returns false when the bytes could not be
   * removed, in which case **nothing is written** and the next sweep tries again.
   */
  private async purge(
    row: { id: string; userId: string; personId: string | null; kind: 'POSTER' | 'SOURCE_PHOTO'; blobKey: string },
    now: Date,
  ): Promise<boolean> {
    try {
      await this.blobs.delete(row.blobKey);
    } catch (cause) {
      /**
       * No key in the message. The failure is worth knowing about; which person's photograph
       * it was is not something that belongs in a log on any day, and least of all this one.
       */
      this.logger.error(`Retention: a blob delete failed (${row.kind}). ${String(cause)}`);
      return false;
    }

    await this.prisma.$transaction(async (tx) => {
      await tx.asset.update({
        where: { id: row.id },
        data: {
          purgedAt: now,
          // Tombstoned as well as purged: every read path in the app already filters on
          // `deleted_at IS NULL` (`PhotoService.currentUrl`, `PosterPipelineService.photoFor`),
          // so this is what stops a purged asset being offered as if its bytes were there.
          // `purged_at` is the richer fact and `deleted_at` is the one the existing queries
          // ask for; writing one without the other would mean a purged photo still resolving.
          deletedAt: now,
        },
      });

      if (row.kind === 'SOURCE_PHOTO' && row.personId) {
        /**
         * `person.photo_key` is a denormalised copy of the blob key, written by
         * `PhotoService.upload`. Left alone it would point at bytes that no longer exist.
         *
         * Guarded on the key matching, so a photo uploaded AFTER this one — which tombstoned
         * this row but left `photo_key` pointing at the newer object — is not cleared by
         * sweeping the older one. That is the whole reason this is `updateMany` with a
         * `photoKey` predicate rather than an `update` by person id.
         */
        await tx.person.updateMany({
          where: { id: row.personId, userId: row.userId, photoKey: row.blobKey },
          data: { photoKey: null },
        });
      }

      if (row.kind === 'POSTER') {
        /**
         * The poster row goes with its image.
         *
         * `PostersService.forPerson` filters `poster.deleted_at IS NULL` and then signs
         * `asset.blob_key` for whatever survives — it does not look at the asset's state at
         * all. Without this, a purged poster would keep appearing in the person's history as a
         * card whose image 404s, which is a worse answer than "it is gone" and is also not
         * what "generated media purged after 180 days" promises.
         *
         * The poster row itself is tombstoned rather than deleted, so `wish.poster_id` — which
         * carries no foreign key — still resolves to a record saying what was sent, even once
         * the picture has been reclaimed.
         */
        await tx.poster.updateMany({
          where: { assetId: row.id, deletedAt: null },
          data: { deletedAt: now },
        });
      }
    });

    return true;
  }

  // -------------------------------------------------------------------------------------
  // FR-SET-06's third clause: "both user-overridable to keep"
  // -------------------------------------------------------------------------------------

  /**
   * Keep this asset for ever, or hand it back to the clock.
   *
   * Switching back to AUTO **restores the original deadline** rather than minting a new one
   * from today. Re-deriving it from `created_at` means a user who keeps something and then
   * changes their mind gets the retention period the policy promised, not a fresh 90 or 180
   * days — which would make "keep, then un-keep" an unlimited extension and the policy page a
   * lie. `expiryFor` is the same arithmetic the two creation sites use, named once.
   */
  async setRetention(
    userId: string,
    assetId: string,
    retention: 'AUTO' | 'KEEP',
  ): Promise<{ assetId: string; retention: 'AUTO' | 'KEEP'; expiresAt: string | null }> {
    const asset = await this.prisma.asset.findFirst({
      where: { id: assetId, userId },
      select: { id: true, kind: true, createdAt: true, expiresAt: true, purgedAt: true },
    });

    if (!asset) {
      // Scoped to the caller's own user id above, so this is also what somebody else's asset
      // id looks like. Deliberately the same answer as a nonexistent one.
      throw new NotFoundException({ code: 'ASSET_NOT_FOUND', message: 'No such item' });
    }
    if (asset.purgedAt) {
      // Keeping something that is already gone would report success and change nothing.
      throw new NotFoundException({
        code: 'ASSET_ALREADY_PURGED',
        message: 'That item has already been removed',
      });
    }

    const expiresAt = retention === 'KEEP' ? asset.expiresAt : expiryFor(asset.kind, asset.createdAt);

    const updated = await this.prisma.asset.update({
      where: { id: asset.id },
      data: {
        retention,
        expiresAt,
        // Un-keeping clears the warning, so the notice period starts again. Otherwise an
        // asset warned a year ago, kept, and then released would be purged on the next sweep
        // with no notice at all.
        purgeWarnedAt: null,
      },
      select: { id: true, retention: true, expiresAt: true },
    });

    return {
      assetId: updated.id,
      retention: updated.retention,
      expiresAt: updated.expiresAt?.toISOString() ?? null,
    };
  }

  /**
   * What is about to go, so the app can say so while there is still time to press Keep.
   *
   * This is the half of FR-SET-06's "with a warning" that a user can see. The other half is
   * the precondition in `decide()` — the sweep refuses to purge generated media that has not
   * been warned and waited. A warning nobody can read is not a warning, and a warning with no
   * teeth is a notification; the clause needs both.
   */
  async warned(userId: string): Promise<{
    items: { assetId: string; kind: string; personId: string | null; expiresAt: string | null; warnedAt: string | null }[];
  }> {
    const rows = await this.prisma.asset.findMany({
      where: { userId, purgedAt: null, retention: 'AUTO', purgeWarnedAt: { not: null } },
      orderBy: [{ expiresAt: 'asc' }, { id: 'asc' }],
      take: 200,
      select: { id: true, kind: true, personId: true, expiresAt: true, purgeWarnedAt: true },
    });

    return {
      items: rows.map((r) => ({
        assetId: r.id,
        kind: r.kind,
        personId: r.personId,
        expiresAt: r.expiresAt?.toISOString() ?? null,
        warnedAt: r.purgeWarnedAt?.toISOString() ?? null,
      })),
    };
  }
}
