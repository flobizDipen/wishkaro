package `in`.wishkaro.app.platform

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import `in`.wishkaro.app.data.local.LocalStore
import `in`.wishkaro.app.push.ReminderAlarmReceiver
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Arms the phone's own alarms from `scheduled_reminder` (FR-REM-02, FR-REM-10).
 *
 * ## Why AlarmManager and not WorkManager
 *
 * WorkManager cannot express an exact time. Its work is batched against Doze maintenance
 * windows, and a 09:00 reminder arriving at 14:00 is the failure this milestone exists to
 * prevent. The only APIs that pierce Doze at an approximately exact instant are
 * `setExactAndAllowWhileIdle` and `setAlarmClock`.
 *
 * WorkManager is still here, underneath, doing the job it is actually good at: a daily
 * worker re-arms everything, because alarms do not survive a force-stop and on several of the
 * OEMs FR-REM-06 names, force-stop is what the battery manager does to you overnight.
 *
 * ## The exact-alarm permission
 *
 * `targetSdk` is 36, and since Android 14 `SCHEDULE_EXACT_ALARM` is denied by default for
 * apps targeting 33+. The install-time alternative, `USE_EXACT_ALARM`, is restricted by Play
 * policy to apps whose core function is an alarm clock or calendar — a birthday reminder is a
 * plausible but not a certain fit, and that is not a bet to make silently inside a milestone
 * about reliability. So this asks for the user-grantable permission and degrades honestly:
 *
 *   granted  -> setExactAndAllowWhileIdle, on time
 *   denied   -> setAndAllowWhileIdle, still pierces Doze, drifts by minutes not hours
 *
 * See docs/M2_PLAN.md §5.
 */
class AndroidReminderScheduler(
    private val context: Context,
    private val local: LocalStore,
    private val now: () -> Instant = Clock.System::now,
) : ReminderScheduler {

    private val alarms: AlarmManager? = context.getSystemService(AlarmManager::class.java)

    override suspend fun rearmAll() {
        val manager = alarms ?: return
        val current = now()

        // Catch up first. A reminder whose moment passed while the device was off, or while
        // the app was force-stopped, is delivered late rather than silently dropped — being
        // an hour late is a far smaller failure than never arriving, which is the failure
        // FR-REM-10 is written about.
        for (row in local.overdueReminders(current.toString())) {
            Log.i(TAG, "Catching up missed reminder ${row.dedupe_key}")
            manager.fireNow(row.dedupe_key)
        }

        for (row in local.upcomingReminders(current.toString(), ALARM_LIMIT)) {
            val at = runCatching { Instant.parse(row.scheduled_at) }.getOrNull() ?: continue
            manager.arm(row.dedupe_key, at.toEpochMilliseconds())
        }
    }

    override fun cancel(dedupeKey: String) {
        alarms?.cancel(pendingIntentFor(dedupeKey))
    }

    override fun canScheduleExact(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return alarms?.canScheduleExactAlarms() == true
    }

    override fun requestExactAlarmPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
            data = Uri.parse("package:${context.packageName}")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        // Guarded like every other settings deep link in this app: the screen does not exist
        // on every build, and a crash is a worse outcome than a missing shortcut.
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "No exact-alarm settings screen on this device", it) }
    }

    @SuppressLint("MissingPermission")
    private fun AlarmManager.arm(dedupeKey: String, atMillis: Long) {
        val intent = pendingIntentFor(dedupeKey)
        try {
            if (canScheduleExact()) {
                setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, intent)
            } else {
                // Inexact, but still Doze-piercing. Minutes of drift, not hours — and the
                // Settings screen is offering the user the permission that removes it.
                setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, intent)
            }
        } catch (denied: SecurityException) {
            // The permission can be revoked between the check and the call. Degrade rather
            // than crash: a slightly late reminder beats a dead process.
            Log.w(TAG, "Exact alarm refused for $dedupeKey; falling back to inexact", denied)
            setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, intent)
        }
    }

    private fun AlarmManager.fireNow(dedupeKey: String) =
        arm(dedupeKey, now().toEpochMilliseconds() + CATCH_UP_DELAY_MS)

    private fun pendingIntentFor(dedupeKey: String): PendingIntent {
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            putExtra(ReminderAlarmReceiver.EXTRA_DEDUPE_KEY, dedupeKey)
            // Distinguishes PendingIntents that are otherwise `filterEquals`-identical.
            // Without it, every alarm would share one PendingIntent and arming the second
            // would silently replace the first.
            data = Uri.parse("wishkaro://alarm/$dedupeKey")
        }
        return PendingIntent.getBroadcast(
            context,
            dedupeKey.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val TAG = "WishKaroScheduler"

        /**
         * A ceiling, not a page. AlarmManager is a shared, finite system resource, and a
         * phone that arms two thousand alarms is a phone the OEM battery manager takes an
         * interest in. Sixty days of a normal address book is a few dozen.
         */
        const val ALARM_LIMIT = 200L

        /** Long enough for the re-arm pass to finish before the catch-up alarms land. */
        const val CATCH_UP_DELAY_MS = 5_000L
    }
}
