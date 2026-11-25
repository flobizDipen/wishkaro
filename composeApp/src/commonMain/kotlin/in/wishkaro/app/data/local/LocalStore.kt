package `in`.wishkaro.app.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import `in`.wishkaro.app.domain.EventDate
import `in`.wishkaro.app.domain.EventType
import `in`.wishkaro.app.domain.Person
import `in`.wishkaro.app.domain.PersonEvent
import `in`.wishkaro.app.domain.PersonTrait
import `in`.wishkaro.app.domain.Sentiment
import `in`.wishkaro.app.domain.TraitKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import wishkaro.db.WishKaroDb

/**
 * SQLDelight is the read source of truth (D23).
 *
 * Every screen reads from here, so the app renders with the radio off. Writes land locally
 * first and are marked `pending_sync`; the sync engine drains them later.
 *
 * Derived columns — `sentiment`, `is_deceased`, `is_minor`, `completeness_score` — are
 * mirrored from the server and never computed here. A local write leaves them as they are
 * and the next pull corrects them, which is why a person's tone can lag a local edit by one
 * sync but can never be invented by the client.
 */
class LocalStore(private val db: WishKaroDb) {

    private val people = db.personQueries
    private val events = db.eventQueries
    private val traits = db.personTraitQueries
    private val sync = db.syncStateQueries
    private val seen = db.seenReminderQueries

    // -----------------------------------------------------------------------------------
    // Reads
    // -----------------------------------------------------------------------------------

    /** People with their events and traits, as a live query. Updates on every write. */
    fun observePeople(): Flow<List<Person>> {
        val peopleFlow = people.selectAll().asFlow().mapToList(Dispatchers.Default)
        val eventsFlow = events.selectAll().asFlow().mapToList(Dispatchers.Default)
        val traitsFlow = traits.selectAll().asFlow().mapToList(Dispatchers.Default)
        return combine(peopleFlow, eventsFlow, traitsFlow) { personRows, eventRows, traitRows ->
            val eventsByPerson = eventRows.groupBy { it.person_id }
            val traitsByPerson = traitRows.groupBy { it.person_id }
            personRows.map { row ->
                row.toPerson(
                    eventsByPerson[row.id].orEmpty().map { it.toEvent() },
                    traitsByPerson[row.id].orEmpty().mapNotNull { it.toTrait() },
                )
            }
        }
    }

    /** One person, live. The detail screen subscribes to this and survives a force-stop. */
    fun observePerson(id: String): Flow<Person?> = observePeople().map { list ->
        list.firstOrNull { it.id == id }
    }

    /**
     * Soft-deleted people, for the restore surface (FR-PPL-08).
     *
     * `WHERE deleted_at IS NOT NULL` narrows that column's nullability, so SQLDelight
     * generates its own row type rather than reusing `Person` — hence the separate mapper
     * below instead of `toPerson`.
     */
    fun observeDeleted(): Flow<List<Person>> =
        people.selectDeleted().asFlow().mapToList(Dispatchers.Default).map { rows ->
            rows.map { row ->
                Person(
                    id = row.id,
                    displayName = row.display_name,
                    relationToOwner = row.relation_to_owner,
                    isSelf = row.is_self != 0L,
                    gender = row.gender,
                    phone = row.phone,
                    email = row.email,
                    notes = row.notes,
                    isDeceased = row.is_deceased != 0L,
                    isMinor = row.is_minor != 0L,
                    completenessScore = row.completeness_score.toInt(),
                    completenessSuggestions = row.completeness_suggestions
                        .split(",").filter { it.isNotBlank() },
                    deletedAt = row.deleted_at,
                    events = emptyList(),
                    traits = emptyList(),
                )
            }
        }

    /** A one-shot snapshot, for checks that must not subscribe — duplicate detection. */
    suspend fun allPeopleOnce(): List<Person> = withContext(Dispatchers.Default) {
        val eventsByPerson = events.selectAll().executeAsList().groupBy { it.person_id }
        people.selectAll().executeAsList().map { row ->
            row.toPerson(eventsByPerson[row.id].orEmpty().map { it.toEvent() }, emptyList())
        }
    }

