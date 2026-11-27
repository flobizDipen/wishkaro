package `in`.wishkaro.app.domain

import `in`.wishkaro.app.ui.importing.guessMapping
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The READER half of definition-of-done item 2:
 *
 * > *"Its CSV re-imported through M3's importer **without a mapping change**."*
 *
 * The writer half is `server/src/modules/account/export-csv.spec.ts`, reading THE SAME FILE.
 * The export format is a rule implemented twice in two languages — a writer in TypeScript and
 * a reader in Kotlin — which is the shape `MILESTONE_CHECKLIST.md` says is held to one shared
 * table of cases, because each side's own suite stays perfectly green while the two disagree.
 *
 * This does not re-implement the export. It takes the rows the fixture says the server writes,
 * assembles them into a file with `Csv.write`, and runs that file through the **real**
 * importer: `Csv.read`, `guessMapping`, `buildImportPlan`. "Without a mapping change" is
 * tested by never touching the mapping — whatever `guessMapping` returns from the header is
 * what the plan is built with. A test that set the columns by hand would prove the opposite of
 * what item 2 claims.
 *
 * Lives in `androidUnitTest` for the same reason `NameTokensTest` does: it reads the fixture
 * off disk, and copying the table into Kotlin would defeat having one table. The same cost
 * applies — Kotlin/Native never runs it, because CI compiles the iOS target and runs nothing
 * on it.
 */
class ExportRoundTripTest {

    @Serializable
    private data class Reimported(
        val name: String,
        val day: Int,
        val month: Int,
        val year: Int? = null,
        val relation: String,
    )

    @Serializable
    private data class Case(val why: String, val row: List<String>, val reimported: Reimported)

    @Serializable
    private data class Mapping(
        val name: Int,
        val date: Int,
        val relation: Int,
        val phone: Int,
        val notes: Int,
    )

    @Serializable
    private data class Fixture(
        val header: List<String> = emptyList(),
        val expectedMapping: Mapping,
        val cases: List<Case> = emptyList(),
    )

    private val fixture: Fixture by lazy {
        Json { ignoreUnknownKeys = true }.decodeFromString(fixtureFile().readText())
    }

    private fun fixtureFile(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "docs/fixtures/export-roundtrip.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("docs/fixtures/export-roundtrip.json not found from ${File(".").absolutePath}")
    }

    /** Today, fixed. `ImportDates` uses it to resolve two-digit years and to reject the future. */
    private val today = LocalDate(2026, 11, 27)

    @Test
    fun the_shared_table_is_actually_being_read() {
        // Without this, a path that stopped resolving would make every test below pass by
        // iterating an empty list — which is the vacuous-green shape M5 shipped twice.
        assertTrue(fixture.cases.size >= 7, "expected the full table, got ${fixture.cases.size}")
        assertEquals(5, fixture.header.size)
    }

    /**
     * **"Without a mapping change", stated as the thing it actually means.**
     *
     * The guesser has to claim all five columns from the export's own header, each exactly
     * once, with nobody touching the screen. If the export ever renames a column to something
     * the guesser does not match — or to something a column earlier in the claim order takes
     * first — the user opens the mapping screen to five empty dropdowns and item 2 is false.
     */
    @Test
    fun the_exports_own_header_maps_itself_with_nobody_touching_the_screen() {
        val mapping = guessMapping(fixture.header)

        assertEquals(fixture.expectedMapping.name, mapping.name)
        assertEquals(fixture.expectedMapping.date, mapping.date)
        assertEquals(fixture.expectedMapping.relation, mapping.relation)
        assertEquals(fixture.expectedMapping.phone, mapping.phone)
        assertEquals(fixture.expectedMapping.notes, mapping.notes)
        assertTrue(mapping.isUsable, "the Import button must be enabled without an edit")
    }

    @Test
    fun every_exported_row_comes_back_as_the_person_it_left_as() {
        val file = Csv.write(listOf(fixture.header) + fixture.cases.map { it.row })
        val rows = Csv.parse(file)

        // The real path, in the real order, with the real guess.
        val mapping = guessMapping(fixture.header)
        val plan = planImport(
            rows = rows,
            mapping = mapping,
            settings = ImportSettings(hasHeaderRow = true),
            index = DedupIndex(),
            today = today,
        )

        assertEquals(
            fixture.cases.size,
            plan.ready.size,
            "every exported row must be importable; failed: " +
                plan.failed.joinToString { "${it.row.cells.firstOrNull()}: ${it.reason}" },
        )

        for ((index, case) in fixture.cases.withIndex()) {
            val ready = plan.ready[index]
            val expected = case.reimported

            assertEquals(expected.name, ready.name, case.why)
            assertEquals(expected.day, ready.date.day, case.why)
            assertEquals(expected.month, ready.date.month, case.why)
            assertEquals(expected.year, ready.date.year, case.why)
            assertEquals(expected.relation, ready.relation, case.why)
        }
    }

    /**
     * **The reason the export writes `3 May 1990` and not `03/05/1990`.**
     *
     * FR-IMP-06's day-first/month-first toggle is a USER PREFERENCE (FR-SET-08 makes it a
     * setting), so a numeric date in the export would re-import as 3 May for one user and
     * 5 March for another — silently, with no row marked failed and nothing on screen to
     * notice. A round trip that depends on a preference is not a round trip.
     *
     * So the same file is imported twice, once under each setting, and must produce identical
     * people. This is the single assertion that makes item 2 true for every user rather than
     * for the one whose toggle happens to be at its default.
     */
    @Test
    fun the_same_export_imports_identically_under_both_settings_of_the_ddMM_toggle() {
        val file = Csv.write(listOf(fixture.header) + fixture.cases.map { it.row })
        val rows = Csv.parse(file)
        val mapping = guessMapping(fixture.header)

        fun planWith(order: DateOrder) = planImport(
            rows = rows,
            mapping = mapping,
            settings = ImportSettings(order = order, hasHeaderRow = true),
            index = DedupIndex(),
            today = today,
        )

        val dayFirst = planWith(DateOrder.DAY_FIRST).ready
        val monthFirst = planWith(DateOrder.MONTH_FIRST).ready

        assertEquals(dayFirst.size, monthFirst.size)
        for (index in dayFirst.indices) {
            assertEquals(
                dayFirst[index].date,
                monthFirst[index].date,
                "${fixture.cases[index].why}: the export's date format must not depend on the toggle",
            )
        }
    }

    /**
     * And none of them is flagged ambiguous, which is the mechanism underneath the test above.
     *
     * `ImportDates.fromNamedMonth` returns `ambiguous = false` unconditionally. Asserting on
     * the flag as well as on the outcome means a future format that happened to agree under
     * both settings — by luck, for this particular table — still fails here.
     */
    @Test
    fun no_exported_date_is_even_marked_ambiguous() {
        for (case in fixture.cases) {
            val parsed = ImportDates.parse(case.row[1], DateOrder.DAY_FIRST, today)
            val success = parsed as? DateParse.Parsed
            assertNotNull(success, "${case.why}: ${case.row[1]} did not parse")
            assertTrue(
                !success.ambiguous,
                "${case.why}: ${case.row[1]} is ambiguous, so it depends on the user's toggle",
            )
        }
    }
}
