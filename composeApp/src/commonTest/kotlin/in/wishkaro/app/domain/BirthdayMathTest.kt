package `in`.wishkaro.app.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The client half of the recurrence rules, tested against the same cases as
 * `server/src/common/date/occurrence.spec.ts`. If these two ever disagree, the list on the
 * phone and the reminder the server sends will disagree too.
 */
class BirthdayMathTest {

    private val ist = TimeZone.of("Asia/Kolkata")

    @Test
    fun leapYears() {
        assertTrue(BirthdayMath.isLeapYear(2024))
        assertFalse(BirthdayMath.isLeapYear(2025))
        assertTrue(BirthdayMath.isLeapYear(2000))
        assertFalse(BirthdayMath.isLeapYear(1900))
    }

    @Test
    fun `29 Feb falls on 28 Feb in a non-leap year`() {
        assertEquals(LocalDate(2027, 2, 28), BirthdayMath.occursOnIn(29, 2, 2027))
        assertEquals(LocalDate(2028, 2, 29), BirthdayMath.occursOnIn(29, 2, 2028))
    }

    @Test
    fun `a birthday still ahead resolves to this year`() {
        val today = LocalDate(2026, 1, 10)
        assertEquals(LocalDate(2026, 3, 14), BirthdayMath.nextOccurrence(14, 3, ist, today))
    }

    @Test
    fun `a birthday already past rolls to next year`() {
        val today = LocalDate(2026, 6, 1)
        assertEquals(LocalDate(2027, 3, 14), BirthdayMath.nextOccurrence(14, 3, ist, today))
    }

    @Test
    fun `a birthday today is today, not next year`() {
        val today = LocalDate(2026, 3, 14)
        assertEquals(today, BirthdayMath.nextOccurrence(14, 3, ist, today))
        assertEquals(0, BirthdayMath.daysUntilNext(14, 3, ist, today))
    }

    @Test
    fun `31 Dec rolls across the year boundary`() {
        val today = LocalDate(2027, 1, 1)
        assertEquals(LocalDate(2027, 12, 31), BirthdayMath.nextOccurrence(31, 12, ist, today))
    }

    @Test
    fun `days until is one the day before`() {
        assertEquals(1, BirthdayMath.daysUntilNext(14, 3, ist, LocalDate(2026, 3, 13)))
    }

    @Test
    fun `age is null when the birth year is unknown, never zero`() {
        assertNull(BirthdayMath.turningAge(14, 3, null, ist, LocalDate(2026, 1, 1)))
    }

    @Test
    fun `age is computed against the occurrence year, not the current year`() {
        // Already past in 2026, so the next occurrence is 2027 and they turn 37, not 36.
        assertEquals(37, BirthdayMath.turningAge(14, 3, 1990, ist, LocalDate(2026, 6, 1)))
        assertEquals(36, BirthdayMath.turningAge(14, 3, 1990, ist, LocalDate(2026, 1, 1)))
    }

    @Test
    fun `29 Feb with an unknown year still resolves`() {
        val next = BirthdayMath.nextOccurrence(29, 2, ist, LocalDate(2026, 6, 1))
        assertEquals(LocalDate(2027, 2, 28), next)
        assertNull(BirthdayMath.turningAge(29, 2, null, ist, LocalDate(2026, 6, 1)))
    }

    @Test
    fun `countdown copy`() {
        assertEquals("Today", BirthdayMath.countdownLabel(0))
        assertEquals("Tomorrow", BirthdayMath.countdownLabel(1))
        assertEquals("in 12 days", BirthdayMath.countdownLabel(12))
    }

    @Test
    fun `date validity accepts 29 Feb but rejects 31 April`() {
        assertTrue(BirthdayMath.isRealDayMonth(29, 2))
        assertFalse(BirthdayMath.isRealDayMonth(30, 2))
        assertFalse(BirthdayMath.isRealDayMonth(31, 4))
        assertTrue(BirthdayMath.isRealDayMonth(31, 12))
        assertFalse(BirthdayMath.isRealDayMonth(1, 13))
    }

    @Test
    fun `formats a day and month without inventing a year`() {
        assertEquals("14 Mar", BirthdayMath.formatDayMonth(14, 3))
    }
}
