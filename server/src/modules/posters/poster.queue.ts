import { Inject, Injectable, Logger, OnApplicationShutdown, OnModuleInit } from '@nestjs/common';
import { Queue, Worker } from 'bullmq';
import type IORedis from 'ioredis';
import { QUEUE_POSTER, REDIS_CONNECTION } from '../../queue/queue.constants';
import { GenerationService } from './generation.service';
import { PosterPipelineService, type PosterJob } from './poster-pipeline.service';

/**
 * FR-POS-09: "poster generation is asynchronous with a visible progress state."
 *
 * ## Why a queue rather than a long request
 *
 * A cache miss is a model call of up to fifteen seconds. Holding an HTTP request open for
 * that length puts the whole thing at the mercy of a phone changing networks — and on
 * India-first mobile data, which is what this product is for, that is not an edge case.
 * The job survives the connection; the app polls `GET /v1/ai/generation/:id`.
 *
 * ## One job, not a scheduler
 *
 * `RemindersScheduler` runs two repeating jobs on `upsertJobScheduler`. This is the other
 * shape: discrete jobs added on demand. Same connection rules — a worker issues blocking
 * Redis commands, so it gets a duplicate of the shared connection rather than sharing it.
 *
 * `attempts: 1`. **Deliberately no retry**, and this is the one place in the app where that
 * is the expensive choice rather than the cheap one: a retried poster job is a second image
 * at ₹1–15, and a bug in the pipeline that throws on every attempt would bill for each one.
 * §10's whole cost-control section exists because of that shape. A failed generation shows
 * the user an error and they press the button again, which is a decision a person made.
 */
@Injectable()
export class PosterQueue implements OnModuleInit, OnApplicationShutdown {
  private readonly logger = new Logger(PosterQueue.name);
  private queue: Queue | null = null;
  private worker: Worker | null = null;

  constructor(
    @Inject(REDIS_CONNECTION) private readonly redis: IORedis,
    private readonly pipeline: PosterPipelineService,
    private readonly generations: GenerationService,
  ) {}

  onModuleInit(): void {
    this.queue = new Queue(QUEUE_POSTER, { connection: this.redis });

    this.worker = new Worker(
      QUEUE_POSTER,
      async (job) => {
        const payload = job.data as PosterJob & { queuedAtMs: number };
        // The latency the USER experiences, so it includes the queue wait — FR-POS-09's
        // targets are about somebody watching a spinner.
        const startedAt = new Date(payload.queuedAtMs);
        try {
          return await this.pipeline.run(payload, startedAt);
        } catch (cause) {
          this.logger.error(`Poster job ${payload.generationId} threw: ${String(cause)}`);
          await this.generations.fail(payload.generationId, 'PIPELINE_ERROR', startedAt);
          // Swallowed rather than rethrown. BullMQ would mark the job failed and, with
          // attempts > 1, pay for another image; the generation row already records what
          // happened and is what the app is polling.
          return { posterId: null, status: 'FAILED' as const };
        }
      },
      {
        connection: this.redis.duplicate(),
        // One at a time. Compositing is CPU-bound in `sharp` and this process also serves
        // HTTP; a concurrency that made posters fast would make every other request slow.
        concurrency: 1,
      },
    );

    this.worker.on('failed', (job, err) =>
      this.logger.error(`Poster job ${job?.id} failed: ${err.message}`),
    );
    this.logger.log('Poster queue started');
  }

  async enqueue(job: PosterJob, queuedAt: Date): Promise<void> {
    if (!this.queue) throw new Error('Poster queue is not started');
    await this.queue.add(
      QUEUE_POSTER,
      { ...job, queuedAtMs: queuedAt.getTime() },
      {
        jobId: job.generationId,
        attempts: 1,
        removeOnComplete: 100,
        removeOnFail: 200,
      },
    );
  }

  async onApplicationShutdown(): Promise<void> {
    await this.worker?.close();
    await this.queue?.close();
  }
}