    suspend fun findPerson(id: String): Person? = withContext(Dispatchers.Default) {
        val row = people.selectById(id).executeAsOneOrNull() ?: return@withContext null
        row.toPerson(
            events.selectForPerson(id).executeAsList().map { it.toEvent() },
            traits.selectForPerson(id).executeAsList().mapNotNull { it.toTrait() },
        )
    }

    // -----------------------------------------------------------------------------------
    // Writes — all local-first, all queued for sync
    // -----------------------------------------------------------------------------------

    /** Writes a person and their first event locally, queued for sync. One transaction. */
    suspend fun addPersonLocally(
        personId: String,
        eventId: String,
        displayName: String,
        relation: String?,
        type: EventType,
        label: String?,
        date: EventDate,
        nowIso: String,
    ) = withContext(Dispatchers.Default) {
        db.transaction {
            insertPerson(
                id = personId,
                displayName = displayName,
                relation = relation,
                isSelf = false,
                nowIso = nowIso,
                pending = true,
            )
            insertEvent(eventId, personId, type, label, date, nowIso, muted = false, pending = true)
        }
    }

    suspend fun updatePersonLocally(
        personId: String,
        displayName: String,
        relation: String?,
        gender: String?,
        phone: String?,
        email: String?,
        notes: String?,
        isDeceased: Boolean,
        nowIso: String,
    ) = withContext(Dispatchers.Default) {
        people.updateProfile(
            display_name = displayName,
            relation_to_owner = relation,
            gender = gender,
            phone = phone,
            email = email,
            notes = notes,
            is_deceased = if (isDeceased) 1L else 0L,
            updated_at = nowIso,
            id = personId,
        )
    }

    /**
     * Soft delete, written as a tombstone rather than a DELETE (D23).
     *
     * The events go with it so the list and the scheduler agree even before the next sync,
     * and so a deletion made offline is still a deletion when it reaches the server.
     */
    suspend fun deletePersonLocally(personId: String, nowIso: String) =
        withContext(Dispatchers.Default) {
            db.transaction {
                people.markDeleted(deleted_at = nowIso, updated_at = nowIso, id = personId)
                events.selectForPerson(personId).executeAsList().forEach {
                    events.markDeleted(deleted_at = nowIso, updated_at = nowIso, id = it.id)
                }
                traits.selectForPerson(personId).executeAsList().forEach {
                    traits.markDeleted(deleted_at = nowIso, updated_at = nowIso, id = it.id)
                }
            }
        }

    suspend fun restorePersonLocally(personId: String, nowIso: String) =
        withContext(Dispatchers.Default) {
            people.markRestored(updated_at = nowIso, id = personId)
        }

    suspend fun addEventLocally(
        eventId: String,
        personId: String,
        type: EventType,
        label: String?,
        date: EventDate,
        nowIso: String,
    ) = withContext(Dispatchers.Default) {
        // A new event on a deceased person is written muted, matching the server's default
        // (FR-EVT-05). The server re-derives it either way; doing it here as well means the
        // local UI is not briefly wrong between the write and the next sync.
        val deceased = people.selectById(personId).executeAsOneOrNull()?.is_deceased != 0L
        val muted = deceased && type == EventType.BIRTHDAY
        insertEvent(eventId, personId, type, label, date, nowIso, muted = muted, pending = true)
    }

    suspend fun updateEventLocally(
        eventId: String,
        type: EventType,
        label: String?,
        date: EventDate,
        nowIso: String,
    ) = withContext(Dispatchers.Default) {
        events.updateEvent(
            type = type.wire,
            label = label,
            day = date.day.toLong(),
            month = date.month.toLong(),
            year = date.year?.toLong(),
            updated_at = nowIso,
            id = eventId,
        )
    }

