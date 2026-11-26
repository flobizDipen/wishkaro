package `in`.wishkaro.app.domain

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Dedup and idempotency, against a fake store, with no UI.
 *
 * Definition-of-done item 5 asks for this to be proven by a test that runs the same import
 * twice, "not by importing by hand and looking" — so `importedTwice` below is the shape that
 * matters most here: it runs the real planner over the real file twice against a store that
 * accumulates, exactly as the phone would.
 */
class ImportPlanTest {

    private val today = LocalDate(2026, 11, 26)

    private val mapping = ColumnMapping(name = 0, date = 1, relation = 2, phone = 3, notes = 4)
    private val settings = ImportSettings(order = DateOrder.DAY_FIRST)

    private fun rows(vararg lines: String) = Csv.parse(lines.joinToString("\n"))

    /** A store that behaves like the database: rows accepted become candidates next time. */
    private class FakeStore {
        private val people = mutableListOf<DedupCandidate>()
        var nextId = 1

        fun index() = DedupIndex(people.toList())

        fun apply(plan: ImportPlan) {
            for (ready in plan.ready) {
                people += DedupCandidate(
                    id = "p${nextId++}",
                    displayName = ready.name,
                    dayMonths = setOf(DedupCandidate.key(ready.date.day, ready.date.month)),
                )
            }
        }

        val size: Int get() = people.size
    }

    // ---------------------------------------------------------------------------------
    // Definition-of-done item 5: the same file twice
    // ---------------------------------------------------------------------------------

    @Test
    fun the_same_file_imported_twice_produces_no_new_people_the_second_time() {
        val file = rows(
            "Name,Date,Relation,Phone,Notes",
            "Aarav Shah,04/01/1990,Cousin,,",
            "Meera Raghunathan,22 Sep 1988,Aunt,,",
            "Rohit Desai,14-03-65,Uncle,,",
        )
        val store = FakeStore()

        val first = planImport(file, mapping, settings, store.index(), today)
        store.apply(first)

        val second = planImport(file, mapping, settings, store.index(), today)
        store.apply(second)

        assertEquals(3, first.ready.size, "all three arrive the first time")
        assertEquals(0, second.ready.size, "and none of them arrive again")
        assertEquals(3, second.duplicates.size, "they are surfaced as duplicates, not swallowed")
        assertEquals(3, store.size, "the store is unchanged by the second run")
        assertTrue(second.duplicates.none { it.withinThisFile }, "they match the DATABASE, not each other")
    }

    @Test
    fun a_third_run_changes_nothing_either() {
        // Idempotent means idempotent, not "stable after two".
        val file = rows("Name,Date,Relation", "Aarav Shah,04/01/1990,Cousin")
        val store = FakeStore()
        repeat(3) { store.apply(planImport(file, mapping, settings, store.index(), today)) }
        assertEquals(1, store.size)
    }

    @Test
    fun the_corrected_file_re_imports_cleanly_and_the_good_rows_do_not_double() {
        // FR-IMP-07 and FR-IMP-08 are one feature: the user fixes the broken row, re-runs
        // the WHOLE file, and only the fixed row is new.
        val original = rows(
            "Name,Date,Relation",
            "Aarav Shah,04/01/1990,Cousin",
            "Diya Menon,31/04/1990,Colleague",
        )
        val store = FakeStore()
        val first = planImport(original, mapping, settings, store.index(), today)
        store.apply(first)
        assertEquals(1, first.ready.size)
        assertEquals(1, first.failed.size)

        val corrected = rows(
            "Name,Date,Relation",
            "Aarav Shah,04/01/1990,Cousin",
            "Diya Menon,30/04/1990,Colleague",
        )
        val second = planImport(corrected, mapping, settings, store.index(), today)
        store.apply(second)

        assertEquals(listOf("Diya Menon"), second.ready.map { it.name })
        assertEquals(2, store.size)
    }

    // ---------------------------------------------------------------------------------
    // Definition-of-done item 6: a duplicate inside one file
    // ---------------------------------------------------------------------------------

    @Test
    fun the_same_aunt_on_two_rows_is_one_aunt() {
        val file = rows(
            "Name,Date,Relation",
            "Meera Raghunathan,22/09/1988,Aunt",
            "Aarav Shah,04/01/1990,Cousin",
            "Meera Raghunathan,22/09/1988,Aunt",
        )

        val plan = planImport(file, mapping, settings, DedupIndex(), today)

        assertEquals(2, plan.ready.size)
        assertEquals(1, plan.duplicates.size)
        assertTrue(plan.duplicates.single().withinThisFile, "it matched a row, not a person")
        assertEquals(4, plan.duplicates.single().row.number, "line 4 — the SECOND Meera, counting the header as line 1")
    }

