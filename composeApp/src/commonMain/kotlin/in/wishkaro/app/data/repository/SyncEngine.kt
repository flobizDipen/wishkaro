package `in`.wishkaro.app.data.repository

import `in`.wishkaro.app.data.local.LocalStore
import `in`.wishkaro.app.data.local.RemoteEvent
import `in`.wishkaro.app.data.local.RemotePerson
import `in`.wishkaro.app.data.remote.SyncEventDto
import `in`.wishkaro.app.data.remote.SyncPersonDto
import `in`.wishkaro.app.data.remote.SyncPushRequest
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

    data class Result(
        val pushedPeople: Int = 0,
        val pushedEvents: Int = 0,
        val pulledPeople: Int = 0,
        val pulledEvents: Int = 0,
    )

    suspend fun sync(): kotlin.Result<Result> = mutex.withLock {
        runCatching {
            val pushed = push()
            val pulled = pull()
            Result(
                pushedPeople = pushed.first,
                pushedEvents = pushed.second,
                pulledPeople = pulled.first,
                pulledEvents = pulled.second,
            )
        }
    }

    private suspend fun push(): Pair<Int, Int> {
        val people = local.pendingPeople()
        val events = local.pendingEvents()
        if (people.isEmpty() && events.isEmpty()) return 0 to 0

        api.syncPush(
            SyncPushRequest(
                people = people.map {
                    SyncPersonDto(
                        id = it.id,
                        displayName = it.display_name,
                        relationToOwner = it.relation_to_owner,
                        isSelf = it.is_self != 0L,
                        updatedAt = it.updated_at,
                        deletedAt = it.deleted_at,
                    )
                },
                events = events.map {
                    SyncEventDto(
                        id = it.id,
                        personId = it.person_id,
                        type = it.type,
                        day = it.day.toInt(),
                        month = it.month.toInt(),
                        year = it.year?.toInt(),
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
        return people.size to events.size
    }

    private suspend fun pull(): Pair<Int, Int> {
        var people = 0
        var events = 0
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
                        updatedAt = it.updatedAt,
                        deletedAt = it.deletedAt,
                    )
                },
                remoteEvents = page.events.map {
                    RemoteEvent(
                        id = it.id,
                        personId = it.personId,
                        type = it.type,
                        day = it.day,
                        month = it.month,
                        year = it.year,
                        updatedAt = it.updatedAt,
                        deletedAt = it.deletedAt,
                    )
                },
                cursor = page.cursor,
            )
            people += page.people.size
            events += page.events.size
            cursor = page.cursor
            if (!page.hasMore) return people to events
        }
        return people to events
    }

    private companion object {
        const val MAX_PAGES = 50
    }
}
