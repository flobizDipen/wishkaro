package `in`.wishkaro.app.domain

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

/**
 * The shape of a month on screen (FR-VIEW-02), worked out without any Compose in sight.
 *
 * A calendar grid is almost entirely off-by-one risk — the leading blanks, whether a month
 * needs five rows or six, February in a leap year, and which day the week starts on. None of
 * that belongs inside a composable, where the only way to check it is to look at a phone.
 */

/**
 * The week starts on SUNDAY.
 *
 * This is an India-first app (`REQUIREMENTS.md`), and Indian calendars — wall, print and
 * the stock Android one in an Indian locale — run Sunday to Saturday. It is a constant
 * rather than a guess at the system locale on purpose: a grid that silently reshuffles
 * itself when someone travels is worse than one that is consistently the local convention.
 */
val WEEK_STARTS_ON: DayOfWeek = DayOfWeek.SUNDAY

/** A single square. [date] is null for the blanks that pad the first and last rows. */
data class MonthCell(val date: LocalDate?)

/**
 * One month, as rows of seven.
 *
 * [weeks] is always whole rows, so a renderer never has to think about ragged edges.
 */
data class MonthGrid(
    val year: Int,
    val month: Int,
    val weeks: List<List<MonthCell>>,
) {
    val days: List<LocalDate> get() = weeks.flatten().mapNotNull { it.date }
}

/** Day-of-week headings in the same order the grid lays its columns out. */
val WEEKDAY_INITIALS: List<String> = run {
    val initials = mapOf(
        DayOfWeek.MONDAY to "M",
        DayOfWeek.TUESDAY to "T",
        DayOfWeek.WEDNESDAY to "W",
        DayOfWeek.THURSDAY to "T",
        DayOfWeek.FRIDAY to "F",
        DayOfWeek.SATURDAY to "S",
        DayOfWeek.SUNDAY to "S",
    )
    (0..6).map { offset ->
        val day = DayOfWeek.entries[(WEEK_STARTS_ON.ordinal + offset) % 7]
        initials.getValue(day)
    }
}

/** How many days [month] has in [year]. February is the only one that needs asking. */
fun daysInMonth(year: Int, month: Int): Int = when (month) {
    2 -> if (BirthdayMath.isLeapYear(year)) 29 else 28
    4, 6, 9, 11 -> 30
    else -> 31
}

/** How far into the week [this] falls, counting from [WEEK_STARTS_ON]. */
private fun DayOfWeek.offsetFromWeekStart(): Int = (ordinal - WEEK_STARTS_ON.ordinal + 7) % 7

/**
 * Builds the grid for one month.
 *
 * Five rows when the month fits in five and six when it does not — rather than always six.
 * A fixed six-row grid leaves an empty row under February most years, and on a phone that
 * is a visible band of nothing under the calendar.
 */
fun monthGrid(year: Int, month: Int): MonthGrid {
    require(month in 1..12) { "month must be 1..12, was $month" }

    val first = LocalDate(year, month, 1)
    val leading = first.dayOfWeek.offsetFromWeekStart()
    val length = daysInMonth(year, month)

    val cells = buildList {
        repeat(leading) { add(MonthCell(null)) }
        for (day in 1..length) add(MonthCell(LocalDate(year, month, day)))
        // Pad only to the end of the LAST row that has a date in it.
        while (size % 7 != 0) add(MonthCell(null))
    }

    return MonthGrid(year = year, month = month, weeks = cells.chunked(7))
}

/** The month [offset] months after this year/month pair, wrapping the year. */
fun monthPlus(year: Int, month: Int, offset: Int): Pair<Int, Int> {
    val zeroBased = (year * 12 + (month - 1)) + offset
    return zeroBased.floorDiv(12) to zeroBased.mod(12) + 1
}

/** "March 2026", for a heading. */
fun monthTitle(year: Int, month: Int): String = "${MONTH_FULL_NAMES[month - 1]} $year"

