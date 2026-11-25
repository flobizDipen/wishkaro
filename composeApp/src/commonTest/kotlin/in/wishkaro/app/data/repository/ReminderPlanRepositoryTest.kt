package `in`.wishkaro.app.data.repository

import `in`.wishkaro.app.data.local.PlanDiff
import `in`.wishkaro.app.data.local.ScheduledReminderRow
import `in`.wishkaro.app.data.remote.PlanReminderDto
import `in`.wishkaro.app.data.remote.PlanResponse
import `in`.wishkaro.app.domain.Person
import `in`.wishkaro.app.domain.ReminderSettings
import `in`.wishkaro.app.domain.ReminderSource
import `in`.wishkaro.app.platform.ReminderScheduler
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The local fallback: a D0 alarm for an event the server has never seen.
 *
 * It is the one place in M2 where the CLIENT decides what a notification says, and the plan
 * (`M2_PLAN.md` §1.3) is explicit that it must never author celebratory copy — the device
 * cannot know what the server knows about whether a person is deceased, and "Happy birthday!"
 * for someone who has died is the failure this product cannot have. That property has to be
 * asserted, not left in a comment.
 *
 * Tested through the narrow [PlanStore] seam rather than LocalStore, which owns a SQLDelight
 * driver and cannot be built in commonTest. The risk here is in what the repository DECIDES,
 * and that needs no database.
 */
class ReminderPlanRepositoryTest {

    private val NOW = Instant.parse("2026-11-25T06:00:00Z")

    /**
     * Pinned, not read from the machine.
     *
     * Every instant asserted below is an absolute UTC moment derived from a LOCAL hour, so a
     * test that took the runner's zone would pass in Asia/Kolkata and fail on a UTC CI box —
     * which is exactly what happened. The product's single highest-bug-density area is
     * timezone handling; its tests should not be the one place that quietly depends on where
     * they run.
     */
    private val IST = kotlinx.datetime.TimeZone.of("Asia/Kolkata")

    @Test
    fun armsAFallbackForAnEventTheServerHasNeverSeen() = runTest {
        val store = FakePlanStore(unsynced = listOf(event("e1", day = 20, month = 12)))
        val repo = repo(store)

        repo.rebuildLocalFallbacks()

        val row = store.fallbacks.values.single()
        assertEquals("e1:2026:D0", row.dedupeKey)
        assertEquals("D0", row.offset)
    }

    /**
     * The property that must not drift. The copy names the person and makes no claim about
     * the occasion — no "birthday", no "turns", no wish, no emoji.
     */
    @Test
    fun theCopyIsToneNeutral() = runTest {
        val store = FakePlanStore(
            unsynced = listOf(event("e1", day = 20, month = 12)),
            people = mapOf("p1" to person("Aai")),
        )
        repo(store).rebuildLocalFallbacks()

        val row = store.fallbacks.values.single()
        val text = "${row.title} ${row.body}".lowercase()
        for (banned in listOf("birthday", "happy", "wish", "celebrat", "congrat", "turns", "gift")) {
            assertTrue(banned !in text, "client-authored copy said \"$banned\": $text")
        }
        assertTrue(Regex("\\p{So}").find(row.title) == null, "client-authored copy carried an emoji")
        assertTrue(row.personName == "Aai")
    }

    /**
     * And it claims no sentiment, so the notification layer offers only the actions that are
     * safe without knowing the mood.
     */
    @Test
    fun itClaimsNoSentiment() = runTest {
        val store = FakePlanStore(unsynced = listOf(event("e1", day = 20, month = 12)))
        repo(store).rebuildLocalFallbacks()

        assertNull(store.fallbacks.values.single().sentiment)
    }

    /**
     * The gate that stops a fallback stealing a real reminder: once an event has reached the
     * server, the plan governs it. A fallback firing at the global hour would otherwise claim
     * the dedupe key and suppress the server's correctly-scheduled one.
     */
    @Test
    fun withdrawsTheFallbackOnceTheEventHasSynced() = runTest {
        val store = FakePlanStore(unsynced = listOf(event("e1", day = 20, month = 12)))
        val repo = repo(store)
        repo.rebuildLocalFallbacks()
        assertEquals(1, store.fallbacks.size)

        // Next pass: the event is no longer pending, so it is no longer unsynced.
        store.unsynced = emptyList()
        repo.rebuildLocalFallbacks()

        assertEquals(0, store.fallbacks.size)
        assertEquals(listOf("e1:2026:D0"), store.cancelled)
    }

    /** A fallback that has already fired is history and must not be withdrawn or re-armed. */
    @Test
    fun leavesAFiredFallbackAlone() = runTest {
        val store = FakePlanStore(unsynced = listOf(event("e1", day = 20, month = 12)))
        repo(store).rebuildLocalFallbacks()
        store.markFired("e1:2026:D0")
        store.unsynced = emptyList()

        repo(store).rebuildLocalFallbacks()

        assertEquals(1, store.fallbacks.size)
        assertTrue(store.cancelled.isEmpty())
    }

