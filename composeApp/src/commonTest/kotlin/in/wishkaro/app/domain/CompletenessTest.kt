package `in`.wishkaro.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The client half of FR-PPL-05. The SCORE and the ORDER are the server's — this covers only
 * what the client does with them, which is read the first entry and find wording for it.
 */
class CompletenessTest {

    private fun person(
        suggestions: List<String>,
        isMinor: Boolean = false,
        phone: String? = null,
        notes: String? = null,
        relation: String? = null,
        events: List<PersonEvent> = emptyList(),
        traits: List<PersonTrait> = emptyList(),
    ) = Person(
        id = "p1",
        displayName = "Aarav",
        relationToOwner = relation,
        isSelf = false,
        gender = null,
        phone = phone,
        email = null,
        notes = notes,
        isDeceased = false,
        isMinor = isMinor,
        completenessScore = 40,
        completenessSuggestions = suggestions,
        deletedAt = null,
        events = events,
        traits = traits,
    )

    @Test
    fun the_next_improvement_is_the_servers_first_suggestion() {
        val p = person(listOf("hobby", "like", "notes"))
        assertEquals(CompletenessField.HOBBY, p.nextImprovement)
    }

    @Test
    fun an_empty_list_asks_for_nothing() {
        assertNull(person(emptyList()).nextImprovement)
    }

    /** FR-PPL-09: the server sends no suggestions for a minor, so the UI asks for nothing. */
    @Test
    fun a_minor_is_never_asked_for_anything() {
        assertNull(person(emptyList(), isMinor = true).nextImprovement)
    }

    /**
     * A newer server may name a field this build does not know. Skipping to the next one it
     * does understand beats showing the user a raw wire name like "budgetBand".
     */
    @Test
    fun an_unknown_field_name_is_skipped_rather_than_shown_raw() {
        val p = person(listOf("somethingNewTheServerAdded", "phone"))
        assertEquals(CompletenessField.PHONE, p.nextImprovement)
    }

    /**
     * The mirrored list goes stale between syncs. Fill in a phone number in aeroplane mode
     * and the server's list still names it, so the app would ask for something already on
     * the screen. The order stays the server's; "is it filled in" is answered locally.
     */
    @Test
    fun a_field_already_filled_in_locally_is_skipped_even_if_the_server_still_suggests_it() {
        val p = person(listOf("phone", "notes"), phone = "+919812345678")
        assertEquals(CompletenessField.NOTES, p.nextImprovement)
    }

    @Test
    fun a_locally_added_trait_is_skipped_too() {
        val p = person(
            listOf("hobby", "like"),
            traits = listOf(PersonTrait("t1", "p1", TraitKind.HOBBY, "cricket")),
        )
        assertEquals(CompletenessField.LIKE, p.nextImprovement)
    }

    @Test
    fun nothing_is_asked_once_everything_suggested_is_filled_in() {
        val p = person(listOf("phone", "relation"), phone = "+91", relation = "Brother")
        assertNull(p.nextImprovement)
    }

    /** FR-EVT-02: a birthday with no year does not satisfy the birth-year field. */
    @Test
    fun a_year_unknown_birthday_does_not_satisfy_the_birth_year_field() {
        val noYear = PersonEvent(
            "e1", "p1", EventType.BIRTHDAY, Sentiment.CELEBRATORY, null, false, EventDate(7, 7, null),
        )
        assertEquals(
            CompletenessField.BIRTH_YEAR,
            person(listOf("birthYear"), events = listOf(noYear)).nextImprovement,
        )

        val withYear = noYear.copy(date = EventDate(7, 7, 1960))
        assertNull(person(listOf("birthYear"), events = listOf(withYear)).nextImprovement)
    }

    @Test
    fun every_field_has_wording_a_sentence_can_use() {
        for (field in CompletenessField.entries) {
            assertTrue(field.prompt.isNotBlank(), "${field.name} has no prompt")
            // Used as "Add ${prompt}", so it must read as an object, not a sentence.
            assertTrue(field.prompt.first().isLowerCase(), "${field.name} prompt is capitalised")
        }
    }
}
