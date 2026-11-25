package `in`.wishkaro.app

import android.app.Application
import android.util.Log
import `in`.wishkaro.app.data.SecureStorageContext
import `in`.wishkaro.app.di.appModule
import `in`.wishkaro.app.di.platformModule
import `in`.wishkaro.app.platform.NotificationChannels
import org.koin.android.ext.koin.androidContext
import `in`.wishkaro.app.platform.NotificationGateway
import `in`.wishkaro.app.platform.ReminderScheduler
import `in`.wishkaro.app.push.ReminderUpkeepWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

class WishKaroApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Must happen before Koin builds TokenStore, which reaches for it eagerly.
        SecureStorageContext.appContext = this

        startKoin {
            androidLogger()
            androidContext(this@WishKaroApplication)
            modules(platformModule(), appModule)
        }

        // Channels are cheap and idempotent, and a push that arrives before the first
        // Activity has ever run still needs one to land in.
        NotificationChannels.createAll(this)

        logPushTokenInDebugBuilds()
        armReminders()
    }

    /**
     * Re-arms the local alarms, and schedules the repair job that keeps doing it.
     *
     * Done at process start rather than when the UI appears, because the two things that
     * destroy alarms — a force-stop by an OEM battery manager, and the user swiping the app
     * away — both leave the next launch as the first chance to notice. The launch may well be
     * a push or an alarm waking the process rather than a person opening the app, which is
     * exactly when it matters most.
     *
     * Reads only the stored plan, so it works with no network and finishes in milliseconds.
     */
    private fun armReminders() {
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                GlobalContext.get().get<ReminderScheduler>().rearmAll()
                ReminderUpkeepWorker.enqueue(this@WishKaroApplication)
            }.onFailure { Log.w("WishKaro", "Could not arm reminders at launch", it) }
        }
    }

    /**
     * Debug builds print the FCM registration token at launch.
     *
     * It is the only way to read the token off a device, and pairing a phone with a local
     * server by hand — to prove push delivery before Google Sign-In is involved — needs it.
     * See docs/M0_VERIFY.md. Never in a release build: this token plus the server key is
     * enough to push to this phone.
     */
    private fun logPushTokenInDebugBuilds() {
        if (!BuildConfig.DEBUG) return
        CoroutineScope(Dispatchers.IO).launch {
            val gateway = GlobalContext.get().get<NotificationGateway>()
            Log.d("Notifications", "FCM token: ${gateway.currentPushToken()}")
        }
    }
}
