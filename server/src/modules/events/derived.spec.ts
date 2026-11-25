import type { EventType, TraitKind } from '@prisma/client';
import {
  COMPLETENESS_TOTAL,
  defaultMutedFor,
  COMPLETENESS_WEIGHTS,
  type CompletenessInput,
  completenessScore,
  completenessSuggestions,
  exactAgeOn,
  isMinorFor,
  milestoneLabelFor,
  sentimentFor,
} from './derived';

const ALL_TYPES: EventType[] = [
  'BIRTHDAY',
  'WEDDING_ANNIVERSARY',
  'ENGAGEMENT_ANNIVERSARY',
  'DEATH_ANNIVERSARY',
  'CUSTOM',
];

// ---------------------------------------------------------------------------------------
// Sentiment
// ---------------------------------------------------------------------------------------

describe('sentimentFor', () => {
  it('makes a death anniversary SOLEMN regardless of the person', () => {
    expect(sentimentFor('DEATH_ANNIVERSARY', false)).toBe('SOLEMN');
    expect(sentimentFor('DEATH_ANNIVERSARY', true)).toBe('SOLEMN');
  });

  it('makes every other type CELEBRATORY for a living person', () => {
    for (const type of ALL_TYPES.filter((t) => t !== 'DEATH_ANNIVERSARY')) {
      expect(sentimentFor(type, false)).toBe('CELEBRATORY');
    }
  });

  /**
   * The case M0's type-only derivation got wrong, and the reason this function has two
   * arguments. A remembrance birthday is type=BIRTHDAY on a deceased person (FR-EVT-05).
   */
  it('makes EVERY event of a deceased person SOLEMN, including their birthday', () => {
    for (const type of ALL_TYPES) {
      expect(sentimentFor(type, true)).toBe('SOLEMN');
    }
  });

  it('has no input combination that yields CELEBRATORY for a deceased person', () => {
    const celebratory = ALL_TYPES.filter((t) => sentimentFor(t, true) === 'CELEBRATORY');
    expect(celebratory).toEqual([]);
  });
});

/**
 * The creation-time half of FR-EVT-05's "disabled by default". Found by the sync walkthrough:
 * PersonDerivationService muted the birthdays that existed at the moment a person became
 * deceased, but a birthday ADDED afterwards arrived unmuted, so the default silently was not
 * one. These cases are the regression guard.
 */
describe('defaultMutedFor', () => {
  it('mutes a birthday created for someone already deceased', () => {
    expect(defaultMutedFor('BIRTHDAY', true)).toBe(true);
  });

  it("leaves a living person's birthday alone", () => {
    expect(defaultMutedFor('BIRTHDAY', false)).toBe(false);
  });

  it('does not mute the death anniversary itself — that one is the point', () => {
    expect(defaultMutedFor('DEATH_ANNIVERSARY', true)).toBe(false);
  });

  it('does not mute other event types for a deceased person', () => {
    for (const type of ALL_TYPES.filter((t) => t !== 'BIRTHDAY')) {
      expect(defaultMutedFor(type, true)).toBe(false);
    }
  });
});

// ---------------------------------------------------------------------------------------
// Milestone labels
// ---------------------------------------------------------------------------------------

describe('milestoneLabelFor', () => {
  it('names the four celebratory milestones on wedding and engagement anniversaries', () => {
    for (const type of ['WEDDING_ANNIVERSARY', 'ENGAGEMENT_ANNIVERSARY'] as EventType[]) {
      expect(milestoneLabelFor(type, 1, false)).toBe('1st');
      expect(milestoneLabelFor(type, 10, false)).toBe('10th');
      expect(milestoneLabelFor(type, 25, false)).toBe('25th Silver');
      expect(milestoneLabelFor(type, 50, false)).toBe('50th Golden');
    }
  });

  it('gives no label to a non-milestone year', () => {
    expect(milestoneLabelFor('WEDDING_ANNIVERSARY', 7, false)).toBeNull();
    expect(milestoneLabelFor('WEDDING_ANNIVERSARY', 24, false)).toBeNull();
    expect(milestoneLabelFor('WEDDING_ANNIVERSARY', 51, false)).toBeNull();
  });

  it('gives birthdays and custom events no milestone label (FR-EVT-03 names two types)', () => {
    for (const type of ['BIRTHDAY', 'CUSTOM'] as EventType[]) {
      for (const years of [1, 10, 25, 50]) {
        expect(milestoneLabelFor(type, years, false)).toBeNull();
      }
    }
  });

  /** FR-EVT-03: a plain year count, never a celebratory milestone name. */
  it('gives a death anniversary a plain year count and never Silver or Golden', () => {
    expect(milestoneLabelFor('DEATH_ANNIVERSARY', 1, true)).toBe('1 year');
    expect(milestoneLabelFor('DEATH_ANNIVERSARY', 12, true)).toBe('12 years');
    expect(milestoneLabelFor('DEATH_ANNIVERSARY', 25, true)).toBe('25 years');
    expect(milestoneLabelFor('DEATH_ANNIVERSARY', 50, true)).toBe('50 years');
  });

  it('never produces a celebratory milestone name for ANY event of a deceased person', () => {
    const forbidden = /silver|golden|\d+(st|nd|rd|th)/i;
    for (const type of ALL_TYPES) {
      for (let years = 0; years <= 100; years++) {
        const label = milestoneLabelFor(type, years, true);
        if (label !== null) expect(label).not.toMatch(forbidden);
      }
    }
  });

  /** FR-EVT-02: year unknown must be handled everywhere, not just in the UI. */
  it('gives no label at all when the year is unknown', () => {
    for (const type of ALL_TYPES) {
      expect(milestoneLabelFor(type, null, false)).toBeNull();
      expect(milestoneLabelFor(type, null, true)).toBeNull();
    }
  });

  it('gives no label on the zeroth year, which is the event itself', () => {
    for (const type of ALL_TYPES) {
      expect(milestoneLabelFor(type, 0, false)).toBeNull();
      expect(milestoneLabelFor(type, 0, true)).toBeNull();
    }
  });
});

