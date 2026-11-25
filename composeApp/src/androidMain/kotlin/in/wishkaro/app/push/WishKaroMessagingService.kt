package `in`.wishkaro.app.push

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import `in`.wishkaro.app.MainActivity
import `in`.wishkaro.app.R
import `in`.wishkaro.app.data.local.LocalStore
import `in`.wishkaro.app.data.repository.AuthRepository
import `in`.wishkaro.app.platform.NotificationChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.time.Clock
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
            val nowIso = Clock.System.now().toString()

            // FR-REM-02: the server may retry, and from M2 a local alarm fires for the same
            // event. The user must still see exactly one notification.
            val isFirstTime = local.claimReminder(dedupeKey, nowIso)
            if (!isFirstTime) {
                Log.i(TAG, "Duplicate reminder $dedupeKey suppressed")
                // Reported, not dropped. A suppressed duplicate is the de-duplication
                // WORKING, and the server has to be able to tell that apart from a push
                // that never arrived — otherwise the G1 number falls as reliability rises.
                local.recordDelivery(dedupeKey, OUTCOME_SUPPRESSED_DUPLICATE, CHANNEL_FCM, nowIso)
                return@launch
            }

            val shown = show(
                title = data["title"] ?: "WishKaro",
                body = data["body"].orEmpty(),
                deepLink = data["deepLink"],
                channelId = channelFor(data),
                notificationId = dedupeKey.hashCode(),
            )

            local.recordDelivery(
                dedupeKey = dedupeKey,
                outcome = if (shown) OUTCOME_SHOWN else OUTCOME_BLOCKED_NO_PERMISSION,
                channel = CHANNEL_FCM,
                occurredAtIso = nowIso,
            )
        }
    }

    /**
     * One channel per ladder rung (FR-REM-05), so a user can silence the T-7 gifting nudge
     * without losing the morning-of reminder. All of them were created at first launch
     * because an Android channel's importance cannot be raised afterwards.
     *
     * Falls back to morning-of for an offset this build does not know, rather than dropping
     * the notification: an unknown rung from a newer server is still a reminder somebody is
     * waiting for.
     */
    private fun channelFor(data: Map<String, String>): String = when {
        data["type"] == "TEST" -> NotificationChannels.SELF_TEST
        data["offset"] == "DIGEST" -> NotificationChannels.DIGEST
        data["offset"] == "D7" -> NotificationChannels.WEEK_BEFORE
        data["offset"] == "D1" -> NotificationChannels.DAY_BEFORE
        else -> NotificationChannels.MORNING_OF
    }

    /** True if a notification was actually drawn. False means the permission is missing. */
    private fun show(
        title: String,
        body: String,
        deepLink: String?,
        channelId: String,
        notificationId: Int,
    ): Boolean {
        if (
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        ) {
            Log.w(TAG, "Reminder arrived but POST_NOTIFICATIONS is not granted")
            return false
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (deepLink != null) data = Uri.parse(deepLink)
        }
        val pending = PendingIntent.getActivity(
            this,
            notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()

        NotificationManagerCompat.from(this).notify(notificationId, notification)
        return true
    }

    private companion object {
        const val TAG = "WishKaroFcm"
        const val OUTCOME_SHOWN = "SHOWN"
        const val OUTCOME_SUPPRESSED_DUPLICATE = "SUPPRESSED_DUPLICATE"
        const val OUTCOME_BLOCKED_NO_PERMISSION = "BLOCKED_NO_PERMISSION"
        const val CHANNEL_FCM = "FCM"
    }
}
