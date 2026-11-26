package `in`.wishkaro.app.domain

import `in`.wishkaro.app.platform.DeviceContact
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contacts import, as far as it can be driven without a device.
 *
 * The shapes here are the ones a real contacts provider produces — including the two this
 * project only found by looking at an actual handset: a birthday row whose date is NULL,
 * and a contacts list where 509 of 515 entries have no date at all.
 */
class ContactsImportTest {

    private val today = LocalDate(2026, 11, 26)

    private fun contact(
        id: String,
        name: String,
        birthday: String? = null,
        phone: String? = null,
    ) = DeviceContact(id, name, phone, birthday, photoUri = null)

    // ---------------------------------------------------------------------------------
    // The review list (FR-IMP-02)
    // ---------------------------------------------------------------------------------

    @Test
    fun contacts_with_a_birthday_come_first_and_arrive_ticked() {
        val review = ContactsImport.review(
            listOf(
                contact("1", "Zara Khan"),
                contact("2", "Aarav Shah", "1990-01-04"),
                contact("3", "Bhavna Joshi"),
                contact("4", "Meera Raghunathan", "--09-22"),
            ),
        )

        assertEquals(listOf("Aarav Shah", "Meera Raghunathan", "Bhavna Joshi", "Zara Khan"), review.map { it.contact.displayName })
        assertEquals(listOf(true, true, false, false), review.map { it.selected })
    }

    @Test
    fun the_dateless_majority_starts_unselected() {
        // The handset this was built against has 515 contacts and 3 usable birthdays.
        // Pre-ticking the rest would make "confirm" mean "import everyone you have ever
        // called", which is the one thing FR-IMP-02 is written to prevent.
        val review = ContactsImport.review((1..500).map { contact("$it", "Person $it") })
        assertTrue(review.none { it.selected })
    }

    @Test
    fun a_vcard_year_less_birthday_is_a_real_date() {
        val choice = ContactsImport.review(listOf(contact("1", "Meera", "--09-22"))).single()
        assertEquals(EventDate(22, 9, null), choice.effectiveDate)
        assertTrue(choice.selected)
    }

    @Test
    fun a_sentinel_year_is_read_as_no_year_rather_than_as_a_year() {
        assertEquals(
            EventDate(4, 1, null),
            ContactsImport.review(listOf(contact("1", "Aarav", "1604-01-04"))).single().effectiveDate,
        )
        assertEquals(
            EventDate(4, 1, null),
            ContactsImport.review(listOf(contact("2", "Aarav", "1904-01-04"))).single().effectiveDate,
        )
    }

    @Test
    fun a_birthday_row_with_no_date_leaves_the_contact_in_the_dateless_half() {
        // Found on the device, not imagined: one contact has a TYPE_BIRTHDAY row whose
        // START_DATE is null. The gateway drops it, so this is the belt on top of that.
        val choice = ContactsImport.review(listOf(contact("1", "Nameless Date", birthday = null))).single()
        assertNull(choice.effectiveDate)
        assertTrue(!choice.selected)
    }

    @Test
    fun a_date_the_user_types_overrides_nothing_and_fills_a_gap() {
        val base = ContactsImport.review(listOf(contact("1", "Zara Khan"))).single()
        val filled = base.copy(manualDate = EventDate(17, 6, 1992), selected = true)

        assertEquals(EventDate(17, 6, 1992), filled.effectiveDate)
        assertTrue(filled.hasDate)
    }

    // ---------------------------------------------------------------------------------
    // Only the selected ones (FR-IMP-01)
    // ---------------------------------------------------------------------------------

    @Test
    fun only_selected_contacts_become_rows() {
        val review = ContactsImport.review(
            listOf(
                contact("1", "Aarav Shah", "1990-01-04", phone = "9820011111"),
                contact("2", "Meera Raghunathan", "1988-09-22"),
                contact("3", "Zara Khan"),
            ),
        ).map { if (it.contact.id == "2") it.copy(selected = false) else it }
            .map { it.copy(relation = "Cousin") }

        val rows = ContactsImport.rows(review)

        assertEquals(1, rows.size)
        assertEquals(listOf("Aarav Shah", "4 Jan 1990", "Cousin", "9820011111", ""), rows.single())
    }

