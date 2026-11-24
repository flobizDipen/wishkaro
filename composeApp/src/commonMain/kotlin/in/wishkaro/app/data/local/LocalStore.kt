package `in`.wishkaro.app.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import `in`.wishkaro.app.domain.EventDate
import `in`.wishkaro.app.domain.Person
import `in`.wishkaro.app.domain.PersonEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import wishkaro.db.WishKaroDb

/**
 * SQLDelight is the read source of truth (D23).
 *
 * Every screen reads from here, so the app renders with the radio off. Writes land locally
 * first and are marked `pending_sync`; the sync engine drains them later.
 */
class LocalStore(private val db: WishKaroDb) {

    private val people = db.personQueries
    private val events = db.eventQueries
    private val sync = db.syncStateQueries
    private val seen = db.seenReminderQueries

    /** People with their events, as a live query. Updates on every local or remote write. */
    fun observePeople(): Flow<List<Person>> {
        val peopleFlow = people.selectAll().asFlow().mapToList(Dispatchers.Default)
        val eventsFlow = events.selectAll().asFlow().mapToList(Dispatchers.Default)
        return combine(peopleFlow, eventsFlow) { personRows, eventRows ->
            val byPerson = eventRows.groupBy { it.person_id }
            personRows.map { row ->
                Person(
                    id = row.id,
                    displayName = row.display_name,
                    relationToOwner = row.relation_to_owner,
                    isSelf = row.is_self != 0L,
                    events = byPerson[row.id].orEmpty().map { e ->
                        PersonEvent(
                            id = e.id,
                            personId = e.person_id,
                            type = e.type,
                            date = EventDate(e.day.toInt(), e.month.toInt(), e.year?.toInt()),
                        )
                    },
                )
            }
        }
    }

    suspend fun findPerson(id: String): Person? = withContext(Dispatchers.Default) {
        val row = people.selectById(id).executeAsOneOrNull() ?: return@withContext null
        Person(
            id = row.id,
            displayName = row.display_name,
            relationToOwner = row.relation_to_owner,
            isSelf = row.is_self != 0L,
            events = events.selectForPerson(id).executeAsList().map { e ->
                PersonEvent(
                    id = e.id,
                    personId = e.person_id,
                    type = e.type,
                    date = EventDate(e.day.toInt(), e.month.toInt(), e.year?.toInt()),
                )
            },
        )
    }

    /** Writes a person and their birthday locally, queued for sync. One transaction. */
    suspend fun addPersonLocally(
        personId: String,
        eventId: String,
        displayName: String,
        relation: String?,
        date: EventDate,
        nowIso: String,
    ) = withContext(Dispatchers.Default) {
        db.transaction {
            people.insertOrReplace(
                id = personId,
                display_name = displayName,
                relation_to_owner = relation,
                is_self = 0L,
                updated_at = nowIso,
                deleted_at = null,
                pending_sync = 1L,
            )
            events.insertOrReplace(
                id = eventId,
                person_id = personId,
                type = "BIRTHDAY",
                day = date.day.toLong(),
                month = date.month.toLong(),
                year = date.year?.toLong(),
                updated_at = nowIso,
                deleted_at = null,
                pending_sync = 1L,
            )
        }
    }

    // ---- sync plumbing ----

    suspend fun pendingPeople() = withContext(Dispatchers.Default) {
        people.selectPendingSync().executeAsList()
    }

    suspend fun pendingEvents() = withContext(Dispatchers.Default) {
        events.selectPendingSync().executeAsList()
    }

    suspend fun clearPersonPending(ids: List<String>) = withContext(Dispatchers.Default) {
        db.transaction { ids.forEach(people::clearPendingSync) }
    }

    suspend fun clearEventPending(ids: List<String>) = withContext(Dispatchers.Default) {
        db.transaction { ids.forEach(events::clearPendingSync) }
    }

    /**
     * Applies a page pulled from the server.
     *
     * Last-write-wins on `updated_at`, resolved here rather than in SQL because UPSERT needs
     * SQLite 3.24 and minSdk is 26. A row still flagged `pending_sync` is never overwritten:
     * the local edit has not reached the server yet, so the server's copy is by definition
     * the older one.
     */
    suspend fun applyRemote(
        remotePeople: List<RemotePerson>,
        remoteEvents: List<RemoteEvent>,
        cursor: String,
    ) = withContext(Dispatchers.Default) {
        db.transaction {
            remotePeople.forEach { remote ->
                val local = people.selectById(remote.id).executeAsOneOrNull()
                if (local != null && (local.pending_sync != 0L || local.updated_at >= remote.updatedAt)) {
                    return@forEach
                }
                people.insertOrReplace(
                    id = remote.id,
                    display_name = remote.displayName,
                    relation_to_owner = remote.relationToOwner,
                    is_self = if (remote.isSelf) 1L else 0L,
                    updated_at = remote.updatedAt,
                    deleted_at = remote.deletedAt,
                    pending_sync = 0L,
                )
            }
            remoteEvents.forEach { remote ->
                val local = events.selectById(remote.id).executeAsOneOrNull()
                if (local != null && (local.pending_sync != 0L || local.updated_at >= remote.updatedAt)) {
                    return@forEach
                }
                events.insertOrReplace(
                    id = remote.id,
                    person_id = remote.personId,
                    type = remote.type,
                    day = remote.day.toLong(),
                    month = remote.month.toLong(),
                    year = remote.year?.toLong(),
                    updated_at = remote.updatedAt,
                    deleted_at = remote.deletedAt,
                    pending_sync = 0L,
                )
            }
            sync.setCursor(cursor)
        }
    }

    suspend fun cursor(): String? = withContext(Dispatchers.Default) {
        sync.selectCursor().executeAsOneOrNull()?.cursor
    }

    // ---- notification de-duplication ----

    /** True the FIRST time a dedupe key is seen; false on every repeat (FR-REM-02). */
    suspend fun claimReminder(dedupeKey: String, nowIso: String): Boolean =
        withContext(Dispatchers.Default) {
            db.transactionWithResult {
                val already = seen.wasSeen(dedupeKey).executeAsOne()
                if (already) {
                    false
                } else {
                    seen.markSeen(dedupeKey, nowIso)
                    true
                }
            }
        }

    /** Sign-out wipes local data (FR-AUTH-07). */
    suspend fun wipe() = withContext(Dispatchers.Default) {
        db.transaction {
            events.deleteAll()
            people.deleteAll()
            seen.deleteAll()
            sync.reset()
        }
    }
}

data class RemotePerson(
    val id: String,
    val displayName: String,
    val relationToOwner: String?,
    val isSelf: Boolean,
    val updatedAt: String,
    val deletedAt: String?,
)

data class RemoteEvent(
    val id: String,
    val personId: String,
    val type: String,
    val day: Int,
    val month: Int,
    val year: Int?,
    val updatedAt: String,
    val deletedAt: String?,
)
