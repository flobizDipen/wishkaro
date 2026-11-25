package `in`.wishkaro.app.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import `in`.wishkaro.app.data.repository.ReminderPlanRepository
import `in`.wishkaro.app.platform.ReminderScheduler
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Re-arms every alarm after a reboot or an app update.
 *
 * AlarmManager does neither for you: a reboot clears every alarm in the system, and replacing
 * the package clears this app's. Without this, the first reboot after installing WishKaro
 * would silently disable the entire local fallback and nothing would say so — the reminders
 * would simply stop arriving on the days FCM also failed, which is precisely the overlap the
 * fallback exists for.
 *
 * `scheduled_reminder` is the durable record; the alarms are derived from it, so re-arming is
 * a read and a loop rather than a network call.
 */
class ReminderBootReceiver : BroadcastReceiver(), KoinComponent {

    private val scheduler: ReminderScheduler by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) {
            return
        }

        Log.i(TAG, "Re-arming reminders after $action")
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                scheduler.rearmAll()
                ReminderUpkeepWorker.enqueue(context)
            } catch (cause: Throwable) {
                Log.e(TAG, "Re-arm after $action failed", cause)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "WishKaroBoot"
    }
}

/**
 * The net under AlarmManager.
 *
 * Alarms do not survive a force-stop, and on Xiaomi, Oppo, Vivo, Realme and Samsung a
 * force-stop is what the battery manager does to an app it decides is idle — usually
 * overnight, which is exactly when the morning's reminders are sitting armed. Nothing tells
 * the app this happened.
 *
 * So a periodic worker refetches the plan and re-arms. WorkManager survives force-stop
 * because the OS owns its queue, and it is the right tool for this because it does NOT need
 * to be exact — "sometime in the next day" is fine for a repair job, and disastrous for the
 * reminder itself, which is why the two are different mechanisms.
 */
class ReminderUpkeepWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {

    private val plan: ReminderPlanRepository by inject()
    private val scheduler: ReminderScheduler by inject()

    override suspend fun doWork(): Result {
        return try {
            // Re-arm first, from what is already stored. The refresh needs a network and the
            // re-arm does not, so doing it in this order means a phone that has been offline
            // for a week still comes out of this with its alarms set.
            scheduler.rearmAll()
            plan.refresh()
            scheduler.rearmAll()
            Result.success()
        } catch (cause: Throwable) {
            Log.w(TAG, "Upkeep failed; will retry", cause)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "WishKaroUpkeep"
        private const val NAME = "wishkaro-reminder-upkeep"

        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReminderUpkeepWorker>(
                REPEAT_HOURS,
                TimeUnit.HOURS,
            ).build()

            // KEEP, not REPLACE: this is called from app launch and from the boot receiver,
            // and REPLACE would reset the period every time the user opened the app — which
            // on a phone opened several times a day means the worker never actually runs.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * Twelve hours, not twenty-four. A repair that runs once a day can be up to a day
         * late, and the thing it repairs is "the alarms for tomorrow morning were destroyed
         * last night" — so the window has to be comfortably shorter than the gap between a
         * battery manager killing the app and the next morning.
         */
        private const val REPEAT_HOURS = 12L
    }
}
