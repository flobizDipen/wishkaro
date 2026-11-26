import { buildPrompt, systemPromptFor, type DraftContext } from './prompt-builder';

/**
 * The prompt, as a string. Pure, no database, no model.
 *
 * What these can and cannot prove is worth being explicit about, because the distinction is
 * the whole of `MILESTONE_CHECKLIST.md` §D here. They can prove that a fact reached the
 * prompt, that a fact was withheld from it, and that the cached prefix does not vary. They
 * cannot prove that the model obeyed any of it — that is `live/draft-live.spec.ts`, against a
 * real model, because a test that asserts on a fake's output tests the fake.
 *
 * `drafting.db.spec.ts` covers the same builder through the service, where the facts come
 * from real rows. These cover the branches that are awkward to arrange in the database:
 * an unknown birth year, a milestone, and Regenerate's avoid list.
 */

function context(over: Partial<DraftContext> = {}): DraftContext {
  return {
    displayName: 'Anika Pillai',
    relationToOwner: 'Sister',
    eventType: 'BIRTHDAY',
    sentiment: 'CELEBRATORY',
    occurrenceYear: 2026,
    age: 32,
    milestoneLabel: null,
    hobbies: ['long-distance running'],
    likes: ['filter coffee'],
    notes: 'Moved to Pune last year.',
    isMinor: false,
    tone: 'WARM',
    language: 'ENGLISH',
    previous: [],
    avoid: [],
    ...over,
  };
}

describe('the cached half', () => {
  it('is one of exactly two strings', () => {
    // Two families; no third, and no per-tone or per-language variant. Eighteen prefixes per
    // family is what putting the chosen tone in the system prompt would cost, and it would
    // cost it silently — the calls would simply stop hitting the cache.
    const all = new Set([
      systemPromptFor('CELEBRATORY'),
      systemPromptFor('SOLEMN'),
      buildPrompt(context()).system,
      buildPrompt(context({ tone: 'POETIC', language: 'HINDI' })).system,
      buildPrompt(context({ displayName: 'Someone Else' })).system,
    ]);
    expect(all.size).toBe(2);
  });

  it('carries every tone and language, not only the one asked for', () => {
    const system = systemPromptFor('CELEBRATORY');
    for (const tone of ['Warm', 'Funny', 'Respectful', 'Short', 'Poetic', 'Heartfelt']) {
      expect(system).toContain(tone);
    }
    for (const language of ['English', 'Hindi', 'Hinglish']) {
      expect(system).toContain(language);
    }
  });

  it('holds nothing that varies per request', () => {
    const system = systemPromptFor('SOLEMN');
    expect(system).not.toContain('Anika');
    expect(system).not.toMatch(/\d{4}-\d{2}-\d{2}/);
    // The year is the subtlest one: a system prompt that mentioned "this year" would go
    // stale once a year, silently, in a way no test would catch at the time.
    expect(system).not.toContain('2026');
  });
});

describe('the volatile half', () => {
  it('states the tone and language that were asked for', () => {
    const { user } = buildPrompt(context({ tone: 'SHORT', language: 'HINGLISH' }));
    expect(user).toContain('Tone: SHORT');
    expect(user).toContain('Language: HINGLISH');
  });

  it('says nothing about age when the birth year was never known', () => {
    // FR-EVT-02: an unknown year must not become a number anywhere, and must not become a
    // vaguer sentence that implies one either. It becomes nothing.
    const { user } = buildPrompt(context({ age: null }));
    expect(user).not.toMatch(/Age they are turning/);
  });

  it('passes a milestone through when there is one', () => {
    const { user } = buildPrompt(
      context({ eventType: 'WEDDING_ANNIVERSARY', milestoneLabel: '25th Silver' }),
    );
    expect(user).toContain('25th Silver');
  });

  it('never states an age on a solemn occasion', () => {
    // "They would have been forty" is a sentence nobody asked this app to produce, and
    // SolemnStrings.years() already refuses to render it on the person screen. The prompt
    // must not be the place it comes back. DraftingService nulls `age` for SOLEMN; this
    // asserts the builder does not reintroduce it if somebody passes one anyway.
    const { user } = buildPrompt(context({ sentiment: 'SOLEMN', tone: 'RESPECTFUL', age: 40 }));
    expect(user).not.toContain('40');
  });

  it('quotes previous years in full', () => {
    const { user } = buildPrompt(
      context({
        previous: [
          { year: 2025, tone: 'WARM', language: 'ENGLISH', text: 'The one about the marathon.' },
          { year: 2024, tone: 'FUNNY', language: 'ENGLISH', text: 'The one about the cake.' },
        ],
      }),
    );
    expect(user).toContain('The one about the marathon.');
    expect(user).toContain('The one about the cake.');
    expect(user).toContain('2025');
    // And it asks for something different in SUBSTANCE, because "do not repeat" alone is
    // satisfied by rewording, which is the failure FR-WSH-02 is actually about.
    expect(user).toContain('different words');
  });

  it('says nothing about history when there is none', () => {
    // A first draft must not carry an empty "here is what you sent before" heading — it is
    // input tokens on every first draft for ever, and it invites the model to invent one.
    const { user } = buildPrompt(context());
    expect(user).not.toContain('sent this person before');
  });

  it('carries the drafts Regenerate has already turned down', () => {
    const { user } = buildPrompt(context({ avoid: ['The first attempt.', 'The second.'] }));
    expect(user).toContain('The first attempt.');
    expect(user).toContain('The second.');
    expect(user).toContain('turned these down');
  });

  it('withholds a minor’s traits and notes, and keeps the relation', () => {
    const { user } = buildPrompt(context({ isMinor: true }));
    expect(user).toContain('Sister');
    expect(user).not.toContain('long-distance running');
    expect(user).not.toContain('filter coffee');
    expect(user).not.toContain('Moved to Pune');
    // Still personal: the draft knows whose birthday it is and how old they are turning.
    expect(user).toContain('Age they are turning: 32');
  });

  it('says the relation is not stated rather than omitting the line', () => {
    // An absent line reads to a model as a field it may fill in. "not stated" reads as a
    // fact, and FR-PPL-01's relation is optional in practice whatever the requirement says.
    const { user } = buildPrompt(context({ relationToOwner: null }));
    expect(user).toContain('not stated');
  });
});