    @Test
    fun a_selected_contact_with_no_date_cannot_become_a_row() {
        val review = listOf(
            ContactChoice(contact("1", "Zara Khan"), relation = "Friend", selected = true),
        )
        assertTrue(ContactsImport.rows(review).isEmpty())
    }

    // ---------------------------------------------------------------------------------
    // The rows are re-read by the shared parser, so they must survive the round trip
    // ---------------------------------------------------------------------------------

    @Test
    fun a_year_less_contact_date_survives_the_round_trip_under_both_toggle_settings() {
        // THE trap. `01-04` written as ISO is two numbers under 13 — exactly the shape the
        // dd/MM toggle arbitrates — so a January the 4th birthday the device was certain
        // about would silently become April the 1st under one setting. A named month cannot
        // be read the other way round.
        val review = listOf(
            ContactChoice(contact("1", "Aarav Shah", "--01-04"), relation = "Cousin", selected = true),
        )
        val rows = ContactsImport.rows(review)

        for (order in DateOrder.entries) {
            val plan = planImport(
                rows,
                ContactsImport.MAPPING,
                ImportSettings(order = order, hasHeaderRow = false),
                DedupIndex(),
                today,
            )
            assertEquals(EventDate(4, 1, null), plan.ready.single().date, "under $order")
        }
    }

    @Test
    fun contacts_go_through_the_same_dedup_as_a_file() {
        val existing = DedupIndex(
            listOf(DedupCandidate("p1", "Aarav Shah", setOf(DedupCandidate.key(4, 1)))),
        )
        val rows = ContactsImport.rows(
            listOf(
                ContactChoice(contact("1", "Aarav Shah", "1990-01-04"), relation = "Cousin", selected = true),
                ContactChoice(contact("2", "Meera Raghunathan", "1988-09-22"), relation = "Aunt", selected = true),
            ),
        )

        val plan = planImport(
            rows, ContactsImport.MAPPING, ImportSettings(hasHeaderRow = false), existing, today,
        )

        assertEquals(listOf("Meera Raghunathan"), plan.ready.map { it.name })
        assertEquals(listOf("Aarav Shah"), plan.duplicates.map { it.ready.name })
    }

    @Test
    fun importing_the_same_contacts_twice_adds_nobody() {
        val choices = listOf(
            ContactChoice(contact("1", "Aarav Shah", "1990-01-04"), relation = "Cousin", selected = true),
            ContactChoice(contact("2", "Meera Raghunathan", "1988-09-22"), relation = "Aunt", selected = true),
        )
        val rows = ContactsImport.rows(choices)
        val settings = ImportSettings(hasHeaderRow = false)

        val first = planImport(rows, ContactsImport.MAPPING, settings, DedupIndex(), today)
        val afterFirst = DedupIndex(
            first.ready.mapIndexed { i, r ->
                DedupCandidate("p$i", r.name, setOf(DedupCandidate.key(r.date.day, r.date.month)))
            },
        )
        val second = planImport(rows, ContactsImport.MAPPING, settings, afterFirst, today)

        assertEquals(2, first.ready.size)
        assertEquals(0, second.ready.size)
    }

    // ---------------------------------------------------------------------------------
    // The bulk relation gate (FR-IMP-03)
    // ---------------------------------------------------------------------------------

    @Test
    fun the_import_button_knows_how_many_rows_still_need_a_relation() {
        val choices = listOf(
            ContactChoice(contact("1", "Aarav", "1990-01-04"), relation = "Cousin", selected = true),
            ContactChoice(contact("2", "Meera", "1988-09-22"), relation = null, selected = true),
            ContactChoice(contact("3", "Rohit", "1965-03-14"), relation = "  ", selected = true),
            ContactChoice(contact("4", "Zara"), relation = null, selected = false),
        )

        assertEquals(2, ContactsImport.missingRelations(choices), "blank counts, unselected does not")
    }
}
