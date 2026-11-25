package `in`.wishkaro.app.data.repository

import `in`.wishkaro.app.data.local.LocalStore
import `in`.wishkaro.app.data.local.RemoteEvent
import `in`.wishkaro.app.data.local.RemotePerson
import `in`.wishkaro.app.data.local.RemoteTrait
import `in`.wishkaro.app.data.remote.SyncEventPush
import `in`.wishkaro.app.data.remote.SyncPersonPush
import `in`.wishkaro.app.data.remote.SyncPushRequest
import `in`.wishkaro.app.data.remote.SyncTraitPush
import `in`.wishkaro.app.data.remote.WishKaroApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Push local changes, then pull remote ones.
 *
 * Push first, deliberately: pulling first would hand the device a server copy of a record it
 * has already edited locally, and last-write-wins would then have to arbitrate between two
 * versions of the same change. Pushing first means the server already knows.
 *
 * A mutex serialises runs. Sync is triggered from several places — app start, after a write,
 * after a push arrives — and two concurrent runs would double-push the same outbox rows.
 */
class SyncEngine(
    private val api: WishKaroApi,
    private val local: LocalStore,
) {
    private val mutex = Mutex()

    data class Counts(val people: Int = 0, val events: Int = 0, val traits: Int = 0) {
        val total: Int get() = people + events + traits
    }

    data class Result(val pushed: Counts = Counts(), val pulled: Counts = Counts())

    suspend fun sync(): kotlin.Result<Result> = mutex.withLock {
        runCatching { Result(pushed = push(), pulled = pull()) }
    }

    private suspend fun push(): Counts {
        val people = local.pendingPeople()
        val events = local.pendingEvents()
        val traits = local.pendingTraits()
        if (people.isEmpty() && events.isEmpty() && traits.isEmpty()) return Counts()

        // The push DTOs omit every server-derived field. The server's write schemas are
        // .strict(), so including `sentiment`, `isMinor` or `completenessScore` would make
        // each sync a 400 — and silently dropping them, which is what a lenient schema used
        // to do, would hide a client bug rather than report it.
        api.syncPush(
            SyncPushRequest(
                people = people.map {
                    SyncPersonPush(
                        id = it.id,
                        displayName = it.display_name,
                        relationToOwner = it.relation_to_owner,
                        isSelf = it.is_self != 0L,
                        gender = it.gender,
                        phone = it.phone,
                        email = it.email,
                        notes = it.notes,
                        isDeceased = it.is_deceased != 0L,
                        updatedAt = it.updated_at,
                        deletedAt = it.deleted_at,
                    )
                },
                events = events.map {
                    SyncEventPush(
                        id = it.id,
                        personId = it.person_id,
                        type = it.type,
                        label = it.label,
                        isMuted = it.is_muted != 0L,
                        day = it.day.toInt(),
                        month = it.month.toInt(),
                        year = it.year?.toInt(),
                        updatedAt = it.updated_at,
                        deletedAt = it.deleted_at,
                    )
                },
                traits = traits.map {
                    SyncTraitPush(
                        id = it.id,
                        personId = it.person_id,
                        kind = it.kind,
                        value = it.value_,
                        updatedAt = it.updated_at,
                        deletedAt = it.deleted_at,
                    )
                },
            ),
        )

        // Clear the outbox only after the server has acknowledged the batch. A record the
        // server skipped as STALE is still settled — the server's copy is newer, and the
        // pull below will bring it down.
        local.clearPersonPending(people.map { it.id })
        local.clearEventPending(events.map { it.id })
        local.clearTraitPending(traits.map { it.id })
        return Counts(people.size, events.size, traits.size)
    }

    private suspend fun pull(): Counts {
        var counts = Counts()
        var cursor = local.cursor()

        // The server pages; keep going until it says there is nothing left. Bounded so a
        // server that always reports hasMore cannot spin here forever.
        repeat(MAX_PAGES) {
            val page = api.syncPull(cursor)
            local.applyRemote(
                remotePeople = page.people.map {
                    RemotePerson(
                        id = it.id,
                        displayName = it.displayName,
                        relationToOwner = it.relationToOwner,
                        isSelf = it.isSelf,
                        gender = it.gender,
                        phone = it.phone,
                        email = it.email,
                        notes = it.notes,
                        isDeceased = it.isDeceased,
                        isMinor = it.isMinor,
                        completenessScore = it.completenessScore,
                        updatedAt = it.updatedAt,
                        deletedAt = it.deletedAt,
                    )
                },
                remoteEvents = page.events.map {
                    RemoteEvent(
                        id = it.id,
                        personId = it.personId,
                        type = it.type,
                        sentiment = it.sentiment,
                        label = it.label,
                        isMuted = it.isMuted,
                        day = it.day,
                        month = it.month,
                        year = it.year,
                        updatedAt = it.updatedAt,
                        deletedAt = it.deletedAt,
                    )
                },
                remoteTraits = page.traits.map {
                    RemoteTrait(
                        id = it.id,
                        personId = it.personId,
                        kind = it.kind,
                        value = it.value,
                        source = it.source,
                        updatedAt = it.updatedAt,
                        deletedAt = it.deletedAt,
                    )
                },
                cursor = page.cursor,
            )
            counts = Counts(
                counts.people + page.people.size,
                counts.events + page.events.size,
                counts.traits + page.traits.size,
            )
            cursor = page.cursor
            if (!page.hasMore) return counts
        }
        return counts
    }

    private companion object {
        const val MAX_PAGES = 50
    }
}
