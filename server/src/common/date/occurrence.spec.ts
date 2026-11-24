import {
  civilFromDbDate,
  compareCivil,
  dbDateFromCivil,
  daysUntil,
  effectiveDate,
  formatCivil,
  InvalidEventDateError,
  isLeapYear,
  nextOccurrence,
  occursOnIn,
  reminderInstantUtc,
  todayIn,
  yearsAt,
  assertValidSpec,
} from './occurrence';

const IST = 'Asia/Kolkata';
const NY = 'America/New_York';

/** A UTC instant, written the way a human reads it. */
const utc = (iso: string) => new Date(`${iso}Z`);

describe('isLeapYear', () => {
  it.each([
    [2024, true],
    [2025, false],
    [2000, true], // divisible by 400
    [1900, false], // divisible by 100 but not 400 — the rule everyone forgets
    [2100, false],
  ])('%i → %s', (year, expected) => {
    expect(isLeapYear(year)).toBe(expected);
  });
});

describe('assertValidSpec', () => {
  it('accepts 29 Feb with an unknown year', () => {
    expect(() => assertValidSpec({ day: 29, month: 2, year: null })).not.toThrow();
  });

  it('accepts 29 Feb in a leap year', () => {
    expect(() => assertValidSpec({ day: 29, month: 2, year: 2024 })).not.toThrow();
  });

  it('rejects 29 Feb in a non-leap year — that date never existed', () => {
    expect(() => assertValidSpec({ day: 29, month: 2, year: 2023 })).toThrow(InvalidEventDateError);
  });

  it('rejects 31 April', () => {
    expect(() => assertValidSpec({ day: 31, month: 4, year: 1990 })).toThrow(InvalidEventDateError);
  });

  it.each([0, 13, -1])('rejects month %i', (month) => {
    expect(() => assertValidSpec({ day: 1, month, year: null })).toThrow(InvalidEventDateError);
  });
});

describe('occursOnIn — leap-day handling (FR-EVT-09)', () => {
  it('keeps 29 Feb in a leap year', () => {
    expect(occursOnIn({ day: 29, month: 2, year: 1996 }, 2028)).toEqual({
      year: 2028,
      month: 2,
      day: 29,
    });
  });

  it('observes a 29 Feb birthday on 28 Feb in a non-leap year', () => {
    expect(occursOnIn({ day: 29, month: 2, year: 1996 }, 2027)).toEqual({
      year: 2027,
      month: 2,
      day: 28,
    });
  });

  it('applies the same rule when the birth year is unknown', () => {
    expect(occursOnIn({ day: 29, month: 2, year: null }, 2027)).toEqual({
      year: 2027,
      month: 2,
      day: 28,
    });
  });

  it('leaves every other date alone', () => {
    expect(occursOnIn({ day: 14, month: 3, year: 1990 }, 2027)).toEqual({
      year: 2027,
      month: 3,
      day: 14,
    });
  });
});

