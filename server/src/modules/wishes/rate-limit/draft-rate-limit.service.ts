import { Inject, Injectable, Logger } from '@nestjs/common';
import type IORedis from 'ioredis';
import { APP_ENV, type AppEnv } from '../../../config/env';
import { REDIS_CONNECTION } from '../../../queue/queue.constants';

/**
 * FR-WSH-08's fair use.
 *
 * Text drafting is free to the user. It is not free to us, and "free and unmetered" with no
 * ceiling is an invitation — the repository is public, the endpoint is behind a token anybody
 * can mint by signing in with Google, and every call bills a card.
 *
 * Deliberately NOT a credit check. docs/BUILD_PROMPT_M5.md rules one out and FR-WSH-08
 * agrees: a metered path that charges nothing is a path nobody tests. This is a ceiling, and
 * a user who hits it is told so in words (definition-of-done item 8) rather than meeting a
 * button that does nothing.
 */

export interface RateLimitVerdict {
  allowed: boolean;
  /** How long until the binding window rolls. Sent to the client as `retryAfterSeconds`. */
  retryAfterSeconds: number;
  /** Which ceiling bound — for the message, and for the log line. */
  window: 'hour' | 'day' | null;
}

const ALLOWED: RateLimitVerdict = { allowed: true, retryAfterSeconds: 0, window: null };

@Injectable()
export class DraftRateLimitService {
  private readonly logger = new Logger(DraftRateLimitService.name);

  constructor(
    @Inject(REDIS_CONNECTION) private readonly redis: IORedis,
    @Inject(APP_ENV) private readonly env: AppEnv,
  ) {}

  /**
   * Count this attempt and say whether it may proceed.
   *
   * **Counted on ATTEMPTS, not successes.** A draft the model refused still cost money, and a
   * limiter that only counted successes would let a user whose every request trips a
   * classifier spend without bound. The ledger and this counter therefore agree about what
   * happened, which is the property that makes the two readable side by side.
   *
   * Fixed windows, not a sliding log. Two INCRs and two EXPIREs against keys that name the
   * hour and the day: no sorted sets, no trimming, no clock arithmetic on read. The cost is
   * the usual fixed-window edge — a user can spend two hours' worth across a window boundary
   * — and at these ceilings that is 30 drafts in a few minutes, which is a cost, not a hole.
   */
  async consume(userId: string, now: Date = new Date()): Promise<RateLimitVerdict> {
    const hourKey = `wishdraft:h:${userId}:${hourBucket(now)}`;
    const dayKey = `wishdraft:d:${userId}:${dayBucket(now)}`;

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

      // ioredis returns [[err, value], ...]; a null reply means the transaction was discarded.
      if (!results) throw new Error('Redis discarded the rate-limit transaction');
      hourCount = replyAsNumber(results[0]);
      dayCount = replyAsNumber(results[2]);
    } catch (cause) {
      /**
       * FAILS CLOSED, and that is a decision rather than an oversight.
       *
       * Open would mean: the meter broke, so the spending became unlimited. Redis being
       * unreachable already means BullMQ is down and this server is degraded — reminders are
       * not being materialised and nothing is being sent — so refusing to also run up an
       * uncapped bill costs a feature that is already in a degraded deployment.
       *
       * The user sees the same "try again shortly" they would see for a rate limit, which is
       * true, and the composer still opens on its local template, so they are not stranded.
       */
      this.logger.error(`Rate limiter unavailable; refusing the draft. ${String(cause)}`);
      return { allowed: false, retryAfterSeconds: 60, window: null };
    }

    if (hourCount > this.env.WISH_DRAFT_LIMIT_HOUR) {
      return { allowed: false, retryAfterSeconds: secondsToNextHour(now), window: 'hour' };
    }
    if (dayCount > this.env.WISH_DRAFT_LIMIT_DAY) {
      return { allowed: false, retryAfterSeconds: secondsToNextDay(now), window: 'day' };
    }
    return ALLOWED;
  }
}

/**
 * UTC buckets, deliberately, where the rest of this app thinks in the owner's timezone.
 *
 * A reminder fires in the user's day because a birthday is a property of their day. A spend
 * ceiling is a property of OUR bill, and a per-user local day would make the same ceiling
 * mean different things for two users and make the key change under a traveller mid-flight.
 */
function hourBucket(now: Date): string {
  return now.toISOString().slice(0, 13); // YYYY-MM-DDTHH
}

function dayBucket(now: Date): string {
  return now.toISOString().slice(0, 10); // YYYY-MM-DD
}

function secondsToNextHour(now: Date): number {
  return 3600 - (now.getUTCMinutes() * 60 + now.getUTCSeconds());
}

function secondsToNextDay(now: Date): number {
  const secondsIntoDay = now.getUTCHours() * 3600 + now.getUTCMinutes() * 60 + now.getUTCSeconds();
  return 86_400 - secondsIntoDay;
}

function replyAsNumber(reply: [Error | null, unknown] | undefined): number {
  if (!reply) throw new Error('Missing reply from the rate-limit transaction');
  const [err, value] = reply;
  if (err) throw err;
  if (typeof value !== 'number') throw new Error(`Unexpected INCR reply: ${String(value)}`);
  return value;
}
