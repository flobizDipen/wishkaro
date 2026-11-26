import { Inject, Logger, Module, type OnModuleInit } from '@nestjs/common';
import { APP_ENV, type AppEnv } from '../../config/env';
import { DraftingService } from './drafting.service';
import { AiUsageService } from './ledger/ai-usage.service';
import { FakeLlmProvider } from './llm/fake.provider';
import { LLM_PROVIDER, type LlmProvider } from './llm/llm-provider';
import { DraftRateLimitService } from './rate-limit/draft-rate-limit.service';
import { WishHistoryService } from './wish-history.service';
import { WishesController } from './wishes.controller';

/**
 * Wish drafting (FR-WSH-01..09), its cost ledger (FR-POS-10) and its rate limit (FR-WSH-08).
 *
 * The provider is chosen ONCE, at boot, from whether a key exists. Not per request: a
 * per-request choice would mean the ledger's `provider` column and the response's `source`
 * could disagree within one deployment, and "which half of today ran on a model" is not a
 * question anybody should have to ask of their own cost table.
 */
@Module({
  controllers: [WishesController],
  providers: [
    DraftingService,
    WishHistoryService,
    AiUsageService,
    DraftRateLimitService,
    FakeLlmProvider,
    {
      provide: LLM_PROVIDER,
      inject: [FakeLlmProvider],
      // ONE branch short of finished, and deliberately not written as a branch yet.
      //
      // The Anthropic adapter lands in step 4 of docs/M5_PLAN.md §7, which needs a key this
      // machine does not have. A `if (env.ANTHROPIC_API_KEY) return real` written now would
      // be a branch nothing can take and nothing can test — which is how a dead path stays
      // in a codebase until the day it runs.
      //
      // Until then, and in CI, every mechanism around the call still runs against the fake:
      // the ledger, the rate limit, the tone restriction and all four error shapes.
      useFactory: (fake: FakeLlmProvider): LlmProvider => fake,
    },
  ],
  exports: [WishHistoryService],
})
export class WishesModule implements OnModuleInit {
  private readonly logger = new Logger(WishesModule.name);

  constructor(@Inject(APP_ENV) private readonly env: AppEnv) {}

  /**
   * Say so, loudly, when there is no key.
   *
   * `ANTHROPIC_API_KEY` is optional in `env.ts` — unlike the Firebase credentials, which
   * refuse to boot — because requiring it would stop CI standing the app up at all and would
   * make `npm run start:dev` impossible for anyone who has not been handed one, for a feature
   * that is one endpoint out of fifteen.
   *
   * The price of that is a deployment that silently serves templates while believing it is
   * serving drafts. This line is what stops that being silent, and `M5_VERIFY.md` records
   * that the check is a log line rather than a boot failure.
   */
  onModuleInit(): void {
    if (!this.env.ANTHROPIC_API_KEY) {
      this.logger.warn(
        'ANTHROPIC_API_KEY is not set. Wish drafting will serve the fake provider — ' +
          'no model is being called and every draft is recorded as a TEMPLATE.',
      );
    }
  }
}
