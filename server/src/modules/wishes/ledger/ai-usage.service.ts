import { Inject, Injectable, Logger } from '@nestjs/common';
import { Prisma } from '@prisma/client';
import { APP_ENV, type AppEnv } from '../../../config/env';
import { PrismaService } from '../../../prisma/prisma.service';
import { PRICING_VERSION, costOf, isPriced } from '../llm/pricing';
import { PROMPT_VERSION } from '../prompt-builder';
import type { DraftOutcome, TokenUsage } from '../llm/llm-provider';

/**
 * The cost ledger.
 *
 * **REQUIREMENTS.md §10 rule 2** is the mandate: "every call is wrapped in a `generation`
 * record with measured INR cost." That rule is in the AI section and is cross-feature, so
 * a ledger under `wishes/` that is feature-TAGGED rather than wish-shaped is exactly what it
 * asks for — M6 writes posters to this table and M8 writes gift ideas.
 *
 * docs/M5_PLAN.md §6.3 raised FR-POS-10 filing a ledger requirement under posters as an open
 * question. It was not one: §10 rule 2 already required this, cross-feature, and M5's plan
 * simply never cited §10. FR-POS-10 is the poster-specific restatement, not the origin.
 *
 * Without it you cannot answer "what does a user cost per year", which is the question M7's
 * pricing is built on.
 */

export const FEATURE_WISH_DRAFT = 'wish_draft';

export interface LedgerEntry {
  userId: string;
  feature: string;
  provider: string;
  model: string;
  outcome: DraftOutcome;
  usage: TokenUsage;
}

/** Thrown when the row cannot be written. Deliberately fatal — see [record]. */
export class LedgerWriteError extends Error {
  constructor(cause: unknown) {
    super(`Could not write the AI cost ledger: ${String(cause)}`);
    this.name = 'LedgerWriteError';
  }
}

@Injectable()
export class AiUsageService {
  private readonly logger = new Logger(AiUsageService.name);

  constructor(
    private readonly prisma: PrismaService,
    @Inject(APP_ENV) private readonly env: AppEnv,
  ) {}

  /**
   * One row, every call, every outcome.
   *
   * **A row that cannot be written fails the request.** docs/BUILD_PROMPT_M5.md: "Every model
   * call writes a ledger row. A call that cannot write one is a bug, not an optimisation."
   * The tempting alternative — log the failure, serve the draft — is how a cost ledger
   * quietly becomes a sample, and a sample cannot answer the question the table exists for.
   * Nothing in this app is more important than knowing what it costs to run.
   *
   * Nothing here touches the message text. §11 bans PII in logs and a drafted wish is prose
   * about a named person; the ledger records what it cost, never what it said.
   */
  async record(entry: LedgerEntry): Promise<void> {
    const rate = this.env.USD_INR_RATE;
    const cost = costOf(entry.model, entry.usage, rate);

    if (!isPriced(entry.model)) {
      // A server-side fallback can land on a model this table has never priced. The row is
      // still written — with a zero that is visibly a gap rather than a guess dressed up as
      // a measurement — and this line is how anybody finds out.
      this.logger.warn(
        `No price for model "${entry.model}"; its ledger rows will read 0. Add it to pricing.ts.`,
      );
    }

    try {
      await this.prisma.aiUsage.create({
        data: {
          userId: entry.userId,
          feature: entry.feature,
          provider: entry.provider,
          model: entry.model,
          outcome: entry.outcome,
          inputTokens: entry.usage.inputTokens,
          outputTokens: entry.usage.outputTokens,
          cacheReadTokens: entry.usage.cacheReadTokens,
          cacheWriteTokens: entry.usage.cacheWriteTokens,
          costUsd: new Prisma.Decimal(cost.usd),
          usdInrRate: new Prisma.Decimal(rate),
          costInr: new Prisma.Decimal(cost.inr),
          pricingVersion: PRICING_VERSION,
          // §10 rule 4. Which words produced this, so a hand-review of the output can be
          // tied to a revision and re-run when the prompt changes.
          promptVersion: PROMPT_VERSION,
        },
      });
    } catch (cause) {
      this.logger.error(`Ledger write failed for ${entry.feature}/${entry.model}`);
      throw new LedgerWriteError(cause);
    }
  }

  /**
   * What one user has cost, over a window. The M7 question, answerable from day one.
   *
   * Returns INR as a number rather than a Decimal: this is a report, not a balance, and
   * nothing downstream does arithmetic on it that a float would spoil.
   */
  async totalFor(userId: string, since: Date): Promise<{ calls: number; inr: number }> {
    const rows = await this.prisma.aiUsage.aggregate({
      where: { userId, createdAt: { gte: since } },
      _count: { _all: true },
      _sum: { costInr: true },
    });
    return {
      calls: rows._count._all,
      inr: Number(rows._sum.costInr ?? 0),
    };
  }
}
