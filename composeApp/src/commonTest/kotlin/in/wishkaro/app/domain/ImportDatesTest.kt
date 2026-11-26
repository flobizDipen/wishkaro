package `in`.wishkaro.app.domain

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every format in FR-IMP-06, the dd/MM toggle, and the two-digit-year pivot.
 *
 * These are pure functions and the project's own experience is that pure functions are not
 * where it breaks — but import is the first feature whose INPUT is hostile, so the table is
 * the specification and it is worth having in full. The seams that use these (the mapping
 * preview, the apply step) get their own tests later.
 *
 * `today` is passed in everywhere. The pivot depends on the current year, and a test that
 * read the system clock would start failing on 1 January of some year nobody is thinking
 * about — and would pass or fail differently on CI, which is the M2 §0.0.2 trap.
 */
class ImportDatesTest {

    private val today = LocalDate(2026, 11, 26)

    private fun parse(raw: String, order: DateOrder = DateOrder.DAY_FIRST) =
        ImportDates.parse(raw, order, today)

    private fun parsed(raw: String, order: DateOrder = DateOrder.DAY_FIRST): EventDate {
        val result = parse(raw, order)
        assertTrue(result is DateParse.Parsed, "expected \"$raw\" to parse, got $result")
        return result.date
    }

    private fun failure(raw: String, order: DateOrder = DateOrder.DAY_FIRST): String {
        val result = parse(raw, order)
        assertTrue(result is DateParse.Failed, "expected \"$raw\" to fail, got $result")
        return result.reason
    }

    // ---------------------------------------------------------------------------------
    // The formats FR-IMP-06 names, one test each
    // ---------------------------------------------------------------------------------

    @Test
    fun dd_slash_MM_slash_yyyy() {
        assertEquals(EventDate(4, 1, 1990), parsed("04/01/1990"))
        assertEquals(EventDate(25, 12, 1988), parsed("25/12/1988"))
    }

    @Test
    fun dd_dash_MM_dash_yy() {
        assertEquals(EventDate(14, 3, 1965), parsed("14-03-65"))
    }

    @Test
    fun MM_slash_dd_slash_yyyy_under_the_month_first_setting() {
        assertEquals(EventDate(1, 4, 1990), parsed("04/01/1990", DateOrder.MONTH_FIRST))
    }

    @Test
    fun d_MMM_yyyy() {
        assertEquals(EventDate(4, 1, 1990), parsed("4 Jan 1990"))
        assertEquals(EventDate(22, 9, 1988), parsed("22 Sep 1988"))
        assertEquals(EventDate(30, 9, 1988), parsed("30 Sept 1988"))
        assertEquals(EventDate(1, 8, 2001), parsed("1 August 2001"))
        assertEquals(EventDate(4, 1, 1990), parsed("Jan 4, 1990"))
    }

    @Test
    fun yyyy_dash_MM_dash_dd() {
        assertEquals(EventDate(4, 1, 1990), parsed("1990-01-04"))
        assertEquals(EventDate(4, 1, 1990), parsed("1990/01/04"))
    }

    @Test
    fun day_and_month_with_no_year() {
        assertEquals(EventDate(25, 12, null), parsed("25/12"))
        assertEquals(EventDate(4, 1, null), parsed("4 Jan"))
        assertEquals(EventDate(4, 1, null), parsed("04-01", DateOrder.DAY_FIRST))
    }

    @Test
    fun separators_people_actually_use() {
        assertEquals(EventDate(4, 1, 1990), parsed("04.01.1990"))
        assertEquals(EventDate(4, 1, 1990), parsed("04 01 1990"))
        assertEquals(EventDate(4, 1, 1990), parsed("  04/01/1990  "))
    }

    // ---------------------------------------------------------------------------------
    // The toggle — and, just as important, when it does NOT apply
    // ---------------------------------------------------------------------------------

