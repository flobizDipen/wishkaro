package `in`.wishkaro.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The hostile half of `.xlsx`, tested where it lives: in `commonMain`, with no zip and no
 * filesystem.
 *
 * The XML here is written by hand on purpose. The committed fixture
 * (`docs/fixtures/family-birthdays.xlsx`, exercised in `XlsxWorkbookTest`) comes from a real
 * writer and covers what THAT writer does — inline strings, every cell present. Excel and
 * Google Sheets do something different: a shared string table, and empty cells simply
 * MISSING from the file. Both shapes have to work, and only one of them can come from the
 * writer that is installed on this machine.
 */
class XlsxTest {

    // ---------------------------------------------------------------------------------
    // Shared strings — what Excel and Google Sheets actually emit
    // ---------------------------------------------------------------------------------

    @Test
    fun a_shared_string_table_is_read_in_order() {
        val xml = """
            <sst count="3" uniqueCount="3">
              <si><t>Name</t></si>
              <si><t>Aarav Shah</t></si>
              <si><t>Cousin</t></si>
            </sst>
        """.trimIndent()

        assertEquals(listOf("Name", "Aarav Shah", "Cousin"), Xlsx.sharedStrings(xml))
    }

    @Test
    fun a_string_split_across_formatting_runs_comes_back_whole() {
        // A name where the surname is bold is ONE string stored in two pieces. Taking only
        // the first <t> would import "Aarav " and lose the surname — and the surname is what
        // the dedup rule matches on.
        val xml = """
            <sst><si><r><t>Aarav </t></r><r><t>Shah</t></r></si></sst>
        """.trimIndent()

        assertEquals(listOf("Aarav Shah"), Xlsx.sharedStrings(xml))
    }

    @Test
    fun entities_are_decoded_in_strings_and_in_attributes() {
        val xml = """<sst><si><t>Shah &amp; Sons &lt;family&gt; &#39;s &#x4F;K</t></si></sst>"""
        assertEquals(listOf("Shah & Sons <family> 's OK"), Xlsx.sharedStrings(xml))
    }

    @Test
    fun xml_space_preserve_keeps_a_leading_space() {
        val xml = """<sst><si><t xml:space="preserve"> Aarav</t></si></sst>"""
        assertEquals(listOf(" Aarav"), Xlsx.sharedStrings(xml))
    }

    // ---------------------------------------------------------------------------------
    // Cells, and the columns that are not there
    // ---------------------------------------------------------------------------------

    @Test
    fun a_missing_cell_holds_its_column_open() {
        // THE one that silently corrupts an import. Excel omits empty cells entirely, so a
        // row whose Relation is blank jumps from B to D. Without reading `r`, the phone
        // number lands in the relation column for that row only — and the mapping preview,
        // which shows the first five rows, may not even contain the row it happens to.
        val sheet = """
            <worksheet><sheetData>
              <row r="1">
                <c r="A1" t="s"><v>0</v></c>
                <c r="B1" t="s"><v>1</v></c>
                <c r="D1" t="s"><v>2</v></c>
              </row>
            </sheetData></worksheet>
        """.trimIndent()

        assertEquals(
            listOf(listOf("Aarav", "04/01/1990", "", "9820011111")),
            Xlsx.rows(sheet, listOf("Aarav", "04/01/1990", "9820011111"), emptySet()),
        )
    }

    @Test
    fun a_row_that_starts_at_column_c_keeps_its_two_empty_columns() {
        val sheet = """<worksheet><sheetData><row r="1"><c r="C1" t="s"><v>0</v></c></row></sheetData></worksheet>"""
        assertEquals(listOf(listOf("", "", "Aarav")), Xlsx.rows(sheet, listOf("Aarav"), emptySet()))
    }

    @Test
    fun a_self_closing_cell_is_an_empty_one() {
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1"/></row></sheetData></worksheet>"""
        assertEquals(listOf(listOf("Aarav", "")), Xlsx.rows(sheet, listOf("Aarav"), emptySet()))
    }

