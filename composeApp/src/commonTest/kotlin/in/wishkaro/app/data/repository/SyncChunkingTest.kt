package `in`.wishkaro.app.data.repository

import `in`.wishkaro.app.data.local.RemoteEvent
import `in`.wishkaro.app.data.local.RemotePerson
import `in`.wishkaro.app.data.local.RemoteTrait
import `in`.wishkaro.app.data.local.RemoteWish
import `in`.wishkaro.app.data.remote.ApiErrorIssue
import `in`.wishkaro.app.data.remote.ApiException
import `in`.wishkaro.app.data.remote.DeliveryReportRequest
import `in`.wishkaro.app.data.remote.DeliveryReportResponse
import `in`.wishkaro.app.data.remote.SyncPullResponse
import `in`.wishkaro.app.data.remote.SyncPushRequest
import `in`.wishkaro.app.data.remote.SyncPushResponse
import `in`.wishkaro.app.data.remote.SyncSkipped
import `in`.wishkaro.app.domain.ReminderSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The outbox blocker, and the floor M3 stands on.
 *
 * Before this, `push()` read the whole outbox with no LIMIT and sent it in ONE request. The
 * server caps each array at 500 and validates `.strict()` before applying anything, so a
 * user who imported 501 people produced a push that 400'd — and 400'd again on every retry
 * for ever, because the outbox still held the same 501 rows. The batch is shared, so the
 * person they added by hand afterwards went down with it. The visible symptom was "the app
 * stopped syncing", days later, with no mention of import anywhere.
 *
 * `FakeServer` below enforces the real limits — the 500-row cap AND the body size — so these
 * tests fail against the old code rather than describing the new code back to itself.
 *
 * See docs/M3_PLAN.md §1.
 */
class SyncChunkingTest {

    private val serverTime = "2026-11-25T10:16:21.410Z"

    // ---------------------------------------------------------------------------------
    // The reported failure
    // ---------------------------------------------------------------------------------

    @Test
    fun an_import_larger_than_the_server_cap_reaches_the_server_completely() = runTest {
        // 501 people, each with a birthday: exactly the shape of an import that used to wedge
        // the outbox for ever.
        val store = FakeOutbox.withImport(people = 501)
        val server = FakeServer(serverTime)

        val result = SyncEngine(server, store).sync().getOrThrow()

        assertEquals(501, server.peopleReceived.size, "every person reached the server")
        assertEquals(501, server.eventsReceived.size, "every birthday reached the server")
        assertEquals(501, result.pushed.people)
        assertTrue(store.stillPending.isEmpty(), "the outbox is empty afterwards")
        assertTrue(
            server.requests.all { it.people.size <= 500 && it.events.size <= 500 },
            "no single request may exceed the server's documented cap",
        )
    }

    /**
     * The second half of definition-of-done item 8, and the half that is really about the
     * bug: the batch is shared, so the failure took unrelated writes down with it.
     */
    @Test
    fun a_person_added_by_hand_after_a_large_import_syncs_too() = runTest {
        val store = FakeOutbox.withImport(people = 600)
        val server = FakeServer(serverTime)
        SyncEngine(server, store).sync().getOrThrow()

        store.addPerson("added-by-hand")
        SyncEngine(server, store).sync().getOrThrow()

        assertTrue(
            "added-by-hand" in server.peopleReceived,
            "a write with nothing to do with the import must not be held hostage by it",
        )
        assertTrue(store.stillPending.isEmpty())
    }

    @Test
    fun no_request_exceeds_the_page_size() = runTest {
        val store = FakeOutbox.withImport(people = 1000)
        val server = FakeServer(serverTime)

        SyncEngine(server, store).sync().getOrThrow()

        assertTrue(
            server.requests.all {
                it.people.size <= PUSH_PAGE_SIZE &&
                    it.events.size <= PUSH_PAGE_SIZE &&
                    it.traits.size <= PUSH_PAGE_SIZE
            },
            "pages were ${server.requests.map { it.people.size }}",
        )
    }

    // ---------------------------------------------------------------------------------
    // The page boundary itself: at the size, and one either side
    // ---------------------------------------------------------------------------------

    @Test
    fun one_page_exactly_at_the_boundary() = runTest {
        val server = pushOnly(FakeOutbox.withPeopleOnly(PUSH_PAGE_SIZE))
        assertEquals(1, server.requests.size)
        assertEquals(PUSH_PAGE_SIZE, server.requests[0].people.size)
    }

