package `in`.wishkaro.app.ui.importing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * The two small things the review found in the mapping screen's starting state.
 *
 * Both are only about what the user is OFFERED before they touch anything, and the live
 * preview makes either visible — but a screen whose own opening guess breaks the rule it
 * enforces on every later edit is a screen that teaches the user not to trust it.
 */
class ColumnGuessTest {

    @Test
    fun one_column_is_never_offered_as_two_different_fields() {
        // `Birthday Notes` matches the date words AND the notes words. Before the fix it was
        // mapped as both, so every imported person's note was their own raw date string.
        val mapping = guessMapping(listOf("Full Name", "Birthday Notes"))

        assertEquals(0, mapping.name)
        assertEquals(1, mapping.date, "date claims it first — a wrong date costs more than a wrong note")
        assertNull(mapping.notes, "and nothing else may claim the same column")
    }

    @Test
    fun an_ordinary_header_is_still_guessed_completely() {
        val mapping = guessMapping(listOf("Full Name", "Birthday", "How I know them", "Mobile", "Notes"))

        assertEquals(0, mapping.name)
        assertEquals(1, mapping.date)
        assertEquals(2, mapping.relation)
        assertEquals(3, mapping.phone)
        assertEquals(4, mapping.notes)
    }

    @Test
    fun every_field_the_guess_fills_points_at_a_different_column() {
        // A header where one word appears in every column is the worst case for independent
        // keyword matching.
        val mapping = guessMapping(
            listOf("Contact Name", "Contact Date", "Contact Type", "Contact Number", "Contact Note"),
        )
        val claimed = listOfNotNull(
            mapping.name, mapping.date, mapping.relation, mapping.phone, mapping.notes,
        )

        assertEquals(claimed.size, claimed.distinct().size, "no column is claimed twice")
    }

    @Test
    fun a_header_that_matches_nothing_leaves_the_user_to_choose() {
        val mapping = guessMapping(listOf("A", "B", "C"))

        assertNull(mapping.name)
        assertNull(mapping.date)
        assertEquals(false, mapping.isUsable, "and the Import button stays disabled")
    }

    @Test
    fun generated_column_letters_do_not_run_off_the_end_of_the_alphabet() {
        assertEquals("A", columnLetters(0))
        assertEquals("Z", columnLetters(25))
        // Before the fix this was "[", the character after Z.
        assertEquals("AA", columnLetters(26))
        assertEquals("AB", columnLetters(27))
        assertEquals("AZ", columnLetters(51))
        assertEquals("BA", columnLetters(52))
        assertNotEquals("[", columnLetters(26))
    }
}