    @Test
    fun an_inline_string_needs_no_table() {
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>Meera</t></is></c></row></sheetData></worksheet>"""
        assertEquals(listOf(listOf("Meera")), Xlsx.rows(sheet, emptyList(), emptySet()))
    }

    @Test
    fun a_formula_hands_over_its_cached_result_rather_than_the_formula() {
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1" t="str"><f>CONCAT(B1,C1)</f><v>Aarav Shah</v></c></row></sheetData></worksheet>"""
        assertEquals(listOf(listOf("Aarav Shah")), Xlsx.rows(sheet, emptyList(), emptySet()))
    }

    @Test
    fun an_error_cell_is_handed_over_as_written_so_the_row_fails_visibly() {
        // #N/A becoming "" would make the row fail with "no date" and send the user looking
        // at the wrong thing.
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1" t="e"><v>#N/A</v></c></row></sheetData></worksheet>"""
        assertEquals(listOf(listOf("#N/A")), Xlsx.rows(sheet, emptyList(), emptySet()))
    }

    @Test
    fun a_shared_string_index_that_points_nowhere_becomes_empty_rather_than_throwing() {
        // A second, good cell keeps the row alive — a row that resolved to nothing at all is
        // a blank row and is dropped, which is the right answer for a real file but would
        // have hidden what this test is about.
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1" t="s"><v>99</v></c><c r="B1" t="s"><v>0</v></c></row></sheetData></worksheet>"""
        assertEquals(listOf(listOf("", "Aarav")), Xlsx.rows(sheet, listOf("Aarav"), emptySet()))
    }

    @Test
    fun wholly_blank_rows_are_dropped() {
        val sheet = """
            <worksheet><sheetData>
              <row r="1"><c r="A1" t="s"><v>0</v></c></row>
              <row r="2"><c r="A2"/><c r="B2"/></row>
              <row r="3"><c r="A3" t="s"><v>1</v></c></row>
            </sheetData></worksheet>
        """.trimIndent()
        assertEquals(2, Xlsx.rows(sheet, listOf("Aarav", "Meera"), emptySet()).size)
    }

    // ---------------------------------------------------------------------------------
    // Is this number a date?
    // ---------------------------------------------------------------------------------

    @Test
    fun a_built_in_date_format_makes_a_number_a_date() {
        // numFmtId 14 is Excel's `mm-dd-yy`. The cell holds 45295 and nothing else.
        val styles = """<styleSheet><cellXfs count="2"><xf numFmtId="0"/><xf numFmtId="14"/></cellXfs></styleSheet>"""
        val dateStyles = Xlsx.dateStyles(styles)
        assertEquals(setOf(1), dateStyles)

        val sheet = """<worksheet><sheetData><row r="1"><c r="A1" s="1"><v>45295</v></c></row></sheetData></worksheet>"""
        assertEquals(listOf(listOf("2024-01-04")), Xlsx.rows(sheet, emptyList(), dateStyles))
    }

    @Test
    fun a_custom_date_format_is_followed_through_both_indirections() {
        // The cell's `s` indexes cellXfs; that xf's numFmtId is a CUSTOM id declared in
        // numFmts. Two hops, and missing either one turns a birthday into forty-five thousand.
        val styles = """
            <styleSheet>
              <numFmts count="1"><numFmt numFmtId="164" formatCode="dd/mm/yyyy"/></numFmts>
              <cellXfs count="2"><xf numFmtId="0"/><xf numFmtId="164"/></cellXfs>
            </styleSheet>
        """.trimIndent()
        assertEquals(setOf(1), Xlsx.dateStyles(styles))
    }

    @Test
    fun a_currency_format_is_not_a_date_however_much_it_contains_a_d() {
        // `"d"#,##0` and `[Red]0.00` both carry letters that a plain `contains` would read as
        // date fields. A budget column turning into 1970 is the failure that catches.
        assertTrue(!Xlsx.looksLikeADate("\"d\"#,##0"))
        assertTrue(!Xlsx.looksLikeADate("[Red]#,##0.00"))
        assertTrue(!Xlsx.looksLikeADate("0.00"))
        assertTrue(Xlsx.looksLikeADate("dd/mm/yyyy"))
        assertTrue(Xlsx.looksLikeADate("[\$-409]d\\-mmm\\-yy;@"))
    }