    @Test
    fun word_order_does_not_create_a_second_person() {
        val file = rows(
            "Name,Date,Relation",
            "Jansari Jaydatt,22/09/1988,Father",
            "Jaydatt Jansari,22/09/1988,Father",
        )
        assertEquals(1, planImport(file, mapping, settings, DedupIndex(), today).ready.size)
    }

    @Test
    fun two_people_who_share_a_surname_are_two_people() {
        // The name rule matches on a shared token, so Aarav and Aarti Shah DO match by name.
        // The day/month is what separates them — which is why the rule is name plus a date
        // and never a name alone.
        val file = rows(
            "Name,Date,Relation",
            "Aarav Shah,04/01/1990,Cousin",
            "Aarti Shah,17/06/1992,Cousin",
        )
        assertEquals(2, planImport(file, mapping, settings, DedupIndex(), today).ready.size)
    }

    @Test
    fun the_same_name_on_a_different_date_is_a_different_person() {
        val existing = DedupIndex(
            listOf(DedupCandidate("p1", "Aarav Shah", setOf(DedupCandidate.key(4, 1)))),
        )
        val file = rows("Name,Date,Relation", "Aarav Shah,17/06/1992,Friend")

        assertEquals(1, planImport(file, mapping, settings, existing, today).ready.size)
    }

    @Test
    fun a_match_is_surfaced_and_never_merged() {
        // FR-PPL-06. The row carries everything needed to ask "Is this the same person?",
        // and the default is to skip — a default of "import anyway" would make idempotency
        // depend on a human noticing.
        val existing = DedupIndex(
            listOf(DedupCandidate("p1", "Aarav Shah", setOf(DedupCandidate.key(4, 1)))),
        )
        val plan = planImport(
            rows("Name,Date,Relation", "Aarav Shah,04/01/1990,Cousin"),
            mapping, settings, existing, today,
        )

        val duplicate = plan.duplicates.single()
        assertEquals("p1", duplicate.matchId)
        assertEquals("Aarav Shah", duplicate.matchName)
        assertEquals("Cousin", duplicate.ready.relation, "the row is kept whole in case the user says import it anyway")
    }

    @Test
    fun a_dedup_index_over_many_people_still_answers_from_the_tokens_it_built_once() {
        // Not a benchmark — a guard that the index is actually an index. If `matches` ever
        // went back to scanning, 600 rows against 400 people would be 240,000 tokenisations
        // and this would take long enough to notice.
        val existing = (1..400).map {
            DedupCandidate("p$it", "Person$it Surname$it", setOf(DedupCandidate.key(1 + it % 28, 1 + it % 12)))
        }
        val index = DedupIndex(existing)
        val file = listOf(listOf("Name", "Date", "Relation")) +
            (1..600).map { listOf("Person$it Surname$it", "${1 + it % 28}/${1 + it % 12}/1990", "Friend") }

        val plan = planImport(file, mapping, settings, index, today)

        assertEquals(400, plan.duplicates.size, "the first 400 are already here")
        assertEquals(200, plan.ready.size)
    }

    // ---------------------------------------------------------------------------------
    // Rows that cannot be written
    // ---------------------------------------------------------------------------------

    @Test
    fun every_kind_of_broken_row_gets_its_own_reason() {
        val file = rows(
            "Name,Date,Relation",
            ",04/01/1990,Friend",
            "Arjun Pillai,,Cousin",
            "Diya Menon,31/04/1990,Colleague",
            "Myra Bhatt,not a date,Friend",
            "Kabir Sethi,04/01/1990,",
        )

        val reasons = planImport(file, mapping, settings, DedupIndex(), today).failed
            .associate { it.row.number to it.reason }

        assertEquals("no name", reasons[2])
        assertEquals("no date", reasons[3])
        assertTrue("not a real date" in reasons.getValue(4))
        assertTrue("could not read" in reasons.getValue(5))
        assertEquals("no relation", reasons[6])
    }

    @Test
    fun a_default_relation_rescues_a_file_that_has_no_relation_column() {
        // Without this, a spreadsheet of names and dates — which is what most of them are —
        // would produce a wall of "no relation" failures instead of importing.
        val file = rows("Name,Date", "Aarav Shah,04/01/1990", "Meera Raghunathan,22/09/1988")
        val plan = planImport(
            file,
            ColumnMapping(name = 0, date = 1),
            settings.copy(defaultRelation = "Friend"),
            DedupIndex(),
            today,
        )

        assertEquals(2, plan.ready.size)
        assertTrue(plan.ready.all { it.relation == "Friend" })
    }

