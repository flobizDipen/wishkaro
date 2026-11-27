package `in`.wishkaro.app.domain

/**
 * The gender options the edit screen offers — **suggestions, not a schema.**
 *
 * ## The decision this file is careful not to overturn
 *
 * `schema.prisma` says, on the column itself:
 *
 * > *Free text, deliberately not an enum: an enum of genders is a product decision this app
 * > has no reason to make, and gift logic in M8 reads traits rather than this field.*
 *
 * That is a recorded decision and it is still right, so **nothing here changes what is
 * stored.** The column is a string, the DTO is a string, the client holds a `String?`, and
 * [OTHER] exists so that anybody whose answer is not on the list can type it. What changed is
 * only the *input*: a free-text box asks the user to invent an answer and to guess its
 * spelling, and three people typing "female", "Female" and "F" produce three values for one
 * thing.
 *
 * ## What gender is actually for, which is less than it looks
 *
 * Checked rather than assumed, because it decides how much this deserves: **nothing in this
 * product reads the VALUE.** It is not in any wish template, any poster prompt, or any
 * model call. The only thing that touches it is `derived.ts`, where filling it in is worth
 * **5 points** of the completeness score:
 *
 * ```ts
 * gender: 5,
 * ```
 *
 * So this is a data-entry nicety on a field that feeds a progress bar. Worth doing because
 * the user is asked for it; not worth a migration, an enum, or a debate about the list.
 *
 * ## The list
 *
 * Short, and ordered so that the two answers most people give are first. "Prefer not to say"
 * is on it deliberately: a profile field with no way to decline is a field people put
 * something false into, and a false value is worse than an empty one on a field that earns 5
 * points and feeds nothing.
 */
object Genders {

    const val OTHER = "Other"

    /** Offered in the dropdown, in this order. */
    val SUGGESTED: List<String> = listOf(
        "Female",
        "Male",
        "Non-binary",
        "Prefer not to say",
        OTHER,
    )

    /**
     * Which dropdown entry a stored value corresponds to.
     *
     * Anything not on the list — including values typed before this existed, and anything
     * entered through [OTHER] — comes back as [OTHER], so the free-text box opens with it
     * still in it rather than silently dropping what somebody wrote.
     */
    fun optionFor(stored: String?): String? = when {
        stored.isNullOrBlank() -> null
        SUGGESTED.any { it.equals(stored, ignoreCase = true) && it != OTHER } ->
            SUGGESTED.first { it.equals(stored, ignoreCase = true) }
        else -> OTHER
    }

    /** True when the stored value needs the free-text box shown beside the dropdown. */
    fun isCustom(stored: String?): Boolean =
        !stored.isNullOrBlank() && optionFor(stored) == OTHER
}
