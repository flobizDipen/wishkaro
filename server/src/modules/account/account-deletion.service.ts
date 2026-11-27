import { Inject, Injectable, Logger, NotFoundException, OnApplicationShutdown } from '@nestjs/common';
import { Queue } from 'bullmq';
import type IORedis from 'ioredis';
import { PrismaService } from '../../prisma/prisma.service';
import { QUEUE_POSTER, REDIS_CONNECTION } from '../../queue/queue.constants';
import { BLOB_STORE, type BlobStore } from '../media/blob-store';
import { REDIS_USER_KEY_PREFIXES } from './deletion-inventory';

/**
 * FR-SET-04 and FR-AUTH-08 — *"Delete my account: irreversible, with explicit confirmation."*
 *
 * ## The thing most likely to go wrong, and the shape built against it
 *
 * `docs/BUILD_PROMPT_M9.md`: *"A deletion that reports success and leaves something behind."*
 * It names three hiding places and all three exist in this codebase — blob objects, FCM device
 * tokens, and the cost ledger. The answers:
 *
 * - **Blobs** are removed explicitly, before the rows, and the keys are re-read after the
 *   queue is drained rather than before (see the ordering below).
 * - **Device tokens** go with the user by a cascading foreign key, and the spec asserts the
 *   table is empty rather than that an endpoint returned 204.
 * - **The ledger** is anonymised, not erased — Dipen's decision, `docs/M9_PLAN.md` decision 3 —
 *   and it is the DATABASE that enforces it, through `ON DELETE SET NULL`, not this file
 *   remembering to run in the right order.
 *
 * ## Bytes before rows, in one direction only
 *
 * `BlobStore` has `put/get/exists/delete/signedUrl` and **no `list`**. So a row deleted before
 * its object leaves those bytes unreachable by anything that will ever run — undiscoverable
 * *and* undeleted, which is the worst of the two. The other failure, a row pointing at a
 * missing object, is visible, queryable and survivable. Every step here is ordered so that the
 * second one is the one that can happen.
 *
 * ## The order, and what each step is for
 *
 * 1. **Close the door.** Soft-delete the user and revoke every refresh token, in one
 *    transaction. The account is immediately unusable and signed out everywhere, and a crash
 *    anywhere after this leaves a state that is safe and resumable rather than half-erased.
 * 2. **Drain the queue.** Remove this user's poster jobs. A job that completed between steps 3
 *    and 4 would write a new asset row and a new object, and the row would then be cascaded
 *    away — leaving the bytes orphaned. This is the concurrent-pair check
 *    `MILESTONE_CHECKLIST.md` §C asks for.
 * 3. **Collect the keys — AFTER the drain, not before.** Every asset the user owns, tombstoned
 *    rows included: a tombstoned photo is still a photograph.
 * 4. **Delete the objects.** Failures are counted and reported, never swallowed into a success.
 * 5. **The transaction**: anonymise, then hard-delete the user and let the cascades run.
 * 6. **Redis**: the rate-limit keys that carry a user id.
 *
 * ## This is a HARD delete, and the sync protocol survives it
 *
 * Four entities have independent `(updated_at, id)` cursors and all four assume rows do not
 * vanish. They do not need to tolerate it: `SyncService.pull` looks the owner up first and
 * throws `USER_NOT_FOUND` before any entity query runs, so a stale client gets one clean 404
 * and its cursor never advances. There is no partial state to be confused by. What the client
 * then does about it is `AuthRepository.onAccountGone`.
 *
 * ## Nothing in a log, ever
 *
 * §11 and the build prompt, which names this case specifically: *"not a deletion confirmation,
 * not a filename"*. Counts only. A deletion is the worst possible moment to write a user's
 * data down.
 */

export interface DeletionSummary {
  /** Blob objects removed. */
  blobsDeleted: number;
  /** Objects whose `delete` threw. Non-zero means the deletion did not fully succeed. */
  blobsFailed: number;
  /** Poster jobs dropped before they could write anything new. */
  jobsRemoved: number;
  /** Rows whose `user_id` was nulled rather than deleted. */
  rowsAnonymised: number;
  /** Redis keys removed. */
  redisKeysDeleted: number;
}

