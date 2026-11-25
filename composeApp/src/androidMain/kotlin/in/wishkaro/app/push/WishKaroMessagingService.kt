package `in`.wishkaro.app.push

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import `in`.wishkaro.app.data.local.LocalStore
import `in`.wishkaro.app.data.repository.AuthRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Receives reminder pushes.
 *
 * The server sends DATA-ONLY messages on purpose: a `notification` payload would be drawn by
 * the system without ever reaching this class when the app is backgrounded, and then neither
 * `dedupe_key` suppression nor the deep link would work.
 */
class WishKaroMessagingService : FirebaseMessagingService(), KoinComponent {

    private val local: LocalStore by inject()
    private val auth: AuthRepository by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * FCM rotates tokens. A token the server does not know about is a user who silently
     * stops receiving reminders — the failure this product can least afford.
     */
    @Deprecated("FirebaseMessagingService.onNewToken is deprecated in Firebase 26 in favour " +
        "of the register()/unregister() model. Still the only callback that fires on rotation, " +
        "so M0 keeps it; revisit when the reminder engine is rebuilt in M2.")
    @Suppress("DEPRECATION")
    override fun onNewToken(token: String) {
        Log.i(TAG, "FCM token rotated; re-registering with the server")
        scope.launch { auth.registerDevice() }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val dedupeKey = data["dedupeKey"]
        if (dedupeKey == null) {
            Log.w(TAG, "Push without a dedupeKey, ignoring")
            return
        }

        scope.launch {
            // One implementation of claim -> draw -> report, shared with the local alarm.
            // The two paths must produce exactly one notification between them (FR-REM-02),
            // and the surest way to make that true is for there to be only one piece of code
            // that can draw one.
            val outcome = ReminderPresenter.present(
                context = applicationContext,
                local = local,
                reminder = ReminderPresenter.Reminder(
                    dedupeKey = dedupeKey,
                    title = data["title"] ?: "WishKaro",
                    body = data["body"].orEmpty(),
                    offset = data["offset"] ?: "D0",
                    type = data["type"] ?: "REMINDER",
                    personId = data["personId"],
                    personName = data["personName"],
                    sentiment = data["sentiment"],
                    deepLink = data["deepLink"],
                ),
                channel = ReminderPresenter.CHANNEL_FCM,
            )
            Log.i(TAG, "Push $dedupeKey -> $outcome")
        }
    }

    private companion object {
        const val TAG = "WishKaroFcm"
    }
}
