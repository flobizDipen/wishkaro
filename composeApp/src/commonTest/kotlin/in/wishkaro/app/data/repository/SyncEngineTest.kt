package `in`.wishkaro.app.data.repository

import `in`.wishkaro.app.data.local.RemoteEvent
import `in`.wishkaro.app.data.local.RemotePerson
import `in`.wishkaro.app.data.local.RemoteTrait
import `in`.wishkaro.app.data.remote.DeliveryReportDto
import `in`.wishkaro.app.data.remote.DeliveryReportRequest
import `in`.wishkaro.app.data.remote.DeliveryReportResponse
import `in`.wishkaro.app.domain.ReminderSettings
import `in`.wishkaro.app.data.remote.SyncApplied
import `in`.wishkaro.app.data.remote.SyncPullResponse
import `in`.wishkaro.app.data.remote.SyncPushRequest
import `in`.wishkaro.app.data.remote.SyncPushResponse
import `in`.wishkaro.app.data.remote.SyncSkipped
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * What the sync engine does with the server's answer.
 *
 * The outbox used to be cleared unconditionally: everything sent had its `pending_sync` flag
 * dropped whether the server took it or not. That was defended on the grounds that a record
 * rejected as STALE is settled anyway, because the server's copy is newer.
 *
 * M1 broke that defence. `PersonDerivationService` bumps `updated_at` from server-side
 * recomputation alone — a completeness backfill, a child turning eighteen — so a genuine
 * offline edit can come back STALE with no competing edit behind it at all. Cleared there, it
 * is gone: no retry, no error, nothing on screen. On the offline-first path that is M1's
 * whole point.
 *
 * These tests drive the real [SyncEngine] against a fake server and a fake outbox, which is
 * what the two interfaces in SyncContracts.kt exist for — neither `WishKaroApi` nor
 * `LocalStore` can be built in `commonTest`.
 */
class SyncEngineTest {

    private val serverTime = "2026-11-25T10:16:21.410Z"

    // The stamp a rejected row should come back with: one millisecond past the server's clock,
    // which is strictly newer than anything the server can currently be holding.
    private val expectedRetryStamp = "2026-11-25T10:16:21.411Z"

    @Test
    fun a_clean_push_clears_the_whole_outbox() = runTest {
        val store = FakeStore(people = listOf("p1", "p2"), events = listOf("e1"))
        val api = FakeApi(SyncPushResponse(serverTime = serverTime))

        SyncEngine(api, store).sync().getOrThrow()

        assertEquals(setOf("p1", "p2"), store.clearedPeople)
        assertEquals(setOf("e1"), store.clearedEvents)
        assertEquals(emptyMap(), store.restampedPeople)
    }

    /** The finding. A rejected record must not leave the outbox. */
    @Test
    fun a_record_the_server_rejected_stays_pending() = runTest {
        val store = FakeStore(people = listOf("p1", "p2"))
        val api = FakeApi(
            SyncPushResponse(
                skipped = listOf(SyncSkipped("p2", "person", "STALE")),
                serverTime = serverTime,
            ),
        )

        SyncEngine(api, store).sync().getOrThrow()

        assertEquals(setOf("p1"), store.clearedPeople, "only the accepted record is settled")
        assertTrue("p2" in store.stillPending, "the rejected edit must survive to be retried")
    }

    /**
     * And it must come back with a timestamp that can actually win. Left as it was, the row
     * would be rejected again on every sync for as long as the app is installed.
     */
    @Test
    fun a_stale_record_is_restamped_past_the_servers_clock() = runTest {
        val store = FakeStore(people = listOf("p1"))
        val api = FakeApi(
            SyncPushResponse(
                skipped = listOf(SyncSkipped("p1", "person", "STALE")),
                serverTime = serverTime,
            ),
        )

        SyncEngine(api, store).sync().getOrThrow()

        assertEquals(mapOf("p1" to expectedRetryStamp), store.restampedPeople)
        assertTrue(
            Instant.parse(expectedRetryStamp) > Instant.parse(serverTime),
            "the retry stamp has to be strictly newer or the next push is STALE too",
        )
    }

    /**
     * The device clock is not used when the server told us its own. A handset running behind
     * the server would otherwise re-stamp into the past and bounce forever.
     */
    @Test
    fun the_retry_stamp_ignores_a_device_clock_that_is_behind() = runTest {
        val store = FakeStore(people = listOf("p1"))
        val api = FakeApi(
            SyncPushResponse(
                skipped = listOf(SyncSkipped("p1", "person", "STALE")),
                serverTime = serverTime,
            ),
        )
        val wayBehind = Instant.parse("2020-01-01T00:00:00Z")

        SyncEngine(api, store, now = { wayBehind }).sync().getOrThrow()

        assertEquals(mapOf("p1" to expectedRetryStamp), store.restampedPeople)
    }

