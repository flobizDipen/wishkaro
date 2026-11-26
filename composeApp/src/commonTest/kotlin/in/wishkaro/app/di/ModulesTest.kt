package `in`.wishkaro.app.di

import `in`.wishkaro.app.data.TokenStore
import `in`.wishkaro.app.platform.ContactsGateway
import `in`.wishkaro.app.platform.FileGateway
import `in`.wishkaro.app.platform.GoogleSignInClient
import `in`.wishkaro.app.platform.NotificationGateway
import `in`.wishkaro.app.platform.OemBatteryHelper
import `in`.wishkaro.app.platform.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import org.koin.test.verify.verify
import wishkaro.db.WishKaroDb
import kotlin.test.Test

/**
 * Every dependency in [appModule] resolves to something that is actually registered.
 *
 * This exists because of a specific failure, and the failure is worth stating plainly: M2
 * introduced `PlanApi` and `PlanStore` as narrow interfaces, and the module kept asking for
 * them with a bare `get()`. `WishKaroApi` and `LocalStore` are registered under their own
 * concrete types, so nothing was bound to the interfaces.
 *
 * That compiled. It passed all 308 unit tests. CI — which compiles and runs unit tests —
 * would have passed it too. And the app threw NoDefinitionFoundException on launch and never
 * drew a frame. The only thing between that build and a release was somebody installing it
 * and looking.
 *
 * `verify()` walks each definition's constructor and checks every parameter against the
 * graph without instantiating anything, so it catches exactly this in milliseconds and with
 * no device.
 *
 * `extraTypes` are the ones the PLATFORM module supplies. They are listed rather than
 * registered deliberately: naming them here makes adding a new platform dependency a visible
 * decision instead of a silent one.
 */
@OptIn(org.koin.core.annotation.KoinExperimentalAPI::class)
class ModulesTest {

    @Test
    fun everyDependencyInTheAppModuleIsRegistered() {
        appModule.verify(
            extraTypes = listOf(
                // From platformModule(), which needs a real Context or platform APIs.
                WishKaroDb::class,
                GoogleSignInClient::class,
                NotificationGateway::class,
                ReminderScheduler::class,
                OemBatteryHelper::class,
                // M3's two new platform seams: contacts need a ContentResolver and a
                // runtime permission, files need the Activity's result launchers.
                ContactsGateway::class,
                FileGateway::class,
                // Built inside the module itself from those factories.
                TokenStore::class,
                CoroutineScope::class,
                // Supplied by an `expect` factory rather than by Koin.
                `in`.wishkaro.app.data.SecureStorage::class,
                // Builtins that appear in constructor signatures.
                String::class,
                Function0::class,
            ),
        )
    }
}
