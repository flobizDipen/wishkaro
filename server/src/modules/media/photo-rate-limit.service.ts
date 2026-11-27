import { Inject, Injectable, Logger } from '@nestjs/common';
import type IORedis from 'ioredis';
import { REDIS_CONNECTION } from '../../queue/queue.constants';
import type { RateLimitVerdict } from '../wishes/rate-limit/draft-rate-limit.service';

/**
 * A ceiling on photo uploads, per user.
 *
 * ## Why this exists
 *
 * `POST /v1/people/:personId/photo` hands attacker-chosen bytes to libvips, and until this
 * it was the only expensive endpoint in the app behind no limiter at all — the draft and
 * poster limiters do not touch it, and there is no global throttler.
 *
 * `limitInputPixels` in `photo.service.ts` is the other half and the more important one:
 * it stops a single 0.72 MB request decoding 256 megapixels. But a legitimate 48 MP photo
 * still costs real memory to decode, so the remaining exposure is CONCURRENCY — the same
 * request, thirty times over, from one signed-in account. That is what a ceiling bounds.
 *
 * ## The numbers
 *
 * Deliberately generous. One photo per person is all the product stores, and a user
 * tidying up a 600-person contact list might reasonably add a few dozen in a sitting; the
 * ceiling is here to stop a loop, not to ration a feature that costs nothing to serve.
 *
 * Same shape as `PosterRateLimitService` and `DraftRateLimitService`: fixed Redis windows,
 * counted on attempts, failing closed. A third copy of that shape is worth collapsing when
 * a fourth appears — noted rather than done, because refactoring two shipped limiters is
 * not a thing to fold into a review fix.
 */
const PER_HOUR = 60;
const PER_DAY = 200;

@Injectable()
export class PhotoRateLimitService {
  private readonly logger = new Logger(PhotoRateLimitService.name);

  constructor(@Inject(REDIS_CONNECTION) private readonly redis: IORedis) {}

  async consume(userId: string, now: Date = new Date()): Promise<RateLimitVerdict> {
    const hourKey = `photo:h:${userId}:${now.toISOString().slice(0, 13)}`;
    const dayKey = `photo:d:${userId}:${now.toISOString().slice(0, 10)}`;

    let hourCount: number;
    let dayCount: number;
    try {
      const results = await this.redis
        .multi()
        .incr(hourKey)
        .expire(hourKey, 3600)
        .incr(dayKey)
        .expire(dayKey, 86_400)
        .exec();

      if (!results) throw new Error('Redis discarded the photo rate-limit transaction');
      hourCount = replyAsNumber(results[0]);
      dayCount = replyAsNumber(results[2]);
    } catch (cause) {
      /**
       * Fails closed, like the other two.
       *
       * The thing being guarded is a decoder being handed arbitrary bytes, so "the meter
       * is down, carry on" is the wrong answer. The cost of failing closed is that a user
       * cannot change a photo while Redis is unreachable — at which point BullMQ is down
       * and no poster can be made anyway.
       */
      this.logger.error(`Photo rate limiter unavailable; refusing the upload. ${String(cause)}`);
      return { allowed: false, retryAfterSeconds: 60, window: null };
    }

    if (hourCount > PER_HOUR) {
      return { allowed: false, retryAfterSeconds: secondsToNextHour(now), window: 'hour' };
    }
    if (dayCount > PER_DAY) {
      return { allowed: false, retryAfterSeconds: secondsToNextDay(now), window: 'day' };
    }
    return { allowed: true, retryAfterSeconds: 0, window: null };
  }
}

function secondsToNextHour(now: Date): number {
  return 3600 - (now.getUTCMinutes() * 60 + now.getUTCSeconds());
}

function secondsToNextDay(now: Date): number {
  const secondsIntoDay = now.getUTCHours() * 3600 + now.getUTCMinutes() * 60 + now.getUTCSeconds();
  return 86_400 - secondsIntoDay;
}

function replyAsNumber(reply: [Error | null, unknown] | undefined): number {
  if (!reply) throw new Error('Missing reply from the photo rate-limit transaction');
  const [err, value] = reply;
  if (err) throw err;
  if (typeof value !== 'number') throw new Error(`Unexpected INCR reply: ${String(value)}`);
  return value;
}
