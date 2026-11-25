import type { Sentiment } from '@prisma/client';
import { LADDER_ORDER, OFFSET_DAYS, ladderFor, reminderHourFor } from './ladder';
import { dedupeKeyFor } from './dedupe-key';
import { shiftCivil } from '../../common/date/occurrence';

const ALL_ON = { ladderD7: true, ladderD1: true, ladderD0: true };

describe('ladderFor (FR-REM-03)', () => {
  it('gives a celebratory event all three rungs by default', () => {
    expect(ladderFor('CELEBRATORY', ALL_ON)).toEqual(['D7', 'D1', 'D0']);
  });

  it('drops a rung the user has switched off', () => {
    expect(ladderFor('CELEBRATORY', { ...ALL_ON, ladderD7: false })).toEqual(['D1', 'D0']);
    expect(ladderFor('CELEBRATORY', { ...ALL_ON, ladderD1: false })).toEqual(['D7', 'D0']);
    expect(ladderFor('CELEBRATORY', { ...ALL_ON, ladderD0: false })).toEqual(['D7', 'D1']);
  });

  it('can be switched off entirely', () => {
    expect(ladderFor('CELEBRATORY', { ladderD7: false, ladderD1: false, ladderD0: false })).toEqual(
      [],
    );
  });
});

/**
 * FR-REM-11 and REQUIREMENTS.md §11's tone-safety row: "no SOLEMN event can ever produce …
 * a T-7 gifting reminder". Filed under correctness, not content.
 *
 * The prompt asks for "the test that fails if a D7 row is ever materialised for a SOLEMN
 * occurrence" — so this asserts the ABSENCE of the rung, not that something suppressed it.
 * A suppressed row is still a row, and a revive, a settings change or a plan fetch could
 * turn it back on.
 */
describe('the solemn ladder (FR-REM-11)', () => {
  it('gives a solemn event T-1 and morning-of, and never T-7', () => {
    expect(ladderFor('SOLEMN', ALL_ON)).toEqual(['D1', 'D0']);
  });

  it('cannot be given T-7 by any combination of user settings', () => {
    // Every reachable settings state, enumerated rather than sampled: if someone adds a
    // fourth toggle later this test keeps covering the cross-product it can reach.
    for (const d7 of [true, false]) {
      for (const d1 of [true, false]) {
        for (const d0 of [true, false]) {
          const rungs = ladderFor('SOLEMN', { ladderD7: d7, ladderD1: d1, ladderD0: d0 });
          expect(rungs).not.toContain('D7');
        }
      }
    }
  });

  it('is the only sentiment that loses a rung — so a new one must choose deliberately', () => {
    const sentiments: Sentiment[] = ['CELEBRATORY', 'SOLEMN'];
    const withD7 = sentiments.filter((s) => ladderFor(s, ALL_ON).includes('D7'));
    expect(withD7).toEqual(['CELEBRATORY']);
  });

  it('still gives the two rungs it is entitled to when D7 is also switched off', () => {
    expect(ladderFor('SOLEMN', { ...ALL_ON, ladderD7: false })).toEqual(['D1', 'D0']);
  });

  /**
   * The rungs a solemn occurrence does NOT get must have no key in play at all, so that the
   * materialiser's reconciliation and the client's plan agree there is nothing there.
   */
  it('leaves the D7 key unminted for a solemn occurrence', () => {
    const minted = ladderFor('SOLEMN', ALL_ON).map((o) => dedupeKeyFor('e1', 2027, o));
    expect(minted).not.toContain(dedupeKeyFor('e1', 2027, 'D7'));
    expect(minted).toEqual(['e1:2027:D1', 'e1:2027:D0']);
  });
});

describe('rung timing', () => {
  it('fires each rung the right number of days before the occurrence', () => {
    expect(OFFSET_DAYS.D7).toBe(7);
    expect(OFFSET_DAYS.D1).toBe(1);
    expect(OFFSET_DAYS.D0).toBe(0);
  });

  /** A 1 January birthday's T-7 is the previous December, which naive month maths gets wrong. */
  it('crosses a year boundary', () => {
    expect(shiftCivil({ year: 2027, month: 1, day: 1 }, -OFFSET_DAYS.D7)).toEqual({
      year: 2026,
      month: 12,
      day: 25,
    });
    expect(shiftCivil({ year: 2027, month: 1, day: 1 }, -OFFSET_DAYS.D1)).toEqual({
      year: 2026,
      month: 12,
      day: 31,
    });
  });

  /** 1 March in a leap year is seven days after 23 February, not 22. */
  it('counts 29 February when there is one', () => {
    expect(shiftCivil({ year: 2028, month: 3, day: 1 }, -7)).toEqual({
      year: 2028,
      month: 2,
      day: 23,
    });
    expect(shiftCivil({ year: 2027, month: 3, day: 1 }, -7)).toEqual({
      year: 2027,
      month: 2,
      day: 22,
    });
  });

  it('keeps the ladder in order, furthest out first', () => {
    expect(LADDER_ORDER).toEqual(['D7', 'D1', 'D0']);
  });
});

describe('reminderHourFor (FR-REM-03)', () => {
  it('uses the global hour when there is no override', () => {
    expect(reminderHourFor(null, 9)).toBe(9);
    expect(reminderHourFor(undefined, 7)).toBe(7);
  });

  it('lets a person override it', () => {
    expect(reminderHourFor(18, 9)).toBe(18);
  });

  /** `??` not `||`. Midnight is a real hour, and `||` would promote it to the global one. */
  it('treats midnight as a real choice', () => {
    expect(reminderHourFor(0, 9)).toBe(0);
  });
});
