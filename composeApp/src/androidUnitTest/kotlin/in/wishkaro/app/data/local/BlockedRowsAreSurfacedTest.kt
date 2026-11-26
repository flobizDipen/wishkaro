package `in`.wishkaro.app.data.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import `in`.wishkaro.app.data.remote.ApiErrorIssue
import `in`.wishkaro.app.data.remote.ApiException
import `in`.wishkaro.app.data.remote.DeliveryReportRequest
import `in`.wishkaro.app.data.remote.DeliveryReportResponse
import `in`.wishkaro.app.data.remote.SyncPullResponse
import `in`.wishkaro.app.data.remote.SyncPushRequest
import `in`.wishkaro.app.data.remote.SyncPushResponse
import `in`.wishkaro.app.data.repository.SyncApi
import `in`.wishkaro.app.data.repository.SyncEngine
import `in`.wishkaro.app.domain.EventDate
import `in`.wishkaro.app.domain.EventType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import wishkaro.db.WishKaroDb
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A record the server refuses must reach the USER, not just a field on a Result nobody reads.
 *
 * THE FINDING, from a second review pass: `observeBlockedCount()` and `blockedRows()` had no
 * callers anywhere in `composeApp/src`, and `SyncEngine.Result.blocked` was read only by
 * tests. Both carried comments promising otherwise — "for the banner that tells the user
 * about them", "Reported here so the UI can tell the user; a row silently set aside would be
 * the same silent-partial-success M3 exists to avoid". There was no banner.
 *
 * What that costs, with no import involved at all: nothing in the person editor caps text
 * length, while `PersonUpsertSchema` caps `notes` at 2,000. Paste a long note and the local
 * write succeeds; the next push is a 400 naming that row; `blockPerson` sets
 * `sync_blocked = 1`; `selectPendingSync` filters it out, so the person leaves the drain for
 * ever — and because `pendingPersonIds()` deliberately includes blocked people, every event
 * of theirs is deferred on every run too. No server-scheduled reminder, ever. Silently.
 *
 * So these drive the REAL engine against a REAL database and assert on the surface the UI
 * reads, rather than on `Result.blocked`, which the chunking tests already cover and which
 * proves nothing about anybody ever seeing it.
 */
class BlockedRowsAreSurfacedTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
        WishKaroDb.Schema.create(it)
    }
    private val db = WishKaroDb(driver)
    private val local = LocalStore(db)

    @AfterTest
    fun close() = driver.close()

    /** Refuses one person by name, the way `ZodValidationPipe` does for an over-long field. */
    private class RefusesOneRow(private val refuseName: String) : SyncApi {
        override suspend fun syncPush(body: SyncPushRequest): SyncPushResponse {
            val index = body.people.indexOfFirst { it.displayName == refuseName }
            if (index >= 0) {
                throw ApiException(
                    400,
                    "VALIDATION_FAILED",
                    "Request body failed validation",
                    listOf(
                        ApiErrorIssue(
                            "people.$index.notes",
                            "String must contain at most 2000 character(s)",
                            "too_big",
                        ),
                    ),
                )
            }
            return SyncPushResponse(serverTime = "2026-11-26T07:00:00.000Z")
        }

        override suspend fun syncPull(cursor: String?) =
            SyncPullResponse(cursor = "c", hasMore = false, serverTime = null)

        override suspend fun reportDelivery(body: DeliveryReportRequest) =
            DeliveryReportResponse(recorded = 0, unknown = 0)
    }

    private suspend fun addPerson(id: String, name: String) {
        local.addPersonLocally(
            personId = id,
            eventId = "e-$id",
            displayName = name,
            relation = "Friend",
            type = EventType.BIRTHDAY,
            label = null,
            date = EventDate(4, 1, 1990),
            nowIso = "2026-11-26T06:00:00.000Z",
        )
    }

    @Test
    fun a_row_the_server_refuses_shows_up_on_the_surface_the_UI_reads() = runTest {
        addPerson("p1", "Aarav Shah")
        addPerson("p2", "Meera Raghunathan")

        SyncEngine(RefusesOneRow("Meera Raghunathan"), local).sync().getOrThrow()

        // The two calls the people list is built from. Before the fix nothing called either,
        // and both of these would have been correct and unread.
        assertEquals(1L, local.observeBlockedCount().first(), "the banner's count")
        val shown = local.blockedRows()
        assertTrue(shown.isNotEmpty(), "and the list behind it is not empty")
        assertEquals("Meera Raghunathan", shown.single().personName, "named, so the user can find them")
        assertTrue(
            "2000 character" in shown.single().reason,
            "with the server's OWN message, which is the only thing that says what to change",
        )
    }

    @Test
    fun nothing_is_surfaced_when_nothing_was_refused() = runTest {
        addPerson("p1", "Aarav Shah")

        SyncEngine(RefusesOneRow("Somebody Else"), local).sync().getOrThrow()

        assertEquals(0L, local.observeBlockedCount().first())
        assertTrue(local.blockedRows().isEmpty(), "no banner on a clean sync")
    }

    /**
     * The consequence the banner is warning about, pinned so it cannot quietly change: a
     * blocked person stops syncing, and so does everything that hangs off them.
     */
    @Test
    fun a_blocked_person_and_their_events_leave_the_drain() = runTest {
        addPerson("p1", "Aarav Shah")
        addPerson("p2", "Meera Raghunathan")
        val api = RefusesOneRow("Meera Raghunathan")

        SyncEngine(api, local).sync().getOrThrow()
        SyncEngine(api, local).sync().getOrThrow()

        assertTrue(
            local.pendingPeople(100).none { it.display_name == "Meera Raghunathan" },
            "the blocked person is not retried for ever",
        )
        assertTrue(
            local.pendingEvents(100).isEmpty(),
            "and her birthday is held back too, because she will never reach the server",
        )
        assertEquals(1L, local.observeBlockedCount().first(), "which is exactly why it is said out loud")
    }

    /** Editing the row clears the block, which is what the banner tells the user to do. */
    @Test
    fun editing_a_blocked_person_re_queues_them() = runTest {
        addPerson("p1", "Meera Raghunathan")
        SyncEngine(RefusesOneRow("Meera Raghunathan"), local).sync().getOrThrow()
        assertEquals(1L, local.observeBlockedCount().first())

        local.updatePersonLocally(
            personId = "p1",
            displayName = "Meera Raghunathan",
            relation = "Aunt",
            gender = null,
            phone = null,
            email = null,
            notes = "short enough now",
            isDeceased = false,
            nowIso = "2026-11-26T08:00:00.000Z",
        )

        assertEquals(0L, local.observeBlockedCount().first(), "the banner goes away")
        assertTrue(local.pendingPeople(100).any { it.id == "p1" }, "and the row is back in the drain")
    }
}
