package `in`.wishkaro.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus

/**
 * The year ahead's month bucketing (FR-VIEW-03).
 *
 * This exists because the bucketing used to live inline in `TimelineScreen`, where there was
 * no seam to test and the only way to see it was to look at a phone — and what it did was
 * drop, silently and completely, every event whose day had already gone by this month. Opened
 * on 26 November, November showed only people born on the 26th or later; on the last day of a
 * month nearly the whole month was missing. Nobody noticed, because a view whose job is to
 * list things has no way of looking wrong when it lists fewer of them.
 *
 * The cases below are the shape of that bug: a day already past, the end of a month, a
 * December-to-January roll, and the invariant that matters more than any single case —
 * every entry lands in exactly one slot.
 */
class TimelineMonthsTest {

    // 26 November 2026 is the day the calendar bug was found on, so the timeline gets the
    // same date: the two views disagreed about this exact day.
    private val nov26 = LocalDate(2026, 11, 26)

    private fun entry(name: String, day: Int, month: Int, today: LocalDate): UpcomingEntry {
        val person = Person(
            id = "p-$name", displayName = name, relationToOwner = null, isSelf = false,
            gender = null, phone = null, email = null, notes = null, isDeceased = false,
            isMinor = false, completenessScore = 0, completenessSuggestions = emptyList(),
            events = emptyList(), traits = emptyList(), deletedAt = null,
        )
        val event = PersonEvent(
            id = "e-$name", personId = person.id, type = EventType.BIRTHDAY,
            sentiment = Sentiment.CELEBRATORY, label = null, isMuted = false,
            date = EventDate(day, month, 1990),
        )
        return upcomingFor(person.copy(events = listOf(event)), TimeZone.UTC, today).single()
    }

    private fun List<TimelineMonth>.slot(year: Int, month: Int): TimelineMonth? =
        firstOrNull { it.year == year && it.month == month }

    private fun List<TimelineMonth>.names(): List<String> =
        flatMap { it.entries }.map { it.displayName }

    // -----------------------------------------------------------------------------------
    // The bug
    // -----------------------------------------------------------------------------------

    @Test
    fun `a birthday earlier this month is still somewhere in the year ahead`() {
        // THE regression. 20 November has gone by on the 26th, so the next occurrence is
        // 20 November 2027 — a key a twelve-month window starting this month never produces.
        // The entry was looked up in a slot that did not exist and disappeared from the view.
        val months = timelineMonths(listOf(entry("Earlier", 20, 11, nov26)), nov26)

        assertEquals(listOf("Earlier"), months.names(), "the entry must appear exactly once")
        assertEquals(
            listOf("Earlier"),
            months.slot(2027, 11)?.entries?.map { it.displayName },
            "and under November 2027, which is when it actually next happens",
        )
    }

    @Test
    fun `a birthday later this month is under this month, not next year`() {
        val months = timelineMonths(listOf(entry("Later", 30, 11, nov26)), nov26)

        assertEquals(listOf("Later"), months.slot(2026, 11)?.entries?.map { it.displayName })
        assertTrue(months.slot(2027, 11)?.entries.isNullOrEmpty())
    }

    @Test
    fun `today itself is in the first slot`() {
        val months = timelineMonths(listOf(entry("Today", 26, 11, nov26)), nov26)

        assertEquals(listOf("Today"), months.first().entries.map { it.displayName })
        assertEquals(2026 to 11, months.first().year to months.first().month)
    }

    // -----------------------------------------------------------------------------------
    // The window
    // -----------------------------------------------------------------------------------

    @Test
    fun `the window is thirteen slots when something this month has already passed`() {
        val months = timelineMonths(listOf(entry("Earlier", 20, 11, nov26)), nov26)

        assertEquals(13, months.size)
        assertEquals(2026 to 11, months.first().year to months.first().month)
        assertEquals(2027 to 11, months.last().year to months.last().month)
    }

    @Test
    fun `the trailing repeat is dropped when nothing falls in it`() {
        // Opened on the 1st, nothing this month has passed, so the thirteenth slot would be
        // an empty November 2027 under an otherwise complete year. Twelve is the right shape.
        val nov1 = LocalDate(2026, 11, 1)
        val months = timelineMonths(listOf(entry("Later", 30, 11, nov1)), nov1)

        assertEquals(12, months.size)
        assertEquals(2026 to 11, months.first().year to months.first().month)
        assertEquals(2027 to 10, months.last().year to months.last().month)
    }

    @Test
    fun `the months run forward with no gaps and no repeats except the trailing one`() {
        val months = timelineMonths(listOf(entry("Earlier", 20, 11, nov26)), nov26)

        months.zipWithNext { a, b ->
            assertEquals(
                monthPlus(a.year, a.month, 1),
                b.year to b.month,
                "${a.year}-${a.month} should be followed by the next month",
            )
        }
    }

