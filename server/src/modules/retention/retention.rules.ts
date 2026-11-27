/**
 * FR-SET-06, as a decision anybody can read, test and argue with — separately from the
 * database work that carries it out.
 *
 * > "Data retention: uploaded source photos purged after **90 days unless attached to a saved
 * > poster**; generated media after **180 days with a warning**; **both user-overridable to
 * > keep**."
 *
 * That is one sentence and **three clauses**, which is why it gets its own file. M6_VERIFY
 * §4.7 bought the checklist entry that says so: *a requirement clause carrying a number can be
 * missed inside a rule that is otherwise honoured* — FR-POS-09 states two latency targets and
 * only one of them ever got a definition-of-done item. Here the clause most likely to be lost
 * is the warning, because a sweep that purges on the date alone looks finished and passes
 * every test anybody would think to write about dates.
 *
 * ## The one structural decision in here
 *
 * **The warning is a precondition of deletion, not a notification the job sends and hopes
 * about.** `purge_warned_at` must be set and at least {@link RETENTION_WARNING_DAYS} old
 * before generated media can be purged. Three things follow, and all three are properties a
 * test can ask for:
 *
 * 1. Generated media cannot be destroyed without notice, by construction — not by the job
 *    remembering to send something first.
 * 2. A deployment whose sweep was switched off for a month does not then delete a year of
 *    posters on the first night it comes back. Every one of them is warned on that sweep and
 *    purged a fortnight later.
 * 3. "Warned" is a fact in the database rather than an event in a log, so the app can show the
 *    user what is about to go while there is still time to press Keep.
 *
 * Source photos get no warning clause in FR-SET-06 and are swept on the date alone. That
 * asymmetry is the requirement's, not an oversight here: a source photo is an input the user
 * uploaded, a poster is a thing they made.
 */

/** FR-SET-06's 90 days. Written by `PhotoService.upload`; re-stated here so the sweep's tests can own the boundary. */
export const SOURCE_PHOTO_RETENTION_DAYS = 90;

/** FR-SET-06's 180 days. Written by `PosterPipelineService.persist`. */
export const GENERATED_MEDIA_RETENTION_DAYS = 180;

/**
 * How long before its expiry generated media is warned, and the minimum notice that must have
 * elapsed before it can be purged. **One constant, used for both**, so the two cannot drift
 * into a window that warns later than it deletes.
 *
 * Fourteen days: long enough that somebody who opens the app weekly sees it twice, short
 * enough that the warning is about something imminent rather than a standing notice people
 * learn to ignore.
 */
export const RETENTION_WARNING_DAYS = 14;

export const DAY_MS = 24 * 60 * 60 * 1000;

/** The facts the rules need. Deliberately not a Prisma row — this file knows nothing about Prisma. */
export interface RetentionCandidate {
  kind: 'POSTER' | 'SOURCE_PHOTO';
  retention: 'AUTO' | 'KEEP';
  expiresAt: Date | null;
  purgeWarnedAt: Date | null;
  purgedAt: Date | null;
  /**
   * SOURCE_PHOTO only: does a poster that has not been tombstoned record this asset as its
   * source? FR-SET-06's "unless attached to a saved poster".
   *
   * A recorded link, never a guess — `poster.source_asset_id` exists and is backfilled
   * precisely so that this is a fact rather than a heuristic evaluated afresh every night.
   * See docs/M9_PLAN.md C3.
   */
  attachedToSavedPoster: boolean;
}

export type RetentionAction =
  /** Nothing to do tonight. */
  | { action: 'SKIP'; reason: SkipReason }
  /** Generated media approaching its expiry: record the warning, delete nothing. */
  | { action: 'WARN' }
  /** Bytes go. */
  | { action: 'PURGE' };

export type SkipReason =
  | 'ALREADY_PURGED'
  | 'KEPT_BY_USER'
  | 'NO_EXPIRY'
  | 'NOT_DUE'
  | 'ATTACHED_TO_POSTER'
  | 'AWAITING_WARNING_PERIOD';

/**
 * What the sweep should do with one asset, right now.
 *
 * Total, pure and ordered. The order is the argument: a user's KEEP beats every clock in the
 * product, and a photo a poster depends on is protected before anybody looks at a date —
 * otherwise a race between "is it due" and "is it attached" would be decided by whichever
 * branch was written first.
 */
export function decide(candidate: RetentionCandidate, now: Date): RetentionAction {
  // Already dealt with. Checked first so a re-run of the sweep is free and idempotent.
  if (candidate.purgedAt) return { action: 'SKIP', reason: 'ALREADY_PURGED' };

  // FR-SET-06's third clause. Before the dates, deliberately: the user said keep, and no
  // deadline overrides that. Switching back to AUTO restores the original deadline, which is
  // why `expires_at` is left alone rather than nulled (see schema.prisma).
  if (candidate.retention === 'KEEP') return { action: 'SKIP', reason: 'KEPT_BY_USER' };

  // An asset with no expiry has no policy. Nothing writes this today — both creation sites set
  // a date — but a null here must never be read as "expired long ago", which is what a naive
  // `expiresAt <= now` on a null would do in SQL and what `null <= now` does NOT do in JS.
  // Stated rather than left to the type.
  if (!candidate.expiresAt) return { action: 'SKIP', reason: 'NO_EXPIRY' };

  // FR-SET-06's first clause, second half. Before the date check, so a photo a poster depends
  // on is never a candidate for warning either — warning about something that will never be
  // deleted is noise that teaches the user to ignore the real ones.
  if (candidate.kind === 'SOURCE_PHOTO' && candidate.attachedToSavedPoster) {
    return { action: 'SKIP', reason: 'ATTACHED_TO_POSTER' };
  }

  const due = candidate.expiresAt.getTime() <= now.getTime();

  if (candidate.kind === 'SOURCE_PHOTO') {
    // No warning clause for source photos. The date alone.
    return due ? { action: 'PURGE' } : { action: 'SKIP', reason: 'NOT_DUE' };
  }

  // Generated media: FR-SET-06's second clause, "with a warning".
  const warnFrom = candidate.expiresAt.getTime() - RETENTION_WARNING_DAYS * DAY_MS;

  if (!candidate.purgeWarnedAt) {
    // Warn as soon as the window opens, and ALSO when the asset is already overdue — an
    // overdue-but-unwarned asset means the sweep was not running, and the user is owed the
    // notice period from now rather than retroactively.
    return now.getTime() >= warnFrom ? { action: 'WARN' } : { action: 'SKIP', reason: 'NOT_DUE' };
  }

  if (!due) return { action: 'SKIP', reason: 'NOT_DUE' };

  const noticeGiven = now.getTime() - candidate.purgeWarnedAt.getTime();
  return noticeGiven >= RETENTION_WARNING_DAYS * DAY_MS
    ? { action: 'PURGE' }
    : { action: 'SKIP', reason: 'AWAITING_WARNING_PERIOD' };
}

/**
 * The expiry an asset of this kind gets, from the moment it was created.
 *
 * Here rather than inline at the two creation sites so that `PhotoService`,
 * `PosterPipelineService` and the un-keep path in `RetentionService` cannot hold three
 * different opinions about what 90 and 180 mean. M6 wrote the arithmetic out longhand in both
 * places; this is the same number, named once.
 */
export function expiryFor(kind: 'POSTER' | 'SOURCE_PHOTO', createdAt: Date): Date {
  const days = kind === 'POSTER' ? GENERATED_MEDIA_RETENTION_DAYS : SOURCE_PHOTO_RETENTION_DAYS;
  return new Date(createdAt.getTime() + days * DAY_MS);
}
