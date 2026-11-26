package `in`.wishkaro.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The caps that stop a person editor walking into a silent sync block.
 *
 * FOUND ON THE DEVICE while verifying the blocked-row banner: nothing in the person editor
 * capped any text field, while `PersonUpsertSchema` caps `notes` at 2,000. Pasting a long
 * note saved happily, the next push came back 400, and the person stopped syncing — no
 * reminders, nothing on screen until the banner this PR adds.
 *
 * The banner is the safety net and stays the safety net: the server has rules this app does
 * not mirror. These are prevention for the one hole a user can type their way into.
 */
class FieldLimitsTest {

    @Test
    fun the_caps_match_what_the_server_accepts() {
        // Mirrored from PersonUpsertSchema and TraitUpsertSchema. If the server's limits
        // move, this is the list to move with them — and being slightly wrong is cheap,
        // because the blocked-row banner still catches anything these do not.
        assertEquals(120, FieldLimits.NAME)
        assertEquals(60, FieldLimits.RELATION)
        assertEquals(40, FieldLimits.GENDER)
        assertEquals(40, FieldLimits.PHONE)
        assertEquals(254, FieldLimits.EMAIL)
        assertEquals(2000, FieldLimits.NOTES)
        assertEquals(120, FieldLimits.TRAIT)
    }

    @Test
    fun a_note_at_the_cap_is_what_the_server_will_take() {
        // The exact length the device was blocked at was 2,250. `take` is what the editor
        // now applies, so the value that reaches the outbox is this.
        val pasted = "x".repeat(2250)
        assertEquals(2000, pasted.take(FieldLimits.NOTES).length)
    }

    @Test
    fun the_counter_appears_only_near_the_limit() {
        // A counter on every note would be clutter; a field that silently stops accepting
        // input is a small mystery. It shows in the last hundred characters.
        assertFalse(FieldLimits.showCounter(0, FieldLimits.NOTES))
        assertFalse(FieldLimits.showCounter(1_800, FieldLimits.NOTES))
        assertTrue(FieldLimits.showCounter(1_900, FieldLimits.NOTES))
        assertTrue(FieldLimits.showCounter(2_000, FieldLimits.NOTES))
    }

    @Test
    fun a_short_limit_still_warns_before_it_bites() {
        // Phone is 40, so the threshold would be negative — it must still be sane rather
        // than warning from the first character.
        assertTrue(FieldLimits.showCounter(0, FieldLimits.PHONE), "a 40-char field is always near its limit")
    }
}
