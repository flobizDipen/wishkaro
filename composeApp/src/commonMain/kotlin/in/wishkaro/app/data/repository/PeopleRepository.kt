package `in`.wishkaro.app.data.repository

import `in`.wishkaro.app.data.local.LocalStore
import `in`.wishkaro.app.domain.EventDate
import `in`.wishkaro.app.domain.EventType
import `in`.wishkaro.app.domain.Person
import `in`.wishkaro.app.domain.nameTokens
import `in`.wishkaro.app.domain.tokensOverlap
import `in`.wishkaro.app.domain.TraitKind
import `in`.wishkaro.app.platform.newId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlin.time.Clock

/**
 * Writes go local-first and are then synced in the background (D23).
 *
 * `addPerson` returns as soon as the local write lands, so the UI never waits on the network
 * and adding someone on the train works exactly like adding someone at a desk.
 */
class PeopleRepository(
    private val local: LocalStore,
    private val sync: SyncEngine,
    private val scope: CoroutineScope,
) {
    fun observePeople(): Flow<List<Person>> = local.observePeople()

    fun observePerson(id: String): Flow<Person?> = local.observePerson(id)

    fun observeDeleted(): Flow<List<Person>> = local.observeDeleted()

    suspend fun findPerson(id: String): Person? = local.findPerson(id)

    private fun now(): String = Clock.System.now().toString()

    suspend fun addPerson(
        displayName: String,
        relation: String?,
        type: EventType,
        label: String?,
        date: EventDate,
    ): String {
        val personId = newId()
        local.addPersonLocally(
            personId = personId,
            eventId = newId(),
            displayName = displayName.trim(),
            relation = relation,
            type = type,
            label = label,
            date = date,
            nowIso = now(),
        )
        syncInBackground()
        return personId
    }

    suspend fun updatePerson(
        personId: String,
        displayName: String,
        relation: String?,
        gender: String?,
        phone: String?,
        email: String?,
        notes: String?,
        isDeceased: Boolean,
    ) {
        local.updatePersonLocally(
            personId = personId,
            displayName = displayName.trim(),
            relation = relation,
            gender = gender?.takeIf { it.isNotBlank() },
            phone = phone?.takeIf { it.isNotBlank() },
            email = email?.takeIf { it.isNotBlank() },
            notes = notes?.takeIf { it.isNotBlank() },
            isDeceased = isDeceased,
            nowIso = now(),
        )
        syncInBackground()
    }

    suspend fun deletePerson(personId: String) {
        local.deletePersonLocally(personId, now())
        syncInBackground()
    }

    suspend fun restorePerson(personId: String) {
        local.restorePersonLocally(personId, now())
        syncInBackground()
    }

    suspend fun addEvent(
        personId: String,
        type: EventType,
        label: String?,
        date: EventDate,
    ): String {
        val eventId = newId()
        local.addEventLocally(eventId, personId, type, label, date, now())
        syncInBackground()
        return eventId
    }

    suspend fun updateEvent(eventId: String, type: EventType, label: String?, date: EventDate) {
        local.updateEventLocally(eventId, type, label, date, now())
        syncInBackground()
    }

    suspend fun setEventMuted(eventId: String, muted: Boolean) {
        local.setEventMuted(eventId, muted, now())
        syncInBackground()
    }

    suspend fun deleteEvent(eventId: String) {
        local.deleteEventLocally(eventId, now())
        syncInBackground()
    }

    suspend fun restoreEvent(eventId: String) {
        local.restoreEventLocally(eventId, now())
        syncInBackground()
    }

    suspend fun addTrait(personId: String, kind: TraitKind, value: String) {
        local.addTraitLocally(newId(), personId, kind, value.trim(), now())
        syncInBackground()
    }

    suspend fun removeTrait(traitId: String) {
        local.deleteTraitLocally(traitId, now())
        syncInBackground()
    }

    /**
     * Possible duplicates of a name about to be added (FR-PPL-06).
     *
     * Run against the LOCAL database rather than the server's /duplicates endpoint, because
     * adding a person has to work with the radio off and a duplicate prompt that only
     * appears when online is worse than one that always appears.
     */
    suspend fun findDuplicates(displayName: String, date: EventDate?): List<Person> {
        val wanted = nameTokens(displayName)
        if (wanted.isEmpty()) return emptyList()
        return local.allPeopleOnce().filter { person ->
            if (person.deletedAt != null) return@filter false
            if (!tokensOverlap(wanted, nameTokens(person.displayName))) return@filter false
            if (date == null) return@filter true
            person.events.any { it.date.day == date.day && it.date.month == date.month }
        }
    }

    fun syncInBackground() {
        scope.launch { sync.sync() }
    }

    suspend fun syncNow(): Result<SyncEngine.Result> = sync.sync()
}