    suspend fun setEventMuted(eventId: String, muted: Boolean, nowIso: String) =
        withContext(Dispatchers.Default) {
            events.setMuted(is_muted = if (muted) 1L else 0L, updated_at = nowIso, id = eventId)
        }

    suspend fun deleteEventLocally(eventId: String, nowIso: String) =
        withContext(Dispatchers.Default) {
            events.markDeleted(deleted_at = nowIso, updated_at = nowIso, id = eventId)
        }

    suspend fun restoreEventLocally(eventId: String, nowIso: String) =
        withContext(Dispatchers.Default) {
            events.markRestored(updated_at = nowIso, id = eventId)
        }

    suspend fun addTraitLocally(
        traitId: String,
        personId: String,
        kind: TraitKind,
        value: String,
        nowIso: String,
    ) = withContext(Dispatchers.Default) {
        traits.insertOrReplace(
            id = traitId,
            person_id = personId,
            kind = kind.wire,
            value_ = value,
            // Always USER from this app. IMPORT and INFERRED are the server's to write.
            source = "USER",
            updated_at = nowIso,
            deleted_at = null,
            pending_sync = 1L,
        )
    }

    suspend fun deleteTraitLocally(traitId: String, nowIso: String) =
        withContext(Dispatchers.Default) {
            traits.markDeleted(deleted_at = nowIso, updated_at = nowIso, id = traitId)
        }

    // -----------------------------------------------------------------------------------
    // Sync plumbing
    // -----------------------------------------------------------------------------------

    suspend fun pendingPeople() = withContext(Dispatchers.Default) {
        people.selectPendingSync().executeAsList()
    }

    suspend fun pendingEvents() = withContext(Dispatchers.Default) {
        events.selectPendingSync().executeAsList()
    }

    suspend fun pendingTraits() = withContext(Dispatchers.Default) {
        traits.selectPendingSync().executeAsList()
    }

    suspend fun clearPersonPending(ids: List<String>) = withContext(Dispatchers.Default) {
        db.transaction { ids.forEach(people::clearPendingSync) }
    }

    suspend fun clearEventPending(ids: List<String>) = withContext(Dispatchers.Default) {
        db.transaction { ids.forEach(events::clearPendingSync) }
    }