// ---------------------------------------------------------------------------------------
// Minor flag
// ---------------------------------------------------------------------------------------

describe('exactAgeOn / isMinorFor', () => {
  const today = { year: 2026, month: 11, day: 25 };

  it('counts a birthday that has already passed this year', () => {
    expect(exactAgeOn({ day: 1, month: 1, year: 2000 }, today)).toBe(26);
  });

  it('does not count a birthday still to come this year', () => {
    expect(exactAgeOn({ day: 31, month: 12, year: 2000 }, today)).toBe(25);
  });

  it('counts the birthday itself as the day the age changes', () => {
    expect(exactAgeOn({ day: 25, month: 11, year: 2008 }, today)).toBe(18);
    expect(exactAgeOn({ day: 26, month: 11, year: 2008 }, today)).toBe(17);
  });

  it('flags a person under 18 and not one over', () => {
    expect(isMinorFor({ day: 1, month: 1, year: 2015 }, today)).toBe(true);
    expect(isMinorFor({ day: 1, month: 1, year: 2000 }, today)).toBe(false);
  });

  it('turns the flag off on the eighteenth birthday, not the day after', () => {
    expect(isMinorFor({ day: 25, month: 11, year: 2008 }, today)).toBe(false);
    expect(isMinorFor({ day: 26, month: 11, year: 2008 }, today)).toBe(true);
  });

  /**
   * A 29 February child must stop being a minor on 28 February in a non-leap year — the date
   * this app actually observes their birthday on (FR-EVT-09). Any other answer makes them a
   * minor on their own birthday.
   */
  it('ages a 29 February birthday on 28 February in a non-leap year', () => {
    // Born 29 Feb 2008 (a leap year — 29 Feb 2009 is not a real date and the spec validator
    // rightly refuses it). 2026 is not a leap year, so this app observes the birthday on
    // 28 February, and that is the day the age must change.
    const born = { day: 29, month: 2, year: 2008 };

    expect(exactAgeOn(born, { year: 2026, month: 2, day: 28 })).toBe(18);
    expect(exactAgeOn(born, { year: 2026, month: 2, day: 27 })).toBe(17);

    expect(isMinorFor(born, { year: 2026, month: 2, day: 28 })).toBe(false);
    expect(isMinorFor(born, { year: 2026, month: 2, day: 27 })).toBe(true);
  });

  it('ages a 29 February birthday on the 29th in a leap year', () => {
    const born = { day: 29, month: 2, year: 2008 };
    expect(exactAgeOn(born, { year: 2028, month: 2, day: 29 })).toBe(20);
    expect(exactAgeOn(born, { year: 2028, month: 2, day: 28 })).toBe(19);
  });

  it('does not flag a person whose birth year is unknown', () => {
    expect(isMinorFor({ day: 1, month: 1, year: null }, today)).toBe(false);
    expect(exactAgeOn({ day: 1, month: 1, year: null }, today)).toBeNull();
  });

  it('does not flag a person with no birthday at all', () => {
    expect(isMinorFor(null, today)).toBe(false);
  });
});

// ---------------------------------------------------------------------------------------
// Completeness
// ---------------------------------------------------------------------------------------

