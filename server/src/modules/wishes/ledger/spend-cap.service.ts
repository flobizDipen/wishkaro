import { Inject, Injectable, Logger } from '@nestjs/common';
import { APP_ENV, type AppEnv } from '../../../config/env';
import { PrismaService } from '../../../prisma/prisma.service';

/**
 * The global daily spend cap (REQUIREMENTS.md §10 rule 6).
 *
 * The rule is "per-user rate limits, **a global daily spend cap**, and automatic degradation
 * to the pre-approved theme library when the cap is hit." M5 shipped the first of those three
 * and not the other two: `WISH_DRAFT_LIMIT_HOUR` and `WISH_DRAFT_LIMIT_DAY` bound what ONE
 * user can spend and say nothing about the total.
 *
 * Why that gap is not hypothetical. This repository is public, the endpoint sits behind a
 * token anybody can mint by signing in with Google, and every call bills **Dipen's personal
 * card**. Fifteen drafts an hour each is a sensible fair-use limit and no limit at all on a
 * thousand accounts.
 *
 * ## What "degradation" means here
 *
 * M5 has no theme library to degrade to — that is M6. Its equivalent is the path the composer
 * already takes when it is offline or the model declines: the phone's own template
 * (`docs/M5_PLAN.md` §4.2). So hitting the cap produces the same 503 the other two failures
 * produce, and the user gets editable words rather than an error. Degradation is a behaviour
 * the app already has; the cap is what triggers it.
 *
 * The user is deliberately NOT told that a spend cap was hit. It is our budget, not theirs,
 * and "we couldn't write this one" is both true and the right amount of detail.
 */
@Injectable()
export class SpendCapService {
  private readonly logger = new Logger(SpendCapService.name);

  constructor(
    private readonly prisma: PrismaService,
    @Inject(APP_ENV) private readonly env: AppEnv,
  ) {}

  /**
   * True when today's total AI spend is at or over the cap.
   *
   * Summed from the ledger rather than from a counter, because the ledger is already the
   * record of what was spent and a second source of truth for the same number is a second
   * thing to get wrong.
   *
   * **It needs `@@index([createdAt])`, and an earlier version of this comment claimed a
   * performance property the schema did not support.** `ai_usage` originally had only
   * `(userId, createdAt)` and `(feature, createdAt)`; `created_at` is the TRAILING column in
   * both, so neither can range-scan on it. Measured on 200,000 rows:
   *
   * | | buffers | time |
   * |---|---|---|
   * | before the index | 1,465 to return 227 rows | 7.77 ms |
   * | after it | 226 | 0.43 ms |
   *
   * The work was proportional to every row the table had ever held, not to one day's — and
   * this runs BEFORE the per-user limiter (see `DraftingService`), so it also ran for every
   * request the limiter was about to reject. A rail meant to stop a runaway was the most
   * expensive thing in the request and got slower daily. On a public endpoint behind a token
   * anybody can mint, a cheap request that forces a full scan is also the shape somebody
   * abuses.
   *
   * The sentence that was here is the kind a reader trusts instead of checking, so the
   * numbers above are measured rather than asserted.
   *
   * **UTC days**, like the per-user limiter and for the same reason: a spend ceiling is a
   * property of our bill, not of any user's calendar, and a per-user local day would make the
   * same cap mean different things for two people.
   *
   * KNOWN RACE, stated rather than hidden: two requests that both read the sum before either
   * writes its row can both pass a cap they would jointly exceed. The overshoot is bounded by
   * the cost of the calls in flight — pennies — and closing it properly means a Redis counter
   * and a second source of truth for the day's spend. Not worth it for a rail whose job is to
   * stop a runaway, not to bill to the rupee.
   */
  async isCapped(now: Date = new Date()): Promise<boolean> {
    const spent = await this.spentToday(now);
    const cap = this.env.AI_DAILY_SPEND_CAP_INR;

    if (spent < cap) return false;

    this.logger.error(
      `AI daily spend cap reached: ₹${spent.toFixed(2)} of ₹${cap}. ` +
        'Drafting is degrading to the on-device template until UTC midnight. ' +
        'Raise AI_DAILY_SPEND_CAP_INR if this is expected.',
    );
    return true;
  }

  /**
   * Today's total, in INR, **across every feature** — and that is the intent rather than an
   * accident of the query.
   *
   * §10 rule 6 says "a global daily spend cap", and the thing being capped is the bill. M6's
   * posters and M8's gift ideas write to this same table and will land in this same total,
   * so the cap they share is the one that actually bounds what a day can cost. A per-feature
   * cap would let three features each sit just under their own ceiling and together spend
   * three times what anybody intended.
   *
   * Also the number worth putting on a dashboard.
   */
  async spentToday(now: Date = new Date()): Promise<number> {
    const startOfDay = new Date(
      Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()),
    );

    const total = await this.prisma.aiUsage.aggregate({
      where: { createdAt: { gte: startOfDay } },
      _sum: { costInr: true },
    });

    // Number, not Decimal: this is a comparison against a configured ceiling, not money
    // being moved, and nothing downstream does arithmetic a float would spoil.
    return Number(total._sum.costInr ?? 0);
  }
}
