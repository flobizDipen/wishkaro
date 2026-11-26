package `in`.wishkaro.app.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The phone number that goes into a `wa.me` link (FR-WSH-05).
 *
 * This is the half of the WhatsApp hand-off that is actually wrong in the field. The Intent
 * around it is three lines; the parsing has to meet every way a number has ever been typed
 * into a contacts app, and `wa.me` rejects anything that is not digits with a country code.
 *
 * Getting it wrong does not fail loudly — it opens a chat with a number that does not exist,
 * or WhatsApp's "this number is not on WhatsApp" sheet, and the user has no idea why.
 *
 * Every number here is INVENTED. The repository is public, and `98765 43210` is the number
 * Indian textbooks use precisely because it belongs to nobody.
 */
class WhatsAppNumberTest {

    @Test
    fun an_international_number_keeps_its_country_code() {
        assertEquals("919876543210", whatsAppNumber("+91 98765 43210"))
        assertEquals("919876543210", whatsAppNumber("+91-98765-43210"))
        assertEquals("919876543210", whatsAppNumber("+919876543210"))
        assertEquals("447700900123", whatsAppNumber("+44 7700 900123"))
    }

    @Test
    fun a_bare_ten_digit_number_is_assumed_indian() {
        // REQUIREMENTS §1: India-first. A ten-digit number with no country code is an Indian
        // mobile, and the alternative is sending the user to a chat that does not exist.
        assertEquals("919876543210", whatsAppNumber("9876543210"))
        assertEquals("919876543210", whatsAppNumber("98765 43210"))
        assertEquals("919876543210", whatsAppNumber("98765-43210"))
    }

    @Test
    fun the_national_zero_prefix_is_dropped() {
        // How the Android dialler often stores a number after a call.
        assertEquals("919876543210", whatsAppNumber("09876543210"))
    }

    @Test
    fun double_zero_is_the_other_way_of_writing_plus() {
        assertEquals("919876543210", whatsAppNumber("00919876543210"))
    }

    @Test
    fun an_already_prefixed_number_without_a_plus_is_left_alone() {
        assertEquals("919876543210", whatsAppNumber("919876543210"))
    }

    @Test
    fun nothing_usable_returns_null_so_whatsapp_opens_its_own_picker() {
        // "I know their birthday but not their number" is an ordinary state for a record in
        // this app — plenty of people here have a date and nothing else. Opening WhatsApp's
        // own picker with the text attached is a better answer than refusing to send.
        assertNull(whatsAppNumber(null))
        assertNull(whatsAppNumber(""))
        assertNull(whatsAppNumber("   "))
        assertNull(whatsAppNumber("not a number"))
        assertNull(whatsAppNumber("12345"))
    }

    @Test
    fun punctuation_and_prefixes_are_stripped_to_digits() {
        assertEquals("919876543210", whatsAppNumber("+91 (98765) 43210"))
        assertEquals("919876543210", whatsAppNumber("tel:+91 98765 43210"))
    }
}
