package `in`.wishkaro.app.data.repository

import `in`.wishkaro.app.data.local.PlanDiff
import `in`.wishkaro.app.data.local.ScheduledReminderRow
import `in`.wishkaro.app.data.remote.PlanResponse
import `in`.wishkaro.app.domain.BirthdayMath
import `in`.wishkaro.app.domain.QuietHours
import `in`.wishkaro.app.domain.ReminderSettings
import `in`.wishkaro.app.platform.ReminderScheduler
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Keeps the device's own copy of the reminder schedule, and the alarms armed from it.
 *
 * ## Why the client replays a plan instead of computing one
 *
 * docs/M2_PLAN.md §1.3. Making the dedupe key derivable (`${eventId}:${year}:${offset}`)
 * lets both sides agree on a reminder's IDENTITY. It does not make its SCHEDULE or its COPY
 * derivable: that would mean reimplementing the ladder, the solemn suppression of T-7,
 * per-person delivery hours, quiet-hours deferral, digest grouping and `override_date` —
 * which the client cannot even see — plus the SOLEMN string set that the server wraps in a
 * 210-case cross-product test. Two implementations of the tone rules in two languages is the
 * thing this project has twice decided not to have, and the failure it decided it for is
 * wishing a dead person a happy birthday.
 *
 * So: the server renders, the client replays, and both paths carry identical keys by
 * construction rather than by two implementations agreeing.
 */
/**
 * The slice of [LocalStore] this repository needs.
 *
 * A narrow interface for the same reason [SyncStore] is one: `LocalStore` owns a SQLDelight
 * driver and cannot be stood up in `commonTest`, which is the only test source set this
 * module has. The part that carries risk here is what the repository DECIDES — which events
 * get a fallback alarm, what it is allowed to say, and when it is withdrawn — and that is
 * testable against a hand-written fake with no database at all.
 *
 * Not a general abstraction over storage. Nothing but this repository should take it.
 */
/**
 * The one call this repository makes over the network.
 *
 * Narrow for the same reason as [SyncApi]: `WishKaroApi` owns Ktor and token refresh, and the
 * fallback path never touches it at all — so a test of what the client decides should not have
 * to build an HTTP client to prove it.
 */
interface PlanApi {
    suspend fun fetchPlan(days: Int): PlanResponse
}

interface PlanStore {
    suspend fun reminderSettings(): ReminderSettings
    suspend fun replacePlan(entries: List<ScheduledReminderRow>): PlanDiff
    suspend fun unsyncedEvents(): List<wishkaro.db.Event>
    suspend fun findPerson(id: String): `in`.wishkaro.app.domain.Person?
    suspend fun localFallbacks(): List<wishkaro.db.Scheduled_reminder>
    suspend fun upsertLocalFallback(entry: ScheduledReminderRow)
    suspend fun deleteScheduled(dedupeKey: String)
}

