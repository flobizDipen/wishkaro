import { Global, Module } from '@nestjs/common';
import { Test } from '@nestjs/testing';
import { APP_ENV, type AppEnv } from '../../config/env';
import { PrismaService } from '../../prisma/prisma.service';
import { REDIS_CONNECTION } from '../../queue/queue.constants';
import { BLOB_STORE } from '../media/blob-store';
import { AccountModule } from '../account/account.module';
import { ExportService } from '../account/export.service';
import { RetentionModule } from './retention.module';
import { RetentionService } from './retention.service';

/**
 * That M9's two new modules can actually be constructed.
 *
 * `MILESTONE_CHECKLIST.md` §D: *"A Koin/DI graph change is covered by `ModulesTest` — it
 * verifies constructors without instantiating."* The server's counterpart is
 * `wishes.module.spec.ts`, and M9 adds two modules to `app.module.ts` that nothing else would
 * ever construct.
 *
 * **CI does not boot this app.** It installs, generates, migrates, lints, typechecks, tests
 * and builds — `npm run build` compiles, it does not run `main.ts`. So a module that cannot
 * resolve a dependency passes every check on the pull request and fails on Railway, at boot,
 * on deploy day. That is the same gap M2 proved on the client side with 308 green tests and an
 * app that could not start.
 *
 * Both modules deliberately declare no `imports`, relying on three `@Global` modules for
 * everything — `PrismaModule`, `MediaModule` (for `BLOB_STORE`) and `QueueModule` (for
 * `REDIS_CONNECTION`). That is a convenience with a sharp edge: removing `@Global` from any of
 * them, or moving a provider out of one, breaks these two silently. This is what notices.
 */

const env = { BLOB_LOCAL_ROOT: './.storage' } as unknown as AppEnv;

/** Stands in for the @Global modules the app boots with. Nothing here is under test. */
@Global()
@Module({
  providers: [
    { provide: APP_ENV, useValue: env },
    { provide: PrismaService, useValue: {} },
    { provide: REDIS_CONNECTION, useValue: { duplicate: () => ({ on: () => undefined }) } },
    {
      provide: BLOB_STORE,
      useValue: { storeName: 'spec', isDurable: false },
    },
  ],
  exports: [APP_ENV, PrismaService, REDIS_CONNECTION, BLOB_STORE],
})
class GlobalsStub {}

async function graph(moduleUnderTest: unknown) {
  /**
   * The scheduler's `onModuleInit` opens a real BullMQ queue and a real worker, so the module
   * is COMPILED rather than INITIALISED. Compiling is what resolves every constructor, which
   * is the thing being checked; initialising would need Redis and would turn a DI test into an
   * integration test.
   */
  return Test.createTestingModule({
    imports: [GlobalsStub, moduleUnderTest as never],
  }).compile();
}

describe('M9 adds two modules to the app graph', () => {
  it('constructs RetentionService and its scheduler from the global providers alone', async () => {
    const moduleRef = await graph(RetentionModule);

    expect(moduleRef.get(RetentionService)).toBeInstanceOf(RetentionService);
  });

  it('exports RetentionService, so the deletion path can reuse the blob ordering', async () => {
    // `retention.module.ts` says why this is exported: M9's account deletion needs the same
    // bytes-before-rows ordering for a different reason, and two implementations of "remove a
    // user's bytes safely" is how the two drift apart.
    const moduleRef = await graph(RetentionModule);

    expect(moduleRef.get(RetentionService, { strict: false })).toBeDefined();
  });

  it('constructs the account module, which §9 has listed since v1 and nothing had built', async () => {
    const moduleRef = await graph(AccountModule);

    expect(moduleRef.get(ExportService)).toBeInstanceOf(ExportService);
  });
});
