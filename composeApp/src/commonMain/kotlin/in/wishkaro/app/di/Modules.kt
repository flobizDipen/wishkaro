package `in`.wishkaro.app.di

import `in`.wishkaro.app.data.TokenStore
import `in`.wishkaro.app.data.createSecureStorage
import `in`.wishkaro.app.data.local.LocalStore
import `in`.wishkaro.app.data.remote.WishKaroApi
import `in`.wishkaro.app.data.repository.AuthRepository
import `in`.wishkaro.app.data.repository.PeopleRepository
import `in`.wishkaro.app.data.repository.PlanApi
import `in`.wishkaro.app.data.repository.PlanStore
import `in`.wishkaro.app.data.repository.ReminderPlanRepository
import `in`.wishkaro.app.data.repository.SyncApi
import `in`.wishkaro.app.data.repository.SyncEngine
import `in`.wishkaro.app.data.repository.SyncStore
import `in`.wishkaro.app.platform.DatabaseDriverFactory
import `in`.wishkaro.app.platform.GoogleSignInClient
import `in`.wishkaro.app.platform.NotificationGateway
import `in`.wishkaro.app.platform.OemBatteryHelper
import `in`.wishkaro.app.platform.PlatformConfig
import `in`.wishkaro.app.platform.ReminderScheduler
import `in`.wishkaro.app.ui.AppStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import org.koin.core.module.Module
import org.koin.dsl.binds
import org.koin.dsl.module
import wishkaro.db.WishKaroDb

/**
 * Platform-supplied bindings: the SQLDelight driver, Google Sign-In and the notification
 * gateway. Each platform provides its own module; everything below is shared.
 */
expect fun platformModule(): Module

val appModule = module {
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    single { WishKaroDb(get<DatabaseDriverFactory>().create()) }

    /*
     * Bound under its interfaces as well as its own type.
     *
     * `LocalStore` implements `SyncStore` and `PlanStore`, and `WishKaroApi` implements
     * `SyncApi` and `PlanApi` — but Koin only resolves what is registered, so until M2 those
     * four interfaces were not resolvable at all. Every call site had to remember to write
     * `get<LocalStore>()`; one that wrote a bare `get()` compiled, passed every test, and
     * threw NoDefinitionFoundException on launch. `binds` removes the trap rather than
     * documenting it, and ModulesTest now checks the graph.
     */
    single { LocalStore(get()) } binds arrayOf(SyncStore::class, PlanStore::class)
    single { TokenStore(createSecureStorage()) }

    single {
        // The API is constructed with a callback rather than a dependency on AuthRepository,
        // which would be a cycle: the repository needs the API to sign in.
        WishKaroApi(
            baseUrl = PlatformConfig.apiBaseUrl,
            tokens = get(),
            onSessionExpired = { get<AuthRepository>().onSessionExpired() },
        )
    } binds arrayOf(SyncApi::class, PlanApi::class)

    single { SyncEngine(api = get<WishKaroApi>(), local = get<LocalStore>()) }
    single { PeopleRepository(local = get(), sync = get(), scope = get()) }
    single {
        // Explicit types, not bare get().
        //
        // `WishKaroApi` and `LocalStore` are registered under their own concrete types, so a
        // bare get() here asks Koin for `PlanApi` and `PlanStore` — interfaces nothing is
        // bound to. That compiles, passes every unit test, and then throws
        // NoDefinitionFoundException on the first launch. It did exactly that once; see the
        // module-verification test in ModulesTest.
        ReminderPlanRepository(
            api = get<WishKaroApi>(),
            local = get<LocalStore>(),
            scheduler = get<ReminderScheduler>(),
        )
    }

    single {
        AuthRepository(
            api = get(),
            tokens = get(),
            local = get(),
            googleSignIn = get<GoogleSignInClient>(),
            notifications = get<NotificationGateway>(),
        )
    }

    single {
        AppStore(
            auth = get(),
            people = get(),
            notifications = get<NotificationGateway>(),
            local = get(),
            plan = get(),
            scheduler = get<ReminderScheduler>(),
            oem = get<OemBatteryHelper>(),
            scope = get(),
        )
    }
}