    @Test
    fun an_ambiguous_row_follows_the_toggle_and_is_marked_ambiguous() {
        val dayFirst = parse("05/06/1990") as DateParse.Parsed
        val monthFirst = parse("05/06/1990", DateOrder.MONTH_FIRST) as DateParse.Parsed

        assertEquals(EventDate(5, 6, 1990), dayFirst.date)
        assertEquals(EventDate(6, 5, 1990), monthFirst.date)
        assertTrue(dayFirst.ambiguous, "the preview has to be able to mark this row")
        assertTrue(monthFirst.ambiguous)
    }

    @Test
    fun a_row_that_cannot_be_month_first_reads_the_same_under_both_settings() {
        // 25 is not a month. The toggle is a tie-breaker for genuinely ambiguous rows, not an
        // instruction to reject rows that can only be read one way.
        assertEquals(EventDate(25, 12, 1988), parsed("25/12/1988", DateOrder.DAY_FIRST))
        assertEquals(EventDate(25, 12, 1988), parsed("25/12/1988", DateOrder.MONTH_FIRST))
        assertTrue(!(parse("25/12/1988") as DateParse.Parsed).ambiguous)
    }

    @Test
    fun a_row_that_cannot_be_day_first_reads_the_same_under_both_settings() {
        assertEquals(EventDate(25, 12, 1988), parsed("12/25/1988", DateOrder.DAY_FIRST))
        assertEquals(EventDate(25, 12, 1988), parsed("12/25/1988", DateOrder.MONTH_FIRST))
    }

    @Test
    fun the_same_number_twice_is_not_ambiguous() {
        // 06/06 is 6 June either way, so marking it ambiguous would put a warning on a row
        // the toggle cannot change — which teaches the user to ignore the warning.
        val result = parse("06/06/1990") as DateParse.Parsed
        assertEquals(EventDate(6, 6, 1990), result.date)
        assertTrue(!result.ambiguous)
    }

    @Test
    fun an_iso_date_is_never_ambiguous() {
        assertEquals(EventDate(4, 1, 1990), parsed("1990-01-04", DateOrder.MONTH_FIRST))
    }

    @Test
    fun a_named_month_is_never_ambiguous() {
        assertEquals(EventDate(4, 1, 1990), parsed("4 Jan 1990", DateOrder.MONTH_FIRST))
    }

    /**
     * Definition-of-done item 4: a file of all-ambiguous dates imports correctly under both
     * settings. Same six rows, both ways, nothing guessed from the shape of the data.
     */
    @Test
    fun a_whole_file_of_ambiguous_rows_flips_cleanly() {
        val rows = listOf("01/02/1990", "03/04/1991", "05/06/1992", "07/08/1993", "09/10/1994", "11/12/1995")

        val dayFirst = rows.map { parsed(it, DateOrder.DAY_FIRST) }
        val monthFirst = rows.map { parsed(it, DateOrder.MONTH_FIRST) }

        assertEquals(listOf(1, 3, 5, 7, 9, 11), dayFirst.map { it.day })
        assertEquals(listOf(2, 4, 6, 8, 10, 12), dayFirst.map { it.month })
        assertEquals(listOf(2, 4, 6, 8, 10, 12), monthFirst.map { it.day })
        assertEquals(listOf(1, 3, 5, 7, 9, 11), monthFirst.map { it.month })
    }

    // ---------------------------------------------------------------------------------
    // The two-digit-year pivot
    // ---------------------------------------------------------------------------------

    @Test
    fun a_two_digit_year_never_lands_in_the_future() {
        // The rule, with today = 2026: 2000+yy if that is at or before this year, else 1900+yy.
        assertEquals(1965, parsed("14-03-65").year, "65 is a birth year, not 2065")
        assertEquals(2010, parsed("14-03-10").year, "a sixteen-year-old")
        assertEquals(2026, parsed("14-03-26").year, "someone born this year")
        assertEquals(1927, parsed("14-03-27").year, "next year cannot be a birth year")
        assertEquals(1999, parsed("14-03-99").year)
        assertEquals(2000, parsed("14-03-00").year)
    }

