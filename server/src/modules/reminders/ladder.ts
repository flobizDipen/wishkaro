import type { ReminderOffset, Sentiment } from '@prisma/client';

/**
 * The reminder ladder (FR-REM-03) and the solemn ladder (FR-REM-11).
 *
 * Pure, and deliberately the ONLY place that decides which rungs an occurrence gets. The
 * alternative — a `if (sentiment === 'SOLEMN') skip D7` somewhere inside the materialiser —
 * is a rule that can be forgotten by the next call site, and REQUIREMENTS.md §11 lists "no
 * SOLEMN event can ever produce … a T-7 gifting reminder" under *correctness*, not under
 * content preferences.
 */

/** How many days before the occurrence each rung fires. */
export const OFFSET_DAYS: Readonly<Record<Exclude<ReminderOffset, 'DIGEST'>, number>> = {
  D7: 7,
  D1: 1,
  D0: 0,
};

/** Ladder order, furthest out first. Stable, because the digest renders in this order too. */
export const LADDER_ORDER = ['D7', 'D1', 'D0'] as const satisfies readonly ReminderOffset[];

export interface LadderSettings {
  ladderD7: boolean;
  ladderD1: boolean;
  ladderD0: boolean;
}

/**
 * Which rungs an occurrence of this sentiment gets.
 *
 * **T-7 is unavailable to a SOLEMN event, whatever the user's settings say.** FR-REM-11:
 * the week-ahead rung exists to give someone time to buy a present, and there is no present
 * to buy for a death anniversary. FR-EVT-04 says the same thing from the other direction —
 * creating a death anniversary "drops the T-7 gifting reminder".
 *
 * This is not a copy change. The row must not be created: a suppressed row is still a row
 * that a later revive, a settings change or a plan fetch could turn back on, and the point
 * is that there is nothing to turn on. The test asserts the absence, not the suppression.
 *
 * A user toggle can only ever REMOVE a rung here, never add one back — so no setting, now or
 * later, can reintroduce T-7 for a solemn occurrence by accident.
 */
export function ladderFor(
  sentiment: Sentiment,
  settings: LadderSettings,
): Exclude<ReminderOffset, 'DIGEST'>[] {
  const enabled: Exclude<ReminderOffset, 'DIGEST'>[] = [];

  // FR-REM-11 first, and as a hard gate rather than a filter folded in with the toggles,
  // so that reading this function tells you the rule rather than making you derive it.
  if (sentiment !== 'SOLEMN' && settings.ladderD7) enabled.push('D7');
  if (settings.ladderD1) enabled.push('D1');
  if (settings.ladderD0) enabled.push('D0');

  return enabled;
}

/**
 * The local hour this person's reminders fire at (FR-REM-03).
 *
 * `?? `, not `||`: 0 is midnight, a real hour someone might choose, and `||` would silently
 * promote it to the global setting.
 */
export function reminderHourFor(
  personOverride: number | null | undefined,
  userHour: number,
): number {
  return personOverride ?? userHour;
}