    @Test
    fun armsNothingForAnOccurrenceAlreadyPast() = runTest {
        // 09:00 on 25 November is 03:30Z, already behind `NOW` of 06:00Z.
        val store = FakePlanStore(unsynced = listOf(event("e1", day = 25, month = 11)))
        repo(store).rebuildLocalFallbacks()

        assertEquals(0, store.fallbacks.size)
    }

    /**
     * Quiet hours are the server's job everywhere except here, so the client's own copy of
     * the rule has to be exercised on the one path that uses it.
     */
    @Test
    fun defersAFallbackPastTheQuietWindow() = runTest {
        val store = FakePlanStore(
            unsynced = listOf(event("e1", day = 20, month = 12)),
            settings = ReminderSettings(reminderHour = 23, quietHoursStart = 22, quietHoursEnd = 7),
        )
        repo(store).rebuildLocalFallbacks()

        // 23:00 on the 20th is inside 22:00–07:00, so it lands at 07:00 on the 21st.
        val at = Instant.parse(store.fallbacks.values.single().scheduledAt)
        assertEquals(Instant.parse("2026-12-21T01:30:00Z"), at)
    }

    @Test
    fun leavesAnHourOutsideTheWindowAlone() = runTest {
        val store = FakePlanStore(
            unsynced = listOf(event("e1", day = 20, month = 12)),
            settings = ReminderSettings(reminderHour = 9, quietHoursStart = 22, quietHoursEnd = 7),
        )
        repo(store).rebuildLocalFallbacks()

        val at = Instant.parse(store.fallbacks.values.single().scheduledAt)
        assertEquals(Instant.parse("2026-12-20T03:30:00Z"), at)
    }

    /**
     * Snooze 1h survives a plan refresh.
     *
     * `rebuildLocalFallbacks()` reconciles by deleting every LOCAL_FALLBACK row it does not
     * currently want, and a snooze key — `${'$'}{dedupeKey}:snooze:${'$'}{millis}` — can never be in
     * that wanted set. Written as LOCAL_FALLBACK it was therefore deleted and its alarm
     * cancelled by the next refresh: app launch, any completed sync, or the upkeep worker.
     * The user taps Snooze at 09:00 and 10:00 passes in silence.
     */
    @Test
    fun aSnoozedReminderSurvivesAPlanRefresh() = runTest {
        val store = FakePlanStore()
        store.addSnooze(
            ScheduledReminderRow(
                dedupeKey = "e1:2026:D0:snooze:1795615088632",
                scheduledAt = "2026-11-25T10:00:00Z",
                offset = "D0",
                title = "Ravi — today",
                body = "A date you saved is today.",
                personId = "p1",
            ),
        )

        repo(store).rebuildLocalFallbacks()

        assertTrue(
            "e1:2026:D0:snooze:1795615088632" in store.rows,
            "the snooze row was deleted by the fallback reconciliation",
        )
        assertTrue(store.cancelled.isEmpty(), "the snooze alarm was cancelled")
    }

    /**
     * And the fix must not disable the reconciliation it sits inside: a fallback for an event
     * that has since reached the server is still withdrawn, snooze row present or not.
     */
    @Test
    fun stillWithdrawsASyncedFallbackWhileASnoozeIsPending() = runTest {
        val store = FakePlanStore(unsynced = listOf(event("e1", day = 20, month = 12)))
        repo(store).rebuildLocalFallbacks()
        assertEquals(1, store.fallbacks.size)

        store.addSnooze(
            ScheduledReminderRow(
                dedupeKey = "e9:2026:D0:snooze:1",
                scheduledAt = "2026-11-25T10:00:00Z",
                offset = "D0",
                title = "x",
                body = "y",
                personId = "p9",
            ),
        )
        store.unsynced = emptyList()
        repo(store).rebuildLocalFallbacks()

        assertEquals(0, store.fallbacks.size, "the synced event's fallback was not withdrawn")
        assertEquals(listOf("e1:2026:D0"), store.cancelled)
        assertTrue("e9:2026:D0:snooze:1" in store.rows, "the snooze was collateral damage")
    }

    /**
     * A plan refresh cancels the alarms for rows the new plan no longer contains.
     *
     * `rearmAll()` only ARMS from the table, so a row deleted by `replacePlan` left its alarm
     * behind: a stale RTC_WAKEUP waking the device to find nothing. Mute an event or switch
     * digest on and every suppressed rung leaves one, on exactly the OEM phones where
     * AndroidReminderScheduler notes that arming a lot of alarms attracts attention.
     */
    @Test
    fun cancelsAlarmsForRowsTheNewPlanDropped() = runTest {
        val store = FakePlanStore(removedByPlan = listOf("e1:2026:D7", "e1:2026:D1"))

        repo(store, plan = emptyList()).refresh()

        assertEquals(listOf("e1:2026:D7", "e1:2026:D1"), store.cancelled)
    }