    @Test
    fun one_page_one_below_the_boundary() = runTest {
        val server = pushOnly(FakeOutbox.withPeopleOnly(PUSH_PAGE_SIZE - 1))
        assertEquals(1, server.requests.size)
    }

    @Test
    fun two_pages_one_above_the_boundary() = runTest {
        val server = pushOnly(FakeOutbox.withPeopleOnly(PUSH_PAGE_SIZE + 1))
        assertEquals(2, server.requests.size)
        assertEquals(PUSH_PAGE_SIZE, server.requests[0].people.size)
        assertEquals(1, server.requests[1].people.size)
    }

    @Test
    fun the_server_cap_itself_at_499_500_and_501() = runTest {
        for (n in listOf(499, 500, 501)) {
            val server = pushOnly(FakeOutbox.withPeopleOnly(n))
            assertEquals(n, server.peopleReceived.size, "all $n rows arrive")
            assertTrue(
                server.requests.all { it.people.size <= 500 },
                "no request near the cap may exceed it ($n rows)",
            )
        }
    }

    // ---------------------------------------------------------------------------------
    // A page is an independent commit
    // ---------------------------------------------------------------------------------

    @Test
    fun a_5xx_on_page_four_leaves_the_first_three_settled_and_stops_the_run() = runTest {
        val store = FakeOutbox.withPeopleOnly(PUSH_PAGE_SIZE * 6)
        val server = FakeServer(serverTime, failOnRequest = 4 to ApiException(503, "UNAVAILABLE", "nope"))

        val result = SyncEngine(server, store).sync()

        assertTrue(result.isFailure, "a 5xx is not about this data; the run stops")
        assertEquals(4, server.requests.size, "page four was attempted; five and six were not")
        assertEquals(PUSH_PAGE_SIZE * 3, server.peopleReceived.size, "three pages landed")
        assertEquals(PUSH_PAGE_SIZE * 3, store.cleared.size, "pages one to three stay settled")
        assertEquals(PUSH_PAGE_SIZE * 3, store.stillPending.size, "the rest is still pending")
    }

    @Test
    fun the_pages_already_settled_are_not_sent_again_on_the_next_run() = runTest {
        val store = FakeOutbox.withPeopleOnly(PUSH_PAGE_SIZE * 3)
        val failing = FakeServer(serverTime, failOnRequest = 3 to ApiException(503, "UNAVAILABLE", "nope"))
        SyncEngine(failing, store).sync()

        val recovered = FakeServer(serverTime)
        SyncEngine(recovered, store).sync().getOrThrow()

        assertEquals(
            PUSH_PAGE_SIZE,
            recovered.peopleReceived.size,
            "only the page that never landed goes again",
        )
        assertTrue(store.stillPending.isEmpty())
    }

    // ---------------------------------------------------------------------------------
    // The poison row: a 400 that is about one row, not about size
    // ---------------------------------------------------------------------------------

    @Test
    fun one_row_the_server_names_is_set_aside_and_the_rest_of_the_page_lands() = runTest {
        val store = FakeOutbox.withPeopleOnly(PUSH_PAGE_SIZE * 3)
        val poison = store.personIdAt(PUSH_PAGE_SIZE + 17) // page two, index 17
        val server = FakeServer(serverTime, rejectRow = poison to "String must contain at most 120 character(s)")

        val result = SyncEngine(server, store).sync().getOrThrow()

        assertEquals(
            "displayName: String must contain at most 120 character(s)",
            store.blocked[poison],
            "the server's own message is what the user is shown",
        )
        assertEquals(
            PUSH_PAGE_SIZE * 3 - 1,
            server.peopleReceived.size,
            "every row but the poison one reached the server",
        )
        assertFalse(poison in server.peopleReceived)
        assertEquals(listOf(poison), result.blocked.map { it.id })
        assertEquals(
            setOf(poison),
            store.stillPending,
            "the blocked row keeps its pending flag — it does still hold an unsent change",
        )
    }

    @Test
    fun a_page_after_a_rejected_one_is_still_sent() = runTest {
        val store = FakeOutbox.withPeopleOnly(PUSH_PAGE_SIZE * 3)
        val poison = store.personIdAt(5) // page ONE
        val server = FakeServer(serverTime, rejectRow = poison to "bad")

        SyncEngine(server, store).sync().getOrThrow()

        // This is the whole point. A 400 is about this data, not about the connection, so
        // pages two and three must not be abandoned the way a 5xx abandons them.
        assertEquals(PUSH_PAGE_SIZE * 2, store.cleared.size - (PUSH_PAGE_SIZE - 1))
        assertTrue(store.stillPending == setOf(poison))
    }

