package `in`.wishkaro.app.domain

import kotlin.time.Clock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn

/**
 * The client's copy of the recurrence rules.
 *
 * It intentionally mirrors `server/src/common/date/occurrence.ts` rather than asking the
 * server: the list has to render with the network off, so "when is the next birthday" has to
 * be answerable on the device. Both implementations are unit-tested against the same cases,
 * and any disagreement between them is a bug in one of the two.
 */
object BirthdayMath {

    fun isLeapYear(year: Int): Boolean =
        (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

    /**
     * The date an event falls on in a given year.
     *
     * A 29 February event is observed on 28 February in a non-leap year (FR-EVT-09) — early
     * rather than late, because a reminder that arrives after the day is worthless.
     */
    fun occursOnIn(day: Int, month: Int, year: Int): LocalDate =
        if (month == 2 && day == 29 && !isLeapYear(year)) {
            LocalDate(year, 2, 28)
        } else {
            LocalDate(year, month, day)
        }

    /** The next occurrence on or after today. An event happening TODAY returns today. */
    fun nextOccurrence(
        day: Int,
        month: Int,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
        today: LocalDate = Clock.System.todayIn(timeZone),
    ): LocalDate {
        val thisYear = occursOnIn(day, month, today.year)
        return if (thisYear >= today) thisYear else occursOnIn(day, month, today.year + 1)
    }

    /** Whole days until the next occurrence. 0 means today. */
    fun daysUntilNext(
        day: Int,
        month: Int,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
        today: LocalDate = Clock.System.todayIn(timeZone),
    ): Int = today.daysUntil(nextOccurrence(day, month, timeZone, today))

    /**
     * Age on the next occurrence, or null when the birth year is unknown.
     *
     * Null, never 0 — "turning 0" is exactly the kind of copy FR-EVT-02 is written to prevent.
     */
    fun turningAge(
        day: Int,
        month: Int,
        year: Int?,
        timeZone: TimeZone = TimeZone.currentSystemDefault(),
        today: LocalDate = Clock.System.todayIn(timeZone),
    ): Int? {
        if (year == null) return null
        return nextOccurrence(day, month, timeZone, today).year - year
    }

    /** "Today", "Tomorrow", "in 12 days" — the only countdown copy M0 needs. */
    fun countdownLabel(daysUntil: Int): String = when (daysUntil) {
        0 -> "Today"
        1 -> "Tomorrow"
        else -> "in $daysUntil days"
    }

    fun formatDayMonth(day: Int, month: Int): String = "$day ${shortMonthName(month)}"

    /**
     * "Jan", "Feb", … — the same list [formatDayMonth] uses.
     *
     * Public since M7, because the design system's `DateBadge` draws the day and the month as
     * two separate pieces of type rather than as one string, and `PeopleListScreen` was already
     * carrying a second copy of this list for its month filter chips. Three copies of twelve
     * strings is how "March" and "Mar" end up disagreeing on two screens.
     */
    fun shortMonthName(month: Int): String = MONTH_NAMES.getOrElse(month - 1) { "?" }

    /** True when the given day/month pair can exist in some year. 29 Feb counts. */
    fun isRealDayMonth(day: Int, month: Int): Boolean {
        if (month !in 1..12 || day < 1) return false
        val longest = if (month == 2) 29 else LEAP_SAFE_MONTH_LENGTHS[month - 1]
        return day <= longest
    }

    private val LEAP_SAFE_MONTH_LENGTHS = intArrayOf(31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)

    private val MONTH_NAMES = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )
}

/** Adds [days] to a date. Small helper so callers do not import DatePeriod everywhere. */
fun LocalDate.plusDays(days: Int): LocalDate = plus(DatePeriod(days = days))
