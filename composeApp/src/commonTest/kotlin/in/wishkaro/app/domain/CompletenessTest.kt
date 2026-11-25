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
    ) = Person(
        id = "p1",
        displayName = "Aarav",
        relationToOwner = null,
        isSelf = false,
        gender = null,
        phone = null,
        email = null,
        notes = null,
        isDeceased = false,
        isMinor = isMinor,
        completenessScore = 40,
        completenessSuggestions = suggestions,
        deletedAt = null,
        events = emptyList(),
        traits = emptyList(),
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

    @Test
    fun every_field_has_wording_a_sentence_can_use() {
        for (field in CompletenessField.entries) {
            assertTrue(field.prompt.isNotBlank(), "${field.name} has no prompt")
            // Used as "Add ${prompt}", so it must read as an object, not a sentence.
            assertTrue(field.prompt.first().isLowerCase(), "${field.name} prompt is capitalised")
        }
    }
}