describe('nextOccurrence', () => {
  it('returns this year when the date is still ahead', () => {
    const r = nextOccurrence({ day: 14, month: 3, year: 1990 }, IST, utc('2026-01-10T00:00:00'));
    expect(r).toEqual({ occurrenceYear: 2026, occursOn: { year: 2026, month: 3, day: 14 } });
  });

  it('rolls to next year once the date has passed', () => {
    const r = nextOccurrence({ day: 14, month: 3, year: 1990 }, IST, utc('2026-06-01T00:00:00'));
    expect(r).toEqual({ occurrenceYear: 2027, occursOn: { year: 2027, month: 3, day: 14 } });
  });

  it('returns TODAY when the event is today — not next year', () => {
    // 2026-03-14T00:30Z is 06:00 on 14 March in IST. The birthday is today.
    const r = nextOccurrence({ day: 14, month: 3, year: 1990 }, IST, utc('2026-03-14T00:30:00'));
    expect(r.occurrenceYear).toBe(2026);
    expect(r.occursOn).toEqual({ year: 2026, month: 3, day: 14 });
  });

  it('still returns today late in the evening of the event day', () => {
    // 23:30 IST on 14 March.
    const r = nextOccurrence({ day: 14, month: 3, year: 1990 }, IST, utc('2026-03-14T18:00:00'));
    expect(r.occursOn).toEqual({ year: 2026, month: 3, day: 14 });
  });

  it('uses the OWNER timezone, not UTC, to decide what "today" is', () => {
    // 2026-03-13T19:00Z is already 00:30 on 14 March in IST. A UTC-based implementation
    // would say the birthday is tomorrow; the owner is already living on the 14th.
    const r = nextOccurrence({ day: 14, month: 3, year: 1990 }, IST, utc('2026-03-13T19:00:00'));
    expect(r.occursOn).toEqual({ year: 2026, month: 3, day: 14 });
  });

  it('handles a year-unknown birthday exactly like a known one', () => {
    const r = nextOccurrence({ day: 2, month: 11, year: null }, IST, utc('2026-11-24T00:00:00'));
    expect(r).toEqual({ occurrenceYear: 2027, occursOn: { year: 2027, month: 11, day: 2 } });
  });

  it('29 Feb between leap years resolves to 28 Feb of the next non-leap year', () => {
    const r = nextOccurrence({ day: 29, month: 2, year: 1996 }, IST, utc('2026-06-01T00:00:00'));
    expect(r).toEqual({ occurrenceYear: 2027, occursOn: { year: 2027, month: 2, day: 28 } });
  });

  it('29 Feb on 28 Feb of a non-leap year counts as today, not next year', () => {
    const r = nextOccurrence({ day: 29, month: 2, year: 1996 }, IST, utc('2027-02-28T04:00:00'));
    expect(r.occursOn).toEqual({ year: 2027, month: 2, day: 28 });
  });

  it('31 Dec rolls over the year boundary correctly', () => {
    const r = nextOccurrence({ day: 31, month: 12, year: 1990 }, IST, utc('2026-12-31T20:00:00'));
    // 2026-12-31T20:00Z is 01:30 on 1 Jan 2027 in IST — the 31st has passed for the owner.
    expect(r).toEqual({ occurrenceYear: 2027, occursOn: { year: 2027, month: 12, day: 31 } });
  });
});

describe('reminderInstantUtc — the conversion that breaks products', () => {
  it('09:00 Asia/Kolkata is 03:30 UTC', () => {
    const at = reminderInstantUtc({ year: 2026, month: 3, day: 14 }, 9, IST);
    expect(at.toISOString()).toBe('2026-03-14T03:30:00.000Z');
  });

  it('09:00 America/New_York is 14:00 UTC in winter (EST, UTC-5)', () => {
    const at = reminderInstantUtc({ year: 2026, month: 1, day: 15 }, 9, NY);
    expect(at.toISOString()).toBe('2026-01-15T14:00:00.000Z');
  });

  it('09:00 America/New_York is 13:00 UTC in summer (EDT, UTC-4)', () => {
    const at = reminderInstantUtc({ year: 2026, month: 7, day: 15 }, 9, NY);
    expect(at.toISOString()).toBe('2026-07-15T13:00:00.000Z');
  });

  it('is DST-correct on the spring-forward day itself', () => {
    // US DST begins 08 March 2026. 09:00 local is already EDT.
    const at = reminderInstantUtc({ year: 2026, month: 3, day: 8 }, 9, NY);
    expect(at.toISOString()).toBe('2026-03-08T13:00:00.000Z');
  });

  it('rejects an unknown timezone rather than silently using UTC', () => {
    expect(() => reminderInstantUtc({ year: 2026, month: 3, day: 14 }, 9, 'Mars/Olympus')).toThrow(
      InvalidEventDateError,
    );
  });
});

