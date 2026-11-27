package `in`.wishkaro.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FR-AUTH-06 and §12's prominent disclosure, held to the clauses the requirement actually
 * states rather than to the fact that a screen exists.
 *
 * ## What this test can and cannot catch
 *
 * It cannot judge whether the copy is good, and a keyword check on prose is a weak instrument
 * — M5 §0.3 bought the entry about a banned-lexicon test pointed at the wrong artifact. What
 * it *can* do is fail when a specific required statement is deleted, which is exactly how the
 * bug it was written for arrived: M3's screen said "it all happens on this phone", which was
 * true of the reading and false of the import, and nothing anywhere could notice.
 *
 * So each assertion below names one clause of FR-AUTH-06 or one fact about this app's data
 * flow, and the phrases are substantive rather than single words.
 */
class ContactsDisclosureTest {

    @Test
    fun it_says_what_is_read() {
        // FR-AUTH-06: "stating what is read".
        val purpose = ContactsDisclosure.PURPOSE.lowercase()
        for (field in listOf("names", "phone numbers", "photos", "birthday")) {
            assertTrue(field in purpose, "the disclosure must name what it reads: $field")
        }
    }

    @Test
    fun it_says_why() {
        // FR-AUTH-06: "why". The reason has to be the user's, not ours.
        val purpose = ContactsDisclosure.PURPOSE.lowercase()
        assertTrue(
            "so that" in purpose || "so you" in purpose,
            "the disclosure must state a purpose, not only a list of fields",
        )
        assertTrue("birthdays you have already saved" in purpose)
    }

    @Test
    fun it_says_nothing_is_uploaded_in_bulk() {
        // FR-AUTH-06 names this clause in as many words.
        //
        // Asserted as "the phrase, negated" rather than one exact sentence: the requirement
        // is that the user is told, not that the screen contains a particular string, and a
        // test pinned to one wording is a test that fails on a comma. This caught its own
        // first draft, which looked for "not uploaded in bulk" against copy that reads
        // "nothing is uploaded in bulk" — the copy was right and the assertion was brittle.
        assertTrue(
            ContactsDisclosure.POINTS.any {
                val lower = it.lowercase()
                "uploaded in bulk" in lower && ("nothing" in lower || "not " in lower)
            },
            "FR-AUTH-06 requires the disclosure to say it is not uploaded in bulk",
        )
    }

    /**
     * **The C10 fix, as a test.**
     *
     * The previous copy said the opposite of this, and the opposite was false. FR-IMP-01 says
     * only the records the user explicitly selects are "persisted **or synced**", and
     * `PeopleRepository.importPeople` writes them with `pending_sync = 1` for the sync engine
     * to drain to the server. If somebody deletes this sentence to make the screen shorter,
     * this test is what says no.
     */
    @Test
    fun it_admits_that_imported_people_leave_the_phone() {
        val admits = ContactsDisclosure.POINTS.any {
            val lower = it.lowercase()
            "sync" in lower && "account" in lower
        }
        assertTrue(
            admits,
            "the people the user imports are synced to the server — the disclosure must say so",
        )
    }

    /**
     * And the claim that made the old copy misleading must not come back.
     *
     * "It all happens on this phone" is a true sentence about the reading and a false one
     * about the import, which is the worst kind: a reviewer reads it as a promise about the
     * whole feature. Stated as a prohibition because that is how it will be re-introduced —
     * by somebody shortening the screen, not by somebody lying.
     */
    @Test
    fun it_never_claims_that_everything_stays_on_the_device() {
        val everything = (listOf(ContactsDisclosure.PURPOSE) + ContactsDisclosure.POINTS)
            .joinToString(" ")
            .lowercase()

        for (claim in listOf(
            "it all happens on this phone",
            "never leaves this phone",
            "never leaves your phone",
            "stays on this phone",
            "nothing is sent anywhere",
        )) {
            assertTrue(claim !in everything, "the disclosure must not claim \"$claim\"")
        }
    }

    @Test
    fun declining_is_offered_and_is_not_a_dead_end() {
        // FR-AUTH-06: "Declining must leave the app fully usable."
        assertEquals("Not now", ContactsDisclosure.DECLINE)
        assertTrue(
            ContactsDisclosure.POINTS.any { "works without this" in it.lowercase() },
            "the disclosure must say the app still works if the user says no",
        )
    }

    @Test
    fun every_node_a_device_sequence_has_to_touch_carries_a_tag() {
        // M4 bought this: Compose hands uiautomator a clickable node with no text and no
        // content-description, so an untagged button cannot be addressed from the view tree.
        // Tags are also only real once read back out of it — item 7 on the handset is where
        // that happens, not here.
        val tags = listOf(
            ContactsDisclosure.TAG_SCREEN,
            ContactsDisclosure.TAG_CONTINUE,
            ContactsDisclosure.TAG_DECLINE,
        )
        assertEquals(tags.size, tags.distinct().size, "two nodes must not share one tag")
        for (tag in tags) {
            // M5 §0.4: a mis-escaped template renders literally, and every row then carries
            // one unaddressable id. The same mistake was then made twice more in M6.
            assertTrue('$' !in tag, "a tag containing \$ did not interpolate: $tag")
            assertTrue(tag.isNotBlank())
        }
    }
}
