package `in`.wishkaro.app.domain

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The calendar's month grid, which definition-of-done item 9 asks for by name.
 *
 * A grid is nearly all off-by-one risk: the leading blanks, five rows against six, February
 * in a leap year, and which day the week starts on. None of it is visible from a screenshot
 * unless you count squares, which is why it is a pure function with a test rather than
 * arithmetic inside a composable.
 */
class MonthGridTest {

    @Test
    fun `every row is a whole week`() {
        for (month in 1..12) {
            val grid = monthGrid(2026, month)
            assertTrue(
                grid.weeks.all { it.size == 7 },
                "month $month has a ragged row: ${grid.weeks.map { it.size }}",
            )
        }
    }

    @Test
    fun `the days run 1 to the length of the month, in order, with nothing missing`() {
        for (year in listOf(2024, 2026, 2100)) {
            for (month in 1..12) {
                val days = monthGrid(year, month).days
                val expected = (1..daysInMonth(year, month)).toList()
                assertEquals(expected, days.map { it.day }, "$year-$month")
            }
        }
    }

    @Test
    fun `the first day sits under its own weekday column`() {
        // 1 March 2026 is a Sunday, and the week starts on Sunday, so it is the first cell.
        val march = monthGrid(2026, 3)
        assertEquals(LocalDate(2026, 3, 1), march.weeks.first()[0].date)
        assertEquals(DayOfWeek.SUNDAY, LocalDate(2026, 3, 1).dayOfWeek)

        // 1 April 2026 is a Wednesday: Sun, Mon, Tue are blank, so index 3.
        val april = monthGrid(2026, 4)
        assertEquals(DayOfWeek.WEDNESDAY, LocalDate(2026, 4, 1).dayOfWeek)
        repeat(3) { assertNull(april.weeks.first()[it].date, "cell $it should be blank") }
        assertEquals(LocalDate(2026, 4, 1), april.weeks.first()[3].date)
    }

    @Test
    fun `a month that fits in five rows gets five, not an empty sixth`() {
        // A fixed six-row grid leaves a band of nothing under February most years.
        val feb2026 = monthGrid(2026, 2)
        assertEquals(28, daysInMonth(2026, 2))
        assertEquals(DayOfWeek.SUNDAY, LocalDate(2026, 2, 1).dayOfWeek)
        assertEquals(4, feb2026.weeks.size, "28 days starting on the first column is exactly four rows")
        assertTrue(feb2026.weeks.last().all { it.date != null }, "no trailing blank row")
    }

    @Test
    fun `a month that needs six rows gets six`() {
        // 1 August 2026 is a Saturday — the last column — so 31 days spill into a sixth row.
        val grid = monthGrid(2026, 8)
        assertEquals(DayOfWeek.SATURDAY, LocalDate(2026, 8, 1).dayOfWeek)
        assertEquals(6, grid.weeks.size)
        assertEquals(LocalDate(2026, 8, 31), grid.days.last())
    }

    @Test
    fun `February has 29 days in a leap year and 28 otherwise`() {
        assertEquals(29, daysInMonth(2024, 2))
        assertEquals(28, daysInMonth(2026, 2))
        assertEquals(29, daysInMonth(2000, 2), "divisible by 400")
        assertEquals(28, daysInMonth(2100, 2), "divisible by 100 but not 400")

        assertEquals(LocalDate(2024, 2, 29), monthGrid(2024, 2).days.last())
        assertEquals(LocalDate(2026, 2, 28), monthGrid(2026, 2).days.last())
    }

    @Test
    fun `the headings line up with the columns`() {
        assertEquals(7, WEEKDAY_INITIALS.size)
        // Sunday first, per WEEK_STARTS_ON and Indian calendar convention.
        assertEquals(listOf("S", "M", "T", "W", "T", "F", "S"), WEEKDAY_INITIALS)
    }

    @Test
    fun `stepping months wraps the year in both directions`() {
        assertEquals(2026 to 12, monthPlus(2026, 11, 1))
        assertEquals(2027 to 1, monthPlus(2026, 12, 1))
        assertEquals(2027 to 3, monthPlus(2026, 12, 3))
        assertEquals(2026 to 1, monthPlus(2026, 12, -11))
        assertEquals(2025 to 12, monthPlus(2026, 1, -1))
        assertEquals(2024 to 11, monthPlus(2026, 11, -24))
    }

    @Test
    fun `a year of stepping forward visits every month once`() {
        val visited = (0..11).map { monthPlus(2026, 3, it) }
        assertEquals(12, visited.toSet().size)
        assertEquals(2026 to 3, visited.first())
        assertEquals(2027 to 2, visited.last())
    }

    @Test
    fun `an invalid month is rejected rather than rendered`() {
        for (bad in listOf(0, 13, -1)) {
            val threw = runCatching { monthGrid(2026, bad) }.isFailure
            assertTrue(threw, "month $bad should not build a grid")
        }
    }

    // -----------------------------------------------------------------------------------
    // Which events land on which square
    // -----------------------------------------------------------------------------------

    private fun entry(name: String, day: Int, month: Int, year: Int? = 1990): UpcomingEntry {
        val person = Person(
            id = "p-$name", displayName = name, relationToOwner = null, isSelf = false,
            gender = null, phone = null, email = null, notes = null, isDeceased = false,
            isMinor = false, completenessScore = 0, completenessSuggestions = emptyList(),
            events = emptyList(), traits = emptyList(), deletedAt = null,
        )
        val event = PersonEvent(
            id = "e-$name", personId = person.id, type = EventType.BIRTHDAY,
            sentiment = Sentiment.CELEBRATORY, label = null, isMuted = false,
            date = EventDate(day, month, year),
        )
        return upcomingFor(person.copy(events = listOf(event)), TimeZone.UTC, LocalDate(2026, 11, 26))
            .single()
    }

    @Test
    fun `a birthday earlier this month still appears on this month's grid`() {
        // The bug this test exists for: matched on NEXT OCCURRENCE, somebody born on the
        // 10th vanishes from a calendar opened on the 26th, because their next birthday is
        // eleven and a half months away and the calendar only reaches eleven months forward.
        val byDay = eventsByDay(listOf(entry("Earlier", 10, 11)), 2026, 11)

        assertEquals(listOf("Earlier"), byDay[10]?.map { it.displayName })
    }

    @Test
    fun `events are grouped under their own day`() {
        val byDay = eventsByDay(
            listOf(entry("A", 5, 3), entry("B", 5, 3), entry("C", 20, 3), entry("D", 5, 4)),
            2026,
            3,
        )

        assertEquals(setOf("A", "B"), byDay[5]?.map { it.displayName }?.toSet())
        assertEquals(listOf("C"), byDay[20]?.map { it.displayName })
        assertNull(byDay[4], "an April birthday is not in March")
    }

    @Test
    fun `29 February lands on the 28th in a non-leap year and on the 29th in a leap one`() {
        val leaper = entry("Leap", 29, 2)

        assertEquals(listOf("Leap"), eventsByDay(listOf(leaper), 2028, 2)[29]?.map { it.displayName })
        assertNull(eventsByDay(listOf(leaper), 2026, 2)[29], "there is no 29th to draw on")
        assertEquals(
            listOf("Leap"),
            eventsByDay(listOf(leaper), 2026, 2)[28]?.map { it.displayName },
            "FR-EVT-09: observed early, on the day the reminder actually arrives",
        )
    }

    @Test
    fun `titles read the way a heading should`() {
        assertEquals("March 2026", monthTitle(2026, 3))
        assertEquals("December 2027", monthTitle(2027, 12))
    }
}
