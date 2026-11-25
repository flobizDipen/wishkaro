import type { EventType } from '@prisma/client';
import { sentimentFor } from '../events/derived';
import { buildReminderCopy } from './reminder-copy';

/**
 * REQUIREMENTS.md §11 makes this a correctness requirement, not a content preference:
 *
 *   "Automated tests assert that no SOLEMN event can ever produce celebratory copy."
 *
 * And FR-EVT-05 is blunter still — silently sending "Happy Birthday!" for someone who has
 * died is named as the worst failure this product can have, and must be impossible by
 * construction rather than by a copy tweak.
 *
 * So this suite does not sample. It enumerates the whole cross-product of inputs that can
 * reach the copy layer and asserts the property over all of it. A sixth event type added
 * later fails here until someone has written its solemn copy.
 */

const ALL_TYPES: EventType[] = [
  'BIRTHDAY',
  'WEDDING_ANNIVERSARY',
  'ENGAGEMENT_ANNIVERSARY',
  'DEATH_ANNIVERSARY',
  'CUSTOM',
];

/**
 * Words that must never appear in copy for a SOLEMN event, and the reason each is here:
 * "celebrate"/"congrat"/"happy" are the register itself; "wish" is this product's own verb
 * for the celebratory action; "many happy returns" is the idiom that would slip past a
 * naive "happy" check if it were ever templated differently.
 */
const BANNED_IN_SOLEMN = [
  'celebrat',
  'congrat',
  'happy',
  'wish',
  'many happy',
  'cheers',
  'party',
  'treat them',
];

const ANY_EMOJI = /\p{Extended_Pictographic}/u;

/** Every combination of inputs that the reminder path can present to the copy layer. */
function everyCopyInput(): Array<{
  label: string;
  input: Parameters<typeof buildReminderCopy>[0];
}> {
  const cases: Array<{ label: string; input: Parameters<typeof buildReminderCopy>[0] }> = [];
  for (const eventType of ALL_TYPES) {
    for (const personIsDeceased of [false, true]) {
      for (const years of [null, 0, 1, 12, 25, 50, 99]) {
        cases.push({
          label: `${eventType} deceased=${personIsDeceased} years=${String(years)}`,
          input: {
            personName: 'Ravi',
            eventType,
            sentiment: sentimentFor(eventType, personIsDeceased),
            years,
          },
        });
      }
    }
  }
  return cases;
}

describe('tone safety', () => {
  const all = everyCopyInput();

  it('covers every event type against both living and deceased people', () => {
    // 5 types x 2 x 7 year values. Guards against the generator silently shrinking.
    expect(all).toHaveLength(70);
    expect(all.filter((c) => c.input.sentiment === 'SOLEMN')).not.toHaveLength(0);
  });

  it('never produces a celebratory word for ANY input that resolves to SOLEMN', () => {
    const offenders: string[] = [];

    for (const { label, input } of all) {
      if (input.sentiment !== 'SOLEMN') continue;
      const copy = buildReminderCopy(input);
      const text = `${copy.title} ${copy.body}`.toLowerCase();
      for (const word of BANNED_IN_SOLEMN) {
        if (text.includes(word)) offenders.push(`${label}: "${word}" in "${text.trim()}"`);
      }
    }

    expect(offenders).toEqual([]);
  });

  it('never produces an emoji for ANY input that resolves to SOLEMN', () => {
    const offenders: string[] = [];

    for (const { label, input } of all) {
      if (input.sentiment !== 'SOLEMN') continue;
      const copy = buildReminderCopy(input);
      const text = `${copy.title} ${copy.body}`;
      if (ANY_EMOJI.test(text)) offenders.push(`${label}: "${text.trim()}"`);
    }

    expect(offenders).toEqual([]);
  });

  /**
   * The specific case M0's type-only derivation got wrong. Kept as its own test so that if
   * it ever regresses, the failure names the scenario rather than just a property.
   */
  it('does not wish a deceased person a happy birthday', () => {
    const copy = buildReminderCopy({
      personName: 'Ravi',
      eventType: 'BIRTHDAY',
      sentiment: sentimentFor('BIRTHDAY', true),
      years: 39,
    });
    const text = `${copy.title} ${copy.body}`.toLowerCase();

    expect(text).not.toContain('birthday');
    expect(text).not.toContain('turn');
    expect(text).not.toMatch(ANY_EMOJI);
  });

  it('still produces warm copy for a living person', () => {
    const copy = buildReminderCopy({
      personName: 'Aarav',
      eventType: 'BIRTHDAY',
      sentiment: sentimentFor('BIRTHDAY', false),
      years: 36,
    });
    expect(copy.body).toContain('36');
    expect(`${copy.title}`).toMatch(ANY_EMOJI);
  });

  /** FR-EVT-02: an unknown year must not become a number anywhere. */
  it('never states an age when the year is unknown, for any type or person', () => {
    for (const eventType of ALL_TYPES) {
      for (const personIsDeceased of [false, true]) {
        const copy = buildReminderCopy({
          personName: 'Meera',
          eventType,
          sentiment: sentimentFor(eventType, personIsDeceased),
          years: null,
        });
        expect(`${copy.title} ${copy.body}`).not.toMatch(/\d/);
      }
    }
  });
});
