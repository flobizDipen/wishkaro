package `in`.wishkaro.app.data.repository

import `in`.wishkaro.app.data.local.RemoteEvent
import `in`.wishkaro.app.data.local.RemotePerson
import `in`.wishkaro.app.data.local.RemoteTrait
import `in`.wishkaro.app.data.local.RemoteWish
import `in`.wishkaro.app.domain.ReminderSettings
import `in`.wishkaro.app.data.remote.DeliveryReportRequest
import `in`.wishkaro.app.data.remote.DeliveryReportResponse
import `in`.wishkaro.app.data.remote.SyncPullResponse
import `in`.wishkaro.app.data.remote.SyncPushRequest
import `in`.wishkaro.app.data.remote.SyncPushResponse

/**
 * The two seams [SyncEngine] works through.
 *
 * Narrow on purpose. `WishKaroApi` owns token refresh and Ktor, and `LocalStore` owns a
 * SQLDelight driver — neither can be stood up in `commonTest`, which is the only test source
 * set this module has. Expressed as interfaces, the part that actually carries risk (what the
 * engine does with the server's answer) is testable with two hand-written fakes and no
 * database at all.
 *
 * They are not a general abstraction over storage or HTTP, and nothing but the sync engine
 * should take them: `LocalStore` remains the type every other caller names.
 */
interface SyncApi {
    suspend fun syncPull(cursor: String?): SyncPullResponse

    suspend fun syncPush(body: SyncPushRequest): SyncPushResponse

    /** FR-REM-10. Drained by the same engine, for the same reason: it needs a network. */
    suspend fun reportDelivery(body: DeliveryReportRequest): DeliveryReportResponse
}

/** The outbox, plus the one write that applies a pulled page. */
interface SyncStore {
    /**
     * One bounded, ordered snapshot of the outbox — oldest first, blocked rows excluded.
     *
     * [limit] is not a page size. The engine takes the whole snapshot and then pages it in
     * memory, because paging the SQL instead would re-read a table that the previous page's
     * settlement has just changed underneath it. See [SyncEngine.push].
     */
    suspend fun pendingPeople(limit: Long): List<wishkaro.db.Person>

    suspend fun pendingEvents(limit: Long): List<wishkaro.db.Event>

    suspend fun pendingTraits(limit: Long): List<wishkaro.db.Person_trait>

    /** M5. The fourth entity, drained exactly like the other three. */
    suspend fun pendingWishes(limit: Long): List<wishkaro.db.Wish>

    /**
     * Ids of every person with unsent changes, blocked ones included and without a limit.
     *
     * Used for one question only: is this event's person still waiting to be pushed? An
     * event whose person is pending but did not make it into this snapshot must wait too —
     * sending it would earn a guaranteed UNKNOWN_PERSON. Cheap because it is ids only.
     */
    suspend fun pendingPersonIds(): Set<String>

    /**
     * Set a row aside: the server named it and refused it, so it leaves the drain while
     * keeping its pending flag. [reason] is the server's own message, shown to the user.
     */
    suspend fun blockPerson(id: String, reason: String)

    suspend fun blockEvent(id: String, reason: String)

    suspend fun blockTrait(id: String, reason: String)

    suspend fun blockWish(id: String, reason: String)

    suspend fun clearPersonPending(ids: List<String>)

    suspend fun clearEventPending(ids: List<String>)

    suspend fun clearTraitPending(ids: List<String>)

    suspend fun clearWishPending(ids: List<String>)

    /**
     * Keep these rows in the outbox but move their `updated_at` to [updatedAt], so the next
     * push carries a timestamp the server will accept.
     *
     * See [SyncEngine.push] for when this is the right answer and when it is not.
     */
    suspend fun restampPersonPending(ids: List<String>, updatedAt: String)

    suspend fun restampEventPending(ids: List<String>, updatedAt: String)

    suspend fun restampTraitPending(ids: List<String>, updatedAt: String)

    suspend fun restampWishPending(ids: List<String>, updatedAt: String)

    suspend fun applyRemote(
        remotePeople: List<RemotePerson>,
        remoteEvents: List<RemoteEvent>,
        remoteTraits: List<RemoteTrait>,
        remoteWishes: List<RemoteWish>,
        cursor: String,
    )

    suspend fun cursor(): String?

    // ---- delivery-report outbox (FR-REM-10) ----

    suspend fun pendingDeliveryReports(limit: Long = 200): List<wishkaro.db.Delivery_report>

    suspend fun clearDeliveryPending(keys: List<String>)

    // ---- reminder settings (FR-REM-03) ----

    suspend fun reminderSettings(): ReminderSettings

    /** True when the server's copy was taken. False means the local edit is still newer. */
    suspend fun applyRemoteSettings(remote: ReminderSettings): Boolean

    suspend fun clearSettingsPending()
}
