import { Inject, Injectable, Logger } from '@nestjs/common';
import type IORedis from 'ioredis';
import { APP_ENV, type AppEnv } from '../../../config/env';
import { REDIS_CONNECTION } from '../../../queue/queue.constants';
import type { RateLimitVerdict } from '../../wishes/rate-limit/draft-rate-limit.service';

/**
 * The per-user ceiling on **live image generation** (§10 rule 6, FR-POS-11).
 *
 * ## Why this is a second limiter and not a reuse of `DraftRateLimitService`
 *
 * `docs/BUILD_PROMPT_M6.md` says it in one line — "Posters need their own ceilings, not
 * their own limiter" — and that is exactly what this is: the same fixed-window Redis shape,
 * the same fail-closed decision, the same UTC buckets, a different prefix and a different
 * pair of numbers. Sharing the counter would make fifteen wish drafts eat a poster
 * allowance, and the two cost three orders of magnitude apart: §10's own table calls a draft
 * "fractions of a paisa" and an image "₹1–15 depending on quality tier".
 *
 * The defaults are 5/hour and 20/day (`POSTER_LIMIT_HOUR`, `POSTER_LIMIT_DAY`). Those are
 * **proposed, not confirmed** — `docs/M6_PLAN.md` §9 item 4 asks Dipen for the real numbers
 * along with whether ₹200/day still stands now that images bill per image. At ₹15 a go, 20 a
 * day for one user is ₹300, which is more than the entire global cap: the two numbers were
 * chosen against different cost shapes and only one of them has been re-examined.
 *
 * ## The rule this file exists to protect
 *
 * **A cache hit consumes no slot.** FR-POS-03's whole economic argument is that the common
 * path costs nothing, and definition-of-done item 5 requires it literally. So nothing calls
 * `consume()` at request time: the pipeline calls it only once it knows the background is a
 * MISS and a live call is about to happen. The cheap path is therefore not rationed like the
 * expensive one, and a user can send as many library posters as they like.
 *
 * The cost of that placement, stated: the queue itself is not rate limited, so a client in a
 * loop can enqueue work that resolves from cache. It is bounded by auth and by the fact that
 * such a job spends nothing — it is CPU, not money — and the alternative is rationing the
 * free path to protect it, which is the thing FR-POS-03 says not to do.
 */
@Injectable()
export class PosterRateLimitService {
  private readonly logger = new Logger(PosterRateLimitService.name);

  constructor(
    @Inject(REDIS_CONNECTION) private readonly redis: IORedis,
    @Inject(APP_ENV) private readonly env: AppEnv,
  ) {}

  /**
   * Count this attempt at a LIVE generation and say whether it may proceed.
   *
   * Counted on attempts rather than successes, for the reason M5 wrote down: an image the
   * model refused still consumed a request, and a limiter that only counted successes would
   * let a user whose every prompt trips a classifier spend without bound.
   */
  async consume(userId: string, now: Date = new Date()): Promise<RateLimitVerdict> {
    return this.count('poster', userId, now, this.env.POSTER_LIMIT_HOUR, this.env.POSTER_LIMIT_DAY);
  }

  /**
   * The COPY call's ceiling — a separate window, on purpose (C8).
   *
   * Copy and image are two rails because they cost three orders of magnitude apart and
   * because a background cache hit serves everyone while copy is per-person. Sharing one
   * counter would let five posters an hour exhaust the allowance for a call that costs
   * fractions of a paisa, and would make a cache hit — which should cost nothing on the
   * image side — consume the image budget.
   */
  async consumeCopy(userId: string, now: Date = new Date()): Promise<RateLimitVerdict> {
    return this.count(
      'postercopy',
      userId,
      now,
      this.env.POSTER_COPY_LIMIT_HOUR,
      this.env.POSTER_COPY_LIMIT_DAY,
    );
  }

  private async count(
    prefix: string,
    userId: string,
    now: Date,
    perHour: number,
    perDay: number,
  ): Promise<RateLimitVerdict> {
    const hourKey = `${prefix}:h:${userId}:${now.toISOString().slice(0, 13)}`;
    const dayKey = `${prefix}:d:${userId}:${now.toISOString().slice(0, 10)}`;

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

      if (!results) throw new Error('Redis discarded the poster rate-limit transaction');
      hourCount = replyAsNumber(results[0]);
      dayCount = replyAsNumber(results[2]);
    } catch (cause) {
      /**
       * Fails closed, and here that is cheaper than it is for drafting.
       *
       * A refused draft leaves the user with the phone's template. A refused live generation
       * leaves them with the pre-approved library, which is the product's main path anyway
       * (FR-POS-04) — so failing closed costs a premium flourish rather than a feature, and
       * the alternative is an uncapped bill on a public endpoint while the meter is down.
       */
      this.logger.error(`Poster rate limiter unavailable; using the library. ${String(cause)}`);
      return { allowed: false, retryAfterSeconds: 60, window: null };
    }

    if (hourCount > perHour) {
      return { allowed: false, retryAfterSeconds: secondsToNextHour(now), window: 'hour' };
    }
    if (dayCount > perDay) {
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
  if (!reply) throw new Error('Missing reply from the poster rate-limit transaction');
  const [err, value] = reply;
  if (err) throw err;
  if (typeof value !== 'number') throw new Error(`Unexpected INCR reply: ${String(value)}`);
  return value;
}
