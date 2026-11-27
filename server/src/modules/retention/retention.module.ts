import { Module } from '@nestjs/common';
import { RetentionController } from './retention.controller';
import { RetentionScheduler } from './retention.scheduler';
import { RetentionService } from './retention.service';

/**
 * FR-SET-06 and §12's "retention limits enforced by a scheduled job, not just stated in
 * policy".
 *
 * No imports: `MediaModule` is `@Global` and supplies `BLOB_STORE`, `PrismaModule` is global,
 * and `QueueModule` is global for the Redis connection. Nothing here needs the AI stack — this
 * is the one part of M9 that touches media without ever generating any.
 *
 * `RetentionService` is exported because M9's account deletion needs the same blob-before-row
 * ordering for a different reason, and two implementations of "remove a user's bytes safely"
 * is how the two drift apart.
 */
@Module({
  controllers: [RetentionController],
  providers: [RetentionService, RetentionScheduler],
  exports: [RetentionService],
})
export class RetentionModule {}
