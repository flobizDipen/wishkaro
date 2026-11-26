package `in`.wishkaro.app.domain

/**
 * What the user actually sent, per person per occasion (FR-HIS-01, FR-WSH-07, FR-PPL-07).
 *
 * M5's floor. The composer is conditioned on this (FR-WSH-02's "must not repeat"), the person
 * screen shows it, and the enrichment nudge hangs off it — so it exists before any of them do.
 *
 * Every enum here is held as a string on the wire and in SQLite, with an `UNKNOWN`-shaped
 * fallback, so a value from a newer server degrades instead of crashing the parse. That rule
 * is [EventType]'s and it applies for the same reason: this app has to keep working on a
 * handset nobody has updated.
 */

/**
 * FR-WSH-03 and FR-WSH-09. Six values, because the two requirements name overlapping but
 * different sets and `HEARTFELT` exists only on the solemn side.
 *
 * Which of these are OFFERED is not a property of the tone — see [tonesFor].
 */
enum class WishTone(val wire: String, val label: String) {
    WARM("WARM", "Warm"),
    FUNNY("FUNNY", "Funny"),
    RESPECTFUL("RESPECTFUL", "Respectful"),
    SHORT("SHORT", "Short"),
    POETIC("POETIC", "Poetic"),
    HEARTFELT("HEARTFELT", "Heartfelt"),
    ;

    companion object {
        fun from(wire: String): WishTone? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * FR-WSH-03. The language the MESSAGE is written in.
 *
 * Not the app's own language: Hindi UI localisation is v1.1 and a different thing. A user
 * reading an English interface sending their grandmother a wish in Hindi is the common case,
 * not the edge one.
 */
enum class WishLanguage(val wire: String, val label: String) {
    ENGLISH("ENGLISH", "English"),
    HINDI("HINDI", "हिन्दी"),
    HINGLISH("HINGLISH", "Hinglish"),
    ;

    companion object {
        fun from(wire: String): WishLanguage? = entries.firstOrNull { it.wire == wire }
    }
}

/** Which door the message went out through (FR-WSH-05). Recorded, never acted on. */
enum class WishChannel(val wire: String, val label: String) {
    WHATSAPP("WHATSAPP", "WhatsApp"),
    SMS("SMS", "SMS"),
    SHARE("SHARE", "Shared"),
    COPY("COPY", "Copied"),
    ;

    companion object {
        fun from(wire: String): WishChannel? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * Who wrote the words.
 *
 * `TEMPLATE` is the offline and refused-draft path (docs/M5_PLAN.md §4), and it is worth
 * recording honestly: next year's "do not repeat this" reads differently for a sentence the
 * model wrote than for one of six the phone can produce.
 */
enum class WishSource(val wire: String) {
    AI("AI"),
    TEMPLATE("TEMPLATE"),
    USER("USER"),
    ;

    companion object {
        fun from(wire: String): WishSource = entries.firstOrNull { it.wire == wire } ?: USER
    }
}

/**
 * One recorded wish.
 *
 * [sentiment] is MIRRORED from the server, never derived here — the same rule [PersonEvent]
 * follows, and for the same reason: a remembrance birthday is `type = BIRTHDAY` with
 * `sentiment = SOLEMN`, and a screen that inferred tone from the type would word a dead
 * person's entry as a celebration.
 */
data class Wish(
    val id: String,
    val personId: String,
    val type: EventType,
    val occurrenceYear: Int,
    val sentiment: Sentiment,
    /** A soft reference. The event may have been deleted since; the wish is still a fact. */
    val eventId: String?,
    val messageText: String,
    val tone: WishTone?,
    val language: WishLanguage?,
    val channel: WishChannel?,
    val source: WishSource,
    val sentAt: String,
) {
    val isSolemn: Boolean get() = sentiment == Sentiment.SOLEMN
}

// ---------------------------------------------------------------------------------------
// Tone rules (FR-WSH-03, FR-WSH-09)
// ---------------------------------------------------------------------------------------

/**
 * The Kotlin half of a rule that exists twice.
 *
 * The server VALIDATES which tones are legal; this side HIDES the chips that are not offered.
 * Neither half can be dropped — a client that only hid them would let a tampered request
 * through, and a server that only validated would show a Funny button that 400s on a death
 * anniversary.
 *
 * `MILESTONE_CHECKLIST.md` §C: a rule implemented twice in two languages is held to one
 * shared table of cases. That table is `docs/fixtures/wish-tones.json`, read by
 * `WishToneRulesTest.kt` here and `tone-rules.spec.ts` on the server. If the two drift, one
 * of those suites goes red.
 *
 * Separate SETS, picked before anything is built — not one list with a conditional inside it.
 * `Tone.kt` makes that argument for the app's static copy and it holds here: a conditional is
 * a thing someone can forget; a set you have to pick is a thing the compiler makes you pick.
 */
private val CELEBRATORY_TONES: List<WishTone> = listOf(
    WishTone.WARM,
    WishTone.FUNNY,
    WishTone.RESPECTFUL,
    WishTone.SHORT,
    WishTone.POETIC,
)

/** FR-WSH-09. No FUNNY, no POETIC. REQUIREMENTS.md §11 files this under correctness. */
private val SOLEMN_TONES: List<WishTone> = listOf(
    WishTone.RESPECTFUL,
    WishTone.SHORT,
    WishTone.HEARTFELT,
)

/** The one place a tone list is produced. Every chip row and every validation reads this. */
fun tonesFor(sentiment: Sentiment): List<WishTone> =
    when (sentiment) {
        Sentiment.CELEBRATORY -> CELEBRATORY_TONES
        Sentiment.SOLEMN -> SOLEMN_TONES
    }

/**
 * The tone the composer opens on.
 *
 * SOLEMN defaults to RESPECTFUL rather than to the head of some shared list. A default that
 * leaked across the two sets would be M1's failure again: the wrong set chosen because nobody
 * chose.
 */
fun defaultToneFor(sentiment: Sentiment): WishTone =
    when (sentiment) {
        Sentiment.CELEBRATORY -> WishTone.WARM
        Sentiment.SOLEMN -> WishTone.RESPECTFUL
    }

fun isToneAllowed(sentiment: Sentiment, tone: WishTone): Boolean = tone in tonesFor(sentiment)

/** Convenience: the tones offered for a person, via their one mirrored sentiment flag. */
fun tonesFor(person: Person): List<WishTone> =
    tonesFor(if (person.isSolemn) Sentiment.SOLEMN else Sentiment.CELEBRATORY)