    @Test
    fun the_built_in_accounting_formats_are_not_dates() {
        // 23-44 sit BETWEEN the two date ranges. A range of 14..47 would swallow them.
        val styles = """<styleSheet><cellXfs count="3"><xf numFmtId="38"/><xf numFmtId="44"/><xf numFmtId="45"/></cellXfs></styleSheet>"""
        assertEquals(setOf(2), Xlsx.dateStyles(styles), "only numFmtId 45 (elapsed time) is a date here")
    }

    @Test
    fun a_number_with_no_date_format_stays_a_number() {
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1"><v>1990</v></c></row></sheetData></worksheet>"""
        // Not "1990.0": it is almost certainly a year in a column of its own.
        assertEquals(listOf(listOf("1990")), Xlsx.rows(sheet, emptyList(), emptySet()))
    }

    @Test
    fun a_date_cell_carrying_a_time_keeps_only_the_day() {
        val sheet = """<worksheet><sheetData><row r="1"><c r="A1" s="0"><v>45295.5416666667</v></c></row></sheetData></worksheet>"""
        assertEquals(listOf(listOf("2024-01-04")), Xlsx.rows(sheet, emptyList(), setOf(0)))
    }

    // ---------------------------------------------------------------------------------
    // Which sheet
    // ---------------------------------------------------------------------------------

    @Test
    fun the_first_tab_is_read_even_when_its_file_is_not_sheet1() {
        // File names follow creation order; tabs follow the user's order. A workbook whose
        // first tab is sheet3.xml is ordinary, and reading sheet1.xml would silently import
        // whatever is on a tab the user is not looking at.
        val entries = mapOf(
            "xl/workbook.xml" to """<workbook><sheets><sheet name="Birthdays" sheetId="3" r:id="rId7"/><sheet name="Old" sheetId="1" r:id="rId1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/><Relationship Id="rId7" Target="worksheets/sheet3.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to """<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>WRONG TAB</t></is></c></row></sheetData></worksheet>""",
            "xl/worksheets/sheet3.xml" to """<worksheet><sheetData><row r="1"><c r="A1" t="inlineStr"><is><t>Aarav</t></is></c></row></sheetData></worksheet>""",
        )

        assertEquals(listOf(listOf("Aarav")), XlsxWorkbook.firstSheetRows(entries))
    }

    @Test
    fun something_that_is_not_a_spreadsheet_reads_as_null_rather_than_throwing() {
        assertNull(XlsxWorkbook.firstSheetRows(emptyMap()))
        assertNull(XlsxWorkbook.firstSheetRows(mapOf("word/document.xml" to "<document/>")))
    }

    // ---------------------------------------------------------------------------------
    // The scanner underneath
    // ---------------------------------------------------------------------------------

    @Test
    fun an_angle_bracket_inside_an_attribute_does_not_end_the_tag() {
        val xml = """<sst><si><t>a &gt; b</t></si><si><t>plain</t></si></sst>"""
        assertEquals(listOf("a > b", "plain"), Xlsx.sharedStrings(xml))
    }

    @Test
    fun namespace_prefixes_do_not_hide_the_elements() {
        // Some writers prefix everything with `x:`. Matching on the raw name would read
        // nothing at all and produce a cheerfully empty import.
        val xml = """<x:sst xmlns:x="..."><x:si><x:t>Aarav</x:t></x:si></x:sst>"""
        assertEquals(listOf("Aarav"), Xlsx.sharedStrings(xml))
    }

    @Test
    fun comments_and_declarations_are_skipped() {
        val xml = """<?xml version="1.0"?><!-- written by something --><sst><si><t>Aarav</t></si></sst>"""
        assertEquals(listOf("Aarav"), Xlsx.sharedStrings(xml))
    }

    @Test
    fun column_references_past_z() {
        assertEquals(0, Xlsx.columnIndex("A1"))
        assertEquals(25, Xlsx.columnIndex("Z1"))
        assertEquals(26, Xlsx.columnIndex("AA1"))
        assertEquals(51, Xlsx.columnIndex("AZ10"))
        assertEquals(702, Xlsx.columnIndex("AAA1"))
        assertNull(Xlsx.columnIndex("7"))
    }
}
