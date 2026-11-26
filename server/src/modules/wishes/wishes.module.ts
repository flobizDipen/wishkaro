import { Inject, Logger, Module, type OnModuleInit } from '@nestjs/common';
import { APP_ENV, type AppEnv } from '../../config/env';
import { DraftingService } from './drafting.service';
import { AiUsageService } from './ledger/ai-usage.service';
import { SpendCapService } from './ledger/spend-cap.service';
import { FakeLlmProvider } from './llm/fake.provider';
import { LLM_PROVIDER, type LlmProvider } from './llm/llm-provider';
import { OpenAiProvider } from './llm/openai.provider';
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
    SpendCapService,
    DraftRateLimitService,
    FakeLlmProvider,
    {
      provide: LLM_PROVIDER,
      inject: [APP_ENV, FakeLlmProvider],
      /**
       * Chosen by NAME, not by which key happens to be present.
       *
       * `env.ts` has already refused to boot if the named provider has no key, so by the
       * time this runs the choice is both explicit and satisfiable. That ordering is the
       * point: pasting a key into `.env` to try something must not silently change what
       * every draft costs and what `source` the history records.
       *
       * The Anthropic case is still absent rather than stubbed. There is no Anthropic key on
       * the build machine, so a branch written for it would be one nothing can take and
       * nothing can test — which is how a dead path survives until the day it runs. `env.ts`
       * accepts `WISH_PROVIDER=anthropic` and this throws on it, by name, which is a better
       * failure than a silent fall back to the fake.
       */
      useFactory: (env: AppEnv, fake: FakeLlmProvider): LlmProvider => {
        switch (env.WISH_PROVIDER) {
          case 'openai':
            // Non-null: env.ts refuses to boot without it.
            return new OpenAiProvider(env.OPENAI_API_KEY!, env.OPENAI_MODEL, env.OPENAI_EFFORT);
          case 'anthropic':
            throw new Error(
              'WISH_PROVIDER=anthropic is not implemented yet — the adapter waits on a key. ' +
                'Use WISH_PROVIDER=openai or WISH_PROVIDER=fake.',
            );
          case 'fake':
          default:
            return fake;
        }
      },
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
    if (this.env.WISH_PROVIDER === 'fake') {
      this.logger.warn(
        'WISH_PROVIDER=fake. Wish drafting is serving the deterministic fake — no model is ' +
          'being called and every draft is recorded as a TEMPLATE, not as AI.',
      );
      return;
    }
    this.logger.log(
      `Wish drafting is live on ${this.env.WISH_PROVIDER} ` +
        `(${this.env.WISH_PROVIDER === 'openai' ? this.env.OPENAI_MODEL : this.env.ANTHROPIC_MODEL}). ` +
        'Every draft now costs money and writes a ledger row.',
    );
  }
}
