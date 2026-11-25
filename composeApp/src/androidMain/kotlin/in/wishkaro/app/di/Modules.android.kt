package `in`.wishkaro.app.di

import android.content.Context
import `in`.wishkaro.app.platform.ActivityHolder
import `in`.wishkaro.app.platform.AndroidGoogleSignInClient
import `in`.wishkaro.app.platform.AndroidNotificationGateway
import `in`.wishkaro.app.platform.DatabaseDriverFactory
import `in`.wishkaro.app.platform.GoogleSignInClient
import `in`.wishkaro.app.platform.NotificationGateway
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single { DatabaseDriverFactory(androidContext()) }
    single<GoogleSignInClient> {
        AndroidGoogleSignInClient(
            appContext = get<Context>(),
            activityProvider = { ActivityHolder.current },
        )
    }
    single<NotificationGateway> { AndroidNotificationGateway(androidContext()) }
}
