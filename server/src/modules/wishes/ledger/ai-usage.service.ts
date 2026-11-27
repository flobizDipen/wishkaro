import { Inject, Injectable, Logger } from '@nestjs/common';
import { Prisma } from '@prisma/client';
import { APP_ENV, type AppEnv } from '../../../config/env';
import { PrismaService } from '../../../prisma/prisma.service';
import { PRICING_VERSION, costOf, imageCostOf, isImagePriced, isPriced } from '../llm/pricing';
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

/**
 * How an entry is priced.
 *
 * Two shapes, because M6 added a cost model this table was not built for.
 * `gpt-image-1` bills **per image**, by size and quality tier, not per token — so a poster's
 * background costs ₹1–15 where a whole wish draft costs fractions of a paisa. An image
 * priced through the token table would read ₹0 on every row, and `SpendCapService` sums
 * `cost_inr` across every feature: an unpriced image is an invisible one, and a cap that
 * cannot see the expensive half is worse than no cap, because the zero looks like it is
 * working.
 */
export type Billing =
  { kind: 'tokens' } | { kind: 'image'; quality: string; size: string; count: number };

const BY_TOKENS: Billing = { kind: 'tokens' };

export interface LedgerEntry {
  userId: string;
  feature: string;
  provider: string;
  model: string;
  outcome: DraftOutcome;
  usage: TokenUsage;
  /** Defaults to token billing, so every M5 call site is unchanged. */
  billing?: Billing;
  /**
   * Which JOB this call belonged to (M6).
   *
   * Null for a wish draft and always will be: a draft is one call and nothing else, so
   * wrapping it in a generation row would be a row per row. A poster is a job that makes
   * zero, one or two calls — see `model Generation`.
   */
  generationId?: string | null;
  /**
   * Which prompt produced it (§10 rule 4). Defaults to M5's `PROMPT_VERSION`.
   *
   * Posters pass their own — `POSTER_COPY_PROMPT_VERSION` or `POSTER_ART_PROMPT_VERSION` —
   * because a hand-review that cannot be traced to a revision stops meaning anything the
   * first time a prompt is edited, and for TBD-5 that review IS the deliverable.
   */
  promptVersion?: string;
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
    const billing = entry.billing ?? BY_TOKENS;
    const cost =
      billing.kind === 'image'
        ? imageCostOf(entry.model, billing.quality, billing.size, billing.count, rate)
        : costOf(entry.model, entry.usage, rate);

    const priced =
      billing.kind === 'image'
        ? isImagePriced(entry.model, billing.quality, billing.size)
        : isPriced(entry.model);

    if (!priced) {
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
          promptVersion: entry.promptVersion ?? PROMPT_VERSION,
          generationId: entry.generationId ?? null,
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