    @Test
    fun cancelsNothingWhenThePlanIsUnchanged() = runTest {
        val store = FakePlanStore()

        repo(store, plan = emptyList()).refresh()

        assertTrue(store.cancelled.isEmpty())
    }

    private fun repo(store: FakePlanStore, plan: List<PlanReminderDto>? = null) =
        ReminderPlanRepository(
            api = object : PlanApi {
                override suspend fun fetchPlan(days: Int) =
                    // The fallback path never fetches, so a call there would be a bug.
                    plan?.let { PlanResponse(reminders = it) }
                        ?: throw UnsupportedOperationException("the fallback path must not fetch")
            },
            local = store,
            scheduler = store.scheduler,
            now = { NOW },
            zone = { IST },
        )
}

private fun event(id: String, day: Int, month: Int) = wishkaro.db.Event(
    id = id,
    person_id = "p1",
    type = "BIRTHDAY",
    day = day.toLong(),
    month = month.toLong(),
    year = 1990L,
    updated_at = "2026-11-25T05:00:00Z",
    deleted_at = null,
    pending_sync = 1L,
    sentiment = "CELEBRATORY",
    label = null,
    is_muted = 0L,
)

private fun person(name: String) = Person(
    id = "p1",
    displayName = name,
    relationToOwner = "Friend",
    isSelf = false,
    gender = null,
    phone = null,
    email = null,
    notes = null,
    isDeceased = false,
    isMinor = false,
    completenessScore = 0,
    completenessSuggestions = emptyList(),
    deletedAt = null,
    events = emptyList(),
    traits = emptyList(),
)

private class FakePlanStore(
    var unsynced: List<wishkaro.db.Event> = emptyList(),
    private val people: Map<String, Person> = emptyMap(),
    private val settings: ReminderSettings = ReminderSettings(),
    /** What `staleFromPlan` would find: rows the incoming plan no longer contains. */
    private val removedByPlan: List<String> = emptyList(),
) : PlanStore {
    /** Keyed by dedupe key, valued by (row, source) — the SQL stores a source per row. */
    val rows = mutableMapOf<String, Pair<ScheduledReminderRow, String>>()
    val fallbacks: Map<String, ScheduledReminderRow>
        get() = rows.filterValues { it.second == ReminderSource.LOCAL_FALLBACK }
            .mapValues { it.value.first }
    private val fired = mutableSetOf<String>()
    val cancelled = mutableListOf<String>()

    val scheduler = object : ReminderScheduler {
        override suspend fun rearmAll() = Unit
        override fun cancel(dedupeKey: String) { cancelled += dedupeKey }
        override fun canScheduleExact() = true
        override fun requestExactAlarmPermission() = Unit
    }

    fun markFired(key: String) { fired += key }

    override suspend fun reminderSettings() = settings
    override suspend fun replacePlan(entries: List<ScheduledReminderRow>) =
        PlanDiff(added = entries.map { it.dedupeKey }, removed = removedByPlan)
    override suspend fun unsyncedEvents() = unsynced
    override suspend fun findPerson(id: String) = people[id]

    /** Mirrors `SELECT * FROM scheduled_reminder WHERE source = 'LOCAL_FALLBACK'`. */
    override suspend fun localFallbacks(): List<wishkaro.db.Scheduled_reminder> =
        fallbacks.values.map {
            wishkaro.db.Scheduled_reminder(
                dedupe_key = it.dedupeKey,
                scheduled_at = it.scheduledAt,
                offset_rung = it.offset,
                title = it.title,
                body = it.body,
                person_id = it.personId,
                person_name = it.personName,
                event_type = it.eventType,
                sentiment = it.sentiment,
                deep_link = it.deepLink,
                source = ReminderSource.LOCAL_FALLBACK,
                fired_at = if (it.dedupeKey in fired) "2026-12-20T03:30:00Z" else null,
            )
        }

    override suspend fun upsertLocalFallback(entry: ScheduledReminderRow) {
        rows[entry.dedupeKey] = entry to ReminderSource.LOCAL_FALLBACK
    }

    /** What `LocalStore.upsertSnooze` does: the same table, a different owner. */
    fun addSnooze(entry: ScheduledReminderRow) {
        rows[entry.dedupeKey] = entry to ReminderSource.SNOOZE
    }

    override suspend fun deleteScheduled(dedupeKey: String) {
        rows.remove(dedupeKey)
    }
}
