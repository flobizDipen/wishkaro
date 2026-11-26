package `in`.wishkaro.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Where a notification's **Send wish** / **Send message** action lands (FR-WSH-01).
 *
 * M2 pointed both at the person screen because the composer did not exist. The event id is
 * recovered from the DEDUPE KEY rather than added to the push payload — `dedupeKeyFor` is
 * `${eventId}:${occurrenceYear}:${offset}` and both sides already compute it, so taking it
 * from there means no new wire field and no second source of truth for which event a
 * reminder is about.
 *
 * The parsing is what goes wrong in the field; the Intent around it is three lines. So this
 * is a pure test of the parsing.
 */
class ComposerDeepLinkTest {

    private fun reminder(
        dedupeKey: String = "e1:2026:D0",
        personId: String? = "p1",
        type: String = "EVENT",
        sentiment: String? = "CELEBRATORY",
    ) = DeliverableReminder(
        dedupeKey = dedupeKey,
        title = "t",
        body = "b",
        offset = "D0",
        type = type,
        personId = personId,
        personName = "Anika Pillai",
        sentiment = sentiment,
        deepLink = "wishkaro://person/$personId",
    )

    @Test
    fun an_ordinary_reminder_links_to_the_composer_with_its_event() {
        assertEquals("wishkaro://wish/p1?event=e1", composerDeepLink(reminder()))
    }

    @Test
    fun a_solemn_reminder_links_the_same_way() {
        // The LABEL differs (FR-REM-11 says "Send message") and the destination does not.
        // Two different screens for the same action would be the regression, not the fix.
        assertEquals(
            "wishkaro://wish/p1?event=e1",
            composerDeepLink(reminder(sentiment = "SOLEMN")),
        )
    }

    @Test
    fun a_key_that_is_not_event_shaped_degrades_to_the_person() {
        // A snooze row, a self-test, or anything a newer server invents. Better to open the
        // composer on "their next occasion" than to build a link to an event id that is
        // really a timestamp.
        assertEquals("wishkaro://wish/p1", composerDeepLink(reminder(dedupeKey = "test:u1:12345")))
        assertEquals("wishkaro://wish/p1", composerDeepLink(reminder(dedupeKey = "nonsense")))
        assertEquals("wishkaro://wish/p1", composerDeepLink(reminder(dedupeKey = "e1:notayear:D0")))
        assertEquals("wishkaro://wish/p1", composerDeepLink(reminder(dedupeKey = ":2026:D0")))
    }

    @Test
    fun a_reminder_about_nobody_gets_no_link() {
        // A self-test and a digest offer no actions at all, and a reminder with no person
        // has nothing to compose about. Returning a link would put a button on a
        // notification that cannot do anything.
        assertNull(composerDeepLink(reminder(personId = null)))
        assertNull(composerDeepLink(reminder(type = "TEST")))
        assertNull(composerDeepLink(reminder(type = "DIGEST")))
    }

    @Test
    fun the_event_id_survives_every_rung() {
        for (offset in listOf("D7", "D1", "D0")) {
            assertEquals("e1", eventIdFromDedupeKey("e1:2026:$offset"))
        }
    }
}