    @Test
    fun a_blocked_row_leaves_the_drain_and_does_not_jam_later_runs() = runTest {
        val store = FakeOutbox.withPeopleOnly(10)
        val poison = store.personIdAt(3)
        SyncEngine(FakeServer(serverTime, rejectRow = poison to "bad"), store).sync().getOrThrow()

        store.addPerson("later")
        val second = FakeServer(serverTime)
        SyncEngine(second, store).sync().getOrThrow()

        assertEquals(
            listOf("later"),
            second.peopleReceived,
            "the blocked row is not retried, and it does not stop the new one",
        )
    }

    @Test
    fun a_400_that_names_no_row_blocks_nothing_and_leaves_the_page_pending() = runTest {
        val store = FakeOutbox.withPeopleOnly(10)
        // A validation failure whose only issue path is the array itself — which is what the
        // `.max(500)` check produces. It names no row, so there is nothing to attribute.
        val server = FakeServer(
            serverTime,
            failOnRequest = 1 to ApiException(
                400,
                "VALIDATION_FAILED",
                "Request body failed validation",
                listOf(ApiErrorIssue(path = "people", message = "Too big", code = "too_big")),
            ),
        )

        val result = SyncEngine(server, store).sync().getOrThrow()

        assertTrue(store.blocked.isEmpty(), "could not attribute is not the same as nothing was wrong")
        assertEquals(10, store.stillPending.size, "the whole page waits for the next run")
        assertTrue(result.blocked.isEmpty())
    }

    @Test
    fun a_400_that_is_not_a_validation_failure_leaves_the_page_pending() = runTest {
        val store = FakeOutbox.withPeopleOnly(10)
        val server = FakeServer(
            serverTime,
            failOnRequest = 1 to ApiException(403, "FORBIDDEN", "no"),
        )

        SyncEngine(server, store).sync().getOrThrow()

        assertTrue(store.blocked.isEmpty())
        assertEquals(10, store.stillPending.size)
    }

    // ---------------------------------------------------------------------------------
    // A page the server accepted but whose response was lost
    // ---------------------------------------------------------------------------------

    /**
     * The window chunking makes more common. The rows stay pending with their original
     * timestamp; the next push gets STALE, because the server stored the client's own clock
     * and `>=` is the comparison. The STALE rule M2 built for a different reason re-stamps
     * them past the server's clock, and the push after that lands.
     *
     * Two extra rounds, no data loss, and crucially no duplicate — ids are client-generated
     * (D23) and every write is an upsert on that id.
     */
    @Test
    fun a_page_the_server_applied_but_whose_response_was_lost_settles_in_two_more_rounds() = runTest {
        val store = FakeOutbox.withPeopleOnly(PUSH_PAGE_SIZE * 2)
        val server = FakeServer(serverTime, loseResponseOnRequest = 2)

        SyncEngine(server, store).sync()
        assertEquals(PUSH_PAGE_SIZE * 2, server.peopleReceived.size, "the server DID apply page two")
        assertEquals(PUSH_PAGE_SIZE, store.stillPending.size, "but the client does not know it")

        // Round two: the server says STALE because its stored copy carries the same stamp.
        SyncEngine(server, store).sync().getOrThrow()
        assertEquals(PUSH_PAGE_SIZE, store.restamped.size, "re-stamped past the server's clock")
        assertEquals(PUSH_PAGE_SIZE, store.stillPending.size, "still pending, deliberately")

        // Round three: strictly newer, so it is applied and the flag finally clears.
        SyncEngine(server, store).sync().getOrThrow()
        assertTrue(store.stillPending.isEmpty())
        assertEquals(
            PUSH_PAGE_SIZE * 2,
            server.peopleReceived.distinct().size,
            "replaying a batch upserts by id — it must not create a second copy of anyone",
        )
    }

    // ---------------------------------------------------------------------------------
    // Rows that arrived by a route the loop was not written for
    // ---------------------------------------------------------------------------------

    /**
     * The ordering guarantee. `upsertEvent` returns UNKNOWN_PERSON when the person is not on
     * the server yet, so an event must never be in an earlier page than its own person.
     */
    @Test
    fun a_person_is_never_in_a_later_page_than_their_own_events() = runTest {
        val store = FakeOutbox.withImport(people = 1000)
        val server = FakeServer(serverTime)

        SyncEngine(server, store).sync().getOrThrow()

        val seenPeople = mutableSetOf<String>()
        for (request in server.requests) {
            seenPeople += request.people.map { it.id }
            for (event in request.events) {
                assertTrue(
                    event.personId in seenPeople,
                    "event ${event.id} was sent before its person ${event.personId}",
                )
            }
        }
    }

