import { Module } from '@nestjs/common';
import { AccountController } from './account.controller';
import { AccountDeletionService } from './account-deletion.service';
import { ExportService } from './export.service';

/**
 * FR-SET-03 (export) and FR-SET-04 (account deletion). §9 has listed both endpoints since v1
 * and neither existed before M9.
 *
 * **No imports.** Account deletion has to drop a user's in-flight poster jobs before it counts
 * their blob keys, and the obvious way to reach them — importing `PostersModule` for
 * `PosterQueue` — drags the entire AI stack into this module's graph: `PostersModule` imports
 * `WishesModule`, which brings the LLM provider, the ledger, the spend cap and the rate
 * limiters. None of that is needed to delete an account, and all of it would then have to boot
 * before a deletion could be served.
 *
 * `AccountDeletionService` opens its own BullMQ `Queue` on the shared Redis connection
 * instead. The queue NAME is the contract (`QUEUE_POSTER`) and it is already a shared
 * constant; a second client handle over the same Redis is what any admin tool would do.
 *
 * So everything comes from the three `@Global` modules: `PrismaModule`, `MediaModule`
 * (`BLOB_STORE`) and `QueueModule` (`REDIS_CONNECTION`).
 */
@Module({
  controllers: [AccountController],
  providers: [ExportService, AccountDeletionService],
  exports: [ExportService, AccountDeletionService],
})
export class AccountModule {}
