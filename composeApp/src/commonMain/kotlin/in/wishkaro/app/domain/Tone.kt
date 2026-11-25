package `in`.wishkaro.app.domain

/**
 * Every user-facing string that depends on tone, in two separate sets.
 *
 * REQUIREMENTS.md §11 makes "no SOLEMN event can ever produce celebratory copy" a correctness
 * requirement rather than a content preference, and FR-EVT-05 asks for it to be impossible by
 * construction rather than fixed by a copy tweak.
 *
 * Two things follow, and both are deliberate:
 *
 *   1. **Separate string sets chosen before any string is built**, not one template with a
 *      conditional inside it. A conditional is a thing someone can forget; a set you have to
 *      pick is a thing the compiler makes you pick.
 *   2. **The client has its own copy.** The server owns notification text, but the app draws
 *      a person's whole screen offline with no server in reach. Deriving tone in the UI from
 *      the event type would get a remembrance birthday wrong, so `sentiment` is mirrored into
 *      SQLite and read from there.
 *
 * `ToneTest` asserts the solemn set against a banned lexicon over the whole cross-product of
 * event types. A sixth type added later fails that test until someone writes its solemn copy.
 */
sealed interface ToneStrings {
    /** Heading above the countdown on the person screen. */
    fun upcoming(eventTitle: String): String

    /** The countdown line itself, e.g. "in 12 days". */
    fun countdown(label: String): String

    /** Shown when the event is today. */
    fun today(eventTitle: String): String

    /** The age or elapsed-years line. Null means: show nothing at all. */
    fun years(count: Int?, type: EventType): String?

    /** The label on the toggle that controls whether this event sends a reminder. */
    fun reminderToggle(enabled: Boolean): String

    /** Empty-state text on a person with no events. */
    val noEvents: String
}

object CelebratoryStrings : ToneStrings {
    override fun upcoming(eventTitle: String): String = "$eventTitle coming up"
    override fun countdown(label: String): String = label
    override fun today(eventTitle: String): String = "$eventTitle is today 🎉"

    override fun years(count: Int?, type: EventType): String? {
        // FR-EVT-02: an unknown year must not become a number anywhere, and must not become
        // a vaguer sentence that implies one either. It becomes nothing.
        if (count == null) return null
        return when (type) {
            EventType.BIRTHDAY -> "Turning $count"
            EventType.WEDDING_ANNIVERSARY, EventType.ENGAGEMENT_ANNIVERSARY ->
                if (count == 1) "1 year together" else "$count years together"
            else -> if (count == 1) "1 year" else "$count years"
        }
    }

    override fun reminderToggle(enabled: Boolean): String =
        if (enabled) "Reminder on" else "Reminder off"

    override val noEvents: String = "No dates yet. Add one and we will remind you."
}

/**
 * The solemn set. No "celebrate", no "wish", no "congrats", no "happy", no emoji — not as a
 * filter applied afterwards, but because none of those words is written here.
 */
object SolemnStrings : ToneStrings {
    override fun upcoming(eventTitle: String): String = "$eventTitle approaching"
    override fun countdown(label: String): String = label
    override fun today(eventTitle: String): String = "$eventTitle is today"

    override fun years(count: Int?, type: EventType): String? {
        if (count == null) return null
        // Only a death anniversary states a count. A remembrance birthday does NOT say
        // "they would have been 40" — a sentence nobody asked this app to produce.
        if (type != EventType.DEATH_ANNIVERSARY) return null
        return if (count == 1) "1 year" else "$count years"
    }

    override fun reminderToggle(enabled: Boolean): String =
        if (enabled) "Remembrance reminder on" else "Remembrance reminder off"

    override val noEvents: String = "No dates recorded."
}

/** The one place tone is selected. Everything user-facing goes through here. */
fun toneFor(sentiment: Sentiment): ToneStrings =
    when (sentiment) {
        Sentiment.CELEBRATORY -> CelebratoryStrings
        Sentiment.SOLEMN -> SolemnStrings
    }

/** Convenience: a person's overall tone, for headers and empty states. */
fun toneFor(person: Person): ToneStrings =
    toneFor(if (person.isSolemn) Sentiment.SOLEMN else Sentiment.CELEBRATORY)
