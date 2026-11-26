package `in`.wishkaro.app.domain

import `in`.wishkaro.app.platform.DeviceContact
import kotlinx.datetime.LocalDate

/**
 * One contact as the review screen holds it (FR-IMP-02).
 *
 * [manualDate] is a date the user typed on the row for a contact the device had none for.
 * It overrides [DeviceContact.rawBirthday] rather than replacing it, so turning the
 * selection off and on again does not lose what they entered.
 */
data class ContactChoice(
    val contact: DeviceContact,
    val relation: String? = null,
    val manualDate: EventDate? = null,
    val selected: Boolean = false,
) {
    /** True when this row has a date from somewhere — the device's, or the user's. */
    val hasDate: Boolean get() = manualDate != null || parsedDeviceDate != null

    /**
     * The device's birthday field, read through the ONE date rule.
     *
     * Android stores whatever the source wrote: `1990-01-04`, `--01-04` with no year, or a
     * sentinel year meaning the same thing. Interpreting it here instead of in
     * [ImportDates] would be a second place where dates are decided.
     */
    val parsedDeviceDate: EventDate? by lazy {
        val raw = contact.rawBirthday ?: return@lazy null
        val yearLess = raw.startsWith("--")
        // `--MM-DD` is the vCard form for "no year" (RFC 6350), and it is MONTH FIRST —
        // always, by the spec, regardless of anybody's locale. Stripping the dashes and
        // handing `01-04` to the parser under the default setting would make it the 1st of
        // April: two numbers under 13 is exactly the shape the dd/MM toggle arbitrates, and
        // there is nothing ambiguous about this one. The device said January the 4th.
        val order = if (yearLess) DateOrder.MONTH_FIRST else DateOrder.DAY_FIRST
        val cleaned = raw.removePrefix("--")
        (ImportDates.parse(cleaned, order, FIXED_TODAY) as? DateParse.Parsed)?.date
    }

    val effectiveDate: EventDate? get() = manualDate ?: parsedDeviceDate

    private companion object {
        /**
         * The pivot only matters for two-digit years, and a contacts provider does not write
         * them — it writes ISO or nothing. A fixed date here keeps [parsedDeviceDate] a pure
         * `by lazy` rather than threading a clock through the whole review screen for a case
         * that cannot arise.
         */
        val FIXED_TODAY = LocalDate(2000, 1, 1)
    }
}

/**
 * Contacts import, expressed as the SAME pipeline a file goes through.
 *
 * The selected contacts become rows of strings and run through [planImport] with a fixed
 * mapping. That is not a shortcut: it means dedup, idempotency, the failure reasons and the
 * result report are literally the same code for both sources. Two import paths with two sets
 * of rules is how "the same import run twice" ends up meaning two different things depending
 * on where the data came from.
 */
object ContactsImport {

    /** The columns the synthetic rows below are built with. */
    val MAPPING = ColumnMapping(name = 0, date = 1, relation = 2, phone = 3, notes = 4)

    /**
     * The review list: contacts WITH a birthday first and pre-selected, the rest after and
     * unselected (FR-IMP-02).
     *
     * Pre-selection is not bulk import. Nothing is written until the user confirms, and the
     * dateless half starts unselected precisely so that confirming does not sweep in 500
     * people the user never looked at. Note which way round that is: the ones with a date
     * are pre-ticked because the user has already told their phone those dates matter.
     */
    fun review(contacts: List<DeviceContact>): List<ContactChoice> =
        contacts.map { ContactChoice(contact = it, selected = false) }
            .map { if (it.hasDate) it.copy(selected = true) else it }
            .sortedWith(
                // Dated first, then by name, so the ones worth looking at are at the top of
                // a list that is 500 long on a real phone.
                compareByDescending<ContactChoice> { it.hasDate }
                    .thenBy { it.contact.displayName.lowercase() },
            )

    /**
     * The selected contacts as rows, ready for [planImport].
     *
     * A selected row with no date is not included: it cannot become a person (FR-PPL-01
     * needs a date, and a person with no event is invisible to every screen this app has).
     * The review screen stops that happening earlier, by refusing to tick a dateless row
     * until a date is entered — this is the backstop, not the rule.
     */
    fun rows(choices: List<ContactChoice>): List<List<String>> =
        choices.filter { it.selected && it.effectiveDate != null }
            .map { choice ->
                val date = choice.effectiveDate!!
                listOf(
                    choice.contact.displayName,
                    unambiguousDate(date),
                    choice.relation.orEmpty(),
                    choice.contact.phone.orEmpty(),
                    "",
                )
            }

    /** How many selected rows still have no relation — what the Import button is waiting on. */
    fun missingRelations(choices: List<ContactChoice>): Int =
        choices.count { it.selected && it.relation.isNullOrBlank() }

    /**
     * `22 Sep 1988`, or `22 Sep` when the year is unknown.
     *
     * A NAMED month, not ISO. These rows are about to be re-read by [ImportDates], and a
     * year-less ISO date is `01-04` — two numbers under 13, which is precisely the shape the
     * dd/MM toggle exists to arbitrate. The date would then flip with a setting that has
     * nothing to do with contacts, where it was never ambiguous to begin with: the device
     * said January the 4th. A named month cannot be read the other way round.
     *
     * A year-unknown date goes out as day-and-month only; inventing a placeholder year would
     * be inventing information, and `event.year` is nullable so that it need not be.
     */
    private fun unambiguousDate(date: EventDate): String {
        val dayMonth = BirthdayMath.formatDayMonth(date.day, date.month)
        return date.year?.let { "$dayMonth $it" } ?: dayMonth
    }
}
