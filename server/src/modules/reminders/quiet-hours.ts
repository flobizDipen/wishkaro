import { DateTime } from 'luxon';

/**
 * Quiet hours (FR-REM-09): no notification inside the user's window, deferred to the
 * window's end rather than dropped.
 *
 * Applied HERE, when the reminder row is written, so that `scheduled_at_utc` is already the
 * deferred instant. Both delivery paths then inherit the deferral from one implementation —
 * which matters more than it sounds, because the local alarm is the path that fires when the
 * server cannot reach the phone, and a fallback that wakes someone at 3am is worse than no
 * fallback at all.
 *
 * The window is a pair of LOCAL HOURS, not instants. It is a property of the user's day: a
 * person with 22:00–07:00 means it in whatever timezone they are standing in, and storing
 * instants would mean rewriting them every time they travelled.
 */

export interface QuietWindow {
  quietHoursStart: number | null;
  quietHoursEnd: number | null;
}

/**
 * Whether a local hour falls inside the window.
 *
 * The midnight-spanning case is the one that breaks naive implementations. `start <= h < end`
 * is right for 09:00–17:00 and silently wrong for 22:00–07:00, where it matches nothing at
 * all — so a user with overnight quiet hours gets woken at 3am by an app that believes it is
 * being quiet.
 *
 * Start inclusive, end exclusive. A reminder due exactly at the window's end is not in the
 * window, which is what makes "defer to the end of the window" terminate rather than defer
 * again by a day.
 */
export function isQuietHour(hour: number, start: number, end: number): boolean {
  if (start === end) return false; // refused at the API; treated as "no window" if it leaks in
  return start < end ? hour >= start && hour < end : hour >= start || hour < end;
}

/**
 * The instant a reminder should actually fire, given the user's quiet hours.
 *
 * Returns the input unchanged when there is no window or the reminder is already outside it
 * — including the case the prompt names as the one to reason about first: Asia/Kolkata,
 * quiet hours 22:00–07:00, a reminder due at 09:00. 09:00 is *outside* that window, so
 * nothing moves, and an implementation that gets the wrap-around wrong will either move it
 * (and deliver every morning reminder at 07:00) or not notice 03:00 at all.
 */
export function applyQuietHours(
  scheduledAtUtc: Date,
  zone: string,
  window: QuietWindow,
): Date {
  const { quietHoursStart: start, quietHoursEnd: end } = window;
  if (start === null || end === null || start === end) return scheduledAtUtc;

  const local = DateTime.fromJSDate(scheduledAtUtc, { zone });
  if (!local.isValid) return scheduledAtUtc;
  if (!isQuietHour(local.hour, start, end)) return scheduledAtUtc;

  // The next time it is `end` o'clock, strictly after the due instant. Same local day for a
  // 03:00 reminder in a 22:00–07:00 window; the next day for a 23:30 one.
  let release = local.set({ hour: end, minute: 0, second: 0, millisecond: 0 });
  if (release <= local) release = release.plus({ days: 1 });

  // A DST spring-forward can delete the window's end hour outright. Luxon normalises a
  // non-existent local time forward, so the release lands on the first instant that does
  // exist — late by an hour, which is the correct direction to be wrong in: the alternative
  // is firing inside the window the user asked to be left alone in.
  return release.toUTC().toJSDate();
}

/** The user's own calendar date for an instant. The key a digest is grouped by. */
export function localDateKey(instant: Date, zone: string): string {
  return DateTime.fromJSDate(instant, { zone }).toFormat('yyyy-MM-dd');
}
