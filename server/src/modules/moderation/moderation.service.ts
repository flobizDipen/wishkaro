import { Inject, Injectable, Logger } from '@nestjs/common';
import type { ModerationCheck, ModerationProvider } from './moderation-provider';
import { MODERATION_PROVIDER, UNCHECKED } from './moderation-provider';
import { checkFreeTexts } from './text-input-rules';

/**
 * Input and output moderation, on every generation, across **both** surfaces
 * (REQUIREMENTS.md §10 rule 7, FR-POS-12).
 *
 * Both means posters and the wish drafting path that already shipped. Building it for
 * posters only would leave rule 7 unmet for the feature that is in users' hands today and
 * move the debt silently to M7 — which is the thing `MILESTONE_CHECKLIST.md` §F exists to
 * stop. `docs/M6_PLAN.md` §5.
 *
 * ## Two layers, and they answer different questions
 *
 *  - `text-input-rules.ts` runs first and needs no key. It is deterministic and is about
 *    text on its way into a prompt — length, invisible characters, injection markers,
 *    contact details. It genuinely runs, so it reports ALLOWED or BLOCKED.
 *  - The provider runs second and is a classifier. Without a key it is the fake, which
 *    reports `UNCHECKED` rather than claiming an approval nobody gave.
 *
 * A combined verdict therefore says the strongest honest thing: BLOCKED if either blocked,
 * ALLOWED only if a classifier actually allowed it, and UNCHECKED when the deterministic
 * rules passed but no classifier was reached. That third value is the one that keeps
 * `M6_VERIFY.md` able to say "structurally met, not actually exercised".
 *
 * **Nothing here logs the text.** §11 bans PII in logs and the text in question is prose
 * about a named person. Categories and reason codes are logged; the sentence never is.
 */
@Injectable()
export class ModerationService {
  private readonly logger = new Logger(ModerationService.name);

  constructor(@Inject(MODERATION_PROVIDER) private readonly provider: ModerationProvider) {}

  get isLive(): boolean {
    return this.provider.isLive;
  }

  /**
   * User-typed text on its way into a prompt.
   *
   * The deterministic rules run first and short-circuit, so a 50 kB payload or an injection
   * marker is refused without being sent to a third party — which is the point of having a
   * check that does not need the network.
   */
  async checkInput(texts: readonly (string | null | undefined)[]): Promise<ModerationCheck> {
    const deterministic = checkFreeTexts(texts);
    if (deterministic.verdict === 'BLOCKED') {
      this.logger.warn(`Input refused: ${deterministic.reasonCode} (${deterministic.categories})`);
      return deterministic;
    }

    const joined = texts.filter((t): t is string => typeof t === 'string' && t.trim() !== '');
    if (joined.length === 0) return deterministic;

    const classified = await this.safely(() => this.provider.checkText(joined.join('\n')));
    return strongest(deterministic, classified);
  }

  /** Model-written text on its way to a user. */
  async checkOutputText(text: string): Promise<ModerationCheck> {
    if (text.trim() === '') return UNCHECKED;
    return this.safely(() => this.provider.checkText(text));
  }

  /** A generated or composited image on its way to a user (FR-POS-12). */
  async checkOutputImage(png: Buffer): Promise<ModerationCheck> {
    return this.safely(() => this.provider.checkImage(png));
  }

  /**
   * A provider is contracted never to throw. This still catches, for the reason
   * `DraftingService.callAndRecord` gives: "contracted not to" is not "cannot".
   *
   * **A classifier that is down returns UNCHECKED, not BLOCKED.** That is fail-OPEN, which
   * is the opposite of what the rate limiter does, and the asymmetry is deliberate: the
   * limiter guards a bill, where failing open means unbounded spending, and this guards
   * content, where failing closed means a bereaved user is refused a remembrance poster
   * because a third party had an outage. The verdict stored is honest either way — nobody
   * reading the row will think this was approved.
   */
  private async safely(run: () => Promise<ModerationCheck>): Promise<ModerationCheck> {
    try {
      return await run();
    } catch (cause) {
      this.logger.error(`Moderation provider threw; recording UNCHECKED. ${String(cause)}`);
      return UNCHECKED;
    }
  }
}

/** BLOCKED beats ALLOWED beats UNCHECKED. */
function strongest(a: ModerationCheck, b: ModerationCheck): ModerationCheck {
  if (a.verdict === 'BLOCKED') return a;
  if (b.verdict === 'BLOCKED') return b;
  if (b.verdict === 'ALLOWED') return b;
  return a.verdict === 'ALLOWED' ? a : b;
}
