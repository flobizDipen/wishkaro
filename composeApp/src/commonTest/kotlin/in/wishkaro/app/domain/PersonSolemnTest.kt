package `in`.wishkaro.app.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `Person.isSolemn` during the window where the server has not answered yet.
 *
 * THE BUG, found on a real device during M5's verification and reachable since M1:
 *
 * `is_deceased` is derived SERVER-SIDE. Between creating a death anniversary on the phone
 * and the next sync pull, the local person row says false — so the app treated a dead person
 * as living. The composer offered Funny and Poetic and opened on "Happy birthday, Rohan",
 * and the person screen headed their history "Wishes sent".
 *
 * REQUIREMENTS.md §11 files tone safety under correctness, not content preference, and
 * FR-EVT-05 asks for it to be impossible by construction. This suite is the construction.
 */
class PersonSolemnTest {

    private fun person(
        deceased: Boolean,
        events: List<PersonEvent>,
    ) = Person(
        id = "p1",
        displayName = "Rohan Deshmukh",
        relationToOwner = "Grandparent",
        isSelf = false,
        gender = null,
        phone = null,
        email = null,
        notes = null,
        isDeceased = deceased,
        isMinor = false,
        completenessScore = 0,
        completenessSuggestions = emptyList(),
        deletedAt = null,
        events = events,
        traits = emptyList(),
    )

    private fun event(
        type: EventType,
        sentiment: Sentiment,
        id: String = "e1",
    ) = PersonEvent(
        id = id,
        personId = "p1",
        type = type,
        sentiment = sentiment,
        label = null,
        isMuted = false,
        date = EventDate(28, 11, 2019),
    )

    @Test
    fun a_death_anniversary_is_solemn_before_the_server_has_derived_anything() {
        // The exact local shape after adding one on the phone: the person flag has not
        // caught up, and the event's own mirrored sentiment has.
        val justCreated = person(
            deceased = false,
            events = listOf(event(EventType.DEATH_ANNIVERSARY, Sentiment.SOLEMN)),
        )
        assertTrue(justCreated.isSolemn)
    }

    @Test
    fun the_event_TYPE_alone_is_enough() {
        // Belt and braces for the case where even the event's sentiment is stale — an older
        // row, or a client that wrote the default. A death anniversary is solemn by its type,
        // which is the first line of the server's own sentimentFor().
        val stale = person(
            deceased = false,
            events = listOf(event(EventType.DEATH_ANNIVERSARY, Sentiment.CELEBRATORY)),
        )
        assertTrue(stale.isSolemn)
    }

    @Test
    fun a_remembrance_birthday_stays_solemn_when_its_event_row_is_stale() {
        // The other direction. The type is BIRTHDAY and the person IS flagged; a stale
        // event sentiment must not be able to un-solemn them.
        val deceased = person(
            deceased = true,
            events = listOf(event(EventType.BIRTHDAY, Sentiment.CELEBRATORY)),
        )
        assertTrue(deceased.isSolemn)
    }

    @Test
    fun one_solemn_event_makes_the_whole_person_solemn() {
        // Somebody with a birthday AND a death anniversary. The server makes every event
        // they own SOLEMN; before it answers, one is enough.
        val mixed = person(
            deceased = false,
            events = listOf(
                event(EventType.BIRTHDAY, Sentiment.CELEBRATORY, id = "e1"),
                event(EventType.DEATH_ANNIVERSARY, Sentiment.SOLEMN, id = "e2"),
            ),
        )
        assertTrue(mixed.isSolemn)
    }

    @Test
    fun an_ordinary_living_person_is_not_solemn() {
        // The rule only ever pushes towards solemn, so this is the assertion that stops it
        // pushing everybody there — which would be a different bug, quieter and just as bad.
        val living = person(
            deceased = false,
            events = listOf(event(EventType.BIRTHDAY, Sentiment.CELEBRATORY)),
        )
        assertFalse(living.isSolemn)
        assertFalse(person(deceased = false, events = emptyList()).isSolemn)
    }

    @Test
    fun the_offered_tones_follow_it() {
        val justCreated = person(
            deceased = false,
            events = listOf(event(EventType.DEATH_ANNIVERSARY, Sentiment.SOLEMN)),
        )
        assertFalse(WishTone.FUNNY in tonesFor(justCreated))
        assertFalse(WishTone.POETIC in tonesFor(justCreated))
    }

    @Test
    fun the_string_set_follows_it() {
        val justCreated = person(
            deceased = false,
            events = listOf(event(EventType.DEATH_ANNIVERSARY, Sentiment.SOLEMN)),
        )
        // toneFor(person) is what the person screen's every heading goes through.
        assertTrue(toneFor(justCreated) === SolemnStrings)
    }
}
