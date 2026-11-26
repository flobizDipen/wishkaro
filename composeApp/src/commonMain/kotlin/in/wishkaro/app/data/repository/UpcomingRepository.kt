package `in`.wishkaro.app.data.repository

import `in`.wishkaro.app.data.local.LocalStore
import `in`.wishkaro.app.domain.UpcomingEntry
import `in`.wishkaro.app.domain.upcomingEvents
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/**
 * The one way to ask "what is coming up", for everything that is not already holding a list
 * of people.
 *
 * This is where the Glance line sits (`docs/M4_PLAN.md` §3). The widget is `androidMain` by
 * definition — Glance is Android-only — but it reaches the data through this, in
 * `commonMain`, and renders [UpcomingEntry]. It never sees `LocalStore`, never sees
 * `AppStore`, and never does a day of date arithmetic of its own. The alternative was the
 * widget growing its own query, which is the duplication this project has refused three
 * times and which `BUILD_PROMPT_M4.md` rules out by name.
 *
 * Deliberately NOT reading `AppStore`: that is a Koin singleton on the Application holding
 * UI state, which is the thing M3 was bitten by twice, and a widget update runs when no UI
 * has ever been composed.
 */
class UpcomingRepository(
    private val local: LocalStore,
    private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
    private val today: (TimeZone) -> LocalDate = { Clock.System.todayIn(it) },
) {

    /**
     * Live, for the views.
     *
     * The day is read per emission rather than captured once. A device left on a desk
     * overnight re-emits when its data changes, and a countdown computed against yesterday
     * would say "Tomorrow" on the morning itself — which is the one word this app cannot get
     * wrong. The widget's own midnight refresh (FR-WID-03) exists for the same reason.
     */
    fun observeUpcoming(withinDays: Int? = null): Flow<List<UpcomingEntry>> =
        local.observePeople().map { people ->
            val zone = timeZone()
            upcomingEvents(people, zone, today(zone), withinDays)
        }

    /**
     * One shot, for the widget.
     *
     * A widget update is a broadcast, not a subscription: it wakes up, reads, draws and is
     * gone. Re-querying costs a few milliseconds and buys a widget that is correct when the
     * app's UI has never been composed, which is the normal case for something on a home
     * screen.
     */
    suspend fun upcomingNow(withinDays: Int? = null, limit: Int? = null): List<UpcomingEntry> {
        val zone = timeZone()
        // No deleted-row filtering here on purpose: `person.selectAll` and `event.selectAll`
        // both carry `WHERE deleted_at IS NULL`, so a soft-deleted person or a removed date
        // never reaches this layer. Filtering again here would read as though it were this
        // object's job, and the next person to change the SQL would not know to look.
        // UpcomingRepositoryTest pins the behaviour against the real schema either way.
        val all = upcomingEvents(local.allPeopleOnce(), zone, today(zone), withinDays)
        return if (limit == null) all else all.take(limit)
    }
}