    @Test
    fun a_rows_own_relation_beats_the_default() {
        val file = rows("Name,Date,Relation", "Aarav Shah,04/01/1990,Cousin", "Meera R,22/09/1988,")
        val plan = planImport(file, mapping, settings.copy(defaultRelation = "Friend"), DedupIndex(), today)

        assertEquals(listOf("Cousin", "Friend"), plan.ready.map { it.relation })
    }

    @Test
    fun over_long_fields_are_reported_rather_than_silently_changed() {
        val file = listOf(
            listOf("Name", "Date", "Relation", "Phone", "Notes"),
            listOf("Aarav Shah", "04/01/1990", "Cousin", "9".repeat(60), "n".repeat(3000)),
        )
        val ready = planImport(file, mapping, settings, DedupIndex(), today).ready.single()

        assertEquals(null, ready.phone, "half a phone number is worse than none")
        assertEquals(2000, ready.notes?.length, "a note is still useful shortened")
        assertEquals(2, ready.warnings.size, "and the user is told about both")
    }

    @Test
    fun a_name_the_server_would_refuse_fails_here_where_the_user_can_see_the_file() {
        // The safety net for a rejected row is sync_blocked; this is only about WHEN the
        // user finds out. "Row 2: the name is longer than 120 characters", now, beats
        // "your sync quietly stopped mentioning this person", three days from now.
        val file = listOf(listOf("Name", "Date", "Relation"), listOf("A".repeat(200), "04/01/1990", "Cousin"))
        val failure = planImport(file, mapping, settings, DedupIndex(), today).failed.single()

        assertTrue("longer than 120" in failure.reason)
    }

    @Test
    fun a_file_with_no_header_row_does_not_lose_its_first_person() {
        val file = rows("Aarav Shah,04/01/1990,Cousin")
        val plan = planImport(file, mapping, settings.copy(hasHeaderRow = false), DedupIndex(), today)

        assertEquals(1, plan.ready.size)
        assertEquals(1, plan.ready.single().row.number)
    }

    // ---------------------------------------------------------------------------------
    // The row number the user is told is the row in THEIR file
    // ---------------------------------------------------------------------------------

    /**
     * A blank line between households is ordinary in a family spreadsheet, and FR-IMP-07's
     * whole purpose is that the user can go and find the row. Numbering after the blank
     * lines had been filtered out made the report point at a different, perfectly good
     * record — and each further blank widened the drift.
     */
    @Test
    fun a_blank_line_does_not_shift_the_rows_below_it() {
        val file = Csv.parse(
            listOf(
                "Name,Date,Relation",       // line 1
                "Aarav Shah,04/01/1990,Cousin",   // line 2
                "",                         // line 3 — the household separator
                "Diya Menon,31/04/1990,Colleague", // line 4, and it is broken
            ).joinToString("\n"),
        )

        val plan = planImport(file, mapping, settings, DedupIndex(), today)

        assertEquals(4, plan.failed.single().row.number, "the user opens line 4 and finds it")
    }

    @Test
    fun several_blank_lines_do_not_accumulate_drift() {
        val lines = mutableListOf("Name,Date,Relation")
        repeat(3) {
            lines += "Person$it,04/01/1990,Friend"
            lines += ""
        }
        lines += "Myra Bhatt,not a date,Friend"

        val plan = planImport(Csv.parse(lines.joinToString("\n")), mapping, settings, DedupIndex(), today)

        assertEquals(lines.size, plan.failed.single().row.number)
        assertEquals(3, plan.ready.size, "and the blank lines are not people")
    }

    @Test
    fun a_blank_line_is_not_reported_as_a_row_that_failed() {
        val file = Csv.parse("Name,Date,Relation\n\nAarav Shah,04/01/1990,Cousin")
        val plan = planImport(file, mapping, settings, DedupIndex(), today)

        assertTrue(plan.failed.isEmpty(), "a separator line is not a person with no name")
        assertEquals(1, plan.ready.size)
    }

    @Test
    fun a_stray_blank_line_above_the_headings_does_not_break_the_mapping() {
        val file = Csv.parse("\nName,Date,Relation\nAarav Shah,04/01/1990,Cousin")
        val plan = planImport(file, mapping, settings, DedupIndex(), today)

        assertEquals(1, plan.ready.size, "the header is found, not assumed to be line 1")
        assertEquals(3, plan.ready.single().row.number)
    }

    // ---------------------------------------------------------------------------------
    // The corrected CSV round trip
    // ---------------------------------------------------------------------------------