    /**
     * And the case the snapshot creates: an event whose person IS pending but fell outside
     * this run's slice. Sending it would earn a guaranteed UNKNOWN_PERSON.
     */
    @Test
    fun an_event_whose_person_is_pending_but_outside_the_snapshot_waits_for_them() = runTest {
        val store = FakeOutbox()
        store.addPerson("p-late")
        store.addEvent("e-late", personId = "p-late")
        store.peopleSnapshotLimit = 0 // the person does not make it into this run's slice

        val server = FakeServer(serverTime)
        val result = SyncEngine(server, store).sync().getOrThrow()

        assertTrue(server.eventsReceived.isEmpty(), "the event is held back, not bounced")
        assertEquals(1, result.deferred)

        // Next run, with room for the person: both go, person first.
        store.peopleSnapshotLimit = null
        SyncEngine(server, store).sync().getOrThrow()
        assertEquals(listOf("e-late"), server.eventsReceived)
        assertTrue(store.stillPending.isEmpty())
    }

    /** An event on a person the server already knows is an orphan unit, and goes on its own. */
    @Test
    fun an_event_on_an_already_synced_person_is_sent_without_waiting() = runTest {
        val store = FakeOutbox()
        store.addEvent("e1", personId = "already-synced")
        val server = FakeServer(serverTime)

        val result = SyncEngine(server, store).sync().getOrThrow()

        assertEquals(listOf("e1"), server.eventsReceived)
        assertEquals(0, result.deferred)
    }

    // ---------------------------------------------------------------------------------
    // The page cap, and saying so
    // ---------------------------------------------------------------------------------

    /**
     * When the run hits its page cap it leaves part of the outbox for next time — and SAYS
     * so. The review finding was that `planPages` worked this out and `sync()` dropped it on
     * the floor, which is the shape MILESTONE_CHECKLIST.md names first and the third time
     * this project has made it.
     */
    @Test
    fun a_run_that_hits_its_page_cap_reports_that_it_did() = runTest {
        val store = FakeOutbox.withPeopleOnly(PUSH_PAGE_SIZE * 5)
        val server = FakeServer(serverTime)

        val result = SyncEngine(server, store, maxPushPages = 2).sync().getOrThrow()

        assertTrue(result.outboxTruncated, "the caller has to be able to know it is not empty")
        assertEquals(2, server.requests.size, "only the pages the cap allows")
        assertEquals(PUSH_PAGE_SIZE * 3, store.stillPending.size, "the rest waits for the next run")
    }

    @Test
    fun a_run_that_drains_everything_does_not_claim_it_was_truncated() = runTest {
        val store = FakeOutbox.withPeopleOnly(PUSH_PAGE_SIZE * 2)
        val result = SyncEngine(FakeServer(serverTime), store, maxPushPages = 50).sync().getOrThrow()

        assertFalse(result.outboxTruncated)
        assertTrue(store.stillPending.isEmpty())
    }

    @Test
    fun the_pages_left_behind_go_on_the_next_run() = runTest {
        val store = FakeOutbox.withPeopleOnly(PUSH_PAGE_SIZE * 3)
        SyncEngine(FakeServer(serverTime), store, maxPushPages = 1).sync().getOrThrow()
        SyncEngine(FakeServer(serverTime), store, maxPushPages = 1).sync().getOrThrow()
        val third = SyncEngine(FakeServer(serverTime), store, maxPushPages = 1).sync().getOrThrow()

        assertTrue(store.stillPending.isEmpty())
        assertFalse(third.outboxTruncated, "the last run had nothing left over")
    }

    // ---------------------------------------------------------------------------------
    // Termination
    // ---------------------------------------------------------------------------------

