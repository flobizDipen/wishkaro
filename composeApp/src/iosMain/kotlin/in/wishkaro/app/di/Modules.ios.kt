package `in`.wishkaro.app.di

import `in`.wishkaro.app.platform.DatabaseDriverFactory
import `in`.wishkaro.app.platform.GoogleSignInClient
import `in`.wishkaro.app.platform.IosGoogleSignInClient
import `in`.wishkaro.app.platform.IosNotificationGateway
import `in`.wishkaro.app.platform.NoopOemBatteryHelper
import `in`.wishkaro.app.platform.NoopReminderScheduler
import `in`.wishkaro.app.platform.NotificationGateway
import `in`.wishkaro.app.platform.OemBatteryHelper
import `in`.wishkaro.app.platform.ReminderScheduler
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module = module {
    single { DatabaseDriverFactory() }
    single<GoogleSignInClient> { IosGoogleSignInClient() }
    single<NotificationGateway> { IosNotificationGateway() }
    // M2 is Android-first; iOS only has to compile. A no-op is the honest shape for that,
    // rather than a half-written UNUserNotificationCenter path nobody has ever run.
    single<ReminderScheduler> { NoopReminderScheduler() }
    single<OemBatteryHelper> { NoopOemBatteryHelper() }
}
