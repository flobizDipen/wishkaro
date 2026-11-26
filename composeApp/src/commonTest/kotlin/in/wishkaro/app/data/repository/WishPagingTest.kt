package `in`.wishkaro.app.data.repository

import `in`.wishkaro.app.data.remote.ApiErrorIssue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * How a wish is packed into a push page (M5).
 *
 * A wish joins the same unit packing that M3 built for events and traits, and it joins it
 * with one fewer constraint than either: its only dependency is its PERSON. That is the whole
 * reason `wish.event_id` is a soft reference with no foreign key — with a real one a wish
 * would need to be in the same page as its event as well, and a unit cannot express two
 * parents. See the Wish model in `schema.prisma` and docs/M5_PLAN.md §2.3.
 *
 * `planPages` is pure, so this tests it directly. The engine-level behaviour it feeds — a
 * page retried without the rows the server named — is covered by `SyncChunkingTest`.
 */
class WishPagingTest {

    @Test
    fun a_wish_travels_with_its_person() {
        // The guarantee that matters: the server applies people before wishes within one
        // request, so a brand-new person and the wish recorded for them can arrive together.
        val plan = planPages(
            people = listOf(personRow("p1")),
            events = emptyList(),
            traits = emptyList(),
            wishes = listOf(wishRow("w1", "p1")),
            pendingPersonIds = setOf("p1"),
        )

        assertEquals(1, plan.pages.size)
        assertEquals(listOf("p1"), plan.pages[0].people.map { it.id })
        assertEquals(listOf("w1"), plan.pages[0].wishes.map { it.id })
        assertEquals(0, plan.deferred)
    }

    @Test
    fun a_wish_whose_person_the_server_already_has_travels_alone() {
        val plan = planPages(
            people = emptyList(),
            events = emptyList(),
            traits = emptyList(),
            wishes = listOf(wishRow("w1", "p1")),
            pendingPersonIds = emptySet(),
        )

        assertEquals(1, plan.pages.size)
        assertEquals(listOf("w1"), plan.pages[0].wishes.map { it.id })
        assertTrue(plan.pages[0].people.isEmpty())
    }

    @Test
    fun a_wish_whose_person_is_pending_but_not_in_this_snapshot_is_deferred() {
        // The third case, which has no unit. Sending it would earn a guaranteed
        // UNKNOWN_PERSON — handled correctly, but a wasted row in the request and a wasted
        // row of the user's data budget. It goes on a later run, once its person has gone.
        val plan = planPages(
            people = emptyList(),
            events = emptyList(),
            traits = emptyList(),
            wishes = listOf(wishRow("w1", "p-elsewhere")),
            pendingPersonIds = setOf("p-elsewhere"),
        )

        assertTrue(plan.pages.isEmpty())
        assertEquals(1, plan.deferred, "deferred, not dropped and not sent")
    }

    @Test
    fun the_wish_slice_has_its_own_page_budget() {
        // 3 people, each with 2 wishes, page size 2. The people cap binds first, and no page
        // may ever split a person from their own wishes.
        val people = (1..3).map { personRow("p$it") }
        val wishes = (1..3).flatMap { listOf(wishRow("w${it}a", "p$it"), wishRow("w${it}b", "p$it")) }

        val plan = planPages(
            people = people,
            events = emptyList(),
            traits = emptyList(),
            wishes = wishes,
            pendingPersonIds = people.mapTo(mutableSetOf()) { it.id },
            pageSize = 2,
        )

        assertEquals(0, plan.deferred)
        assertEquals(6, plan.pages.sumOf { it.wishes.size }, "every wish is sent exactly once")
        plan.pages.forEach { page ->
            val peopleHere = page.people.mapTo(mutableSetOf()) { it.id }
            page.wishes.forEach { wish ->
                assertTrue(
                    wish.person_id !in people.map { it.id } || wish.person_id in peopleHere,
                    "wish ${wish.id} was split from person ${wish.person_id}",
                )
            }
        }
    }

    @Test
    fun a_person_with_more_wishes_than_a_page_still_gets_a_page_rather_than_looping() {
        // The emptiness check in the packer. A unit bigger than a whole page gets a page to
        // itself; without that guard the loop flushes empty pages for ever.
        val plan = planPages(
            people = listOf(personRow("p1")),
            events = emptyList(),
            traits = emptyList(),
            wishes = (1..5).map { wishRow("w$it", "p1") },
            pendingPersonIds = setOf("p1"),
            pageSize = 2,
        )

        assertEquals(1, plan.pages.size)
        assertEquals(5, plan.pages[0].wishes.size)
    }

    @Test
    fun the_server_can_name_a_wish_by_index_and_only_that_row_is_set_aside() {
        // `ZodValidationPipe` reports `wishes.1.messageText`. Index 1 is this page's array,
        // which is why rowsRejectedBy takes the page rather than the whole outbox.
        val page = PushPage(
            wishes = listOf(wishRow("w0", "p1"), wishRow("w1", "p1"), wishRow("w2", "p1")),
        )
        val rejected = rowsRejectedBy(
            page,
            listOf(ApiErrorIssue(path = "wishes.1.messageText", message = "too long")),
        )

        assertEquals(1, rejected.size)
        assertEquals(WISH, rejected[0].entity)
        assertEquals("w1", rejected[0].id)
        assertEquals("messageText: too long", rejected[0].reason)

        val retry = page.without(rejected)
        assertEquals(listOf("w0", "w2"), retry.wishes.map { it.id })
    }

    @Test
    fun an_issue_naming_no_row_attributes_nothing() {
        // "Could not attribute this failure" is not "nothing was wrong". An index past the
        // end means the server and this page disagree about what was sent, which is a bug to
        // find rather than a row to delete.
        val page = PushPage(wishes = listOf(wishRow("w0", "p1")))
        assertTrue(rowsRejectedBy(page, listOf(ApiErrorIssue("wishes.9.tone", "bad"))).isEmpty())
        assertTrue(rowsRejectedBy(page, listOf(ApiErrorIssue("wishes", "too many"))).isEmpty())
    }
}

private fun personRow(id: String) = wishkaro.db.Person(
    id = id,
    display_name = "Person $id",
    relation_to_owner = "Friend",
    is_self = 0,
    updated_at = "2026-11-25T10:00:00Z",
    deleted_at = null,
    pending_sync = 1,
    photo_key = null,
    gender = null,
    phone = null,
    email = null,
    notes = null,
    is_deceased = 0,
    is_minor = 0,
    completeness_score = 0,
    completeness_suggestions = "",
    sync_blocked = 0,
    sync_block_reason = null,
)

private fun wishRow(id: String, personId: String) = wishkaro.db.Wish(
    id = id,
    person_id = personId,
    type = "BIRTHDAY",
    occurrence_year = 2026,
    sentiment = "CELEBRATORY",
    event_id = null,
    message_text = "A message.",
    tone = "WARM",
    language = "ENGLISH",
    channel = "WHATSAPP",
    source = "AI",
    poster_id = null,
    sent_at = "2026-03-14T04:30:00Z",
    updated_at = "2026-03-14T04:30:00Z",
    deleted_at = null,
    pending_sync = 1,
    sync_blocked = 0,
    sync_block_reason = null,
)