    @Test
    fun `empty months in the middle are kept`() {
        // The shape of the year is the point: a view that skipped the quiet months would be
        // the upcoming list with different headings.
        val months = timelineMonths(listOf(entry("Alone", 15, 3, nov26)), nov26)

        assertEquals(12, months.size, "nothing passed this month, so no trailing repeat")
        assertTrue(months.count { it.entries.isEmpty() } == 11)
        assertEquals(listOf("Alone"), months.slot(2027, 3)?.entries?.map { it.displayName })
    }

    // -----------------------------------------------------------------------------------
    // Year rolls
    // -----------------------------------------------------------------------------------

    @Test
    fun `December rolls into January without losing anybody`() {
        val dec15 = LocalDate(2026, 12, 15)
        val entries = listOf(
            entry("StillToCome", 20, 12, dec15),
            entry("AlreadyGone", 10, 12, dec15),
            entry("NewYear", 5, 1, dec15),
        )

        val months = timelineMonths(entries, dec15)

        assertEquals(13, months.size)
        assertEquals(2026 to 12, months.first().year to months.first().month)
        assertEquals(2027 to 12, months.last().year to months.last().month)
        assertEquals(listOf("StillToCome"), months.slot(2026, 12)?.entries?.map { it.displayName })
        assertEquals(listOf("NewYear"), months.slot(2027, 1)?.entries?.map { it.displayName })
        assertEquals(listOf("AlreadyGone"), months.slot(2027, 12)?.entries?.map { it.displayName })
    }

    @Test
    fun `31 December opened on 31 December is today, not next year`() {
        val dec31 = LocalDate(2026, 12, 31)
        val months = timelineMonths(listOf(entry("NewYearsEve", 31, 12, dec31)), dec31)

        assertEquals(listOf("NewYearsEve"), months.first().entries.map { it.displayName })
        assertEquals(2026 to 12, months.first().year to months.first().month)
    }

    // -----------------------------------------------------------------------------------
    // The invariant
    // -----------------------------------------------------------------------------------

    @Test
    fun `every day of every month lands in exactly one slot, from every day of the year`() {
        // The property the twelve-month window broke. Checked against a whole year of
        // "today"s so that the failure cannot hide in one corner of the calendar: 2028 is a
        // leap year, which is where the dates are hardest.
        var today = LocalDate(2028, 1, 1)
        val end = LocalDate(2029, 1, 1)
        while (today < end) {
            val entries = (1..12).flatMap { month ->
                listOf(1, 15, daysInMonth(2028, month)).distinct().map { day ->
                    entry("d$day-m$month", day, month, today)
                }
            }

            val months = timelineMonths(entries, today)
            val placed = months.names()

            assertEquals(
                entries.size,
                placed.size,
                "on $today, ${entries.size - placed.size} entries fell outside every slot",
            )
            assertEquals(placed.size, placed.toSet().size, "on $today something was placed twice")

            today = today.plus(DatePeriod(days = 1))
        }
    }

    @Test
    fun `29 February is placed, in leap years and out of them`() {
        for (today in listOf(LocalDate(2027, 6, 1), LocalDate(2028, 6, 1), LocalDate(2028, 3, 1))) {
            val months = timelineMonths(listOf(entry("Leap", 29, 2, today)), today)
            assertEquals(listOf("Leap"), months.names(), "on $today")
        }
    }

    // -----------------------------------------------------------------------------------
    // Order
    // -----------------------------------------------------------------------------------

    @Test
    fun `a slot is sorted by how soon, not by day of month`() {
        val entries = listOf(
            entry("Last", 28, 11, nov26),
            entry("First", 27, 11, nov26),
            entry("Middle", 29, 11, nov26),
        )

        val months = timelineMonths(entries, nov26)

        assertEquals(
            listOf("First", "Last", "Middle"),
            months.first().entries.map { it.displayName },
        )
    }

    @Test
    fun `countdowns never go backwards down the whole list`() {
        // What the thirteenth slot buys beyond correctness: the timeline is the reminder
        // queue with month headings, so reading it top to bottom is reading it in order.
        // Bucketing on the event's own month — the calendar's fix — would stand a 364-day
        // countdown next to a 1-day one in the first slot.
        val entries = listOf(
            entry("Passed", 2, 11, nov26),
            entry("Soon", 27, 11, nov26),
            entry("Spring", 14, 4, nov26),
            entry("Autumn", 3, 9, nov26),
        )

        val days = timelineMonths(entries, nov26).flatMap { it.entries }.map { it.daysUntil }

        assertEquals(days.sorted(), days, "the year ahead must read in order")
    }
}
