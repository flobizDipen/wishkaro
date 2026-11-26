package `in`.wishkaro.app.domain

import `in`.wishkaro.app.platform.readZipTextEntries
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole `.xlsx` path on a real file: zip, XML, rows.
 *
 * `XlsxTest` covers the hostile parsing in `commonTest` with hand-written XML, because the
 * shapes that matter most — a shared string table, missing cells — are not what the writer
 * available on this machine produces. This is the other end: a file written by a real
 * spreadsheet library, opened through the actual Android zip reader, with nothing stubbed.
 *
 * `docs/fixtures/family-birthdays.xlsx` contains INVENTED people only. The repo is public.
 */
class XlsxWorkbookTest {

    private val rows: List<List<String>> by lazy {
        val bytes = fixture("family-birthdays.xlsx").readBytes()
        val entries = readZipTextEntries(bytes)
        assertTrue(entries.isNotEmpty(), "the zip reader found nothing in the fixture")
        XlsxWorkbook.firstSheetRows(entries) ?: error("no sheet in the fixture")
    }

    private fun fixture(name: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "docs/fixtures/$name")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("docs/fixtures/$name not found from ${File(".").absolutePath}")
    }

    @Test
    fun the_header_row_is_the_users_own_column_names() {
        // These are what the mapping screen offers. They are deliberately NOT the names this
        // app uses — "How I know them" has to be mappable onto Relation by the user.
        assertEquals(
            listOf("Full Name", "Birthday", "How I know them", "Mobile", "Notes"),
            rows.first(),
        )
    }

    @Test
    fun every_data_row_is_present_and_the_second_sheet_is_not() {
        assertEquals(11, rows.size, "one header plus ten people")
        assertTrue(rows.none { it.any { cell -> "ignored" in cell } }, "only the first tab is read")
    }

    @Test
    fun a_real_date_cell_comes_back_as_an_unambiguous_date() {
        // Written by the library as a serial number with a custom `yyyy-mm-dd` format. There
        // is nothing in the cell itself to say it is a date — the format is the only signal,
        // and it is two indirections away in styles.xml.
        assertEquals("1990-01-04", rows[1][1])
    }

    @Test
    fun text_dates_are_handed_over_untouched_for_the_client_to_interpret() {
        assertEquals("22 Sep 1988", rows[2][1])
        assertEquals("14-03-65", rows[3][1])
        assertEquals("25/12", rows[4][1])
    }

    @Test
    fun a_blank_cell_does_not_shift_the_rest_of_its_row() {
        // Ishaan has no relation. If the empty cell were dropped, "05/06/1992" would end up
        // in the relation column and the row would import as a person called Ishaan whose
        // relation is a date.
        val ishaan = rows.first { it[0] == "Ishaan Patel" }
        assertEquals(5, ishaan.size)
        assertEquals("05/06/1992", ishaan[1])
        assertEquals("", ishaan[2])
    }

    @Test
    fun quotes_and_commas_in_a_name_survive() {
        assertTrue(rows.any { it[0] == "Rajesh \"Raj\" Kumar, Jr" })
    }

    @Test
    fun a_number_typed_into_the_phone_column_is_not_rendered_as_a_float() {
        val rajesh = rows.first { it[0].startsWith("Rajesh") }
        assertEquals("9820033333", rajesh[3], "not 9.820033333E9 and not 9820033333.0")
    }

    @Test
    fun indic_names_survive_the_whole_path() {
        // Zip to UTF-8 to XML entities to rows. This is also the input the name-token rule
        // has to handle, so a mangled encoding here would look like a dedup bug later.
        assertTrue(rows.any { it[0] == "जयदत्त जांसरी" })
        assertTrue(rows.any { it[0] == "જયદત્ત જાંસરી" })
    }

    @Test
    fun something_that_is_not_a_zip_reads_as_empty_rather_than_throwing() {
        // A user picking a .csv they renamed, or a corrupt download. The import flow reports
        // "this file could not be read"; it must not crash.
        assertEquals(emptyMap(), readZipTextEntries("Name,Date\nAarav,04/01/1990".toByteArray()))
    }
}