    suspend fun clearTraitPending(ids: List<String>) = withContext(Dispatchers.Default) {
        db.transaction { ids.forEach(traits::clearPendingSync) }
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
        remoteTraits: List<RemoteTrait>,
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
                    photo_key = null,
                    gender = remote.gender,
                    phone = remote.phone,
                    email = remote.email,
                    notes = remote.notes,
                    is_deceased = if (remote.isDeceased) 1L else 0L,
                    is_minor = if (remote.isMinor) 1L else 0L,
                    completeness_score = remote.completenessScore.toLong(),
                    completeness_suggestions = remote.completenessSuggestions.joinToString(","),
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
                    sentiment = remote.sentiment,
                    label = remote.label,
                    is_muted = if (remote.isMuted) 1L else 0L,
                    day = remote.day.toLong(),
                    month = remote.month.toLong(),
                    year = remote.year?.toLong(),
                    updated_at = remote.updatedAt,
                    deleted_at = remote.deletedAt,
                    pending_sync = 0L,
                )
            }
            remoteTraits.forEach { remote ->
                val local = traits.selectById(remote.id).executeAsOneOrNull()
                if (local != null && (local.pending_sync != 0L || local.updated_at >= remote.updatedAt)) {
                    return@forEach
                }
                traits.insertOrReplace(
                    id = remote.id,
                    person_id = remote.personId,
                    kind = remote.kind,
                    value_ = remote.value,
                    source = remote.source,
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

    // -----------------------------------------------------------------------------------
    // Notification de-duplication
    // -----------------------------------------------------------------------------------

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
            traits.deleteAll()
            events.deleteAll()
            people.deleteAll()
            seen.deleteAll()
            sync.reset()
        }
    }

    // -----------------------------------------------------------------------------------
    // Row mapping
    // -----------------------------------------------------------------------------------

    private fun insertPerson(
        id: String,
        displayName: String,
        relation: String?,
        isSelf: Boolean,
        nowIso: String,
        pending: Boolean,
    ) {
        people.insertOrReplace(
            id = id,
            display_name = displayName,
            relation_to_owner = relation,
            is_self = if (isSelf) 1L else 0L,
            photo_key = null,
            gender = null,
            phone = null,
            email = null,
            notes = null,
            is_deceased = 0L,
            is_minor = 0L,
            completeness_score = 0L,
            completeness_suggestions = "",
            updated_at = nowIso,
            deleted_at = null,
            pending_sync = if (pending) 1L else 0L,
        )
    }

    private fun insertEvent(
        id: String,
        personId: String,
        type: EventType,
        label: String?,
        date: EventDate,
        nowIso: String,
        muted: Boolean,
        pending: Boolean,
    ) {
        events.insertOrReplace(
            id = id,
            person_id = personId,
            type = type.wire,
            // A placeholder the server immediately overwrites on the next pull. The client
            // never decides tone; this value only has to be safe for the seconds before the
            // server's answer arrives, and for a brand-new event on a living person it is.
            sentiment = if (type == EventType.DEATH_ANNIVERSARY) "SOLEMN" else "CELEBRATORY",
            label = label,
            is_muted = if (muted) 1L else 0L,
            day = date.day.toLong(),
            month = date.month.toLong(),
            year = date.year?.toLong(),
            updated_at = nowIso,
            deleted_at = null,
            pending_sync = if (pending) 1L else 0L,
        )
    }
}

private fun wishkaro.db.Person.toPerson(
    events: List<PersonEvent>,
    traits: List<PersonTrait>,
): Person = Person(
    id = id,
    displayName = display_name,
    relationToOwner = relation_to_owner,
    isSelf = is_self != 0L,
    gender = gender,
    phone = phone,
    email = email,
    notes = notes,
    isDeceased = is_deceased != 0L,
    isMinor = is_minor != 0L,
    completenessScore = completeness_score.toInt(),
    completenessSuggestions = completeness_suggestions.split(",").filter { it.isNotBlank() },
    deletedAt = deleted_at,
    events = events,
    traits = traits,
)

private fun wishkaro.db.Event.toEvent(): PersonEvent = PersonEvent(
    id = id,
    personId = person_id,
    type = EventType.from(type),
    sentiment = Sentiment.from(sentiment),
    label = label,
    isMuted = is_muted != 0L,
    date = EventDate(day.toInt(), month.toInt(), year?.toInt()),
)

/** Null when the server sends a trait kind this build does not know about yet. */
private fun wishkaro.db.Person_trait.toTrait(): PersonTrait? {
    val parsed = TraitKind.from(kind) ?: return null
    return PersonTrait(id = id, personId = person_id, kind = parsed, value = value_)
}

data class RemotePerson(
    val id: String,
    val displayName: String,
    val relationToOwner: String?,
    val isSelf: Boolean,
    val gender: String?,
    val phone: String?,
    val email: String?,
    val notes: String?,
    val isDeceased: Boolean,
    val isMinor: Boolean,
    val completenessScore: Int,
    val completenessSuggestions: List<String>,
    val updatedAt: String,
    val deletedAt: String?,
)

data class RemoteEvent(
    val id: String,
    val personId: String,
    val type: String,
    val sentiment: String,
    val label: String?,
    val isMuted: Boolean,
    val day: Int,
    val month: Int,
    val year: Int?,
    val updatedAt: String,
    val deletedAt: String?,
)

data class RemoteTrait(
    val id: String,
    val personId: String,
    val kind: String,
    val value: String,
    val source: String,
    val updatedAt: String,
    val deletedAt: String?,
)
