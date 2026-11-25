package `in`.wishkaro.app.di

import `in`.wishkaro.app.data.TokenStore
import `in`.wishkaro.app.data.createSecureStorage
import `in`.wishkaro.app.data.local.LocalStore
import `in`.wishkaro.app.data.remote.WishKaroApi
import `in`.wishkaro.app.data.repository.AuthRepository
import `in`.wishkaro.app.data.repository.PeopleRepository
import `in`.wishkaro.app.data.repository.SyncEngine
import `in`.wishkaro.app.platform.DatabaseDriverFactory
import `in`.wishkaro.app.platform.GoogleSignInClient
import `in`.wishkaro.app.platform.NotificationGateway
import `in`.wishkaro.app.platform.PlatformConfig
import `in`.wishkaro.app.ui.AppStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import org.koin.core.module.Module
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
    single { LocalStore(get()) }
    single { TokenStore(createSecureStorage()) }

    single {
        // The API is constructed with a callback rather than a dependency on AuthRepository,
        // which would be a cycle: the repository needs the API to sign in.
        WishKaroApi(
            baseUrl = PlatformConfig.apiBaseUrl,
            tokens = get(),
            onSessionExpired = { get<AuthRepository>().onSessionExpired() },
        )
    }

    single { SyncEngine(api = get<WishKaroApi>(), local = get<LocalStore>()) }
    single { PeopleRepository(local = get(), sync = get(), scope = get()) }

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
            scope = get(),
        )
    }
}