    /** Only if the server said nothing does the device clock stand in. */
    @Test
    fun the_device_clock_is_the_fallback_when_the_server_gives_no_time() = runTest {
        val store = FakeStore(events = listOf("e1"))
        val api = FakeApi(
            SyncPushResponse(skipped = listOf(SyncSkipped("e1", "event", "STALE"))),
        )
        val fixed = Instant.parse("2026-11-25T12:00:00Z")

        SyncEngine(api, store, now = { fixed }).sync().getOrThrow()

        assertEquals(mapOf("e1" to "2026-11-25T12:00:00.001Z"), store.restampedEvents)
    }

    /**
     * UNKNOWN_PERSON is not a losing race — the parent person simply has not landed yet.
     * The row waits, unchanged; a new timestamp would not make the person exist.
     */
    @Test
    fun an_event_whose_person_is_missing_waits_without_being_restamped() = runTest {
        val store = FakeStore(events = listOf("e1", "e2"))
        val api = FakeApi(
            SyncPushResponse(
                skipped = listOf(SyncSkipped("e1", "event", "UNKNOWN_PERSON")),
                serverTime = serverTime,
            ),
        )

        SyncEngine(api, store).sync().getOrThrow()

        assertEquals(setOf("e2"), store.clearedEvents)
        assertTrue("e1" in store.stillPending)
        assertEquals(emptyMap(), store.restampedEvents, "nothing to re-assert against")
    }

    /**
     * NOT_OWNED is the one rejection that never resolves. Holding it would re-push the same
     * record on every sync for the life of the install.
     */
    @Test
    fun a_record_owned_by_another_account_is_dropped_rather_than_retried() = runTest {
        val store = FakeStore(traits = listOf("t1"))
        val api = FakeApi(
            SyncPushResponse(
                skipped = listOf(SyncSkipped("t1", "trait", "NOT_OWNED")),
                serverTime = serverTime,
            ),
        )

        SyncEngine(api, store).sync().getOrThrow()

        assertEquals(setOf("t1"), store.clearedTraits)
        assertEquals(emptyMap(), store.restampedTraits)
    }

    /** Rejections are reported upward instead of being swallowed. */
    @Test
    fun the_result_carries_what_the_server_refused() = runTest {
        val skipped = listOf(
            SyncSkipped("p1", "person", "STALE"),
            SyncSkipped("e1", "event", "UNKNOWN_PERSON"),
        )
        val store = FakeStore(people = listOf("p1"), events = listOf("e1"))

        val result = SyncEngine(FakeApi(SyncPushResponse(skipped = skipped, serverTime = serverTime)), store)
            .sync()
            .getOrThrow()

        assertEquals(skipped, result.rejected)
        assertEquals(2, result.pushed.total)
    }

    /**
     * A reason this build has never heard of settles like an acknowledgement rather than
     * sticking in the outbox. A newer server inventing a rejection code must not be able to
     * wedge an old client into pushing the same row forever.
     */
    @Test
    fun an_unrecognised_rejection_reason_does_not_wedge_the_outbox() = runTest {
        val store = FakeStore(people = listOf("p1"))
        val api = FakeApi(
            SyncPushResponse(
                skipped = listOf(SyncSkipped("p1", "person", "SOMETHING_NEW")),
                serverTime = serverTime,
            ),
        )

        SyncEngine(api, store).sync().getOrThrow()

        assertEquals(setOf("p1"), store.clearedPeople)
    }

    /** A rejection against one entity must not settle another's rows. */
    @Test
    fun rejections_are_matched_to_their_own_entity() = runTest {
        val store = FakeStore(people = listOf("x"), events = listOf("x"), traits = listOf("x"))
        val api = FakeApi(
            SyncPushResponse(
                // Same id in all three tables — ids are client-generated, so this is possible.
                skipped = listOf(SyncSkipped("x", "event", "STALE")),
                serverTime = serverTime,
            ),
        )

        SyncEngine(api, store).sync().getOrThrow()

        assertEquals(setOf("x"), store.clearedPeople)
        assertEquals(setOf("x"), store.clearedTraits)
        assertEquals(emptySet(), store.clearedEvents)
        assertEquals(mapOf("x" to expectedRetryStamp), store.restampedEvents)
    }

