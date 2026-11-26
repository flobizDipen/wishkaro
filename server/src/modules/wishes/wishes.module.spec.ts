import { Global, Module } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { APP_ENV, type AppEnv } from '../../config/env';
import { PrismaService } from '../../prisma/prisma.service';
import { REDIS_CONNECTION } from '../../queue/queue.constants';
import { FakeLlmProvider } from './llm/fake.provider';
import { LLM_PROVIDER, type LlmProvider } from './llm/llm-provider';
import { OpenAiProvider } from './llm/openai.provider';
import { ModerationService } from '../moderation/moderation.service';
import { WishesModule } from './wishes.module';

/**
 * Which provider the DI graph actually hands out.
 *
 * This is the server's counterpart to `ModulesTest.kt`, which `MILESTONE_CHECKLIST.md` §D
 * requires for a Koin graph change and which the server has never had one of. The factory in
 * `wishes.module.ts` is a `switch` on an env value, and a `switch` that returns the wrong
 * branch is not a crash — it is a deployment quietly serving templates while the logs say it
 * is live, or a bill arriving for a provider nobody chose.
 *
 * It resolves the real token with the real factory. Prisma and Redis are stubbed because
 * neither is touched by the thing under test; the provider is not.
 */

const baseEnv = {
  WISH_PROVIDER: 'fake',
  OPENAI_API_KEY: undefined,
  OPENAI_MODEL: 'gpt-6-luna',
  OPENAI_EFFORT: 'low',
  ANTHROPIC_MODEL: 'claude-opus-5-5',
  USD_INR_RATE: 89,
  WISH_DRAFT_LIMIT_HOUR: 15,
  WISH_DRAFT_LIMIT_DAY: 50,
} as unknown as AppEnv;

/**
 * Stands in for the three @Global modules the app boots with — AppConfigModule, PrismaModule
 * and QueueModule.
 *
 * They are stubbed because none of them is the thing under test: the factory reads APP_ENV
 * and nothing else. The provider it returns is NOT stubbed, which is the whole point.
 */
async function providerFor(over: Partial<Record<string, unknown>>): Promise<LlmProvider> {
  @Global()
  @Module({
    providers: [
      { provide: APP_ENV, useValue: { ...baseEnv, ...over } },
      { provide: PrismaService, useValue: {} },
      { provide: REDIS_CONNECTION, useValue: {} },
      // M6: ModerationModule is @Global in the app, so DraftingService resolves it from
      // outside WishesModule. Stubbed for the same reason the other three are — the thing
      // under test is which LLM provider the factory returns, and nothing else.
      { provide: ModerationService, useValue: {} },
    ],
    exports: [APP_ENV, PrismaService, REDIS_CONNECTION, ModerationService],
  })
  class StubGlobals {}

  const moduleRef = await Test.createTestingModule({
    imports: [StubGlobals, WishesModule],
  }).compile();
  return moduleRef.get<LlmProvider>(LLM_PROVIDER);
}

describe('WishesModule — which provider the graph hands out', () => {
  it('hands out the fake by default', async () => {
    const provider = await providerFor({});
    expect(provider).toBeInstanceOf(FakeLlmProvider);
    // And it says so honestly, which is what keeps `source` on a wish truthful.
    expect(provider.isLive).toBe(false);
    expect(provider.providerName).toBe('fake');
  });

  it('hands out OpenAI when asked, carrying the configured model', async () => {
    const provider = await providerFor({ WISH_PROVIDER: 'openai', OPENAI_API_KEY: 'sk-test' });
    expect(provider).toBeInstanceOf(OpenAiProvider);
    expect(provider.providerName).toBe('openai');
    expect(provider.isLive).toBe(true);
    // The model reaches the provider rather than being read from env by the caller — which
    // is what stopped failed OpenAI calls being logged against claude-opus-5-5.
    expect(provider.modelName).toBe('gpt-6-luna');
  });

  it('fails LOUDLY on a provider whose adapter is not written', async () => {
    // `anthropic` parses in env.ts and has no adapter yet. A silent fall back to the fake
    // here would be a deployment that was told to call Claude and serves templates instead.
    await expect(
      providerFor({ WISH_PROVIDER: 'anthropic', ANTHROPIC_API_KEY: 'sk-test' }),
    ).rejects.toThrow(/not implemented/i);
  });

  it('every provider the graph can hand out reports a model the ledger can price', async () => {
    // A provider whose model is absent from pricing.ts writes rows of zero cost, which read
    // as free drafts. Cheap to assert here, and it goes red the day somebody adds a fourth.
    const { isPriced } = await import('./llm/pricing');
    for (const over of [{}, { WISH_PROVIDER: 'openai', OPENAI_API_KEY: 'sk-test' }]) {
      const provider = await providerFor(over);
      expect(isPriced(provider.modelName)).toBe(true);
    }
  });
});
