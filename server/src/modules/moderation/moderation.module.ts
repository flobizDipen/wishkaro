import { Global, Inject, Logger, Module, type OnModuleInit } from '@nestjs/common';
import { APP_ENV, type AppEnv } from '../../config/env';
import { FakeModerationProvider } from './fake.moderation-provider';
import { MODERATION_PROVIDER, type ModerationProvider } from './moderation-provider';
import { ModerationService } from './moderation.service';
import { OpenAiModerationProvider } from './openai.moderation-provider';
import { ReportsController } from './reports.controller';
import { ReportsService } from './reports.service';

/**
 * §10 rule 7, for **both** surfaces.
 *
 * `@Global`, because posters and wish drafts both need it and a second instance choosing a
 * different provider would make "was this moderated" depend on which module asked.
 */
@Global()
@Module({
  controllers: [ReportsController],
  providers: [
    ModerationService,
    ReportsService,
    FakeModerationProvider,
    {
      provide: MODERATION_PROVIDER,
      inject: [APP_ENV, FakeModerationProvider],
      /**
       * Chosen by NAME, like every other provider in this app, and `env.ts` has already
       * refused to boot if `openai` was named without a key.
       */
      useFactory: (env: AppEnv, fake: FakeModerationProvider): ModerationProvider => {
        switch (env.MODERATION_PROVIDER) {
          case 'openai':
            return new OpenAiModerationProvider(env.OPENAI_API_KEY!);
          case 'fake':
          default:
            return fake;
        }
      },
    },
  ],
  exports: [ModerationService],
})
export class ModerationModule implements OnModuleInit {
  private readonly logger = new Logger(ModerationModule.name);

  constructor(@Inject(APP_ENV) private readonly env: AppEnv) {}

  /**
   * Say at boot that nothing is being classified.
   *
   * §10 rule 7 is a HARD rule and this deployment does not meet it in substance when the
   * fake is in place — the deterministic input rules run, a classifier does not. A silent
   * fake here would be a deployment that believes it moderates. `M5_VERIFY.md` §4.6 records
   * rule 7 as unmet; this line is what keeps that true in the logs rather than only in a
   * document.
   */
  onModuleInit(): void {
    if (this.env.MODERATION_PROVIDER === 'fake') {
      this.logger.warn(
        'MODERATION_PROVIDER=fake. The deterministic input rules run; NO classifier does. ' +
          'Every generation records moderation=UNCHECKED, which is honest and is not a pass. ' +
          'REQUIREMENTS.md §10 rule 7 is structurally met and not actually exercised.',
      );
      return;
    }
    this.logger.log(`Moderation is live on ${this.env.MODERATION_PROVIDER}.`);
  }
}
