import type { EventType } from '@prisma/client';
import { resolveOccasion } from './occasion';
import { familyFor, isThemeAllowed, themesFor } from './theme-library';

/**
 * The gate, asked directly.
 *
 * This is the pure half. `occasion.db.spec.ts` is the half that constructs the stale window
 * against real Postgres, because the two M5 bugs this exists to prevent were both invisible
 * to unit tests — every fixture in this repository builds a person the way the SERVER
 * returns one, with `is_deceased` already set, which is precisely the state the bug cannot
 * occur in.
 */

const CELEBRATORY_TYPES: EventType[] = [
  'BIRTHDAY',
  'WEDDING_ANNIVERSARY',
  'ENGAGEMENT_ANNIVERSARY',
  'CUSTOM',
];

describe('resolveOccasion — the stale window, as a unit', () => {
  it('reads SOLEMN from a death anniversary ROW even while the person flag still says false', () => {
    // The exact shape of `cb5919d`: the event exists, `recompute` has not run, so the
    // person row has not caught up. Reading `person.isDeceased` here returns false.
    const occasion = resolveOccasion({
      person: { isDeceased: false, liveEventTypes: ['DEATH_ANNIVERSARY', 'BIRTHDAY'] },
      eventType: 'BIRTHDAY',
      occurrenceYear: 2026,
      eventYear: 1990,
    });

    expect(occasion.deceased).toBe(true);
    expect(occasion.sentiment).toBe('SOLEMN');
  });

  it('is SOLEMN for a death anniversary even with no other evidence at all', () => {
    const occasion = resolveOccasion({
      person: { isDeceased: false, liveEventTypes: [] },
      eventType: 'DEATH_ANNIVERSARY',
      occurrenceYear: 2026,
      eventYear: 2014,
    });

    expect(occasion.sentiment).toBe('SOLEMN');
    // A plain year count, never an ordinal that reads as an achievement.
    expect(occasion.milestone).toBe('12 years');
  });

  it('is SOLEMN for every event type once the person is deceased', () => {
    for (const type of CELEBRATORY_TYPES) {
      const occasion = resolveOccasion({
        person: { isDeceased: true, liveEventTypes: [type] },
        eventType: type,
        occurrenceYear: 2026,
        eventYear: 2001,
      });
      expect(occasion.sentiment).toBe('SOLEMN');
      // FR-EVT-03: anything else owned by a deceased person gets no milestone label.
      expect(occasion.milestone).toBeNull();
    }
  });

  it('keeps a living person celebratory, and keeps their milestone', () => {
    const occasion = resolveOccasion({
      person: { isDeceased: false, liveEventTypes: ['WEDDING_ANNIVERSARY'] },
      eventType: 'WEDDING_ANNIVERSARY',
      occurrenceYear: 2026,
      eventYear: 2001,
    });

    expect(occasion.sentiment).toBe('CELEBRATORY');
    expect(occasion.milestone).toBe('25th Silver');
  });

  it('leaves years and milestone null when the start year was never known (FR-EVT-02)', () => {
    const occasion = resolveOccasion({
      person: { isDeceased: false, liveEventTypes: ['BIRTHDAY'] },
      eventType: 'BIRTHDAY',
      occurrenceYear: 2026,
      eventYear: null,
    });

    expect(occasion.years).toBeNull();
    expect(occasion.milestone).toBeNull();
  });
});

describe('the family that sentiment maps to', () => {
  it('offers no celebratory theme for a solemn occasion, filtered OUT rather than ranked', () => {
    const solemn = themesFor('SOLEMN');

    expect(solemn.length).toBeGreaterThan(0);
    expect(solemn.every((t) => t.family === 'SOLEMN')).toBe(true);

    // FR-POS-13 is "filtered out of the picker", so the celebratory ids must be ABSENT
    // from the list rather than at the bottom of it.
    const celebratoryIds = themesFor('CELEBRATORY').map((t) => t.id);
    for (const id of celebratoryIds) {
      expect(solemn.map((t) => t.id)).not.toContain(id);
      expect(isThemeAllowed('SOLEMN', id)).toBe(false);
    }
  });

  it('refuses an unknown theme id rather than falling back to one that exists', () => {
    expect(isThemeAllowed('SOLEMN', 'no-such-theme')).toBe(false);
    expect(isThemeAllowed('CELEBRATORY', 'no-such-theme')).toBe(false);
  });

  it('maps each sentiment to exactly one family', () => {
    expect(familyFor('SOLEMN')).toBe('SOLEMN');
    expect(familyFor('CELEBRATORY')).toBe('CELEBRATORY');
  });
});
