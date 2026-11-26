import { Injectable } from '@nestjs/common';
import type { ModerationCheck, ModerationProvider } from './moderation-provider';
import { UNCHECKED, blocked } from './moderation-provider';

/**
 * A moderator that checks nothing, and **says so**.
 *
 * The committed default, because `MODERATION_PROVIDER=openai` needs a key this machine does
 * not have. The important decision is what it returns: `UNCHECKED`, not `ALLOWED`.
 *
 * Returning `ALLOWED` would have been one word's difference and a lie in the one table that
 * exists to answer "was this moderated". Every `generation` row in the database would read
 * as approved by a classifier that was never called, and the first person to query "how
 * often does moderation bite" would get a confident zero. `WishSource.TEMPLATE` exists for
 * the same reason: the server records what actually happened, not what it would like to be
 * true.
 *
 * `nextVerdict` forces a block, so that "a blocked generation degrades, writes its row and
 * returns BLOCKED rather than FAILED" is demonstrated rather than argued — the same
 * affordance `FakeLlmProvider` and `FakeImageProvider` have.
 */
@Injectable()
export class FakeModerationProvider implements ModerationProvider {
  readonly providerName = 'fake';
  readonly isLive = false;

  /** Set by a test. 'pass' records UNCHECKED; 'block' records a BLOCKED verdict. */
  nextVerdict: 'pass' | 'block' = 'pass';

  async checkText(_text: string): Promise<ModerationCheck> {
    return this.answer();
  }

  async checkImage(_png: Buffer): Promise<ModerationCheck> {
    return this.answer();
  }

  private answer(): ModerationCheck {
    return this.nextVerdict === 'block' ? blocked(['simulated'], 'MODERATION_BLOCKED') : UNCHECKED;
  }
}
