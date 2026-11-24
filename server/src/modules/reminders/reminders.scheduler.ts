import { Inject, Injectable, Logger, OnApplicationShutdown, OnModuleInit } from '@nestjs/common';
import { Queue, Worker } from 'bullmq';
import type IORedis from 'ioredis';
import { QUEUE_MATERIALISE, QUEUE_SEND, REDIS_CONNECTION } from '../../queue/queue.constants';
import { RemindersService } from './reminders.service';

/**
 * How often the materialiser runs. Nightly is enough in principle (FR-REM-01), but at ten
 * minutes a newly added birthday is scheduled while you are still looking at the screen — and
 * M0's whole purpose is a fast feedback loop. M2 can slow this down and add the ladder.
 */
const MATERIALISE_EVERY_MS = 10 * 60 * 1000;

/** The send loop. Thirty seconds keeps the two-minute self-test honest. */
const SEND_EVERY_MS = 30 * 1000;

@Injectable()
export class RemindersScheduler implements OnModuleInit, OnApplicationShutdown {
  private readonly logger = new Logger(RemindersScheduler.name);
  private readonly queues: Queue[] = [];
  private readonly workers: Worker[] = [];

  constructor(
    @Inject(REDIS_CONNECTION) private readonly redis: IORedis,
    private readonly reminders: RemindersService,
  ) {}

  async onModuleInit(): Promise<void> {
    await this.startQueue(QUEUE_MATERIALISE, MATERIALISE_EVERY_MS, async () => {
      const summary = await this.reminders.materialise();
      if (summary.remindersCreated || summary.remindersRescheduled || summary.remindersSuppressed) {
        this.logger.log(
          `Materialise: ${summary.eventsConsidered} event(s), ` +
            `${summary.remindersCreated} created, ${summary.remindersRescheduled} rescheduled, ` +
            `${summary.remindersSuppressed} suppressed`,
        );
      }
    });

    await this.startQueue(QUEUE_SEND, SEND_EVERY_MS, async () => {
      const summary = await this.reminders.sendDue();
      if (summary.claimed > 0) {
        this.logger.log(
          `Send: claimed ${summary.claimed}, delivered ${summary.delivered}, failed ${summary.failed}`,
        );
      }
    });

    this.logger.log('Reminder queues started');
  }

  /** Runs the materialiser for one user now, instead of waiting for the next tick. */
  async materialiseNow(userId: string): Promise<void> {
    try {
      await this.reminders.materialise({ userId });
    } catch (cause) {
      this.logger.error(
        `Immediate materialise for ${userId} failed: ${cause instanceof Error ? cause.message : cause}`,
      );
    }
  }

  private async startQueue(name: string, everyMs: number, run: () => Promise<void>): Promise<void> {
    const queue = new Queue(name, { connection: this.redis });
    this.queues.push(queue);

    // A job scheduler is idempotent on its id, so a restart does not stack duplicates.
    await queue.upsertJobScheduler(
      `${name}:every-${everyMs}ms`,
      { every: everyMs },
      { name, opts: { removeOnComplete: 20, removeOnFail: 50 } },
    );

    // Workers issue blocking Redis commands, so each one needs a connection of its own.
    const worker = new Worker(name, async () => run(), {
      connection: this.redis.duplicate(),
      concurrency: 1,
    });
    worker.on('failed', (job, err) => this.logger.error(`${name} job ${job?.id} failed: ${err.message}`));
    this.workers.push(worker);
  }

  async onApplicationShutdown(): Promise<void> {
    await Promise.all(this.workers.map((w) => w.close()));
    await Promise.all(this.queues.map((q) => q.close()));
  }
}
