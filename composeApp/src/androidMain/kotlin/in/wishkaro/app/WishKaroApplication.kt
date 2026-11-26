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
import `in`.wishkaro.app.data.local.LocalStore
import `in`.wishkaro.app.push.ReminderUpkeepWorker
import `in`.wishkaro.app.widget.WidgetUpdates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.GlobalContext
import org.koin.core.context.startKoin

private const val WIDGET_DEBOUNCE_MS = 1_500L

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
        keepTheWidgetCurrent()
    }

    /**
     * FR-WID-03 — the widget updates on data change and at least daily at midnight.
     *
     * The data-change half is done by watching the SAME flow the list watches, rather than
     * by calling a refresh from every write site. There are a dozen of those — add, edit,
     * delete, restore, mute, import, and every sync pull — and a rule that has to be
     * remembered at a dozen call sites is a rule that gets forgotten at the thirteenth.
     * `MILESTONE_CHECKLIST.md` §C is full of values that were computed and then dropped for
     * exactly that reason.
     *
     * `drop(1)` skips the emission that arrives just for subscribing, and the debounce
     * matters at this project's real size: importing 566 people emits once per chunk, and
     * without it the widget would redraw a dozen times during a sync nobody is watching.
     */
    private fun keepTheWidgetCurrent() {
        WidgetUpdates.schedule(this)
        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                // Once at process start, before watching for changes. A widget's
                // PendingIntents are invalidated when the package is replaced, and if
                // nothing redraws it afterwards every tap on it silently does nothing —
                // which is exactly what happened on the home screen after an `install -r`,
                // and looked like a bug in the actions rather than a stale rendering.
                // It also means a widget placed while the app was not running catches up
                // the first time anything starts this process.
                WidgetUpdates.refresh(this@WishKaroApplication)

                GlobalContext.get().get<LocalStore>().observePeople()
                    .drop(1)
                    .debounce(WIDGET_DEBOUNCE_MS)
                    .collect { WidgetUpdates.refresh(this@WishKaroApplication) }
            }.onFailure { Log.w("WishKaro", "Widget updates stopped", it) }
        }
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