    @Test
    fun an_empty_outbox_talks_to_nobody() = runTest {
        val store = FakeStore()
        val api = FakeApi(SyncPushResponse(serverTime = serverTime))

        SyncEngine(api, store).sync().getOrThrow()

        assertEquals(0, api.pushes, "an empty push is a wasted round trip on mobile data")
    }
}

// -------------------------------------------------------------------------------------
// Fakes
// -------------------------------------------------------------------------------------

private class FakeApi(
    private val pushResponse: SyncPushResponse,
    /** Lets a test make the metrics endpoint fail without failing the whole sync. */
    private val deliveryFails: Boolean = false,
) : SyncApi {
    var pushes = 0
        private set

    val deliveryBatches = mutableListOf<List<DeliveryReportDto>>()

    override suspend fun syncPush(body: SyncPushRequest): SyncPushResponse {
        pushes += 1
        return pushResponse
    }

    override suspend fun syncPull(cursor: String?): SyncPullResponse =
        SyncPullResponse(cursor = "c1", hasMore = false)

    override suspend fun reportDelivery(body: DeliveryReportRequest): DeliveryReportResponse {
        if (deliveryFails) throw IllegalStateException("delivery endpoint down")
        deliveryBatches += body.reports
        return DeliveryReportResponse(recorded = body.reports.size)
    }
}

/**
 * An outbox that records what was asked of it.
 *
 * `stillPending` is the question the finding was really about: which ids the engine left for
 * the next run.
 */