    /**
     * Both held outcomes keep their pending flag by design, so a drain written as "page until
     * the outbox is empty" would spin on them for ever. The snapshot is what makes the loop
     * terminate; this is the test that says so.
     */
    @Test
    fun rows_the_server_holds_do_not_make_the_drain_loop() = runTest {
        val store = FakeOutbox.withPeopleOnly(5)
        store.addEvent("orphan", personId = "nobody")
        val server = FakeServer(
            serverTime,
            alwaysSkip = listOf(
                SyncSkipped(store.personIdAt(0), "person", "STALE"),
                SyncSkipped("orphan", "event", "UNKNOWN_PERSON"),
            ),
        )

        SyncEngine(server, store).sync().getOrThrow()

        assertEquals(1, server.requests.size, "one page, sent once — not retried inside the run")
        assertEquals(setOf(store.personIdAt(0), "orphan"), store.stillPending)
    }
}

// =====================================================================================
// Fakes
// =====================================================================================

/**
 * A server that enforces what the real one enforces.
 *
 * The row cap and the body size are both real limits (`SyncPushSchema.max(500)` and the 2 MB
 * JSON body), and a fake that did not enforce them would let the old unchunked push pass
 * these tests.
 */
private class FakeServer(
    private val serverTime: String,
    /** Request number (1-based) to fail, and how. */
    private val failOnRequest: Pair<Int, ApiException>? = null,
    /** Request number whose response is lost AFTER the server applied it. */
    private val loseResponseOnRequest: Int? = null,
    /** A person id the server refuses by name, with its reason. */
    private val rejectRow: Pair<String, String>? = null,
    private val alwaysSkip: List<SyncSkipped> = emptyList(),
) : SyncApi {

    val requests = mutableListOf<SyncPushRequest>()
    val peopleReceived = mutableListOf<String>()
    val eventsReceived = mutableListOf<String>()

    /** What the server has stored, by id — so a replay can answer STALE like the real one. */
    private val stored = mutableMapOf<String, String>()

    override suspend fun syncPush(body: SyncPushRequest): SyncPushResponse {
        val n = requests.size + 1

        require(body.people.size <= 500 && body.events.size <= 500 && body.traits.size <= 500) {
            "the server caps each array at 500; got ${body.people.size} people"
        }
        require(estimatedBytes(body) <= 2 * 1024 * 1024) { "body over the 2 MB limit" }

        requests += body
        failOnRequest?.let { (which, failure) -> if (n == which) throw failure }

        rejectRow?.let { (id, message) ->
            val index = body.people.indexOfFirst { it.id == id }
            if (index >= 0) {
                throw ApiException(
                    400,
                    "VALIDATION_FAILED",
                    "Request body failed validation",
                    listOf(ApiErrorIssue("people.$index.displayName", message, "too_big")),
                )
            }
        }

        // Apply, with the real last-write-wins: a replay of the same stamp is STALE.
        val skipped = mutableListOf<SyncSkipped>()
        for (person in body.people) {
            val held = stored[person.id]
            if (held != null && held >= person.updatedAt) {
                skipped += SyncSkipped(person.id, "person", "STALE")
            } else {
                stored[person.id] = person.updatedAt
                peopleReceived += person.id
            }
        }
        for (event in body.events) {
            stored[event.id] = event.updatedAt
            eventsReceived += event.id
        }

        if (n == loseResponseOnRequest) throw ApiException(0, "NETWORK", "response lost")

        return SyncPushResponse(skipped = skipped + alwaysSkip, serverTime = serverTime)
    }

    private fun estimatedBytes(body: SyncPushRequest): Int =
        body.people.sumOf { 120 + it.displayName.length + (it.notes?.length ?: 0) } +
            body.events.size * 120 +
            body.traits.size * 120

    override suspend fun syncPull(cursor: String?) =
        SyncPullResponse(cursor = "c", hasMore = false, serverTime = serverTime)

    override suspend fun reportDelivery(body: DeliveryReportRequest) =
        DeliveryReportResponse(recorded = 0, unknown = 0)
}

/** An outbox that behaves like the SQL one: ordered, bounded, and blockable. */
private class FakeOutbox : SyncStore {

    private val people = linkedMapOf<String, wishkaro.db.Person>()
    private val events = linkedMapOf<String, wishkaro.db.Event>()
    private val pending = mutableSetOf<String>()

    val cleared = mutableSetOf<String>()
    val restamped = mutableMapOf<String, String>()
    val blocked = mutableMapOf<String, String>()

    /**
     * Caps the PEOPLE slice only, to reproduce the one case the snapshot creates: an event
     * whose person is pending but did not fit into this run.
     */
    var peopleSnapshotLimit: Long? = null

    val stillPending: Set<String> get() = pending.toSet()

    fun personIdAt(index: Int): String = people.keys.elementAt(index)

    fun addPerson(id: String) {
        people[id] = row(id)
        pending += id
    }

