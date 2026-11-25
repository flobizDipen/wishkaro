package `in`.wishkaro.app.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The client's half of FR-REM-09.
 *
 * The server normally does this for us — `scheduled_at_utc` arrives already deferred — so
 * this rule only runs on one path: the local fallback alarm for an event created offline
 * that the server has never materialised (docs/M2_PLAN.md §1.3).
 *
 * That makes it a SECOND implementation of a rule, which is a real cost and is named rather
 * than hidden. The mitigation is this file: the same table of cases as
 * `server/src/modules/reminders/quiet-hours.spec.ts`, so the two cannot drift silently. If
 * either side changes its mind about a boundary, one of the two suites goes red.
 */
class QuietHoursTest {

    @Test
    fun wrapsAroundMidnight() {
        // The case that breaks the naive version. `start <= h < end` is right for 09:00–17:00
        // and matches NOTHING for 22:00–07:00 — so a user with overnight quiet hours gets
        // woken at 3am by an app that believes it is being quiet.
        for (h in listOf(22, 23, 0, 1, 2, 3, 4, 5, 6)) {
            assertTrue(QuietHours.isQuietHour(h, 22, 7), "$h:00 should be inside 22:00–07:00")
        }
        for (h in listOf(7, 8, 9, 12, 17, 20, 21)) {
            assertFalse(QuietHours.isQuietHour(h, 22, 7), "$h:00 should be outside 22:00–07:00")
        }
    }

    @Test
    fun handlesAWindowThatDoesNotWrap() {
        for (h in listOf(9, 10, 16)) assertTrue(QuietHours.isQuietHour(h, 9, 17))
        for (h in listOf(8, 17, 18, 0)) assertFalse(QuietHours.isQuietHour(h, 9, 17))
    }

    @Test
    fun startIsInclusiveAndEndIsExclusive() {
        assertTrue(QuietHours.isQuietHour(22, 22, 7))
        // Exclusive at the end is what makes "defer to the window's end" terminate rather
        // than defer again by a day.
        assertFalse(QuietHours.isQuietHour(7, 22, 7))
    }

    @Test
    fun aZeroWidthWindowIsNoWindow() {
        // Refused at the API, because 22:00–22:00 reads as "never quiet" or "always quiet"
        // depending which way you take the interval. If one ever reaches the client it is
        // treated as no window — the direction that keeps reminders arriving.
        for (h in 0..23) assertFalse(QuietHours.isQuietHour(h, 22, 22))
    }

    /**
     * The case docs/BUILD_PROMPT_M2.md names as the one to reason about first: Asia/Kolkata,
     * quiet hours 22:00–07:00, a reminder due at 09:00. It is OUTSIDE the window.
     */
    fun nineAmIsNotQuietUnderAnOvernightWindow() {
        assertFalse(QuietHours.isQuietHour(9, 22, 7))
    }

    @Test
    fun theNineAmCaseFromTheBuildPrompt() = nineAmIsNotQuietUnderAnOvernightWindow()
}
