import type { ReminderOffset } from '@prisma/client';

/**
 * The reminder de-duplication key.
 *
 * Two independent delivery paths carry the same reminder — the server's FCM push and, from
 * M2, an alarm the phone set for itself (FR-REM-02). The user must see exactly ONE
 * notification, and the only thing standing between them and two is that both sides compute
 * the same string. So the string is a pure function, here, with a test, rather than an
 * expression inlined at two call sites in two languages.
 *
 * ## Why the occurrence YEAR and not the occurrence date
 *
 * M0 and M1 used `${occurrence.id}:D0`. The phone has never seen an occurrence id —
 * `event_occurrence` is not a sync entity — so it could not produce that key at all, which
 * is the blocker M2 opened with (docs/M2_PLAN.md §1).
 *
 * The obvious replacement is `${eventId}:${YYYY-MM-DD}:${offset}`, built from the effective
 * occurrence date. It is wrong, and subtly so. THREE things move an occurrence's effective
 * date after its reminder row already exists:
 *
 *   1. the user edits the event's day or month;
 *   2. the user sets a per-occurrence `override_date` (FR-EVT-10) — which the client cannot
 *      see at all, because overrides live on `event_occurrence`;
 *   3. 29 February falls back to 28 February in a non-leap year (FR-EVT-09).
 *
 * With a date in the key, each of those turns one reminder into two keys. The server can
 * reconcile its own stale row. The *client* cannot: a phone that has not synced since the
 * edit still holds the old date, fires `E:2027-04-04:D0`, and then the server's push arrives
 * carrying `E:2027-04-05:D0`. Two notifications for one birthday — precisely the failure
 * FR-REM-02 forbids, and invisible to any test that exercises one path at a time.
 *
 * `occurrenceYear` cannot move. It is already the uniqueness key of
 * `event_occurrence(event_id, occurrence_year)`, so there is exactly one occurrence per event
 * per year by construction. Under it a stale client fires on the wrong DAY — a visible
 * staleness bug that the next sync corrects — but the user still sees one notification.
 * Wrong-day-once beats right-day-twice.
 *
 * The client computes the same value from `BirthdayMath.nextOccurrence(day, month, zone).year`
 * with no new data and no new sync entity.
 */
export function dedupeKeyFor(
  eventId: string,
  occurrenceYear: number,
  offset: ReminderOffset,
): string {
  if (!Number.isInteger(occurrenceYear)) {
    throw new TypeError(`occurrenceYear must be an integer, got ${occurrenceYear}`);
  }
  return `${eventId}:${occurrenceYear}:${offset}`;
}

/**
 * The self-test key (FR-REM-07). Deliberately a different shape: a self-test has no event and
 * no occurrence, it must work before the user has added anybody, and it must never collide
 * with a real reminder.
 */
export function testDedupeKey(userId: string, scheduledAtUtc: Date): string {
  return `test:${userId}:${scheduledAtUtc.getTime()}`;
}

/**
 * The digest key (FR-REM-08). One per user per local day, because a digest is a property of
 * the day rather than of any one event — which is also what makes "exactly one digest" a
 * uniqueness constraint rather than a hope.
 *
 * `localDate` is the user's own calendar date, `YYYY-MM-DD`, not a UTC date: a digest for
 * "today in Asia/Kolkata" is one thing even though it spans two UTC dates.
 */
export function digestDedupeKey(userId: string, localDate: string): string {
  return `digest:${userId}:${localDate}`;
}

/** True for keys this module minted for a real event occurrence, and not for the others. */
export function isEventDedupeKey(key: string): boolean {
  return !key.startsWith('test:') && !key.startsWith('digest:');
}
