package `in`.wishkaro.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The messages the phone writes on its own.
 *
 * These ship. They render with the radio off, when the model declines, and when the rate
 * limit bites — so unlike a prompt, they are copy the recipient actually reads, and the same
 * lexicon guarantee `ToneTest` puts on `SolemnStrings` applies to them word for word.
 *
 * That distinction cost a test earlier in this milestone: the *server's* solemn system prompt
 * cannot be held to a banned lexicon, because a prompt has to name what it forbids
 * ("never congratulate"). These are not prompts. They are the words themselves, so the
 * lexicon check is exactly right here and is the one place in M5 it can be made at all
 * without a live model.
 */
class WishTemplatesTest {

    // The same list ToneTest uses. Two copies of a lexicon would drift, but they are testing
    // two different sets of strings that must both satisfy it, and importing a private val
    // across test classes is worse than restating eight words with this comment attached.
    private val bannedInSolemn = listOf(
        "celebrat", "congrat", "happy", "wish", "cheers", "party", "together", "turning",
    )

    @Test
    fun the_solemn_templates_are_actually_exercised() {
        // Guards against the sweep below silently producing nothing and passing on an empty
        // list — the failure mode every enumerating test has.
        assertTrue(allTemplatesFor(Sentiment.SOLEMN).size >= 6)
        assertTrue(allTemplatesFor(Sentiment.CELEBRATORY).size >= 10)
    }

    @Test
    fun no_solemn_template_uses_a_celebratory_word() {
        val offenders = allTemplatesFor(Sentiment.SOLEMN).filter { text ->
            bannedInSolemn.any { text.lowercase().contains(it) }
        }
        assertEquals(emptyList(), offenders, "a remembrance template used a celebratory word")
    }

    @Test
    fun no_solemn_template_uses_an_emoji_or_an_exclamation_mark() {
        // FR-EVT-04 names emoji. The exclamation mark is this file's own rule: it is the
        // punctuation of good news and there is none here.
        val offenders = allTemplatesFor(Sentiment.SOLEMN).filter { text ->
            text.any { it.isHighSurrogate() } || text.contains('!')
        }
        assertEquals(emptyList(), offenders, "a remembrance template was decorated")
    }

    @Test
    fun every_offered_tone_has_templates() {
        // The composer offers a tone list and this file answers it. If the two disagree,
        // `localTemplate` silently substitutes the default tone — so the user picks Heartfelt
        // and gets Respectful, with nothing on screen to say so.
        for (sentiment in Sentiment.entries) {
            assertEquals(
                tonesFor(sentiment).toSet(),
                templatedTones(sentiment),
                "the offered tones and the templated tones disagree for $sentiment",
            )
        }
    }

    @Test
    fun regenerate_works_offline() {
        // Rotation, not randomness: a random pick can repeat immediately, and "Regenerate
        // gave me the same thing" reads as broken rather than as unlucky.
        val produced = (0..2).map { localTemplate(Sentiment.CELEBRATORY, WishTone.WARM, "Anika", it) }
        assertEquals(3, produced.toSet().size, "three taps of Regenerate gave fewer than three messages")
    }

    @Test
    fun rotation_wraps_rather_than_crashing() {
        // The index is a tap counter and nothing bounds it. A negative one should not happen
        // and must not throw if it does.
        assertTrue(localTemplate(Sentiment.SOLEMN, WishTone.SHORT, "Rohan", 97).isNotBlank())
        assertTrue(localTemplate(Sentiment.SOLEMN, WishTone.SHORT, "Rohan", -1).isNotBlank())
    }

    @Test
    fun a_tone_outside_the_set_falls_back_rather_than_returning_nothing() {
        // A saved tone that no longer applies — the user picked Funny on a living person,
        // then that person was marked deceased. An empty composer here would be the blank
        // screen leading nowhere that FR-VIEW-05 forbids.
        val text = localTemplate(Sentiment.SOLEMN, WishTone.FUNNY, "Rohan")
        assertTrue(text.isNotBlank())
        assertTrue(bannedInSolemn.none { text.lowercase().contains(it) }, "fell back into the wrong set")
    }

    @Test
    fun the_message_uses_a_first_name_not_the_whole_record() {
        // "Happy birthday, Anika Pillai" reads like a letter from a bank.
        val text = localTemplate(Sentiment.CELEBRATORY, WishTone.WARM, "Anika Pillai")
        assertTrue(text.contains("Anika"))
        assertTrue(!text.contains("Pillai"))
    }

    @Test
    fun a_one_word_name_still_works() {
        assertTrue(localTemplate(Sentiment.CELEBRATORY, WishTone.SHORT, "Anika").contains("Anika"))
        // And a blank display name does not produce "Happy birthday, ." — it produces
        // something, which is all the composer needs to put in an editable box.
        assertTrue(localTemplate(Sentiment.CELEBRATORY, WishTone.SHORT, "   ").isNotBlank())
    }

    @Test
    fun no_template_mentions_a_hobby_a_note_or_an_age() {
        // Templates take a name and nothing else, on purpose. A template that tried to be
        // personal from stored strings produces the uncanny thing, and it would also put a
        // minor's profile into a message — REQUIREMENTS §12 against FR-WSH-02.
        val all = Sentiment.entries.flatMap { allTemplatesFor(it) }
        for (text in all) {
            assertTrue(text.none { it.isDigit() }, "a template contained a number: $text")
        }
    }
}
