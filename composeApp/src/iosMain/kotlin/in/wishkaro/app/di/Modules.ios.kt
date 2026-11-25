package `in`.wishkaro.app.di

import `in`.wishkaro.app.platform.DatabaseDriverFactory
import `in`.wishkaro.app.platform.GoogleSignInClient
import `in`.wishkaro.app.platform.IosGoogleSignInClient
import `in`.wishkaro.app.platform.IosNotificationGateway
import `in`.wishkaro.app.platform.NotificationGateway
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single { DatabaseDriverFactory() }
    single<GoogleSignInClient> { IosGoogleSignInClient() }
    single<NotificationGateway> { IosNotificationGateway() }
}