function profile(over: Partial<CompletenessInput> = {}): CompletenessInput {
  return {
    relationToOwner: null,
    gender: null,
    phone: null,
    email: null,
    notes: null,
    birthday: null,
    traitKinds: [],
    isMinor: false,
    ...over,
  };
}

describe('completenessScore', () => {
  it('keeps the weights summing to exactly 100', () => {
    expect(COMPLETENESS_TOTAL).toBe(100);
  });

  it('scores an empty profile 0 and a full one 100', () => {
    expect(completenessScore(profile())).toBe(0);

    const full = profile({
      relationToOwner: 'Brother',
      gender: 'male',
      phone: '+91…',
      email: 'a@b.c',
      notes: 'likes filter coffee',
      birthday: { day: 22, month: 9, year: 1988 },
      traitKinds: ['HOBBY', 'LIKE', 'DISLIKE', 'BUDGET_BAND'] as TraitKind[],
    });
    expect(completenessScore(full)).toBe(100);
  });

  it('does not credit a birthday whose year is unknown', () => {
    const unknown = profile({ birthday: { day: 7, month: 7, year: null } });
    expect(completenessScore(unknown)).toBe(0);

    const known = profile({ birthday: { day: 7, month: 7, year: 1960 } });
    expect(completenessScore(known)).toBe(COMPLETENESS_WEIGHTS.birthYear);
  });

  it('does not credit a whitespace-only field', () => {
    expect(completenessScore(profile({ notes: '   ' }))).toBe(0);
    expect(completenessScore(profile({ notes: 'x' }))).toBe(COMPLETENESS_WEIGHTS.notes);
  });

  it('weights hobbies above an email address, because gifts are the point', () => {
    expect(COMPLETENESS_WEIGHTS.hobby).toBeGreaterThan(COMPLETENESS_WEIGHTS.email);
  });
});

describe('completenessSuggestions', () => {
  it('asks for the heaviest missing field first', () => {
    expect(completenessSuggestions(profile())[0]).toBe('hobby');
  });

  it('stops asking once a field is filled', () => {
    const withHobby = profile({ traitKinds: ['HOBBY'] as TraitKind[] });
    expect(completenessSuggestions(withHobby)).not.toContain('hobby');
  });

  /** FR-PPL-09: flagged records are excluded from profiling. */
  it('asks for nothing at all about a minor', () => {
    expect(completenessSuggestions(profile({ isMinor: true }))).toEqual([]);
  });
});

/**
 * The invariant the CLIENT leans on to tell "complete" apart from "not scored yet".
 *
 * `person_detail` has only two signals: the score and the mirrored suggestion list. A person
 * the server has never scored has an empty list AND a score of 0, which is indistinguishable
 * from a perfect profile unless this holds — so it is asserted here rather than assumed over
 * there. Breaking it would make a freshly added person claim there is nothing left to add.
 */
describe('a sub-100 score always has something to suggest (non-minor)', () => {
  const base: CompletenessInput = {
    relationToOwner: null,
    gender: null,
    phone: null,
    email: null,
    notes: null,
    birthday: null,
    traitKinds: [],
    isMinor: false,
  };

  it('holds for an empty profile', () => {
    expect(completenessScore(base)).toBe(0);
    expect(completenessSuggestions(base).length).toBeGreaterThan(0);
  });

  it('holds for every partially-filled profile reachable by one field', () => {
    const variations: Array<Partial<CompletenessInput>> = [
      { relationToOwner: 'Friend' },
      { gender: 'female' },
      { phone: '+919812345678' },
      { email: 'a@b.test' },
      { notes: 'likes trains' },
      { birthday: { day: 2, month: 12, year: 1990 } },
      { traitKinds: ['HOBBY'] },
      { traitKinds: ['HOBBY', 'LIKE'] },
      { traitKinds: ['HOBBY', 'LIKE', 'DISLIKE'] },
    ];
    for (const v of variations) {
      const input = { ...base, ...v };
      const score = completenessScore(input);
      const suggestions = completenessSuggestions(input);
      expect(score).toBeLessThan(100);
      expect(suggestions.length).toBeGreaterThan(0);
    }
  });

  it('and only a 100 score has nothing left', () => {
    const complete: CompletenessInput = {
      relationToOwner: 'Friend',
      gender: 'female',
      phone: '+919812345678',
      email: 'a@b.test',
      notes: 'likes trains',
      birthday: { day: 2, month: 12, year: 1990 },
      traitKinds: ['HOBBY', 'LIKE', 'DISLIKE', 'BUDGET_BAND'],
      isMinor: false,
    };
    expect(completenessScore(complete)).toBe(100);
    expect(completenessSuggestions(complete)).toEqual([]);
  });
});
