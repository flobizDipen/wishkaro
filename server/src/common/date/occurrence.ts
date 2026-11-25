import { DateTime } from 'luxon';

/**
 * Pure date and recurrence logic. No NestJS, no Prisma, no I/O — so the highest-bug-density
 * code in the product (REQUIREMENTS.md §11) is testable without a database.
 *
 * Two rules hold everywhere in this file:
 *   1. Every instant that leaves here is UTC.
 *   2. Every *date* a human would recognise is computed in the owner's timezone.
 */

/** A recurring annual date. `year` is null when the birth year is unknown (FR-EVT-02). */
export interface EventDateSpec {
  day: number;
  month: number;
  year: number | null;
}

/** A calendar date with no time and no zone. */
export interface CivilDate {
  year: number;
  month: number;
  day: number;
}

export interface Occurrence {
  /** The calendar year this occurrence belongs to. Stable key for the materialiser. */
  occurrenceYear: number;
  /** The date it actually falls on, after the leap-day rule. */
  occursOn: CivilDate;
}

export class InvalidEventDateError extends Error {}

export function isLeapYear(year: number): boolean {
  return (year % 4 === 0 && year % 100 !== 0) || year % 400 === 0;
}

function daysInMonth(year: number, month: number): number {
  return DateTime.fromObject({ year, month, day: 1 }, { zone: 'utc' }).daysInMonth ?? 31;
}

export function assertValidSpec(spec: EventDateSpec): void {
  if (!Number.isInteger(spec.month) || spec.month < 1 || spec.month > 12) {
    throw new InvalidEventDateError(`month must be 1..12, got ${spec.month}`);
  }
  if (!Number.isInteger(spec.day) || spec.day < 1) {
    throw new InvalidEventDateError(`day must be >= 1, got ${spec.day}`);
  }
  // 29 Feb is legal as a *spec* even though it does not exist every year — that is the
  // whole point of FR-EVT-09. Validate against the longest possible month instead.
  const longest = spec.month === 2 ? 29 : daysInMonth(2000, spec.month);
  if (spec.day > longest) {
    throw new InvalidEventDateError(`day ${spec.day} does not exist in month ${spec.month}`);
  }
  if (spec.year !== null) {
    if (!Number.isInteger(spec.year) || spec.year < 1900 || spec.year > 2200) {
      throw new InvalidEventDateError(`year must be 1900..2200 or null, got ${spec.year}`);
    }
    if (spec.month === 2 && spec.day === 29 && !isLeapYear(spec.year)) {
      throw new InvalidEventDateError(`29 Feb ${spec.year} is not a real date`);
    }
  }
}

/**
 * The date an event falls on in a given year.
 *
 * A 29 February event lands on 28 February in a non-leap year (FR-EVT-09) — observed early
 * rather than late, because a reminder that arrives after the day is worthless.
 */
export function occursOnIn(spec: EventDateSpec, occurrenceYear: number): CivilDate {
  assertValidSpec(spec);
  if (spec.month === 2 && spec.day === 29 && !isLeapYear(occurrenceYear)) {
    return { year: occurrenceYear, month: 2, day: 28 };
  }
  return { year: occurrenceYear, month: spec.month, day: spec.day };
}

export function compareCivil(a: CivilDate, b: CivilDate): number {
  if (a.year !== b.year) return a.year - b.year;
  if (a.month !== b.month) return a.month - b.month;
  return a.day - b.day;
}

/** Today's date as seen by someone standing in `zone`. */
export function todayIn(zone: string, now: Date): CivilDate {
  const dt = DateTime.fromJSDate(now, { zone });
  if (!dt.isValid) throw new InvalidEventDateError(`unknown timezone: ${zone}`);
  return { year: dt.year, month: dt.month, day: dt.day };
}

/**
 * The next occurrence on or after today, in the owner's timezone.
 *
 * "On or after" matters: an event happening *today* is the single most important thing this
 * app can show, and rolling it to next year is the classic off-by-one in date code.
 */
export function nextOccurrence(spec: EventDateSpec, zone: string, now: Date): Occurrence {
  assertValidSpec(spec);
  const today = todayIn(zone, now);
  const thisYear = occursOnIn(spec, today.year);
  if (compareCivil(thisYear, today) >= 0) {
    return { occurrenceYear: today.year, occursOn: thisYear };
  }
  return { occurrenceYear: today.year + 1, occursOn: occursOnIn(spec, today.year + 1) };
}

/**
 * The effective date of an occurrence: COALESCE(override_date, occurs_on).
 *
 * Nothing writes an override in M0, but every read goes through here from day one so the
 * feature is a UI change later rather than an audit of every call site (OD-16).
 */
export function effectiveDate(occursOn: CivilDate, overrideDate: CivilDate | null): CivilDate {
  return overrideDate ?? occursOn;
}

/**
 * The UTC instant at which a reminder for `date` should fire, given a local hour.
 *
 * This is the conversion that makes or breaks the product: 09:00 in Asia/Kolkata is 03:30 UTC,
 * and 09:00 in America/New_York is 13:00 or 14:00 UTC depending on the time of year. Nothing
 * here caches an offset.
 */
export function reminderInstantUtc(date: CivilDate, hourLocal: number, zone: string): Date {
  const dt = DateTime.fromObject(
    { year: date.year, month: date.month, day: date.day, hour: hourLocal, minute: 0, second: 0 },
    { zone },
  );
  if (!dt.isValid) {
    throw new InvalidEventDateError(`cannot build ${hourLocal}:00 on ${formatCivil(date)} in ${zone}`);
  }
  return dt.toUTC().toJSDate();
}

/** Age (or years elapsed) on a given occurrence. Null when the year is unknown — never 0. */
export function yearsAt(spec: EventDateSpec, occurrenceYear: number): number | null {
  if (spec.year === null) return null;
  return occurrenceYear - spec.year;
}

/** Whole days from today to the occurrence, in the owner's timezone. 0 means today. */
export function daysUntil(occursOn: CivilDate, zone: string, now: Date): number {
  const today = todayIn(zone, now);
  const a = DateTime.fromObject(today, { zone: 'utc' });
  const b = DateTime.fromObject(occursOn, { zone: 'utc' });
  return Math.round(b.diff(a, 'days').days);
}

export function formatCivil(d: CivilDate): string {
  const mm = String(d.month).padStart(2, '0');
  const dd = String(d.day).padStart(2, '0');
  return `${d.year}-${mm}-${dd}`;
}

/** Converts a Prisma `@db.Date` value to a CivilDate without a timezone round-trip. */
export function civilFromDbDate(value: Date): CivilDate {
  return { year: value.getUTCFullYear(), month: value.getUTCMonth() + 1, day: value.getUTCDate() };
}

/** Converts a CivilDate to the UTC-midnight Date that Prisma stores in a `@db.Date` column. */
export function dbDateFromCivil(d: CivilDate): Date {
  return new Date(Date.UTC(d.year, d.month - 1, d.day, 0, 0, 0, 0));
}

export function isValidTimezone(zone: string): boolean {
  return DateTime.local().setZone(zone).isValid;
}
