package `in`.wishkaro.app.ui.importing

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import `in`.wishkaro.app.data.local.LocalStore
import `in`.wishkaro.app.data.remote.DeliveryReportRequest
import `in`.wishkaro.app.data.remote.DeliveryReportResponse
import `in`.wishkaro.app.data.remote.SyncPullResponse
import `in`.wishkaro.app.data.remote.SyncPushRequest
import `in`.wishkaro.app.data.remote.SyncPushResponse
import `in`.wishkaro.app.data.repository.PeopleRepository
import `in`.wishkaro.app.data.repository.SyncApi
import `in`.wishkaro.app.data.repository.SyncEngine
import `in`.wishkaro.app.platform.ContactsGateway
import `in`.wishkaro.app.platform.DeviceContact
import `in`.wishkaro.app.platform.FileGateway
import `in`.wishkaro.app.platform.PermissionOutcome
import `in`.wishkaro.app.platform.PickedFile
import `in`.wishkaro.app.platform.PickedPhoto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import wishkaro.db.WishKaroDb
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The import flow must never be able to lock itself shut.
 *
 * FOUND BY USING IT, during M3's own verification: the file picker silently stopped opening.
 * Every tap on "Choose a file" did nothing, there was no error anywhere, and force-stopping
 * the app was the only cure — because `_busy` had been left true and `ImportStore` is a
 * singleton on the Application, so the flag outlived the Activity.
 *
 * The picker and the permission dialog are both awaited through a `CompletableDeferred` that
 * a DIFFERENT Activity completes. When that result never arrives the coroutine stays
 * suspended for ever, and no `finally` can save it — nothing is thrown and nothing returns.
 * So the recovery cannot live on the hanging path: leaving and re-entering the flow has to
 * release the flag. That is what these two pin.
 */
class BusyFlagTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
        WishKaroDb.Schema.create(it)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val local = LocalStore(WishKaroDb(driver))

    @AfterTest
    fun close() {
        scope.cancel()
        driver.close()
    }

    /** A picker that never answers — the exact state that caused the lock-up. */
    private class NeverAnsweringFiles : FileGateway {
        val reached = CompletableDeferred<Unit>()

        override suspend fun pickSpreadsheet(): PickedFile? {
            reached.complete(Unit)
            CompletableDeferred<Unit>().await() // never completes, like a result never delivered
            return null
        }

        override suspend fun saveCsv(suggestedName: String, content: String) = false

        override suspend fun saveTextFile(suggestedName: String, content: String) = false

        override suspend fun pickPhoto(): PickedPhoto? = null
    }

    private object NoContacts : ContactsGateway {
        override fun hasPermission() = false
        override suspend fun requestPermission() = PermissionOutcome.DENIED
        override fun openAppSettings() = Unit
        override suspend fun readContacts(): List<DeviceContact> = emptyList()
    }

    private object OfflineApi : SyncApi {
        override suspend fun syncPull(cursor: String?) =
            SyncPullResponse(cursor = "c", hasMore = false, serverTime = null)

        override suspend fun syncPush(body: SyncPushRequest) = SyncPushResponse(serverTime = null)

        override suspend fun reportDelivery(body: DeliveryReportRequest) =
            DeliveryReportResponse(recorded = 0, unknown = 0)
    }

    private fun store(files: FileGateway) = ImportStore(
        people = PeopleRepository(local = local, sync = SyncEngine(OfflineApi, local), scope = scope),
        contacts = NoContacts,
        files = files,
        scope = scope,
    )

    @Test
    fun leaving_and_re_entering_releases_a_picker_that_never_came_back() = runTest {
        val files = NeverAnsweringFiles()
        val store = store(files)

        store.pickFile()
        files.reached.await()
        assertTrue(store.busy.value, "the flow is waiting on the picker")

        // What the user does when nothing happens: back out of import, go in again.
        store.clearResult()

        assertFalse(store.busy.value, "and the flow is usable again")
        assertTrue(store.fileState.value is FileState.Choosing)
    }

    @Test
    fun after_recovering_a_second_pick_can_actually_start() = runTest {
        val first = NeverAnsweringFiles()
        val store = store(first)

        store.pickFile()
        first.reached.await()
        store.clearResult()

        // Before the fix this returned immediately on `if (_busy.value) return`, which is
        // precisely why every later tap on "Choose a file" did nothing at all.
        val second = NeverAnsweringFiles()
        ImportStore(
            people = PeopleRepository(local = local, sync = SyncEngine(OfflineApi, local), scope = scope),
            contacts = NoContacts,
            files = second,
            scope = scope,
        ).also { it.pickFile() }
        second.reached.await()

        assertFalse(store.busy.value)
    }
}