    @Test
    fun the_pivot_moves_with_the_year_rather_than_being_fixed() {
        // The whole reason it is derived from `today` and not a constant: in 2030, 27 is a
        // plausible birth year and must stop being read as 1927.
        val in2030 = LocalDate(2030, 1, 1)
        assertEquals(
            EventDate(14, 3, 2027),
            (ImportDates.parse("14-03-27", DateOrder.DAY_FIRST, in2030) as DateParse.Parsed).date,
        )
    }

    @Test
    fun a_four_digit_year_is_left_alone_even_when_it_is_in_the_future() {
        // Someone counting towards an anniversary typed it out in full. The pivot recovers
        // information a two-digit year threw away; it does not second-guess one that is there.
        assertEquals(EventDate(14, 3, 2030), parsed("14-03-2030"))
    }

    // ---------------------------------------------------------------------------------
    // Year sentinels — "there is no year here", not "this row is broken"
    // ---------------------------------------------------------------------------------

    @Test
    fun the_contact_field_sentinels_mean_year_unknown() {
        // Android's contacts and several exporters write these for "no year given". The
        // server's schema starts at 1900, and `event.year` is already nullable (M1), so the
        // sentinel maps onto the representation that exists rather than inventing a second.
        assertEquals(EventDate(14, 3, null), parsed("1604-03-14"))
        assertEquals(EventDate(14, 3, null), parsed("1904-03-14"))
        assertEquals(EventDate(14, 3, null), parsed("0000-03-14"))
    }

    @Test
    fun a_year_past_the_servers_range_is_a_failure_not_a_sentinel() {
        assertTrue("out of range" in failure("14-03-2300"))
    }

    // ---------------------------------------------------------------------------------
    // Dates that do not exist
    // ---------------------------------------------------------------------------------

    @Test
    fun impossible_dates_fail_with_a_reason_the_user_can_act_on() {
        assertTrue("not a real date" in failure("31/04/1990"))
        assertTrue("leap year" in failure("29/02/2023"))
        assertTrue("no month" in failure("13/13/1990"))
    }

    @Test
    fun the_twenty_ninth_of_february_with_no_year_is_a_real_birthday() {
        // It is, and M1's leap-day rule already carries it (FR-EVT-09). Refusing it here
        // would reject a person the app can otherwise handle perfectly.
        assertEquals(EventDate(29, 2, null), parsed("29/02"))
        assertEquals(EventDate(29, 2, 2024), parsed("29/02/2024"))
    }

    @Test
    fun unreadable_cells_fail_rather_than_becoming_something() {
        assertEquals("no date", failure(""))
        assertEquals("no date", failure("   "))
        assertTrue("could not read" in failure("not a date"))
        assertTrue("could not read" in failure("12"))
        assertTrue("could not read" in failure("1/2/3/4"))
        assertTrue("could not read" in failure("4 Januarius 1990"), "an unknown month name")
    }

    // ---------------------------------------------------------------------------------
    // Excel serials
    // ---------------------------------------------------------------------------------

    @Test
    fun an_excel_serial_becomes_the_date_excel_shows() {
        assertEquals(
            EventDate(4, 1, 2024),
            (ImportDates.fromExcelSerial(45295) as DateParse.Parsed).date,
        )
        assertEquals(
            EventDate(1, 3, 1900),
            (ImportDates.fromExcelSerial(61) as DateParse.Parsed).date,
        )
    }

    @Test
    fun excels_phantom_leap_day_is_refused_rather_than_resolved() {
        // Serial 60 is 29 February 1900, a day that did not happen: Excel believes 1900 was
        // a leap year. Everything below 61 is pre-1900 anyway, which this app stores as
        // "year unknown", so guessing is pointless as well as wrong.
        assertTrue(ImportDates.fromExcelSerial(60) is DateParse.Failed)
        assertTrue(ImportDates.fromExcelSerial(1) is DateParse.Failed)
    }
}