private class FakeStore(
    people: List<String> = emptyList(),
    events: List<String> = emptyList(),
    traits: List<String> = emptyList(),
) : SyncStore {

    private val pendingPeopleIds = people.toMutableList()
    private val pendingEventIds = events.toMutableList()
    private val pendingTraitIds = traits.toMutableList()

    val clearedPeople = mutableSetOf<String>()
    val clearedEvents = mutableSetOf<String>()
    val clearedTraits = mutableSetOf<String>()

    val restampedPeople = mutableMapOf<String, String>()
    val restampedEvents = mutableMapOf<String, String>()
    val restampedTraits = mutableMapOf<String, String>()

    /** Everything sent that the engine did not clear. */
    val stillPending: Set<String>
        get() = (pendingPeopleIds + pendingEventIds + pendingTraitIds).toSet() -
            (clearedPeople + clearedEvents + clearedTraits)

    /** Blocked rows, by id, with the server's reason. */
    val blocked = mutableMapOf<String, String>()

    override suspend fun pendingPersonIds(): Set<String> = pendingPeopleIds.toSet()

    override suspend fun blockPerson(id: String, reason: String) {
        blocked[id] = reason
    }

    override suspend fun blockEvent(id: String, reason: String) {
        blocked[id] = reason
    }

    override suspend fun blockTrait(id: String, reason: String) {
        blocked[id] = reason
    }

    override suspend fun pendingPeople(limit: Long): List<wishkaro.db.Person> =
        pendingPeopleIds.take(limit.toInt()).map { id ->
            wishkaro.db.Person(
                id = id,
                display_name = "Person $id",
                relation_to_owner = null,
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
        }

    override suspend fun pendingEvents(limit: Long): List<wishkaro.db.Event> =
        pendingEventIds.take(limit.toInt()).map { id ->
            wishkaro.db.Event(
                id = id,
                person_id = "p1",
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
        }

    override suspend fun pendingTraits(limit: Long): List<wishkaro.db.Person_trait> =
        pendingTraitIds.take(limit.toInt()).map { id ->
            wishkaro.db.Person_trait(
                id = id,
                person_id = "p1",
                kind = "HOBBY",
                value_ = "Cricket",
                source = "USER",
                updated_at = "2026-11-25T10:00:00Z",
                deleted_at = null,
                pending_sync = 1,
                sync_blocked = 0,
                sync_block_reason = null,
            )
        }

    override suspend fun clearPersonPending(ids: List<String>) {
        clearedPeople += ids
    }

    override suspend fun clearEventPending(ids: List<String>) {
        clearedEvents += ids
    }

    override suspend fun clearTraitPending(ids: List<String>) {
        clearedTraits += ids
    }

    override suspend fun restampPersonPending(ids: List<String>, updatedAt: String) {
        ids.forEach { restampedPeople[it] = updatedAt }
    }

    override suspend fun restampEventPending(ids: List<String>, updatedAt: String) {
        ids.forEach { restampedEvents[it] = updatedAt }
    }

    override suspend fun restampTraitPending(ids: List<String>, updatedAt: String) {
        ids.forEach { restampedTraits[it] = updatedAt }
    }

    override suspend fun applyRemote(
        remotePeople: List<RemotePerson>,
        remoteEvents: List<RemoteEvent>,
        remoteTraits: List<RemoteTrait>,
        cursor: String,
    ) = Unit

    override suspend fun cursor(): String? = null

    // ---- delivery-report outbox ----

    val pendingDelivery = mutableListOf<wishkaro.db.Delivery_report>()
    val clearedDelivery = mutableSetOf<String>()

    override suspend fun pendingDeliveryReports(limit: Long): List<wishkaro.db.Delivery_report> =
        pendingDelivery.filter { it.pending_sync == 1L }.take(limit.toInt())

    override suspend fun clearDeliveryPending(keys: List<String>) {
        clearedDelivery += keys
        pendingDelivery.replaceAll {
            if (it.dedupe_key in keys) it.copy(pending_sync = 0L) else it
        }
    }

    // ---- reminder settings ----

    var settings = ReminderSettings()
    val remoteSettingsApplied = mutableListOf<ReminderSettings>()
    var settingsPendingCleared = false

    override suspend fun reminderSettings(): ReminderSettings = settings

    override suspend fun applyRemoteSettings(remote: ReminderSettings): Boolean {
        remoteSettingsApplied += remote
        settings = remote
        return true
    }

    override suspend fun clearSettingsPending() {
        settingsPendingCleared = true
        settings = settings.copy(pendingSync = false)
    }
}

private fun report(key: String, outcome: String = "SHOWN", channel: String = "LOCAL") =
    wishkaro.db.Delivery_report(
        dedupe_key = key,
        outcome = outcome,
        channel = channel,
        occurred_at = "2026-12-02T03:30:00Z",
        pending_sync = 1L,
    )

/**
 * FR-REM-10. The delivery outbox is the instrument the rest of M2 is measured with, and it
 * is written in the conditions that make it hardest to send — a local alarm firing on a phone
 * that has been off the network for days. These are about what happens to a report that
 * cannot be delivered immediately.
 */
class DeliveryReportDrainTest {

    @Test
    fun handsPendingReportsToTheServerAndClearsThem() = runTest {
        val api = FakeApi(SyncPushResponse())
        val store = FakeStore()
        store.pendingDelivery += report("e1:2026:D0")
        store.pendingDelivery += report("e2:2026:D7", outcome = "SUPPRESSED_DUPLICATE", channel = "FCM")

        val result = SyncEngine(api, store) { Instant.parse("2026-12-02T04:00:00Z") }.sync()

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrThrow().deliveryReported)
        assertEquals(listOf("e1:2026:D0", "e2:2026:D7"), api.deliveryBatches.single().map { it.dedupeKey })
        assertEquals(setOf("e1:2026:D0", "e2:2026:D7"), store.clearedDelivery)
    }

    @Test
    fun sendsNothingWhenThereIsNothingToReport() = runTest {
        val api = FakeApi(SyncPushResponse())
        val store = FakeStore()

        SyncEngine(api, store) { Instant.parse("2026-12-02T04:00:00Z") }.sync()

        assertTrue(api.deliveryBatches.isEmpty())
    }

    /**
     * A report that was never acknowledged must stay pending. Re-sending is free — the server
     * is idempotent on the dedupe key — whereas clearing it optimistically deletes a data
     * point from the one number that says whether this product works.
     */
    @Test
    fun keepsReportsPendingWhenTheServerRefusesThem() = runTest {
        val api = FakeApi(SyncPushResponse(), deliveryFails = true)
        val store = FakeStore()
        store.pendingDelivery += report("e1:2026:D0")

        val result = SyncEngine(api, store) { Instant.parse("2026-12-02T04:00:00Z") }.sync()

        assertTrue(result.isSuccess, "a dead metrics endpoint must not fail the whole sync")
        assertEquals(0, result.getOrThrow().deliveryReported)
        assertTrue(store.clearedDelivery.isEmpty())
        assertEquals(1L, store.pendingDelivery.single().pending_sync)
    }

    /** The user's data is worth more than the metric: a dead endpoint cannot block a push. */
    @Test
    fun aFailedReportStillLetsTheOutboxPush() = runTest {
        val api = FakeApi(SyncPushResponse(applied = SyncApplied(people = 1)), deliveryFails = true)
        val store = FakeStore(people = listOf("p1"))
        store.pendingDelivery += report("e1:2026:D0")

        val result = SyncEngine(api, store) { Instant.parse("2026-12-02T04:00:00Z") }.sync()

        assertEquals(1, api.pushes)
        assertTrue(result.getOrThrow().pushed.people == 1)
    }
}