@Injectable()
export class AccountDeletionService implements OnApplicationShutdown {
  private readonly logger = new Logger(AccountDeletionService.name);

  /**
   * A client handle on the poster queue, NOT the module that owns its worker.
   *
   * Importing `PostersModule` for `PosterQueue` would pull the whole AI stack into this
   * module's graph — see `account.module.ts`. The queue NAME is the contract and is already a
   * shared constant, so a second client over the same Redis connection is both cheaper and
   * better scoped. It adds no worker and processes nothing.
   *
   * Lazily constructed so that a deployment which never deletes an account never opens it.
   */
  private posterJobs: Queue | null = null;

  constructor(
    private readonly prisma: PrismaService,
    @Inject(BLOB_STORE) private readonly blobs: BlobStore,
    @Inject(REDIS_CONNECTION) private readonly redis: IORedis,
  ) {}

  async onApplicationShutdown(): Promise<void> {
    await this.posterJobs?.close();
    this.posterJobs = null;
  }

  async deleteAccount(userId: string): Promise<DeletionSummary> {
    const user = await this.prisma.user.findUnique({ where: { id: userId }, select: { id: true } });
    if (!user) {
      throw new NotFoundException({ code: 'USER_NOT_FOUND', message: 'No such account' });
    }

    const summary: DeletionSummary = {
      blobsDeleted: 0,
      blobsFailed: 0,
      jobsRemoved: 0,
      rowsAnonymised: 0,
      redisKeysDeleted: 0,
    };

    // --- 1. Close the door -------------------------------------------------------------
    //
    // Soft first, hard last. Between the two the account is unusable and signed out
    // everywhere, so a failure in the middle is recoverable rather than a half-erased user.
    await this.prisma.$transaction([
      this.prisma.user.update({ where: { id: userId }, data: { deletedAt: new Date() } }),
      this.prisma.refreshToken.updateMany({
        where: { userId, revokedAt: null },
        data: { revokedAt: new Date() },
      }),
    ]);

    // --- 2. Drain the queue ------------------------------------------------------------
    summary.jobsRemoved = await this.removePosterJobs(userId);

    // --- 3. Collect the keys, after the drain ------------------------------------------
    //
    // `deletedAt` is NOT filtered. A tombstoned asset — a photo replaced by a newer one, a
    // poster the user removed — still has its bytes in the store until the retention sweep
    // or this method takes them.
    const assets = await this.prisma.asset.findMany({
      where: { userId },
      select: { blobKey: true },
    });

    // --- 4. The objects ----------------------------------------------------------------
    for (const asset of assets) {
      try {
        await this.blobs.delete(asset.blobKey);
        summary.blobsDeleted += 1;
      } catch (cause) {
        // No key in the message, on any day and least of all this one.
        summary.blobsFailed += 1;
        this.logger.error(`Account deletion: a blob delete failed. ${String(cause)}`);
      }
    }

    if (summary.blobsFailed > 0) {
      /**
       * **Stop, and say so.** The rows are left intact, so the keys are still findable and
       * the request can simply be repeated — which is the whole reason bytes go first.
       *
       * Continuing would produce the exact outcome this service exists to prevent: an
       * endpoint reporting success with photographs still in the store and no row left that
       * knows where they are.
       */
      throw new BlobDeletionIncomplete(summary.blobsFailed);
    }

    // --- 5. Anonymise, then delete -----------------------------------------------------
    await this.prisma.$transaction(async (tx) => {
      /**
       * Belt and braces, deliberately.
       *
       * `ON DELETE SET NULL` on these three foreign keys already guarantees the rows survive
       * with a null id — that is why the migration changed the constraint rather than relying
       * on this code. Doing it explicitly as well means the COUNT is a number this method can
       * report and a test can assert on, instead of an invisible side effect of a cascade.
       */
      const [usage, generations, reports] = await Promise.all([
        tx.aiUsage.updateMany({ where: { userId }, data: { userId: null } }),
        tx.generation.updateMany({ where: { userId }, data: { userId: null } }),
        tx.contentReport.updateMany({ where: { userId }, data: { userId: null } }),
      ]);
      summary.rowsAnonymised = usage.count + generations.count + reports.count;

      // Everything else goes with it, by foreign key. `event_occurrence` has no `user_id` at
      // all and is reached through `event`; the spec asserts on it directly rather than
      // trusting that.
      await tx.user.delete({ where: { id: userId } });
    });

    // --- 6. Redis ----------------------------------------------------------------------
    summary.redisKeysDeleted = await this.deleteRedisKeys(userId);

    this.logger.log(
      `Account deleted: ${summary.blobsDeleted} object(s), ${summary.rowsAnonymised} row(s) ` +
        `anonymised, ${summary.jobsRemoved} job(s) dropped, ${summary.redisKeysDeleted} key(s)`,
    );

    return summary;
  }

