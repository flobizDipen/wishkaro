import { buildCopyPrompt, userTextIn, type CopyPromptInput } from './poster-prompt';
import { buildPrompt, type DraftContext } from '../wishes/prompt-builder';

/**
 * FR-PPL-09 and §12's *"minor flag suppresses profiling, personalisation and affiliate
 * tracking"* — on **both** surfaces that build a prompt.
 *
 * ## Why this file exists
 *
 * The build prompt says of this box: *"Verify it, do not rebuild it."* Verifying it found
 * that the poster surface had **no test at all** — `isMinor` appears in `prompt-builder.spec.ts`
 * and in `drafting.db.spec.ts`, and nowhere under `modules/posters`. M6 added the suppression
 * at `poster-prompt.ts:92` and nothing has ever asked whether it still happens.
 *
 * It also found that **the two surfaces do not agree about what the flag suppresses**, which
 * is recorded in `docs/M9_VERIFY.md` and raised rather than resolved. This file pins what each
 * one does today, so that whichever way the disagreement is settled, the change is visible.
 */

function copyInput(overrides: Partial<CopyPromptInput> = {}): CopyPromptInput {
  return {
    displayName: 'Aarav Pillai',
    relationToOwner: 'Nephew',
    eventType: 'BIRTHDAY',
    sentiment: 'CELEBRATORY',
    milestone: null,
    language: 'ENGLISH',
    isMinor: false,
    themeIds: ['confetti-bright', 'warm-gradient'],
    ...overrides,
  };
}

describe('FR-PPL-09 on the poster surface', () => {
  it('carries the relation for an adult', () => {
    const { user } = buildCopyPrompt(copyInput({ isMinor: false }));
    expect(user).toContain('Nephew');
  });

  /**
   * The assertion that had no home until now. Without it, deleting the `!input.isMinor`
   * guard at `poster-prompt.ts:92` sends a flagged child's relation to a model with every
   * poster suite still green.
   */
  it('withholds the relation for a flagged minor', () => {
    const { user } = buildCopyPrompt(copyInput({ isMinor: true }));

    expect(user).not.toContain('Nephew');
    // Still personal: the poster is for a named person on a named occasion. FR-PPL-09
    // suppresses profiling, not the fact that it is somebody's birthday.
    expect(user).toContain('Aarav Pillai');
  });

  it('withholds it on a solemn occasion too, where the template family also changes', () => {
    const { user } = buildCopyPrompt(
      copyInput({ isMinor: true, eventType: 'DEATH_ANNIVERSARY', sentiment: 'SOLEMN' }),
    );
    expect(user).not.toContain('Nephew');
  });

  /**
   * §10 rule 7's coverage must not shrink when the flag is set.
   *
   * `userTextIn` is what `prompt-coverage.spec.ts` uses to guarantee every piece of user text
   * in a prompt is moderated (M6 §0.7a). A suppression that removed a field from the prompt
   * but left it in the declared coverage — or the reverse — would make the moderation claim
   * depend on whether the person is a minor, which is the wrong axis for it to depend on.
   */
  it('declares the same user text it actually renders, minor or not', () => {
    for (const isMinor of [true, false]) {
      const input = copyInput({ isMinor });
      const declared = userTextIn(input);
      const { user } = buildCopyPrompt(input);

      for (const text of declared) {
        expect(user).toContain(text);
      }
    }
  });
});

describe('the two surfaces disagree, and this is where that is visible', () => {
  /**
   * **Raised, not resolved — `docs/M9_VERIFY.md` and `docs/M9_PLAN.md`.**
   *
   * FR-PPL-09: a flagged record is "excluded from any profiling, recommendation
   * personalisation **beyond age band**, and all ad/affiliate tracking".
   *
   * - The **drafting** prompt reads that as permitting the relation and suppressing hobbies,
   *   likes and notes. Its own comment argues the case: "Relation, occasion, milestone and
   *   age band is literally that."
   * - The **poster** prompt reads it as forbidding the relation, which is the only profile
   *   field it carries at all.
   *
   * Both are defensible readings of one sentence and they cannot both be what the Data Safety
   * form says. This test asserts the divergence rather than hiding it, so that settling it is
   * a visible change in one place rather than a discovery in a Play review.
   */
  it('keeps the relation when drafting a wish and drops it when writing poster copy', () => {
    const context: DraftContext = {
      displayName: 'Aarav Pillai',
      relationToOwner: 'Nephew',
      eventType: 'BIRTHDAY',
      sentiment: 'CELEBRATORY',
      occurrenceYear: 2026,
      age: 10,
      milestoneLabel: null,
      hobbies: ['tabla'],
      likes: [],
      notes: null,
      isMinor: true,
      tone: 'WARM',
      language: 'ENGLISH',
      previous: [],
      avoid: [],
    };
    const wish = buildPrompt(context);

    const poster = buildCopyPrompt(copyInput({ isMinor: true }));

    expect(wish.user).toContain('Nephew');
    expect(poster.user).not.toContain('Nephew');
  });
});
