package `in`.wishkaro.app.domain

/**
 * A phone number as two fields — a country code and the rest — and back again.
 *
 * ## Why this exists
 *
 * `phone` is one free-text column on the server and one `OutlinedTextField` in the app, and a
 * user typing their sister's number into it has to decide, unprompted, whether to write
 * `9876543210`, `+91 98765 43210`, `098765 43210` or `0091 98765 43210`. All four work —
 * `whatsAppNumber()` in `AndroidMessageSender` parses every one of them and has a test per
 * case — but the field gives no sign of that, so the user is guessing at a question the app
 * has already answered.
 *
 * Showing `+91` as a thing you can see and change turns the guess into a default.
 *
 * ## What is NOT changed
 *
 * **The stored value is still one string**, and still free text. [join] produces
 * `"+91 9876543210"`, which `whatsAppNumber()` already handles — its very first test case is
 * `"+91 98765 43210"`. Numbers stored by earlier milestones, in any of the four shapes above,
 * still load, still split sensibly, and still dial. No migration, no server change, and no new
 * failure mode in the one piece of this app that is *"actually wrong in the field"*.
 *
 * ## Why a list of countries rather than a hardcoded +91
 *
 * The obvious reading of "for phone we can have +91" is to prefix the field with a fixed
 * `+91`. That would be wrong for this app specifically: `whatsAppNumber()` already supports
 * `+44 7700 900123` and is tested on it, and WishKaro is an app for remembering **family
 * birthdays** in a country whose families are spread across the Gulf, the UK, the US and
 * Singapore. Hardcoding the prefix would take a working case away.
 *
 * So +91 is the **default**, first in the list and selected when nothing says otherwise, and
 * the rest of the list is where Indian families actually are.
 */
data class PhoneParts(
    /** Always one of [PhoneNumbers.COUNTRY_CODES]' dial codes, e.g. `"+91"`. */
    val dialCode: String,
    /** Everything after it, digits only. May be empty. */
    val national: String,
)

data class CountryCode(val dialCode: String, val label: String)

object PhoneNumbers {

    /** The default, and the reason this app exists where it exists (REQUIREMENTS §1). */
    const val DEFAULT_DIAL_CODE = "+91"

    /**
     * India first, then where Indian families are.
     *
     * Deliberately short. A picker of 240 countries is a scroll; these cover the diaspora
     * this product is for, and a number from anywhere else still types fine — [split] keeps
     * an unrecognised code rather than discarding it, and the field accepts it.
     */
    val COUNTRY_CODES: List<CountryCode> = listOf(
        CountryCode("+91", "India"),
        CountryCode("+971", "UAE"),
        CountryCode("+966", "Saudi Arabia"),
        CountryCode("+968", "Oman"),
        CountryCode("+974", "Qatar"),
        CountryCode("+965", "Kuwait"),
        CountryCode("+1", "USA / Canada"),
        CountryCode("+44", "United Kingdom"),
        CountryCode("+65", "Singapore"),
        CountryCode("+60", "Malaysia"),
        CountryCode("+61", "Australia"),
        CountryCode("+64", "New Zealand"),
        CountryCode("+49", "Germany"),
        CountryCode("+977", "Nepal"),
        CountryCode("+880", "Bangladesh"),
        CountryCode("+94", "Sri Lanka"),
    )

    /**
     * Split a stored number into a code and the rest.
     *
     * The order of the cases is the whole of it, and it mirrors `whatsAppNumber()` so that the
     * two cannot disagree about what a number means:
     *
     *  - an explicit `+` or `00` names its own country, so the longest matching code wins —
     *    **longest first matters**, or `+9779876543210` (Nepal) is read as `+91` plus a wrong
     *    national part;
     *  - a bare ten digits is an Indian mobile, because India-first;
     *  - eleven digits starting `0` is the national form the Android dialler stores;
     *  - anything else is handed back under the default code rather than mangled.
     */
    fun split(stored: String?): PhoneParts {
        val raw = stored?.trim().orEmpty()
        if (raw.isEmpty()) return PhoneParts(DEFAULT_DIAL_CODE, "")

        val explicit = raw.startsWith("+") || raw.startsWith("00")
        val digits = raw.filter { it.isDigit() }
        if (digits.isEmpty()) return PhoneParts(DEFAULT_DIAL_CODE, "")

        if (explicit) {
            val withoutZeros = if (raw.startsWith("00")) digits.removePrefix("00") else digits
            val match = COUNTRY_CODES
                .map { it.dialCode.removePrefix("+") }
                .filter { withoutZeros.startsWith(it) && withoutZeros.length > it.length }
                .maxByOrNull { it.length }
            if (match != null) {
                return PhoneParts("+$match", withoutZeros.removePrefix(match))
            }
            // A country this list does not carry. Keep what the user typed rather than
            // forcing it under +91, which would silently change whose number it is.
            return PhoneParts("+$withoutZeros", "")
        }

        return when {
            digits.length == 10 -> PhoneParts(DEFAULT_DIAL_CODE, digits)
            digits.length == 11 && digits.startsWith("0") -> PhoneParts(DEFAULT_DIAL_CODE, digits.drop(1))
            else -> PhoneParts(DEFAULT_DIAL_CODE, digits)
        }
    }

    /**
     * Put them back together, or null when there is no number.
     *
     * A space between the code and the rest, because that is how a person reads a number back
     * and `whatsAppNumber()` strips it anyway. Null rather than a bare `"+91"` when the
     * national part is empty: a person with no number must store NULL, not a country code —
     * `PeopleRepository` already maps blank to null, and `"+91"` is not blank.
     */
    fun join(dialCode: String, national: String): String? {
        val digits = national.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        val code = dialCode.trim().ifEmpty { DEFAULT_DIAL_CODE }
        return "$code $digits"
    }
}
