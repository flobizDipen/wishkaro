package `in`.wishkaro.app.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.todayIn
import kotlinx.datetime.plus
import kotlinx.datetime.DatePeriod
import java.util.concurrent.TimeUnit
import kotlin.time.Clock

/** The manifest entry point. Glance finds the widget through this. */
class UpcomingWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = UpcomingWidget()
}

/**
 * FR-WID-03 — the widget updates on data change and at least daily at midnight local.
 *
 * Two mechanisms, because they answer two different questions.
 *
 * [refresh] is the data-change half: anything that writes a person or an event calls it, and
 * the widget redraws from the same query the app just changed.
 *
 * [MidnightWidgetWorker] is the clock half, and it is NOT redundant. Nothing writes to the
 * database at midnight, yet every countdown on the widget changes meaning: "Tomorrow"
 * becomes "Today", and the person whose birthday it is moves to the top. A widget that only
 * refreshes on writes would greet the user on the morning itself with yesterday's wording —
 * which is the single most visible thing this app could get wrong.
 *
 * Riding on M2's existing WorkManager infrastructure, as the build prompt says: a second
 * job, not new plumbing.
 */
object WidgetUpdates {

    private const val TAG = "WishKaroWidget"
    private const val WORK_NAME = "wishkaro-widget-midnight"

    /**
     * Redraw every placed widget. Safe to call when none are placed, and safe to call often.
     *
     * Never throws at the caller: a widget that fails to redraw is a cosmetic problem, and
     * letting it take down the write that triggered it would turn that into a real one.
     */
    suspend fun refresh(context: Context) {
        try {
            // Ask first. The data-change half of FR-WID-03 watches the people flow for the
            // life of the process and calls this on every write, and most installs have no
            // widget on a home screen at all — this is the one decision [isPlaced] exists
            // to inform, and it was being computed and then not read, which is the failure
            // MILESTONE_CHECKLIST.md §C is a list of.
            if (!isPlaced(context)) {
                Log.d(TAG, "No widget placed; nothing to refresh")
                return
            }
            UpcomingWidget().updateAll(context)
        } catch (cause: Throwable) {
            Log.w(TAG, "Could not refresh the widget", cause)
        }
    }

    /** True when the user actually has one on a home screen. Keeps the logs honest. */
    suspend fun isPlaced(context: Context): Boolean = try {
        GlanceAppWidgetManager(context).getGlanceIds(UpcomingWidget::class.java).isNotEmpty()
    } catch (cause: Throwable) {
        Log.w(TAG, "Could not ask whether a widget is placed", cause)
        false
    }

    /**
     * Schedules the midnight redraw.
     *
     * The first run is aligned to the next local midnight and it repeats daily. WorkManager
     * cannot promise the exact instant — it batches against Doze — and that is fine here in
     * a way it would not be for a reminder: a countdown that updates at 00:12 is correct by
     * the time anybody looks at it, which is why M2 used AlarmManager for reminders and
     * WorkManager for repair. Same division, same reasons.
     */
    fun schedule(context: Context) {
        val zone = TimeZone.currentSystemDefault()
        val now = Clock.System.now()
        val nextMidnight = Clock.System.todayIn(zone)
            .plus(DatePeriod(days = 1))
            .atStartOfDayIn(zone)
        val delay = (nextMidnight - now).inWholeMinutes.coerceAtLeast(1)

        val request = PeriodicWorkRequestBuilder<MidnightWidgetWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delay, TimeUnit.MINUTES)
            .build()

        // KEEP for the same reason M2's upkeep worker uses it: this is called on every app
        // launch, and REPLACE would reset the period each time — so on a phone opened
        // several times a day the job would never actually run.
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}

class MidnightWidgetWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        WidgetUpdates.refresh(applicationContext)
        return Result.success()
    }
}

