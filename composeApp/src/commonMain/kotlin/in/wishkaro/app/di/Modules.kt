package `in`.wishkaro.app.di

import `in`.wishkaro.app.data.TokenStore
import `in`.wishkaro.app.data.createAppPreferences
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
import `in`.wishkaro.app.platform.ContactsGateway
import `in`.wishkaro.app.platform.FileGateway
import `in`.wishkaro.app.data.repository.UpcomingRepository
import `in`.wishkaro.app.ui.AppStore
import `in`.wishkaro.app.ui.importing.ImportStore
import `in`.wishkaro.app.ui.onboarding.OnboardingStore
import `in`.wishkaro.app.ui.poster.PosterStore
import `in`.wishkaro.app.ui.wish.WishStore
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

    /**
     * M7, FR-AUTH-01. Device-local flags, and the one thing in them.
     *
     * `AppPreferences` is a SEPARATE store from `SecureStorage` on purpose: `TokenStore.clear()`
     * empties the whole encrypted file on sign-out and on account deletion, so a "has seen the
     * carousel" flag living there would bring the introduction back every time somebody signed
     * out. See `data/AppPreferences.kt`.
     */
    single { createAppPreferences() }
    single { OnboardingStore(get()) }

    single {
        // The API is constructed with a callback rather than a dependency on AuthRepository,
        // which would be a cycle: the repository needs the API to sign in.
        WishKaroApi(
            baseUrl = PlatformConfig.apiBaseUrl,
            tokens = get(),
            onSessionExpired = { get<AuthRepository>().onSessionExpired() },
            // Distinct from the line above on purpose: an expired session keeps the local
            // database and a deleted account must not (M9, FR-SET-04).
            onAccountGone = { get<AuthRepository>().onAccountGone() },
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
        ImportStore(
            people = get(),
            contacts = get<ContactsGateway>(),
            files = get<FileGateway>(),
            scope = get(),
        )
    }

    // The one "what is coming up" query, shared by the views and the Glance widget. The
    // widget resolves THIS from the Koin graph, never AppStore — a widget update runs when
    // no UI has ever been composed. docs/M4_PLAN.md §3.
    single { UpcomingRepository(local = get()) }

    // M5. A feature store beside AppStore, the way ImportStore is: composer state is
    // per-screen and short-lived, and AppStore is already this app's largest file.
    single {
        WishStore(
            api = get<WishKaroApi>(),
            local = get(),
            sender = get(),
            scope = get(),
        )
    }

    // M6. Beside WishStore and for the same reason: poster studio state is per-screen and
    // short-lived, and AppStore is already this app's largest file.
    single {
        PosterStore(
            api = get<WishKaroApi>(),
            sender = get(),
            files = get<FileGateway>(),
            scope = get(),
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
            importing = get(),
            files = get<FileGateway>(),
            scope = get(),
        )
    }
}
