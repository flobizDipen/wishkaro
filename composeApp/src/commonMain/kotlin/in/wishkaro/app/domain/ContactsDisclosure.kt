package `in`.wishkaro.app.domain

/**
 * The prominent disclosure shown before the `READ_CONTACTS` runtime permission
 * (FR-AUTH-06, FR-IMP-01, and §12's unticked "prominent disclosure screen before the
 * contacts permission").
 *
 * ## Why the words live here rather than in the composable
 *
 * Because they *are* the requirement. FR-AUTH-06 does not ask for a screen — it asks for a
 * screen "stating what is read, why, and that it is not uploaded in bulk", and Play's own
 * policy turns those clauses into the thing a reviewer checks. A rule that lives in prose
 * inside a `@Composable` can only be tested by rendering a screen; a rule that lives in a
 * value can be held to its clauses by a unit test, which is `MILESTONE_CHECKLIST.md` §D's
 * "a test must ask the layer that holds the rule".
 *
 * ## What M9 changed, and why it mattered more than a missing screen would have
 *
 * `docs/BUILD_PROMPT_M9.md` says M3 shipped contacts import without a prominent disclosure.
 * It did not — `ImportScreens.kt` has had one since M3, before the system dialog, on the only
 * route to it. What it said was the problem:
 *
 * > "• It all happens on this phone."
 *
 * That is true of the **reading**. It is false of what happens after you tap Import.
 * FR-IMP-01 says only the records the user explicitly selects are "persisted **or synced**",
 * and `PeopleRepository.importPeople` writes them with `pending_sync = 1` for the sync engine
 * to drain to the server. The selected contacts leave the phone. The next line — "the people
 * you do not pick are never saved, never sent anywhere" — made it worse by implication: it is
 * accurate, and by contrast it suggests the ones you *do* pick are also staying put.
 *
 * A disclosure that misdescribes the data flow is a worse Play finding than a missing one, and
 * it is also simply not true. See `docs/M9_PLAN.md` C10.
 */
object ContactsDisclosure {

    const val TITLE: String = "Read your contacts?"

    /** What is read, and why. FR-AUTH-06's first two clauses. */
    const val PURPOSE: String =
        "WishKaro reads the names, phone numbers, photos and birthday fields of the " +
            "contacts on this phone, so that it can offer you the birthdays you have " +
            "already saved instead of making you type them again."

    /**
     * The rest of the clauses, as separate statements rather than one paragraph — a reviewer
     * and a user both read a list, and neither reads a wall.
     *
     * The third bullet is the one M9 added. It is the only sentence on this screen that says
     * anything leaves the device, and it is the sentence the previous copy's "it all happens
     * on this phone" contradicted.
     */
    val POINTS: List<String> = listOf(
        "Your contacts are read on this phone, and nothing is uploaded in bulk.",
        "You choose who comes in. Nothing is saved until you tap Import.",
        "The people you do import are saved to your WishKaro account and sync to your " +
            "other devices, so your reminders work everywhere you sign in.",
        "The contacts you do not pick are not saved, not uploaded and not kept.",
        "You can say no. Everything else in WishKaro works without this.",
    )

    const val CONTINUE: String = "Continue"

    /** Declining must be as easy as accepting, and must not be a dead end (FR-AUTH-06). */
    const val DECLINE: String = "Not now"

    /** Addressable from a device sequence. M4 bought this: an untagged Compose node has no text of its own in the view tree. */
    const val TAG_SCREEN: String = "contacts_disclosure"
    const val TAG_CONTINUE: String = "contacts_disclosure_continue"
    const val TAG_DECLINE: String = "contacts_disclosure_decline"
}
