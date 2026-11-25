package `in`.wishkaro.app.data.repository

import `in`.wishkaro.app.data.local.LocalStore
import `in`.wishkaro.app.domain.EventDate
import `in`.wishkaro.app.domain.Person
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

    suspend fun findPerson(id: String): Person? = local.findPerson(id)

    suspend fun addPerson(displayName: String, relation: String?, date: EventDate): String {
        val personId = newId()
        local.addPersonLocally(
            personId = personId,
            eventId = newId(),
            displayName = displayName.trim(),
            relation = relation,
            date = date,
            nowIso = Clock.System.now().toString(),
        )
        syncInBackground()
        return personId
    }

    fun syncInBackground() {
        scope.launch { sync.sync() }
    }

    suspend fun syncNow(): Result<SyncEngine.Result> = sync.sync()
}