    fun addEvent(id: String, personId: String) {
        events[id] = eventRow(id, personId)
        pending += id
    }

    private fun cap(requested: Long) = requested.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    override suspend fun pendingPeople(limit: Long): List<wishkaro.db.Person> =
        people.values.filter { it.id in pending && it.id !in blocked }
            .sortedWith(compareBy({ it.updated_at }, { it.id }))
            .take(cap(peopleSnapshotLimit ?: limit))

    override suspend fun pendingEvents(limit: Long): List<wishkaro.db.Event> =
        events.values.filter { it.id in pending && it.id !in blocked }
            .sortedWith(compareBy({ it.updated_at }, { it.id }))
            .take(cap(limit))

    override suspend fun pendingTraits(limit: Long): List<wishkaro.db.Person_trait> = emptyList()

    override suspend fun pendingPersonIds(): Set<String> =
        people.values.filter { it.id in pending }.mapTo(mutableSetOf()) { it.id }

    override suspend fun blockPerson(id: String, reason: String) {
        blocked[id] = reason
    }

    override suspend fun blockEvent(id: String, reason: String) {
        blocked[id] = reason
    }

    override suspend fun blockTrait(id: String, reason: String) {
        blocked[id] = reason
    }

    override suspend fun clearPersonPending(ids: List<String>) {
        cleared += ids
        pending -= ids.toSet()
    }

    override suspend fun clearEventPending(ids: List<String>) {
        cleared += ids
        pending -= ids.toSet()
    }

    override suspend fun clearTraitPending(ids: List<String>) {
        cleared += ids
        pending -= ids.toSet()
    }

    override suspend fun restampPersonPending(ids: List<String>, updatedAt: String) {
        ids.forEach {
            restamped[it] = updatedAt
            people[it] = people.getValue(it).copy(updated_at = updatedAt)
        }
    }

    override suspend fun restampEventPending(ids: List<String>, updatedAt: String) {
        ids.forEach {
            restamped[it] = updatedAt
            events[it] = events.getValue(it).copy(updated_at = updatedAt)
        }
    }

    override suspend fun restampTraitPending(ids: List<String>, updatedAt: String) = Unit

    override suspend fun pendingWishes(limit: Long): List<wishkaro.db.Wish> = emptyList()

    override suspend fun blockWish(id: String, reason: String) = Unit

    override suspend fun clearWishPending(ids: List<String>) = Unit

    override suspend fun restampWishPending(ids: List<String>, updatedAt: String) = Unit

    override suspend fun applyRemote(
        remotePeople: List<RemotePerson>,
        remoteEvents: List<RemoteEvent>,
        remoteTraits: List<RemoteTrait>,
        remoteWishes: List<RemoteWish>,
        cursor: String,
    ) = Unit

    override suspend fun cursor(): String? = null

    override suspend fun pendingDeliveryReports(limit: Long) = emptyList<wishkaro.db.Delivery_report>()

    override suspend fun clearDeliveryPending(keys: List<String>) = Unit

    override suspend fun reminderSettings() = ReminderSettings()

    override suspend fun applyRemoteSettings(remote: ReminderSettings) = false

    override suspend fun clearSettingsPending() = Unit

    companion object {
        /** `n` people, nothing else. */
        fun withPeopleOnly(n: Int) = FakeOutbox().apply {
            repeat(n) { addPerson("p${it.toString().padStart(5, '0')}") }
        }

        /** What an import produces: a person and a birthday each, in one transaction. */
        fun withImport(people: Int) = FakeOutbox().apply {
            repeat(people) {
                val id = "p${it.toString().padStart(5, '0')}"
                addPerson(id)
                addEvent("e${it.toString().padStart(5, '0')}", personId = id)
            }
        }
    }
}

private fun row(id: String) = wishkaro.db.Person(
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

private fun eventRow(id: String, personId: String) = wishkaro.db.Event(
    id = id,
    person_id = personId,
    type = "BIRTHDAY",
    day = 22,
    month = 9,
    year = 1988,
    updated_at = "2026-11-25T10:00:00Z",
    deleted_at = null,
    pending_sync = 1,
    sentiment = "CELEBRATORY",
    label = null,
    is_muted = 0,
    sync_blocked = 0,
    sync_block_reason = null,
)

private suspend fun pushOnly(store: FakeOutbox): FakeServer {
    val server = FakeServer("2026-11-25T10:16:21.410Z")
    SyncEngine(server, store).sync().getOrThrow()
    return server
}
