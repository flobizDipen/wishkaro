package `in`.wishkaro.app.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessaging
import `in`.wishkaro.app.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Notification channels.
 *
 * One channel per ladder step (FR-REM-05) so a user can silence the T-7 gifting nudge without
 * losing the morning-of reminder. M0 only sends morning-of, but the channels are created now
 * — an Android channel's importance cannot be raised after creation, so getting the split
 * right later means inventing new channel ids.
 */
object NotificationChannels {
    const val MORNING_OF = "reminders_morning_of"
    const val DAY_BEFORE = "reminders_day_before"
    const val WEEK_BEFORE = "reminders_week_before"
    const val SELF_TEST = "self_test"

    /**
     * M2. Digest mode replaces the whole ladder (docs/M2_PLAN.md §2.1), so this channel
     * stands in for MORNING_OF and takes its importance.
     *
     * Adding a channel id to an app that is already installed is fine — the rule that forced
     * the other four to be created up front is that an EXISTING channel's importance cannot
     * be RAISED afterwards. A new id has no history to be bound by.
     */
    const val DIGEST = "reminders_digest"

    fun createAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        listOf(
            Triple(MORNING_OF, "Birthday reminders", NotificationManager.IMPORTANCE_HIGH),
            Triple(DAY_BEFORE, "Day-before reminders", NotificationManager.IMPORTANCE_DEFAULT),
            Triple(WEEK_BEFORE, "Week-ahead reminders", NotificationManager.IMPORTANCE_DEFAULT),
            Triple(DIGEST, "Daily digest", NotificationManager.IMPORTANCE_HIGH),
            Triple(SELF_TEST, "Test notifications", NotificationManager.IMPORTANCE_HIGH),
        ).forEach { (id, name, importance) ->
            manager.createNotificationChannel(NotificationChannel(id, name, importance))
        }
    }
}

/**
 * Bridges the suspend world to the Activity Result API, which can only be driven from an
 * Activity. MainActivity installs [requester] when it is created.
 */
object PermissionBridge {
    @Volatile
    var requester: (suspend () -> Boolean)? = null

    suspend fun request(): Boolean = requester?.invoke() ?: false

    /** Used by MainActivity to turn a permission callback into a suspending call. */
    class Pending {
        val result = CompletableDeferred<Boolean>()
    }
}

class AndroidNotificationGateway(private val context: Context) : NotificationGateway {

    override fun hasPermission(): Boolean {
        // POST_NOTIFICATIONS only exists from Android 13. Below that, notifications are
        // allowed unless the user turned them off in settings.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    override suspend fun requestPermission(): Boolean {
        if (hasPermission()) return true
        return PermissionBridge.request()
    }

    override suspend fun currentPushToken(): String? = suspendCancellableCoroutine { cont ->
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener { task ->
            if (task.isSuccessful) {
                // Debug builds only. Pairing a device with the server by hand — to prove push
                // delivery before Google Sign-In is in the picture — needs this token, and
                // there is no other way to read it off the device. Never in a release build:
                // anyone holding both this and the server key can push to this phone.
                if (BuildConfig.DEBUG) Log.d("Notifications", "FCM token: ${task.result}")
                cont.resume(task.result)
            } else {
                Log.w("Notifications", "Could not obtain an FCM token", task.exception)
                cont.resume(null)
            }
        }
    }
}