class ReminderPlanRepository(
    private val api: PlanApi,
    private val local: PlanStore,
    private val scheduler: ReminderScheduler,
    private val now: () -> Instant = Clock.System::now,
) {

    /**
     * Fetches the plan and re-arms from it.
     *
     * Returns false rather than throwing when the network is not there — this runs on app
     * launch, after every sync and from a daily worker, and none of those should surface a
     * failure to the user. The alarms already armed keep working; that is the entire point
     * of storing them.
     */
    suspend fun refresh(horizonDays: Int = PLAN_HORIZON_DAYS): Boolean {
        val response = runCatching { api.fetchPlan(horizonDays) }.getOrNull() ?: return false

        local.replacePlan(
            response.reminders.map {
                ScheduledReminderRow(
                    dedupeKey = it.dedupeKey,
                    scheduledAt = it.scheduledAtUtc,
                    offset = it.offset,
                    title = it.title,
                    body = it.body,
                    personId = it.personId,
                    personName = it.personName,
                    eventType = it.eventType,
                    sentiment = it.sentiment,
                    deepLink = it.deepLink,
                )
            },
        )
        rebuildLocalFallbacks()
        scheduler.rearmAll()
        return true
    }

    /**
     * A D0 alarm for every event the server has never seen.
     *
     * The narrow gap the plan design leaves open: a person added in aeroplane mode has no
     * reminder row on the server, so the plan says nothing about them, so without this they
     * get no local alarm either. FR-REM-10 is specifically about the device being offline,
     * so leaving that hole would be odd.
     *
     * Three things keep it honest:
     *
     *  - **Only unsynced events qualify** (`pending_sync = 1`). The moment an event reaches
     *    the server the plan governs it, and the fallback is withdrawn — otherwise a
     *    fallback firing at the global hour would claim the key and suppress the server's
     *    correctly-scheduled reminder.
     *  - **The copy is tone-neutral.** The client does not know whether this person is
     *    deceased in the way the server's derivation does, and a client-authored
     *    "Happy birthday!" is the one failure this product cannot have. So it names the
     *    person and the date and makes no claim about the occasion.
     *  - **The key is the same** `${eventId}:${year}:D0`, so when the server does materialise
     *    and push, the claim already exists and the user still sees exactly one.
     */
    suspend fun rebuildLocalFallbacks() {
        val settings = local.reminderSettings()
        val zone = TimeZone.currentSystemDefault()
        val today = Clock.System.todayIn(zone)

        val existing = local.localFallbacks().associateBy { it.dedupe_key }
        val wanted = mutableSetOf<String>()

        for (event in local.unsyncedEvents()) {
            val occursOn = BirthdayMath.nextOccurrence(
                day = event.day.toInt(),
                month = event.month.toInt(),
                timeZone = zone,
                today = today,
            )
            val fireAt = quietAdjusted(occursOn, settings.reminderHour, settings, zone)
            if (fireAt <= now()) continue

            val key = "${event.id}:${occursOn.year}:D0"
            wanted += key
            val person = local.findPerson(event.person_id)
            local.upsertLocalFallback(
                ScheduledReminderRow(
                    dedupeKey = key,
                    scheduledAt = fireAt.toString(),
                    offset = "D0",
                    // Deliberately flat. See the note above: the client must never author
                    // celebratory copy, because it cannot know what the server knows.
                    title = person?.displayName?.let { "$it — today" } ?: "A date today",
                    // No product name: "WishKaro" contains this app's own word for the
                    // celebratory action, and the tone-neutrality test should not have to be
                    // taught to ignore it on the one notification the CLIENT writes. Android
                    // prints the app name above every notification anyway.
                    body = "A date you saved is today. Open the app for the details.",
                    personId = event.person_id,
                    personName = person?.displayName,
                    eventType = event.type,
                    // Absent on purpose: unknown, not CELEBRATORY. The notification layer
                    // treats a missing sentiment as "offer nothing that assumes a mood".
                    sentiment = null,
                    deepLink = "wishkaro://person/${event.person_id}",
                ),
            )
        }

        // Withdraw fallbacks whose event has since reached the server, or moved.
        for ((key, row) in existing) {
            if (key !in wanted && row.fired_at == null) {
                local.deleteScheduled(key)
                scheduler.cancel(key)
            }
        }
    }

    /**
     * The one place quiet hours are evaluated on the client.
     *
     * The server normally does this for us — `scheduled_at_utc` arrives already deferred —
     * so this exists solely for the local fallback above. It is a second implementation of
     * FR-REM-09, which is a cost worth naming: both are tested against the same table of
     * cases, the midnight-spanning window included.
     */
    private fun quietAdjusted(
        date: LocalDate,
        hour: Int,
        settings: ReminderSettings,
        zone: TimeZone,
    ): Instant {
        val start = settings.quietHoursStart
        val end = settings.quietHoursEnd
        val due = LocalDateTime(date.year, date.month, date.day, hour, 0).toInstant(zone)
        if (start == null || end == null || start == end) return due
        if (!QuietHours.isQuietHour(hour, start, end)) return due

        // The next time it is `end` o'clock after the due instant: the same day for an
        // early-morning reminder, the next day for a late-evening one.
        val sameDay = LocalDateTime(date.year, date.month, date.day, end, 0).toInstant(zone)
        if (sameDay > due) return sameDay
        val nextDay = date.plusDays(1)
        return LocalDateTime(nextDay.year, nextDay.month, nextDay.day, end, 0).toInstant(zone)
    }

    private companion object {
        /** Comfortably past FR-REM-10's "offline > 48h", and inside the server's own cap. */
        const val PLAN_HORIZON_DAYS = 60
    }
}

private fun LocalDate.plusDays(n: Int): LocalDate = plus(DatePeriod(days = n))