val MONTH_FULL_NAMES: List<String> = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)

/**
 * The events that fall on each day of a month, keyed by day-of-month.
 *
 * Matched on the event's OWN month, not on where its next occurrence lands. Those differ for
 * every date that has already gone by this year, and matching on the next occurrence made
 * the current month look half empty: somebody born on the 10th disappeared from a calendar
 * opened on the 26th, because their next birthday is in the same month a year away and a
 * calendar that only reaches eleven months forward never gets there. A month grid is a view
 * of the month, not of the reminder queue.
 *
 * Placement still goes through [BirthdayMath.occursOnIn], so 29 February appears on the 28th
 * in a non-leap year — on the day the user will actually be reminded, rather than on a
 * square that does not exist.
 */
fun eventsByDay(entries: List<UpcomingEntry>, year: Int, month: Int): Map<Int, List<UpcomingEntry>> =
    entries
        .filter { it.event.date.month == month }
        .groupBy { BirthdayMath.occursOnIn(it.event.date.day, it.event.date.month, year).day }

/** One month of the year ahead, with whatever falls in it. */
data class TimelineMonth(
    val year: Int,
    val month: Int,
    val entries: List<UpcomingEntry>,
)

/**
 * FR-VIEW-03's year ahead, as month slots — **thirteen** of them, not twelve.
 *
 * Twelve is the bug. The timeline buckets each entry by where its NEXT occurrence lands, and
 * for anything whose day has already gone by this month that is the same month a year away.
 * Generating only `today.month .. today.month + 11` never produces that key, so those entries
 * were looked up in a slot that does not exist and vanished from the view entirely. Opened on
 * 26 November, November showed only people born on the 26th or later, and on the last day of
 * a month nearly the whole month was gone.
 *
 * Thirteen is not a fudge, it is the exact span: an annual event's next occurrence is always
 * within one year of today, so it falls between this month and the same month next year
 * INCLUSIVE, and that closed interval is thirteen slots wide. Every entry then has exactly
 * one slot, and every slot exists.
 *
 * **Why not [eventsByDay]'s fix.** The calendar had the same symptom and the opposite cure:
 * match on the event's own month, because a month grid is a view of the month rather than of
 * the reminder queue. The timeline IS the queue — it is ordered by [UpcomingEntry.daysUntil]
 * and every row shows a countdown — so putting a 20 November 2027 occurrence under a
 * "November 2026" heading would both mislabel the year and break that ordering, standing a
 * 364-day countdown next to a 2-day one. Same symptom, same root (next occurrence versus own
 * month), different right answer, because the two views mean different things by "month".
 *
 * The trailing slot is dropped when nothing falls in it, so a month opened on its 1st shows a
 * plain twelve. It only appears once something in this month has already passed — which is
 * precisely when the user needs to be told that it has.
 *
 * Empty slots in the middle are kept on purpose: a year view that skipped them would be the
 * upcoming list with different headings, and the point is to see the SHAPE of the year.
 */
fun timelineMonths(entries: List<UpcomingEntry>, today: LocalDate): List<TimelineMonth> {
    val byMonth = entries.groupBy { it.nextOccurrence.year to it.nextOccurrence.month.number }
    val slots = (0..MONTHS_AHEAD).map { offset ->
        val (year, month) = monthPlus(today.year, today.month.number, offset)
        TimelineMonth(
            year = year,
            month = month,
            // Ties break the way upcomingEvents already broke them, so the order inside a
            // slot is the order the Upcoming list would have shown.
            entries = byMonth[year to month].orEmpty()
                .sortedWith(compareBy({ it.daysUntil }, { it.displayName }, { it.event.id })),
        )
    }
    return if (slots.last().entries.isEmpty()) slots.dropLast(1) else slots
}

/**
 * Offset of the last slot: the same month next year. See [timelineMonths] for why the window
 * is closed at both ends rather than eleven months wide.
 */
private const val MONTHS_AHEAD = 12