    @Test
    fun the_corrected_csv_hands_back_the_users_own_columns_and_values() {
        val file = rows(
            "Full Name,Birthday,How I know them",
            "Aarav Shah,04/01/1990,Cousin",
            "Diya Menon,31/04/1990,Colleague",
        )
        val plan = planImport(file, mapping, settings, DedupIndex(), today)

        val csv = correctedCsv(file.first(), plan.failed)
        val reparsed = Csv.parse(csv)

        assertEquals(listOf("Full Name", "Birthday", "How I know them", REASON_COLUMN), reparsed[0])
        assertEquals(2, reparsed.size, "only the row that failed")
        assertEquals("Diya Menon", reparsed[1][0], "their value, not our interpretation of it")
        assertEquals("31/04/1990", reparsed[1][1], "unchanged, so they can see what they typed")
        assertTrue("not a real date" in reparsed[1][3])
    }

    @Test
    fun a_row_WIDER_than_the_header_still_puts_its_reason_in_the_reason_column() {
        // The review finding. One stray comma makes a row wider than the header, and a file
        // like that is exactly what import exists to cope with. Padding only the SHORT rows
        // left the reason past the column the header declared, so the user opened the
        // corrected file and found the explanation in the wrong place.
        val file = listOf(
            listOf("Name", "Date", "Relation"),
            listOf("Diya Menon", "31/04/1990", "Colleague", "oops, a stray comma"),
        )
        val plan = planImport(file, mapping, settings, DedupIndex(), today)

        val reparsed = Csv.parse(correctedCsv(file.first(), plan.failed))

        assertEquals(reparsed[0].size, reparsed[1].size, "every line is the same width")
        assertEquals(REASON_COLUMN, reparsed[0].last())
        assertTrue("not a real date" in reparsed[1].last(), "and the reason is in that column")
        assertTrue(
            "oops, a stray comma" in reparsed[1],
            "the user's extra value is kept, not truncated away",
        )
    }

    @Test
    fun a_headerless_file_wider_than_the_substituted_header_still_lines_up() {
        // The other half: with no header row the saved file falls back to a five-name
        // default, so any headerless file with more than five columns misaligned too.
        val wide = listOf(List(8) { "cell$it" })
        val plan = planImport(
            wide,
            ColumnMapping(name = 0, date = 1),
            settings.copy(hasHeaderRow = false, defaultRelation = "Friend"),
            DedupIndex(),
            today,
        )

        val reparsed = Csv.parse(correctedCsv(listOf("Name", "Date", "Relation", "Phone", "Notes"), plan.failed))

        assertEquals(1, plan.failed.size, "cell1 is not a date")
        assertEquals(reparsed[0].size, reparsed[1].size)
        assertEquals(REASON_COLUMN, reparsed[0].last())
        assertTrue("could not read" in reparsed[1].last())
    }

    @Test
    fun a_short_row_still_puts_its_reason_in_the_reason_column() {
        // A file where some rows have fewer cells than the header is ordinary. Without
        // padding, the reason would land in whichever column the row happened to end at.
        val file = listOf(listOf("Name", "Date", "Relation"), listOf("Arjun Pillai"))
        val plan = planImport(file, mapping, settings, DedupIndex(), today)

        val reparsed = Csv.parse(correctedCsv(file.first(), plan.failed))
        assertEquals(4, reparsed[1].size)
        assertEquals("no date", reparsed[1][3])
    }

    // ---------------------------------------------------------------------------------
    // The toggle, through the planner rather than the parser
    // ---------------------------------------------------------------------------------

    @Test
    fun the_whole_plan_flips_with_the_date_order() {
        val file = rows("Name,Date,Relation", "Ishaan Patel,05/06/1992,Friend")

        val dayFirst = planImport(file, mapping, settings, DedupIndex(), today).ready.single()
        val monthFirst = planImport(
            file, mapping, settings.copy(order = DateOrder.MONTH_FIRST), DedupIndex(), today,
        ).ready.single()

        assertEquals(EventDate(5, 6, 1992), dayFirst.date)
        assertEquals(EventDate(6, 5, 1992), monthFirst.date)
        assertTrue(dayFirst.ambiguousDate, "the preview marks it, because flipping changes it")
    }

    @Test
    fun flipping_the_toggle_makes_an_already_imported_row_look_new() {
        // Worth knowing and worth stating: idempotency is per (name, day, month), so a
        // re-import under the OTHER setting is genuinely a different date and genuinely a
        // different person as far as the rule is concerned. The duplicate prompt is what
        // catches it for the user; the rule cannot.
        val existing = DedupIndex(
            listOf(DedupCandidate("p1", "Ishaan Patel", setOf(DedupCandidate.key(5, 6)))),
        )
        val file = rows("Name,Date,Relation", "Ishaan Patel,05/06/1992,Friend")

        val flipped = planImport(file, mapping, settings.copy(order = DateOrder.MONTH_FIRST), existing, today)
        assertEquals(1, flipped.ready.size)
    }
}
