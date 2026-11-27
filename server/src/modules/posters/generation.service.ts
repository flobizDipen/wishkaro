import { Injectable, Logger } from '@nestjs/common';
import { Prisma, type GenerationStatus, type ModerationVerdict } from '@prisma/client';
import { PrismaService } from '../../prisma/prisma.service';

/**
 * The `generation` record REQUIREMENTS.md §8 asks for, and §10 rule 2 requires around every
 * call.
 *
 * ## Why this is not `AiUsage`
 *
 * Raised rather than quietly resolved in `docs/M6_PLAN.md` §3.3, and the schema comment on
 * `model Generation` carries the argument. In short: the two have different cardinalities.
 * `AiUsage` is one row **per model call** — that is its contract, and `SpendCapService` sums
 * it. A generation is one user-visible **job** making zero model calls on a cache hit, one
 * on a copy-only path and two on a full miss.
 *
 * One table cannot mean both without one meaning becoming a lie, and definition-of-done
 * item 5 requires the expensive one to stay true: a cache hit must write "no ledger row for
 * a generation that did not happen". So a hit writes a `Generation` with `cacheHit = true`
 * and **no** `AiUsage` row at all, and the cache-hit rate — §13's health metric, TBD-3's
 * input, FR-MON-03a's margin guard — is one query over this table.
 */

export const FEATURE_POSTER = 'poster';

export interface GenerationOutcome {
  status: GenerationStatus;
  cacheHit: boolean;
  /** A rail bit — the spend cap, the per-user limit, or a provider failure. Not a hit. */
  degraded: boolean;
  moderation: ModerationVerdict;
  promptHash: string | null;
  promptVersion: string | null;
  errorCode: string | null;
}

@Injectable()
export class GenerationService {
  private readonly logger = new Logger(GenerationService.name);

  constructor(private readonly prisma: PrismaService) {}

  /** Accepted and queued. The app shows progress from here (FR-POS-09). */
  async open(userId: string, personId: string): Promise<{ id: string; createdAt: Date }> {
    return this.prisma.generation.create({
      data: { userId, personId, feature: FEATURE_POSTER, status: 'QUEUED' },
      select: { id: true, createdAt: true },
    });
  }

  async markRunning(id: string): Promise<void> {
    await this.prisma.generation.update({ where: { id }, data: { status: 'RUNNING' } });
  }

  /**
   * Close the job, with the latency the USER experienced.
   *
   * Measured from `createdAt` rather than from when the worker picked the job up, so it
   * includes the queue wait. FR-POS-09's targets — p95 under 15s on a miss, under 2s on a
   * hit — are about somebody watching a spinner, and a number that excluded the wait would
   * be the one measurement that cannot go wrong no matter how long the queue is.
   */
  async close(id: string, outcome: GenerationOutcome, startedAt: Date): Promise<void> {
    const completedAt = new Date();

    /**
     * `cost_inr` is summed from this job's own `ai_usage` rows rather than passed in.
     *
     * Denormalising from the rows means the two can never disagree, and on a cache hit the
     * sum is a genuine zero — no call was made — rather than a default that happens to look
     * like one.
     */
    const spent = await this.prisma.aiUsage.aggregate({
      where: { generationId: id },
      _sum: { costInr: true },
    });

    await this.prisma.generation.update({
      where: { id },
      data: {
        status: outcome.status,
        cacheHit: outcome.cacheHit,
        degraded: outcome.degraded,
        moderation: outcome.moderation,
        promptHash: outcome.promptHash,
        promptVersion: outcome.promptVersion,
        errorCode: outcome.errorCode,
        costInr: spent._sum.costInr ?? new Prisma.Decimal(0),
        latencyMs: completedAt.getTime() - startedAt.getTime(),
        completedAt,
      },
    });
  }

  /**
   * Close a job that threw.
   *
   * Separate from `close` and deliberately forgiving: this runs in a `catch`, so a second
   * failure here would leave the row at RUNNING for ever and the app spinning on it. The
   * error is logged rather than rethrown.
   */
  async fail(id: string, errorCode: string, startedAt: Date): Promise<void> {
    try {
      await this.close(
        id,
        {
          status: 'FAILED',
          cacheHit: false,
          degraded: false,
          moderation: 'UNCHECKED',
          promptHash: null,
          promptVersion: null,
          errorCode,
        },
        startedAt,
      );
    } catch (cause) {
      this.logger.error(`Could not mark generation ${id} failed: ${String(cause)}`);
    }
  }
}
