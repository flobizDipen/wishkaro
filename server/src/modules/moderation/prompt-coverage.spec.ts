import { buildPrompt, userTextIn as wishTextIn } from '../wishes/prompt-builder';
import { buildCopyPrompt, userTextIn as posterTextIn } from '../posters/poster-prompt';

/**
 * §10 rule 7's "every generation", enforced against the prompts rather than against a
 * hand-written list.
 *
 * The rule was implemented twice and differently: `drafting.service.ts` checked the
 * `avoid` list and the notes, `poster-pipeline.service.ts` checked the display name and
 * the notes. So the name went unchecked on the surface that sends it to a model, the
 * hobbies and likes went unchecked on both, and whether the rule applied at all depended
 * on which field the user had typed into.
 *
 * Each builder now declares what it carries, next to itself. This is what stops the
 * declaration drifting from the prompt: every user-typed value that appears in the
 * rendered prompt must appear in the moderation list. Add a field to a prompt without
 * adding it to `userTextIn` and this goes red.
 *
 * Invented people only (MILESTONE_CHECKLIST.md §F) — and deliberately distinctive strings,
 * so a substring match cannot pass by accident.
 */

const SENTINELS = {
  displayName: 'Zzqq Displayname',
  relationToOwner: 'Zzqq Relation',
  notes: 'Zzqq Notes',
  hobby: 'Zzqq Hobby',
  like: 'Zzqq Like',
  avoid: 'Zzqq Avoid',
};

describe('every user-typed string in a prompt is moderated', () => {
  it('the wish draft prompt', () => {
    const context = {
      displayName: SENTINELS.displayName,
      relationToOwner: SENTINELS.relationToOwner,
      eventType: 'BIRTHDAY' as const,
      sentiment: 'CELEBRATORY' as const,
      occurrenceYear: 2026,
      age: 35,
      milestoneLabel: null,
      hobbies: [SENTINELS.hobby],
      likes: [SENTINELS.like],
      notes: SENTINELS.notes,
      isMinor: false,
      tone: 'WARM' as const,
      language: 'ENGLISH' as const,
      previous: [],
      avoid: [SENTINELS.avoid],
    };

    const prompt = `${buildPrompt(context).system}\n${buildPrompt(context).user}`;
    const moderated = wishTextIn(context);

    for (const value of Object.values(SENTINELS)) {
      if (!prompt.includes(value)) continue; // not carried by this prompt — fine
      expect(moderated).toContain(value);
    }
  });

  it('the poster copy prompt', () => {
    const input = {
      displayName: SENTINELS.displayName,
      relationToOwner: SENTINELS.relationToOwner,
      eventType: 'BIRTHDAY' as const,
      sentiment: 'CELEBRATORY' as const,
      milestone: null,
      language: 'english',
      themeIds: ['ph-confetti'],
      isMinor: false,
    };

    const built = buildCopyPrompt(input);
    const prompt = `${built.system}\n${built.user}`;
    const moderated = posterTextIn(input);

    for (const value of Object.values(SENTINELS)) {
      if (!prompt.includes(value)) continue;
      expect(moderated).toContain(value);
    }
  });

  it('honours FR-PPL-09 on the poster prompt: a minor is not profiled, so nothing to check', () => {
    // The relation is the only profile field the copy prompt ever carries, and the minor
    // flag suppresses it in the prompt. The moderation list must agree, or it would be
    // checking a string that was never sent.
    const input = {
      displayName: SENTINELS.displayName,
      relationToOwner: SENTINELS.relationToOwner,
      eventType: 'BIRTHDAY' as const,
      sentiment: 'CELEBRATORY' as const,
      milestone: null,
      language: 'english',
      themeIds: ['ph-confetti'],
      isMinor: true,
    };

    const built = buildCopyPrompt(input);
    expect(`${built.system}\n${built.user}`).not.toContain(SENTINELS.relationToOwner);
    expect(posterTextIn(input)).not.toContain(SENTINELS.relationToOwner);
  });
});
