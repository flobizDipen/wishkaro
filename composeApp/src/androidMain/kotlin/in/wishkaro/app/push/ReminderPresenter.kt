package `in`.wishkaro.app.push

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import `in`.wishkaro.app.MainActivity
import `in`.wishkaro.app.R
import `in`.wishkaro.app.data.local.LocalStore
import `in`.wishkaro.app.platform.NotificationChannels
import kotlin.time.Clock

/**
 * Draws a reminder, exactly once, whichever path delivered it.
 *
 * The two paths — the server's FCM push and the phone's own alarm — must produce one
 * notification between them (FR-REM-02), so the claim, the drawing, the action buttons and
 * the delivery report all live here rather than being written twice. Whoever gets there
 * first wins the key; the loser reports a suppressed duplicate, which is the de-duplication
 * working rather than a delivery failure.
 */
object ReminderPresenter {

    const val OUTCOME_SHOWN = "SHOWN"
    const val OUTCOME_SUPPRESSED_DUPLICATE = "SUPPRESSED_DUPLICATE"
    const val OUTCOME_BLOCKED_NO_PERMISSION = "BLOCKED_NO_PERMISSION"

    const val CHANNEL_FCM = "FCM"
    const val CHANNEL_LOCAL = "LOCAL"

    private const val TAG = "WishKaroReminder"

    data class Reminder(
        val dedupeKey: String,
        val title: String,
        val body: String,
        val offset: String,
        val type: String,
        val personId: String?,
        val personName: String?,
        val sentiment: String?,
        val deepLink: String?,
    )

    /**
     * Claims the key, draws the notification and records the outcome.
     *
     * Returns what happened, so the caller can log it; the delivery report is written here
     * regardless, because a path that forgot to report would be indistinguishable from a
     * path that never fired — and telling those apart is the whole point of FR-REM-10.
     */
    suspend fun present(
        context: Context,
        local: LocalStore,
        reminder: Reminder,
        channel: String,
    ): String {
        val nowIso = Clock.System.now().toString()

        val first = local.claimReminder(reminder.dedupeKey, nowIso)
        if (!first) {
            Log.i(TAG, "Duplicate reminder ${reminder.dedupeKey} suppressed (via $channel)")
            local.recordDelivery(reminder.dedupeKey, OUTCOME_SUPPRESSED_DUPLICATE, channel, nowIso)
            return OUTCOME_SUPPRESSED_DUPLICATE
        }

        val outcome = if (show(context, reminder)) OUTCOME_SHOWN else OUTCOME_BLOCKED_NO_PERMISSION
        local.recordDelivery(reminder.dedupeKey, outcome, channel, nowIso)
        return outcome
    }

    private fun show(context: Context, reminder: Reminder): Boolean {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Reminder arrived but POST_NOTIFICATIONS is not granted")
            return false
        }

        val notificationId = reminder.dedupeKey.hashCode()
        val builder = NotificationCompat.Builder(context, channelFor(reminder))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(reminder.title)
            .setContentText(reminder.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent(context, reminder, notificationId))

        for (action in actionsFor(reminder)) {
            builder.addAction(0, action.label, action.intent(context, reminder, notificationId))
        }

        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
        return true
    }

    /**
     * One channel per ladder rung (FR-REM-05), so the T-7 gifting nudge can be silenced
     * without losing the morning-of reminder. All of them were created at first launch,
     * because an Android channel's importance cannot be raised afterwards.
     *
     * An offset this build does not recognise falls back to morning-of rather than being
     * dropped: an unknown rung from a newer server is still a reminder someone is waiting for.
     */
    private fun channelFor(reminder: Reminder): String = when {
        reminder.type == "TEST" -> NotificationChannels.SELF_TEST
        reminder.offset == "DIGEST" -> NotificationChannels.DIGEST
        reminder.offset == "D7" -> NotificationChannels.WEEK_BEFORE
        reminder.offset == "D1" -> NotificationChannels.DAY_BEFORE
        else -> NotificationChannels.MORNING_OF
    }

    private fun openIntent(context: Context, reminder: Reminder, id: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            reminder.deepLink?.let { data = Uri.parse(it) }
        }
        return PendingIntent.getActivity(
            context,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private data class Action(
        val label: String,
        val intent: (Context, Reminder, Int) -> PendingIntent,
    )

    /**
     * Notification actions (FR-REM-04, FR-REM-11).
     *
     * Three rules, and the third is the one with teeth:
     *
     *  - T-7 leads with **Gift ideas**, morning-of leads with **Send wish** (FR-REM-04).
     *  - Snooze and Mark done are wired for real. Send wish and Gift ideas belong to M5 and
     *    M8, so they open the person rather than inventing half a feature.
     *  - **A SOLEMN reminder offers only Send message and Mark done** (FR-REM-11). Gift ideas
     *    is ABSENT, not disabled, and so is Snooze — "remind me again in an hour" is not a
     *    thing to offer about a death anniversary. REQUIREMENTS.md §11 files this under
     *    correctness.
     *
     * A reminder with no sentiment at all — the local fallback for an event the server has
     * never seen — is treated as unknown rather than celebratory, and gets the solemn-safe
     * set. The client does not know what the server knows, so it offers nothing that assumes
     * a mood.
     */
    private fun actionsFor(reminder: Reminder): List<Action> {
        if (reminder.type == "TEST" || reminder.type == "DIGEST") return emptyList()
        if (reminder.personId == null) return emptyList()

        val markDone = Action("Mark done") { ctx, r, id ->
            ReminderActionReceiver.pendingIntent(ctx, ReminderActionReceiver.ACTION_MARK_DONE, r, id)
        }

        if (reminder.sentiment != "CELEBRATORY") {
            return listOf(
                Action("Send message") { ctx, r, id -> openIntent(ctx, r, id + ACTION_ID_OFFSET) },
                markDone,
            )
        }

        val sendWish = Action("Send wish") { ctx, r, id -> openIntent(ctx, r, id + ACTION_ID_OFFSET) }
        val giftIdeas = Action("Gift ideas") { ctx, r, id ->
            openIntent(ctx, r, id + ACTION_ID_OFFSET * 2)
        }
        val snooze = Action("Snooze 1h") { ctx, r, id ->
            ReminderActionReceiver.pendingIntent(ctx, ReminderActionReceiver.ACTION_SNOOZE, r, id)
        }

        // T-7 is framed around gifting, so it leads with the gift action; morning-of leads
        // with the wish. Android shows at most three.
        return if (reminder.offset == "D7") {
            listOf(giftIdeas, snooze, markDone)
        } else {
            listOf(sendWish, snooze, markDone)
        }
    }

    /** Keeps an action's PendingIntent request code clear of the notification's own. */
    private const val ACTION_ID_OFFSET = 1_000_003
}
