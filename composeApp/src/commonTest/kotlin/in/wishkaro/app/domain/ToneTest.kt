package `in`.wishkaro.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The client half of the tone guarantee. The server has its own suite over notification copy;
 * this one covers the strings the app draws on screen, which it draws offline with no server
 * in reach.
 *
 * It enumerates rather than samples, so adding a sixth event type fails here until someone
 * has written its solemn copy.
 */
class ToneTest {

    private val bannedInSolemn = listOf(
        "celebrat", "congrat", "happy", "wish", "cheers", "party", "together", "turning",
    )

    private fun allSolemnStrings(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (type in EventType.entries) {
            val title = if (type == EventType.CUSTOM) "Shop anniversary" else type.label
            out += "upcoming/$type" to SolemnStrings.upcoming(title)
            out += "today/$type" to SolemnStrings.today(title)
            out += "countdown/$type" to SolemnStrings.countdown("in 12 days")
            for (years in listOf(null, 0, 1, 12, 25, 50)) {
                SolemnStrings.years(years, type)?.let { out += "years/$type/$years" to it }
            }
        }
        out += "toggleOn" to SolemnStrings.reminderToggle(true)
        out += "toggleOff" to SolemnStrings.reminderToggle(false)
        out += "noEvents" to SolemnStrings.noEvents
        return out
    }

    @Test
    fun solemn_copy_never_uses_a_celebratory_word() {
        val offenders = allSolemnStrings().filter { (_, text) ->
            bannedInSolemn.any { text.lowercase().contains(it) }
        }
        assertEquals(emptyList(), offenders, "solemn copy contained a celebratory word")
    }

    @Test
    fun solemn_copy_never_uses_an_emoji() {
        // Emoji live well above the BMP; any surrogate pair in this copy is one.
        val offenders = allSolemnStrings().filter { (_, text) ->
            text.any { it.isHighSurrogate() } || text.any { it in "🎉🎂💍✨🥳🎁" }
        }
        assertEquals(emptyList(), offenders, "solemn copy contained an emoji")
    }

    @Test
    fun the_solemn_set_is_actually_exercised() {
        // Guards against the generator above silently producing nothing and the two tests
        // above passing on an empty list.
        assertTrue(allSolemnStrings().size > 20)
    }

    @Test
    fun a_remembrance_birthday_does_not_state_an_age() {
        // "They would have been 40" is a sentence this product does not produce. Only a
        // death anniversary states a count.
        assertNull(SolemnStrings.years(40, EventType.BIRTHDAY))
        assertEquals("12 years", SolemnStrings.years(12, EventType.DEATH_ANNIVERSARY))
    }

    @Test
    fun an_unknown_year_produces_no_number_in_either_tone() {
        for (type in EventType.entries) {
            assertNull(CelebratoryStrings.years(null, type), "celebratory $type")
            assertNull(SolemnStrings.years(null, type), "solemn $type")
        }
    }

    @Test
    fun tone_is_chosen_by_sentiment_not_by_event_type() {
        // The case a type-driven UI gets wrong: a BIRTHDAY whose sentiment is SOLEMN.
        assertEquals(SolemnStrings, toneFor(Sentiment.SOLEMN))
        assertEquals(CelebratoryStrings, toneFor(Sentiment.CELEBRATORY))

        val remembrance = PersonEvent(
            id = "e1",
            personId = "p1",
            type = EventType.BIRTHDAY,
            sentiment = Sentiment.SOLEMN,
            label = null,
            isMuted = false,
            date = EventDate(22, 9, 1988),
        )
        val tone = toneFor(remembrance.sentiment)
        assertEquals(SolemnStrings, tone)
        assertTrue(!tone.today(remembrance.title).contains("🎉"))
    }

    @Test
    fun an_unrecognised_sentiment_falls_back_to_solemn() {
        // Saying too little is recoverable. Saying "Happy Birthday!" to a grieving user is not.
        assertEquals(Sentiment.SOLEMN, Sentiment.from(null))
        assertEquals(Sentiment.SOLEMN, Sentiment.from("SOMETHING_NEW"))
        assertEquals(Sentiment.CELEBRATORY, Sentiment.from("CELEBRATORY"))
    }

    @Test
    fun a_deceased_person_is_solemn_everywhere() {
        val person = Person(
            id = "p1",
            displayName = "Ravi",
            relationToOwner = "Uncle",
            isSelf = false,
            gender = null,
            phone = null,
            email = null,
            notes = null,
            isDeceased = true,
            isMinor = false,
            completenessScore = 0,
            deletedAt = null,
            events = emptyList(),
            traits = emptyList(),
        )
        assertTrue(person.isSolemn)
        assertEquals(SolemnStrings, toneFor(person))
        assertNotNull(toneFor(person).noEvents)
    }
}
