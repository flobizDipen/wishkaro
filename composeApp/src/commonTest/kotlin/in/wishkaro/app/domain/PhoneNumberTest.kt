package `in`.wishkaro.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Splitting a stored number into a country code and the rest.
 *
 * **The reason this has a test at all** is that `whatsAppNumber()` already exists, already
 * parses every shape a number has ever been typed in, and carries a comment saying it is *"the
 * half of the WhatsApp hand-off that is actually wrong in the field"*. Putting a second parser
 * in front of it is exactly the duplication this project refuses — so these cases are written
 * against the SAME shapes `WhatsAppNumberTest` covers, and the two have to agree about what a
 * number means. Where they disagree, the field would show one country and WhatsApp would open
 * another, which is the worst possible version of this feature.
 *
 * Every number here is invented. `98765 43210` is the number Indian textbooks use precisely
 * because it belongs to nobody.
 */
class PhoneNumberTest {

    @Test
    fun nothing_stored_gives_the_default_code_and_an_empty_number() {
        assertEquals(PhoneParts("+91", ""), PhoneNumbers.split(null))
        assertEquals(PhoneParts("+91", ""), PhoneNumbers.split(""))
        assertEquals(PhoneParts("+91", ""), PhoneNumbers.split("   "))
    }

    @Test
    fun a_bare_ten_digit_number_is_indian() {
        // The same rule `whatsAppNumber()` applies, for the same reason: REQUIREMENTS §1.
        assertEquals(PhoneParts("+91", "9876543210"), PhoneNumbers.split("9876543210"))
        assertEquals(PhoneParts("+91", "9876543210"), PhoneNumbers.split("98765 43210"))
        assertEquals(PhoneParts("+91", "9876543210"), PhoneNumbers.split("98765-43210"))
    }

    @Test
    fun the_national_zero_prefix_is_dropped() {
        assertEquals(PhoneParts("+91", "9876543210"), PhoneNumbers.split("09876543210"))
    }

    @Test
    fun an_explicit_country_code_is_kept() {
        assertEquals(PhoneParts("+91", "9876543210"), PhoneNumbers.split("+91 98765 43210"))
        assertEquals(PhoneParts("+91", "9876543210"), PhoneNumbers.split("+919876543210"))
        assertEquals(PhoneParts("+44", "7700900123"), PhoneNumbers.split("+44 7700 900123"))
        assertEquals(PhoneParts("+971", "501234567"), PhoneNumbers.split("+971 50 123 4567"))
        assertEquals(PhoneParts("+1", "2025550143"), PhoneNumbers.split("+1 202 555 0143"))
    }

    @Test
    fun double_zero_is_the_other_way_of_writing_plus() {
        assertEquals(PhoneParts("+91", "9876543210"), PhoneNumbers.split("00919876543210"))
        assertEquals(PhoneParts("+44", "7700900123"), PhoneNumbers.split("00447700900123"))
    }

    /**
     * The case that decides the order of the match, and the reason it is longest-first.
     *
     * `+977` is Nepal. Matched shortest-first, `+9779876543210` reads as `+91` and a national
     * part beginning `79`, which is a different country and a number that does not exist. Of
     * the codes in the list, +91/+971/+977, +1/+1 and +6x/+6xx all overlap this way.
     */
    @Test
    fun the_longest_matching_country_code_wins() {
        assertEquals(PhoneParts("+977", "9801234567"), PhoneNumbers.split("+977 9801234567"))
        assertEquals(PhoneParts("+971", "501234567"), PhoneNumbers.split("+971501234567"))
        assertEquals(PhoneParts("+91", "9876543210"), PhoneNumbers.split("+919876543210"))
        assertEquals(PhoneParts("+880", "1712345678"), PhoneNumbers.split("+8801712345678"))
    }

    @Test
    fun a_country_this_app_does_not_list_is_kept_rather_than_forced_under_91() {
        // +595 is Paraguay, which is not in COUNTRY_CODES. Reading it as +91 would silently
        // change whose number it is, which is worse than showing something the list has no
        // name for.
        val parts = PhoneNumbers.split("+595 981 123456")
        assertEquals("+595981123456", parts.dialCode)
        assertEquals("", parts.national)
    }

    @Test
    fun joining_puts_a_space_in_and_keeps_only_digits() {
        assertEquals("+91 9876543210", PhoneNumbers.join("+91", "98765 43210"))
        assertEquals("+44 7700900123", PhoneNumbers.join("+44", "7700-900123"))
    }

    /**
     * No number means NULL, not a country code.
     *
     * `PeopleRepository.updatePerson` maps a blank phone to null so that "no number" is a
     * real absence — and `"+91"` is not blank. Without this, every person edited after this
     * change would acquire a phone number consisting of a country code, the completeness
     * score would count it as filled, and `whatsAppNumber("+91")` would return `"91"` and
     * open a chat with nobody.
     */
    @Test
    fun a_code_with_no_number_is_null() {
        assertNull(PhoneNumbers.join("+91", ""))
        assertNull(PhoneNumbers.join("+91", "   "))
        assertNull(PhoneNumbers.join("+91", "not a number"))
    }

    /**
     * Round trip: anything the app stores must split and re-join to the same meaning.
     *
     * Asserted on what `whatsAppNumber()` would make of it rather than on the string, because
     * the string is allowed to be tidied — `"98765 43210"` becoming `"+91 9876543210"` is the
     * point — and what must not change is which chat opens.
     */
    @Test
    fun a_round_trip_never_changes_which_chat_would_open() {
        val stored = listOf(
            "9876543210", "98765 43210", "+91 98765 43210", "+919876543210",
            "09876543210", "00919876543210", "+44 7700 900123", "+971 50 123 4567",
        )
        for (value in stored) {
            val parts = PhoneNumbers.split(value)
            val rejoined = PhoneNumbers.join(parts.dialCode, parts.national)
            val expected = "+" + value.filter { it.isDigit() }
                .let { digits ->
                    when {
                        value.startsWith("+") -> digits
                        value.startsWith("00") -> digits.removePrefix("00")
                        digits.length == 10 -> "91$digits"
                        digits.length == 11 && digits.startsWith("0") -> "91${digits.drop(1)}"
                        else -> digits
                    }
                }
            assertEquals(
                expected,
                "+" + rejoined!!.filter { it.isDigit() },
                "round trip changed the number for \"$value\"",
            )
        }
    }
}