describe('a user who changes timezone with a reminder already scheduled (FR-EVT-08)', () => {
  const spec = { day: 14, month: 3, year: 1990 };
  const now = utc('2026-01-10T00:00:00');

  it('moves the scheduled instant while keeping the same calendar date', () => {
    const occ = nextOccurrence(spec, IST, now);
    const before = reminderInstantUtc(occ.occursOn, 9, IST);

    // The user flies to New York. The occurrence date does not change — their friend's
    // birthday is still 14 March — but 09:00 now means a different instant.
    const after = reminderInstantUtc(occ.occursOn, 9, NY);

    expect(before.toISOString()).toBe('2026-03-14T03:30:00.000Z');
    expect(after.toISOString()).toBe('2026-03-14T13:00:00.000Z');
    expect(after.getTime()).toBeGreaterThan(before.getTime());
  });

  it('can move the occurrence date too, when the move crosses a day boundary', () => {
    // 2026-03-13T19:00Z: already the 14th in IST, still the 13th in New York.
    const at = utc('2026-03-13T19:00:00');
    expect(nextOccurrence(spec, IST, at).occursOn.day).toBe(14);
    expect(nextOccurrence(spec, NY, at).occursOn.day).toBe(14);
    // ...and for an event on the 13th, the two zones disagree about whether it has passed.
    const thirteenth = { day: 13, month: 3, year: 1990 };
    expect(nextOccurrence(thirteenth, IST, at).occurrenceYear).toBe(2027);
    expect(nextOccurrence(thirteenth, NY, at).occurrenceYear).toBe(2026);
  });
});

describe('yearsAt', () => {
  it('returns the age when the birth year is known', () => {
    expect(yearsAt({ day: 14, month: 3, year: 1990 }, 2026)).toBe(36);
  });

  it('returns null — never 0 — when the year is unknown', () => {
    expect(yearsAt({ day: 14, month: 3, year: null }, 2026)).toBeNull();
  });
});

describe('effectiveDate — COALESCE(override_date, occurs_on)', () => {
  const occursOn = { year: 2026, month: 10, day: 2 };

  it('falls back to occurs_on when there is no override', () => {
    expect(effectiveDate(occursOn, null)).toEqual(occursOn);
  });

  it('prefers the override when one is set', () => {
    const override = { year: 2026, month: 10, day: 14 };
    expect(effectiveDate(occursOn, override)).toEqual(override);
  });
});

describe('daysUntil', () => {
  it('is 0 on the day itself', () => {
    expect(daysUntil({ year: 2026, month: 3, day: 14 }, IST, utc('2026-03-14T05:00:00'))).toBe(0);
  });

  it('is 1 the day before, in the owner timezone', () => {
    expect(daysUntil({ year: 2026, month: 3, day: 14 }, IST, utc('2026-03-13T05:00:00'))).toBe(1);
  });

  it('spans a DST transition without drifting to 29 or 31 days', () => {
    // 1 March → 1 April 2026 in New York, across spring-forward.
    expect(daysUntil({ year: 2026, month: 4, day: 1 }, NY, utc('2026-03-01T17:00:00'))).toBe(31);
  });
});

describe('db date round-trip', () => {
  it('survives a trip through the Prisma @db.Date representation', () => {
    const d = { year: 2026, month: 2, day: 28 };
    expect(civilFromDbDate(dbDateFromCivil(d))).toEqual(d);
  });

  it('does not shift the day for a timezone behind UTC', () => {
    // The classic bug: storing local midnight and reading it back a day early.
    const stored = dbDateFromCivil({ year: 2026, month: 1, day: 1 });
    expect(stored.toISOString()).toBe('2026-01-01T00:00:00.000Z');
    expect(civilFromDbDate(stored).day).toBe(1);
  });
});

describe('helpers', () => {
  it('formats a civil date as ISO', () => {
    expect(formatCivil({ year: 2026, month: 3, day: 4 })).toBe('2026-03-04');
  });

  it('orders civil dates', () => {
    expect(compareCivil({ year: 2026, month: 1, day: 1 }, { year: 2026, month: 1, day: 2 })).toBeLessThan(0);
    expect(compareCivil({ year: 2027, month: 1, day: 1 }, { year: 2026, month: 12, day: 31 })).toBeGreaterThan(0);
  });

  it('reads today in the owner timezone', () => {
    expect(todayIn(IST, utc('2026-03-13T19:00:00'))).toEqual({ year: 2026, month: 3, day: 14 });
    expect(todayIn('UTC', utc('2026-03-13T19:00:00'))).toEqual({ year: 2026, month: 3, day: 13 });
  });
});
