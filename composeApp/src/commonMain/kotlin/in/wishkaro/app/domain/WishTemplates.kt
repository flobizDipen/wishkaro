package `in`.wishkaro.app.domain

/**
 * The messages this phone can write on its own, with no server and no model.
 *
 * **This is not a fallback bolted on at the end — it is one of two things the composer is
 * built to show, and it renders in three different situations** (docs/M5_PLAN.md §4.2):
 *
 *   1. there is no network,
 *   2. the model declined to write a draft, and
 *   3. the rate limit was hit and the user still has a message to send this morning.
 *
 * One code path, three triggers, one set of tests. The third is the subtle one: "come back in
 * forty minutes" is not an answer on the morning of somebody's birthday.
 *
 * ## Two sets, picked before anything is built
 *
 * The same construction `Tone.kt` uses, and for the same reason: a conditional is a thing
 * someone can forget; a set you have to pick is a thing the compiler makes you pick. A
 * remembrance template is NOT a birthday template with the word "happy" taken out, and
 * `WishTemplatesTest` holds the solemn set to the same banned lexicon `ToneTest` holds
 * `SolemnStrings` to.
 *
 * ## What these deliberately do not do
 *
 * No hobbies, no notes, no history, no age. A template that tried to be personal from six
 * stored strings produces the uncanny thing — "Happy birthday! I hope you enjoy your
 * long-distance running!" — which is worse than plainly generic, and it would also quietly
 * put a minor's profile into a message (REQUIREMENTS §12). The only thing that varies is
 * the name, and the relation where it reads naturally.
 *
 * They are marked on screen as written on the phone, so the user knows the good version is
 * one tap away, and they are recorded as [WishSource.TEMPLATE] so next year's no-repeat rule
 * can tell them apart from something a model wrote.
 */

/**
 * A handful per tone rather than one, so that Regenerate does something offline too.
 *
 * `index` rotates through them. Not random: a random pick can repeat immediately, and
 * "Regenerate gave me the same thing" reads as broken rather than as unlucky.
 */
private val CELEBRATORY_TEMPLATES: Map<WishTone, List<(String) -> String>> = mapOf(
    WishTone.WARM to listOf(
        { name -> "Happy birthday, $name. Hope this year treats you kindly — and that we see each other properly soon." },
        { name -> "Many happy returns, $name. Thinking of you today and wishing you a really good year ahead." },
        { name -> "Happy birthday, $name. Wishing you a quiet, happy day and a year that goes your way." },
    ),
    WishTone.FUNNY to listOf(
        { name -> "Happy birthday, $name. Another year, same you, which is honestly the best part." },
        { name -> "Happy birthday, $name. I was going to write something clever, but you'd only hold it against me next year." },
        { name -> "Happy birthday, $name. Eat something you shouldn't. I won't tell." },
    ),
    WishTone.RESPECTFUL to listOf(
        { name -> "Wishing you a very happy birthday, $name. May the year ahead bring you health and happiness." },
        { name -> "Many happy returns of the day, $name. Wishing you every good thing in the year to come." },
        { name -> "Happy birthday, $name. Wishing you and your family a wonderful year ahead." },
    ),
    WishTone.SHORT to listOf(
        { name -> "Happy birthday, $name!" },
        { name -> "Many happy returns, $name." },
        { name -> "Happy birthday, $name — have a good one." },
    ),
    WishTone.POETIC to listOf(
        { name -> "Another year, $name,\nand the same wish as always —\nthat it is a gentle one." },
        { name -> "May this year find you well, $name,\nand leave you better than it found you." },
        { name -> "Happy birthday, $name.\nHere's to a year worth remembering." },
    ),
)

/**
 * The solemn set.
 *
 * No "celebrate", no "wish", no "congrats", no "happy", no emoji — not as a filter applied
 * afterwards, but because none of those words is written here. FR-EVT-04 makes that a rule
 * and REQUIREMENTS §11 files it under correctness rather than content preference.
 *
 * They are also deliberately short and plain. The failure mode for remembrance copy is
 * ornament: a phone-written line that reaches for profundity about somebody else's dead
 * relative is the thing that gets an app uninstalled and screenshotted. These say one true
 * thing and stop.
 */
private val SOLEMN_TEMPLATES: Map<WishTone, List<(String) -> String>> = mapOf(
    WishTone.RESPECTFUL to listOf(
        { name -> "Thinking of $name today, and of all of you." },
        { name -> "Remembering $name today. Holding you in my thoughts." },
        { name -> "Today I am thinking of $name, and of the family." },
    ),
    WishTone.SHORT to listOf(
        { name -> "Thinking of $name today." },
        { name -> "Remembering $name." },
        { name -> "$name is in my thoughts today." },
    ),
    WishTone.HEARTFELT to listOf(
        { name -> "Thinking of $name today. Some people leave a space that does not close, and that is its own kind of love." },
        { name -> "Remembering $name today, and how much they meant to you. I am here if you want company." },
        { name -> "$name is on my mind today. I hope you are being gentle with yourself." },
    ),
)

/**
 * One locally written message.
 *
 * [index] rotates, so Regenerate works with the radio off. [tone] falling outside the set for
 * this sentiment returns the DEFAULT tone's template rather than nothing: a composer that
 * opened empty because a saved tone no longer applied would be a blank screen that leads
 * nowhere, which is exactly what FR-VIEW-05 forbids.
 */
fun localTemplate(
    sentiment: Sentiment,
    tone: WishTone,
    displayName: String,
    index: Int = 0,
): String {
    val set = when (sentiment) {
        Sentiment.CELEBRATORY -> CELEBRATORY_TEMPLATES
        Sentiment.SOLEMN -> SOLEMN_TEMPLATES
    }
    val options = set[tone] ?: set.getValue(defaultToneFor(sentiment))
    // The name alone, not the full display string — "Happy birthday, Anika Pillai" reads
    // like a letter from a bank. First token, falling back to the whole thing.
    val name = displayName.trim().substringBefore(' ').ifBlank { displayName.trim() }
    return options[((index % options.size) + options.size) % options.size](name)
}

/** Every template this build can produce, for the lexicon test to sweep. */
internal fun allTemplatesFor(sentiment: Sentiment): List<String> {
    val set = when (sentiment) {
        Sentiment.CELEBRATORY -> CELEBRATORY_TEMPLATES
        Sentiment.SOLEMN -> SOLEMN_TEMPLATES
    }
    return set.flatMap { (_, options) -> options.map { it("Anika") } }
}

/** The tones each set actually has templates for. Must equal [tonesFor]. */
internal fun templatedTones(sentiment: Sentiment): Set<WishTone> =
    when (sentiment) {
        Sentiment.CELEBRATORY -> CELEBRATORY_TEMPLATES.keys
        Sentiment.SOLEMN -> SOLEMN_TEMPLATES.keys
    }
