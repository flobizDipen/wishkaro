package `in`.wishkaro.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The CSV reader, against the shapes real exports actually have.
 *
 * Every case here is something a file from Excel, Google Sheets or a phone backup does on
 * its own — not grammar exercises. The round-trip tests matter most: FR-IMP-07's corrected
 * CSV only works if what we write can be read back.
 */
class CsvTest {

    @Test
    fun a_plain_file() {
        assertEquals(
            listOf(listOf("Name", "Date"), listOf("Aarav Shah", "04/01/1990")),
            Csv.parse("Name,Date\nAarav Shah,04/01/1990"),
        )
    }

    @Test
    fun excels_byte_order_mark_is_not_part_of_the_first_column_name() {
        // Excel writes a UTF-8 BOM. Left in, the first header reads "﻿Name" and the
        // mapping screen offers the user a column whose name does not match what they see.
        val rows = Csv.parse("﻿Name,Date\nAarav Shah,04/01/1990")
        assertEquals("Name", rows[0][0])
    }

    @Test
    fun windows_line_endings() {
        assertEquals(
            listOf(listOf("Name"), listOf("Aarav")),
            Csv.parse("Name\r\nAarav\r\n"),
        )
    }

    @Test
    fun old_mac_line_endings() {
        assertEquals(listOf(listOf("Name"), listOf("Aarav")), Csv.parse("Name\rAarav"))
    }

    @Test
    fun a_quoted_field_may_contain_the_delimiter() {
        assertEquals(
            listOf(listOf("Aarav Shah", "Mumbai, Maharashtra", "Friend")),
            Csv.parse("Aarav Shah,\"Mumbai, Maharashtra\",Friend"),
        )
    }

    @Test
    fun a_quoted_field_may_contain_a_newline() {
        // A notes column with a wrapped address is the usual source of this. Treating the
        // newline as a row break would shift every subsequent row by one column.
        val rows = Csv.parse("Name,Notes\nAarav,\"line one\nline two\"\nMeera,x")
        assertEquals(3, rows.size)
        assertEquals("line one\nline two", rows[1][1])
        assertEquals(listOf("Meera", "x"), rows[2])
    }

    @Test
    fun a_doubled_quote_is_one_literal_quote() {
        assertEquals(
            listOf(listOf("Rajesh \"Raj\" Kumar")),
            Csv.parse("\"Rajesh \"\"Raj\"\" Kumar\""),
        )
    }

    @Test
    fun a_quote_in_the_middle_of_an_unquoted_field_is_just_a_character() {
        // Not a parse error, and not the start of a quoted section. Somebody typed it.
        assertEquals(listOf(listOf("Rajesh \"Raj\" Kumar", "Friend")), Csv.parse("Rajesh \"Raj\" Kumar,Friend"))
    }

    @Test
    fun empty_cells_are_preserved_because_a_missing_relation_is_information() {
        assertEquals(listOf(listOf("Aarav", "", "04/01/1990")), Csv.parse("Aarav,,04/01/1990"))
    }

    @Test
    fun blank_lines_are_dropped_but_blank_cells_are_not() {
        val rows = Csv.parse("Name,Date\n\nAarav,04/01/1990\n\n")
        assertEquals(2, rows.size)
    }

    @Test
    fun a_file_that_does_not_end_in_a_newline_still_has_its_last_row() {
        assertEquals(2, Csv.parse("Name\nAarav").size)
    }

    @Test
    fun a_file_that_does_end_in_a_newline_does_not_gain_an_empty_one() {
        assertEquals(2, Csv.parse("Name\nAarav\n").size)
    }

    // ---- the delimiter ----

    @Test
    fun a_semicolon_file_from_a_european_locale() {
        assertEquals(
            listOf(listOf("Name", "Date"), listOf("Aarav", "04/01/1990")),
            Csv.parse("Name;Date\nAarav;04/01/1990"),
        )
    }

    @Test
    fun a_tab_separated_file() {
        assertEquals(listOf(listOf("Name", "Date")), Csv.parse("Name\tDate"))
    }

    @Test
    fun a_delimiter_inside_quotes_does_not_vote_for_itself() {
        // Three commas, all inside one quoted cell, against one real semicolon. Counting
        // them blind would pick the comma and produce one column of nonsense.
        assertEquals(';', Csv.sniffDelimiter("Name;\"a,b,c,d\""))
    }

    @Test
    fun a_single_column_file_falls_back_to_the_comma() {
        assertEquals(',', Csv.sniffDelimiter("Name\nAarav"))
    }

    // ---- writing, for the corrected-CSV round trip (FR-IMP-07) ----

    @Test
    fun what_we_write_is_what_we_read_back() {
        val rows = listOf(
            listOf("Name", "Date", "Relation", "Reason"),
            listOf("Aarav Shah", "31/04/1990", "Friend", "\"31/04/1990\" is not a real date"),
            listOf("Meera, the neighbour", "", "", "no date"),
            listOf("Line\nbreak", "04/01/1990", "Cousin", "ok"),
        )

        assertEquals(rows, Csv.parse(Csv.write(rows)))
    }

    @Test
    fun only_cells_that_need_quoting_get_quoted() {
        // A file where every cell is quoted is legal and horrible to read in the editor the
        // user is about to fix it in.
        assertEquals("Aarav,Friend", Csv.write(listOf(listOf("Aarav", "Friend"))))
    }
}
