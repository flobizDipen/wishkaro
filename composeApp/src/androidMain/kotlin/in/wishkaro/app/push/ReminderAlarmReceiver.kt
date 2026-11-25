package `in`.wishkaro.app.push

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import `in`.wishkaro.app.data.local.LocalStore
import `in`.wishkaro.app.platform.ReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.time.Clock
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * The local half of the reminder engine (FR-REM-02, FR-REM-10).
 *
 * Fires from AlarmManager with no network involved at all — which is the point. It reads the
 * reminder the server already rendered out of `scheduled_reminder` and hands it to
 * [ReminderPresenter], so a reminder delivered during a week-long outage says exactly what
 * the push would have said and carries exactly the same dedupe key.
 */
class ReminderAlarmReceiver : BroadcastReceiver(), KoinComponent {

    private val local: LocalStore by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val dedupeKey = intent.getStringExtra(EXTRA_DEDUPE_KEY)
        if (dedupeKey == null) {
            Log.w(TAG, "Alarm fired with no dedupe key")
            return
        }

        // The work outlives onReceive, so the broadcast has to be held open. Without this the
        // process can be killed mid-write and the reminder is lost — on a phone whose OEM is
        // already looking for an excuse to kill it.
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                fire(context, dedupeKey)
            } catch (cause: Throwable) {
                Log.e(TAG, "Local reminder $dedupeKey failed", cause)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun fire(context: Context, dedupeKey: String) {
        val row = local.scheduledReminder(dedupeKey)
        if (row == null) {
            Log.i(TAG, "Alarm for $dedupeKey has no plan row; the server must have cancelled it")
            return
        }
        if (row.fired_at != null) {
            Log.i(TAG, "Alarm for $dedupeKey already fired")
            return
        }

        val outcome = ReminderPresenter.present(
            context = context,
            local = local,
            reminder = ReminderPresenter.Reminder(
                dedupeKey = row.dedupe_key,
                title = row.title,
                body = row.body,
                offset = row.offset_rung,
                type = if (row.offset_rung == "DIGEST") "DIGEST" else "REMINDER",
                personId = row.person_id,
                personName = row.person_name,
                sentiment = row.sentiment,
                deepLink = row.deep_link,
            ),
            channel = ReminderPresenter.CHANNEL_LOCAL,
        )

        // Marked fired whatever the outcome, including a suppressed duplicate: the alarm has
        // done its job either way, and leaving it unmarked would have the catch-up pass on
        // the next launch try it again.
        local.markReminderFired(dedupeKey, Clock.System.now().toString())
        Log.i(TAG, "Local alarm $dedupeKey -> $outcome")
    }

    companion object {
        const val EXTRA_DEDUPE_KEY = "dedupeKey"
        private const val TAG = "WishKaroAlarm"
    }
}

/**
 * Snooze and Mark done (FR-REM-04).
 *
 * Both are wired for real, unlike Send wish and Gift ideas, which belong to M5 and M8 and
 * open the person instead.
 */
class ReminderActionReceiver : BroadcastReceiver(), KoinComponent {

    private val local: LocalStore by inject()
    private val scheduler: ReminderScheduler by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val dedupeKey = intent.getStringExtra(ReminderAlarmReceiver.EXTRA_DEDUPE_KEY) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, dedupeKey.hashCode())
        NotificationManagerCompat.from(context).cancel(notificationId)

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    ACTION_SNOOZE -> snooze(dedupeKey)
                    ACTION_MARK_DONE -> markDone(dedupeKey)
                }
            } catch (cause: Throwable) {
                Log.e(TAG, "Action ${intent.action} on $dedupeKey failed", cause)
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Re-arms the same reminder an hour out, under a distinct key.
     *
     * `:snooze` rather than the original key, because the original has already been claimed
     * in `seen_reminder` — re-arming it would fire an alarm that the de-duplication then
     * swallowed, which looks from the outside exactly like a snooze that silently did
     * nothing. The suffix is appended rather than replacing the key so the row still says
     * which reminder it came from.
     */
    private suspend fun snooze(dedupeKey: String) {
        val row = local.scheduledReminder(dedupeKey) ?: return
        val at = Clock.System.now().plus(kotlin.time.Duration.parse("1h"))
        val snoozedKey = "$dedupeKey:snooze:${at.toEpochMilliseconds()}"

        local.upsertLocalFallback(
            `in`.wishkaro.app.data.local.ScheduledReminderRow(
                dedupeKey = snoozedKey,
                scheduledAt = at.toString(),
                offset = row.offset_rung,
                title = row.title,
                body = row.body,
                personId = row.person_id,
                personName = row.person_name,
                eventType = row.event_type,
                sentiment = row.sentiment,
                deepLink = row.deep_link,
            ),
        )
        scheduler.rearmAll()
        Log.i(TAG, "Snoozed $dedupeKey until $at")
    }

    /**
     * Marks the reminder done so nothing else fires for it.
     *
     * The key stays claimed in `seen_reminder`, so a push that arrives afterwards — the
     * server's copy, late — is suppressed rather than redrawing a notification the user has
     * already dealt with.
     */
    private suspend fun markDone(dedupeKey: String) {
        local.markReminderFired(dedupeKey, Clock.System.now().toString())
        local.claimReminder(dedupeKey, Clock.System.now().toString())
        scheduler.cancel(dedupeKey)
        Log.i(TAG, "Marked $dedupeKey done")
    }

    companion object {
        const val ACTION_SNOOZE = "in.wishkaro.app.action.SNOOZE"
        const val ACTION_MARK_DONE = "in.wishkaro.app.action.MARK_DONE"
        const val EXTRA_NOTIFICATION_ID = "notificationId"
        private const val TAG = "WishKaroAction"

        fun pendingIntent(
            context: Context,
            action: String,
            reminder: ReminderPresenter.Reminder,
            notificationId: Int,
        ): PendingIntent {
            val intent = Intent(context, ReminderActionReceiver::class.java).apply {
                this.action = action
                putExtra(ReminderAlarmReceiver.EXTRA_DEDUPE_KEY, reminder.dedupeKey)
                putExtra(EXTRA_NOTIFICATION_ID, notificationId)
            }
            return PendingIntent.getBroadcast(
                context,
                // Distinct per (reminder, action), or Android reuses one PendingIntent for
                // both buttons and Snooze starts marking things done.
                notificationId * 31 + action.hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
