package `in`.wishkaro.app.di

import android.content.Context
import `in`.wishkaro.app.platform.ActivityHolder
import `in`.wishkaro.app.platform.AndroidContactsGateway
import `in`.wishkaro.app.platform.AndroidFileGateway
import `in`.wishkaro.app.platform.ContactsGateway
import `in`.wishkaro.app.platform.AndroidMessageSender
import `in`.wishkaro.app.platform.FileGateway
import `in`.wishkaro.app.platform.MessageSender
import `in`.wishkaro.app.platform.AndroidGoogleSignInClient
import `in`.wishkaro.app.platform.AndroidNotificationGateway
import `in`.wishkaro.app.platform.DatabaseDriverFactory
import `in`.wishkaro.app.platform.GoogleSignInClient
import `in`.wishkaro.app.platform.AndroidReminderScheduler
import `in`.wishkaro.app.platform.NotificationGateway
import `in`.wishkaro.app.platform.OemBatteryHelper
import `in`.wishkaro.app.platform.AndroidOemBatteryHelper
import `in`.wishkaro.app.platform.ReminderScheduler
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
    single<ReminderScheduler> { AndroidReminderScheduler(androidContext(), get()) }
    single<OemBatteryHelper> { AndroidOemBatteryHelper(androidContext()) }
    single<ContactsGateway> { AndroidContactsGateway(androidContext()) }
    single<FileGateway> { AndroidFileGateway(androidContext()) }
    single<MessageSender> { AndroidMessageSender(androidContext()) }
}
