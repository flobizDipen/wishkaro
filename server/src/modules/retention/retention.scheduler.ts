import { Inject, Injectable, Logger, OnApplicationShutdown, OnModuleInit } from '@nestjs/common';
import { Queue, Worker } from 'bullmq';
import type IORedis from 'ioredis';
import { QUEUE_RETENTION, REDIS_CONNECTION } from '../../queue/queue.constants';
import { RetentionService } from './retention.service';

/**
 * §12: *"Retention limits enforced by a **scheduled job**, not just stated in policy."*
 *
 * The same shape as `RemindersScheduler` — a repeatable job on `upsertJobScheduler`, which is
 * idempotent on its id so a restart does not stack duplicates, and a worker on its own
 * duplicated connection because workers issue blocking Redis commands. That file is the
 * pattern; this is not a new one.
 *
 * **Daily rather than every ten minutes.** The reminder materialiser runs often because a
 * newly added birthday should be scheduled while you are still looking at the screen. Nothing
 * here is urgent in that way: an asset one hour past 90 days is not a different asset from one
 * an hour short of it, and a sweep is the one job in this app that deletes things. Running it
 * rarely means a bug in it does bounded damage before anybody notices.
 */
const SWEEP_EVERY_MS = 24 * 60 * 60 * 1000;

@Injectable()
export class RetentionScheduler implements OnModuleInit, OnApplicationShutdown {
  private readonly logger = new Logger(RetentionScheduler.name);
  private queue: Queue | null = null;
  private worker: Worker | null = null;

  constructor(
    @Inject(REDIS_CONNECTION) private readonly redis: IORedis,
    private readonly retention: RetentionService,
  ) {}

  async onModuleInit(): Promise<void> {
    const queue = new Queue(QUEUE_RETENTION, { connection: this.redis });
    this.queue = queue;

    await queue.upsertJobScheduler(
      `${QUEUE_RETENTION}:every-${SWEEP_EVERY_MS}ms`,
      { every: SWEEP_EVERY_MS },
      { name: QUEUE_RETENTION, opts: { removeOnComplete: 20, removeOnFail: 50 } },
    );

    this.worker = new Worker(
      QUEUE_RETENTION,
      async () => {
        const summary = await this.retention.sweep();

        /**
         * **Counts and durations. Nothing else, ever.**
         *
         * §11 and the build prompt: "Nothing in a log, ever. Not an export path, not a
         * deletion confirmation, not a filename." There is no key here, no asset id, no user
         * id and no person — and a sweep that logged nothing at all would be unoperable, so
         * this is the line that answers "what did it do last night" without writing anybody's
         * data down on the night it is being destroyed. docs/M9_PLAN.md C7.
         *
         * Silent on a nothing-happened night, like the materialiser next door, so the log is
         * a record of actions rather than of ticks.
         */
        if (summary.warned || summary.purged || summary.failed) {
          this.logger.log(
            `Retention: considered ${summary.considered}, warned ${summary.warned}, ` +
              `purged ${summary.purged} (${summary.purgedPhotos} photo, ${summary.purgedPosters} poster), ` +
              `failed ${summary.failed}, ${summary.durationMs}ms`,
          );
        }
      },
      { connection: this.redis.duplicate(), concurrency: 1 },
    );

    this.worker.on('failed', (job, err) =>
      this.logger.error(`Retention job ${job?.id} failed: ${err.message}`),
    );

    this.logger.log('Retention sweep scheduled');
  }

  async onApplicationShutdown(): Promise<void> {
    await this.worker?.close();
    await this.queue?.close();
  }
}
