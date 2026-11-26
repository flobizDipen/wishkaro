import { Global, Inject, Logger, Module, type OnModuleInit } from '@nestjs/common';
import { APP_ENV, type AppEnv } from '../../config/env';
import { BLOB_STORE, type BlobStore } from './blob-store';
import { LocalBlobStore } from './local-blob-store';
import { MediaController } from './media.controller';

/**
 * Where bytes live (FR-POS-01's compositing output, FR-POS-03's cache, FR-PPL-04's photo).
 *
 * `@Global` because the poster pipeline, the theme cache and the photo upload all need the
 * same store and none of them should be able to pick a different one.
 */
@Global()
@Module({
  controllers: [MediaController],
  providers: [
    {
      provide: BLOB_STORE,
      inject: [APP_ENV],
      /**
       * Chosen BY NAME, not by which credentials happen to exist — the same decision
       * `WishesModule` took for the LLM provider, for the same reason.
       *
       * The `r2` case throws rather than being stubbed. `wishes.module.ts` wrote the argument
       * down when it did this for Anthropic: "a branch written for it would be one nothing
       * can take and nothing can test — which is how a dead path survives until the day it
       * runs." An S3 adapter against credentials nobody has is type-checked fiction, and
       * writing it would pull two AWS SDK packages into a public repository to support it.
       */
      useFactory: (env: AppEnv): BlobStore => {
        switch (env.BLOB_STORE) {
          case 'r2':
            throw new Error(
              'BLOB_STORE=r2 is not implemented — the adapter waits on an R2 account, a ' +
                'bucket and credentials, none of which exist. REQUIREMENTS.md §8, §11 and ' +
                '§14 all name R2 and none of them is configured; docs/M6_PLAN.md §3.2 raises ' +
                'it. Use BLOB_STORE=local.',
            );
          case 'local':
          default:
            return new LocalBlobStore(
              env.BLOB_LOCAL_ROOT,
              // Falls back to the access secret so a dev server boots without a sixth secret
              // to generate. Production sets its own; `.env.example` says why.
              env.BLOB_SIGNING_SECRET ?? env.JWT_ACCESS_SECRET,
            );
        }
      },
    },
  ],
  exports: [BLOB_STORE],
})
export class MediaModule implements OnModuleInit {
  private readonly logger = new Logger(MediaModule.name);

  constructor(@Inject(BLOB_STORE) private readonly store: BlobStore) {}

  /**
   * Say at boot whether the store survives a deploy.
   *
   * FR-POS-03's economics assume artwork is "generated once and served thousands of times".
   * On an ephemeral filesystem it is generated once per deploy, which is a different product
   * with a different bill — and the symptom is a cache-hit rate that quietly resets rather
   * than anything breaking. One log line is what stops that being discovered from a graph.
   */
  onModuleInit(): void {
    if (this.store instanceof LocalBlobStore) {
      this.store.logWhereItIs();
      return;
    }
    this.logger.log(`Blob store is ${this.store.storeName} (durable: ${this.store.isDurable}).`);
  }
}