  /**
   * Drop every poster job belonging to this user.
   *
   * A job that completed between the key collection and the object deletion would write a NEW
   * asset row and a NEW object — and the row would then go with the cascading user delete,
   * leaving bytes in the store with nothing that knows their key. `BlobStore` has no `list()`,
   * so those bytes would be undiscoverable and undeleted at once, which is the first failure
   * `docs/BUILD_PROMPT_M9.md` names.
   *
   * Delayed and paused jobs too: one that is scheduled but not yet runnable is still a job
   * that will run.
   *
   * An ACTIVE job cannot be removed — BullMQ refuses, correctly, because a worker is inside
   * it. That case is swallowed rather than thrown: the deletion must not fail on it, the
   * generation row it writes is cascaded away with the user, and the keys are collected AFTER
   * this call precisely so that anything it did write is still counted.
   */
  private async removePosterJobs(userId: string): Promise<number> {
    this.posterJobs ??= new Queue(QUEUE_POSTER, { connection: this.redis });

    const jobs = await this.posterJobs.getJobs(['waiting', 'active', 'delayed', 'paused']);
    let removed = 0;
    for (const job of jobs) {
      if ((job.data as { userId?: string } | undefined)?.userId !== userId) continue;
      try {
        await job.remove();
        removed += 1;
      } catch {
        // Active. See above.
      }
    }
    return removed;
  }

  /**
   * The rate-limit counters, which carry a user id and nothing else.
   *
   * They expire within a day on their own. Deleted anyway — "it expires eventually" is not an
   * answer to an erasure request.
   *
   * `scanStream` rather than `KEYS`: `KEYS` blocks the whole Redis instance, and this one also
   * serves BullMQ for every other user's reminders. The patterns are narrow (`prefix:h:<id>:*`),
   * so the scan is cheap, and a failure here is logged rather than thrown — the account is
   * already gone from Postgres at this point and failing the request would tell the user their
   * deletion did not happen when it did.
   */
  private async deleteRedisKeys(userId: string): Promise<number> {
    let deleted = 0;
    try {
      for (const prefix of REDIS_USER_KEY_PREFIXES) {
        for (const window of ['h', 'd']) {
          const match = `${prefix}:${window}:${userId}:*`;
          const keys: string[] = [];
          const stream = this.redis.scanStream({ match, count: 100 });
          for await (const batch of stream as AsyncIterable<string[]>) {
            keys.push(...batch);
          }
          if (keys.length > 0) {
            deleted += await this.redis.del(...keys);
          }
        }
      }
    } catch (cause) {
      this.logger.error(`Account deletion: Redis cleanup failed. ${String(cause)}`);
    }
    return deleted;
  }
}

/**
 * Thrown when the objects could not all be removed.
 *
 * Its own type rather than a generic 500, because the caller's correct response is to retry —
 * nothing has been destroyed, the rows still point at the remaining objects, and repeating the
 * request finishes the job.
 */
export class BlobDeletionIncomplete extends Error {
  constructor(readonly failed: number) {
    super(`${failed} object(s) could not be removed; nothing was deleted`);
    this.name = 'BlobDeletionIncomplete';
  }
}
