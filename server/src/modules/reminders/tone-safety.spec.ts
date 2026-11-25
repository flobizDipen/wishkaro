import type { EventType, ReminderOffset } from '@prisma/client';
import { sentimentFor } from '../events/derived';
import { buildDigestCopy, buildReminderCopy } from './reminder-copy';

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
        // M2 widens the cross-product by the ladder. D7 is unreachable for a SOLEMN event
        // because ladderFor() never creates the rung (FR-REM-11) — it is enumerated here
        // anyway, so the copy layer's safety does not depend on the scheduler's.
        for (const offset of ALL_OFFSETS) {
          cases.push({
            label: `${eventType} deceased=${personIsDeceased} years=${String(years)} ${offset}`,
            input: {
              personName: 'Ravi',
              eventType,
              sentiment: sentimentFor(eventType, personIsDeceased),
              years,
              offset,
            },
          });
        }
      }
    }
  }
  return cases;
}

const ALL_OFFSETS: ReminderOffset[] = ['D7', 'D1', 'D0'];

describe('tone safety', () => {
  const all = everyCopyInput();

  it('covers every event type against both living and deceased people', () => {
    // 5 types x 2 deceased x 7 year values x 3 ladder rungs. Guards against the generator
    // silently shrinking — M2 multiplied it by the ladder, and a future rung must widen it
    // again rather than quietly narrowing the property being proved.
    expect(all).toHaveLength(210);
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

/**
 * The digest puts a death anniversary and a birthday on neighbouring lines of ONE
 * notification (FR-REM-08). There is no per-item register to branch on, so the only safe
 * answer is a list with no register at all — and that has to be asserted, not assumed.
 */
describe('tone safety in the digest (FR-REM-08 x FR-REM-11)', () => {
  const mixed = [
    {
      personName: 'Ravi',
      eventType: 'BIRTHDAY' as EventType,
      sentiment: sentimentFor('BIRTHDAY', false),
      offset: 'D0' as ReminderOffset,
    },
    {
      personName: 'Aai',
      eventType: 'DEATH_ANNIVERSARY' as EventType,
      sentiment: sentimentFor('DEATH_ANNIVERSARY', true),
      offset: 'D0' as ReminderOffset,
    },
  ];

  it('never uses celebratory language, even for the celebratory item', () => {
    const copy = buildDigestCopy(mixed);
    const text = `${copy.title}\n${copy.body}`.toLowerCase();
    for (const banned of BANNED_IN_SOLEMN) {
      expect(text).not.toContain(banned);
    }
  });

  it('carries no emoji at all', () => {
    const copy = buildDigestCopy(mixed);
    expect(ANY_EMOJI.test(copy.title)).toBe(false);
    expect(ANY_EMOJI.test(copy.body)).toBe(false);
  });

  it('calls a death anniversary a remembrance, not an anniversary to mark', () => {
    const copy = buildDigestCopy([mixed[1]!]);
    expect(copy.body).toContain('Aai (remembrance)');
  });

  it('groups the day by rung, furthest out last', () => {
    const copy = buildDigestCopy([
      { ...mixed[0]!, offset: 'D7' },
      { ...mixed[0]!, personName: 'Sita', offset: 'D1' },
      { ...mixed[1]! },
    ]);
    expect(copy.body.split('\n')).toEqual([
      'Today: Aai (remembrance)',
      'Tomorrow: Sita (birthday)',
      'In 7 days: Ravi (birthday)',
    ]);
    expect(copy.title).toBe('3 dates coming up');
  });

  it('counts one date as one', () => {
    expect(buildDigestCopy([mixed[0]!]).title).toBe('1 date coming up');
  });
});
