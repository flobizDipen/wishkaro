package `in`.wishkaro.app.ui

import `in`.wishkaro.app.data.repository.AuthRepository
import `in`.wishkaro.app.data.repository.AuthState
import `in`.wishkaro.app.data.repository.PeopleRepository
import `in`.wishkaro.app.data.repository.SignInCancelled
import `in`.wishkaro.app.domain.EventDate
import `in`.wishkaro.app.domain.EventType
import `in`.wishkaro.app.domain.Person
import `in`.wishkaro.app.domain.PersonCard
import `in`.wishkaro.app.domain.TraitKind
import `in`.wishkaro.app.domain.toCard
import `in`.wishkaro.app.data.local.BlockedRow
import `in`.wishkaro.app.data.local.LocalStore
import `in`.wishkaro.app.data.repository.ReminderPlanRepository
import `in`.wishkaro.app.domain.ReminderSettings
import `in`.wishkaro.app.platform.NotificationGateway
import `in`.wishkaro.app.platform.OemBatteryHelper
import `in`.wishkaro.app.platform.OemHelperOutcome
import `in`.wishkaro.app.platform.OemInstructions
import `in`.wishkaro.app.platform.OemVendor
import `in`.wishkaro.app.platform.ReminderScheduler
import `in`.wishkaro.app.ui.importing.ImportStore
import `in`.wishkaro.app.ui.nav.NavEvent
import `in`.wishkaro.app.ui.nav.Screen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * What the Settings screen knows about this device's ability to actually deliver a reminder
 * (FR-SET-02, FR-REM-06).
 *
 * Kept apart from [ReminderSettings] because none of it is a user preference — it is what the
 * operating system is currently willing to let this app do, and it changes underneath the app
 * without any write from here.
 */
data class ReliabilityState(
    val notificationsAllowed: Boolean = true,
    val exactAlarmsAllowed: Boolean = true,
    val batteryUnrestricted: Boolean = true,
    val vendor: OemVendor = OemVendor.OTHER,
    val instructions: List<String> = emptyList(),
)

/** What the self-test has observed so far (FR-REM-07). */
sealed interface SelfTestState {
    data object Idle : SelfTestState
    data object Queueing : SelfTestState
    data class Waiting(val dedupeKey: String, val since: Instant) : SelfTestState
    data class Delivered(val secondsTaken: Long) : SelfTestState
    data class NotArrived(val dedupeKey: String) : SelfTestState
    data class Failed(val reason: String) : SelfTestState
}

/** Search and filter state for the people list (FR-PPL-10). */
data class PeopleFilter(
    val query: String = "",
    val relation: String? = null,
    val month: Int? = null,
) {
    val isActive: Boolean get() = query.isNotBlank() || relation != null || month != null
}

/**
 * The app's data and the actions that change it.
 *
 * M1's comment here said the hand-rolled back stack would get a navigation library "the
 * moment it needs deep links into a nested graph". M4 is that moment, and the back stack has
 * left: it is now a Navigation 3 stack in Compose saved state, built in `App.kt` (see
 * `in.wishkaro.app.ui.nav` and `docs/M4_PLAN.md` §2).
 *
 * The rule that came with the move, and the reason for it: **nothing that drives navigation
 * lives on this object.** It is a Koin `single` on the Application, so anything kept here
 * outlives the Activity — which is exactly how M3 produced a stale import report that
 * bounced the user out of the flow, and a busy flag that locked the file picker shut. What
 * is left is data, the actions that write it, and [navEvents], which is a one-shot channel
 * rather than a value anybody can read twice.
 */
class AppStore(
    private val auth: AuthRepository,
    private val people: PeopleRepository,
    private val notifications: NotificationGateway,
    private val local: LocalStore,
    private val plan: ReminderPlanRepository,
    private val scheduler: ReminderScheduler,
    private val oem: OemBatteryHelper,
    private val importing: ImportStore,
    private val scope: CoroutineScope,
    private val now: () -> Instant = Clock.System::now,
) {
    /**
     * Navigations asked for by something FINISHING, delivered exactly once.
     *
     * A `Channel`, not a `StateFlow`, and that is the whole design. A value on this object
     * can be read again by the next Activity; a channel element cannot. `MILESTONE_
     * CHECKLIST.md` carries this rule because M3 paid for it twice in one milestone.
     *
     * Buffered rather than rendezvous because the deep-link handler runs in
     * `MainActivity.onCreate`, before `setContent`, so the event has to wait for a collector
     * that does not exist yet. Buffered rather than conflated because two of these can
     * legitimately be in flight — a sign-in that offers import, say — and dropping one
     * silently is the failure this whole mechanism exists to avoid.
     */
    private val _navEvents = Channel<NavEvent>(Channel.BUFFERED)
    val navEvents: Flow<NavEvent> = _navEvents.receiveAsFlow()

    private fun navigate(event: NavEvent) {
        _navEvents.trySend(event)
    }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _filter = MutableStateFlow(PeopleFilter())
    val filter: StateFlow<PeopleFilter> = _filter.asStateFlow()

    /**
     * Possible duplicates of the name currently being typed into Add Person (FR-PPL-06).
     * Non-empty means the screen shows "Is this the same person?" before it will save.
     */
    private val _duplicates = MutableStateFlow<List<Person>>(emptyList())
    val duplicates: StateFlow<List<Person>> = _duplicates.asStateFlow()

    private val _settings = MutableStateFlow(ReminderSettings())
    val settings: StateFlow<ReminderSettings> = _settings.asStateFlow()

    private val _reliability = MutableStateFlow(ReliabilityState())
    val reliability: StateFlow<ReliabilityState> = _reliability.asStateFlow()

    private val _selfTest = MutableStateFlow<SelfTestState>(SelfTestState.Idle)
    val selfTest: StateFlow<SelfTestState> = _selfTest.asStateFlow()

    /** Set when a notification was tapped, so the list can highlight them. */
    private val _focusedPersonId = MutableStateFlow<String?>(null)
    val focusedPersonId: StateFlow<String?> = _focusedPersonId.asStateFlow()

    val authState: StateFlow<AuthState> = auth.state

    /**
     * Every person, unfiltered, sorted by how soon their next event is.
     *
     * Public because a detail screen has to be able to show somebody the filter is currently
     * hiding — a deep link from a notification or the widget does not care what the user
     * last typed into the search box.
     */
    val allCards: StateFlow<List<PersonCard>> = people.observePeople()
        .map { list ->
            val zone = TimeZone.currentSystemDefault()
            val today = Clock.System.todayIn(zone)
            // ALPHABETICAL, for FR-VIEW-04: this is the directory, and a directory sorted
            // by how soon somebody's birthday is cannot be scanned for a name. Sorting by
            // imminence is what the Upcoming view (FR-VIEW-01) is for, and it has its own
            // query now. Case-insensitive, or "aarav" files after "Zoya".
            list.map { it.toCard(zone, today) }
                .sortedWith(
                    compareBy(String.CASE_INSENSITIVE_ORDER) { it.person.displayName },
                )
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The filtered list. Applied here rather than in SQL so that typing is instant and does
     * not re-run a query per keystroke; the list is bounded by what one person can remember
     * about, and the SQL path exists for when it is not.
     */
    val cards: StateFlow<List<PersonCard>> = combine(allCards, _filter) { list, f ->
        list.filter { card ->
            val p = card.person
            val matchesQuery = f.query.isBlank() ||
                p.displayName.contains(f.query.trim(), ignoreCase = true)
            val matchesRelation = f.relation == null ||
                p.relationToOwner.equals(f.relation, ignoreCase = true)
            val matchesMonth = f.month == null || p.events.any { it.date.month == f.month }
            matchesQuery && matchesRelation && matchesMonth
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Every relation actually in use, for the filter chips. */
    val knownRelations: StateFlow<List<String>> = allCards
        .map { list -> list.mapNotNull { it.person.relationToOwner }.distinct().sorted() }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val deletedPeople: StateFlow<List<Person>> = people.observeDeleted()
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Records the server refused by name, which this device has set aside (`sync_blocked`).
     *
     * This exists because the machinery to produce it already did and NOTHING READ IT. The
     * chunked push blocks a row so one bad record cannot stop the outbox draining — which is
     * exactly right — and the row then leaves the drain for ever, taking every event of that
     * person with it, so no reminder is ever scheduled for them server-side. Until now the
     * app said nothing at all.
     *
     * That is the silent partial success this whole milestone was built to prevent, arriving
     * through the machinery built to prevent it. A mechanism with no output is not a
     * mechanism, it is a place the problem goes to be forgotten.
     */
    val blockedRecords: StateFlow<List<BlockedRow>> = local.observeBlockedCount()
        .map { count -> if (count == 0L) emptyList() else local.blockedRows() }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // `selectedPerson` used to live here, derived from the screen. It has gone: the back
    // stack is no longer on this object, so the destination is the only thing that knows
    // which person it is about, and it resolves its own from `allCards` at the call site.
    // One fewer piece of UI state on an Application-scoped singleton.

    // -----------------------------------------------------------------------------------
    // Session
    // -----------------------------------------------------------------------------------

    // -----------------------------------------------------------------------------------
    // Import (M3)
    // -----------------------------------------------------------------------------------

    /**
     * Every route into import comes through here — the people-list action, the empty state
     * and the first-run offer — so this is the one place that can guarantee the flow starts
     * without a previous run's report still sitting in the store.
     *
     * It has to happen BEFORE an import screen composes. Those screens navigate to the
     * report by observing `result != null`, so a leftover result made them bounce straight
     * back out: the user could not reach the contacts list at all until they had tapped
     * Done on a report they had already read. Reachable because there is no BackHandler —
     * the system back button on the report finishes the Activity while the store, a
     * singleton on the Application, keeps the result.
     */
    fun openImport() {
        importing.clearResult()
        navigate(NavEvent.Go(Screen.ImportStart))
    }

    /**
     * FR-AUTH-05's "add your first 3 people", where contacts import is offered.
     *
     * Only for somebody who has nobody — a returning user signing in on a new phone syncs
     * their people down and must not be sent into an import flow they do not need. "Not now"
     * lands on the people list, whose empty state offers the same thing again (FR-VIEW-05),
     * so declining costs nothing and the route stays visible.
     *
     * The count is read at the END of [afterSignedIn], after its pull has run, so a
     * restored account is never mistaken for a new one.
     *
     * Only on an explicit sign-in, never on a session restore at launch. Being navigated
     * somewhere you did not ask to go is tolerable once, in the five seconds after you
     * create an account; it is not tolerable every time you open the app.
     */
    private suspend fun offerImportToANewUser() {
        if (people.findAnyPerson() == null) navigate(NavEvent.Go(Screen.ImportStart))
    }

    /**
     * Restore the session, and get everything that needs one going.
     *
     * No longer decides what is on screen. `App.kt` branches on [authState] for that, which
     * is what makes it impossible to press back out of a signed-in app into the sign-in
     * screen, and impossible for a sign-out to leave the previous user's stack behind.
     */
    fun start() {
        auth.restore()
        if (auth.state.value is AuthState.SignedIn) {
            afterSignedIn()
            // A deep link handled in onCreate got here before the session did. Now there is
            // one, so the person it named can finally be opened.
            openFocusedPersonIfSignedIn()
        }
    }

    fun signIn() {
        if (_busy.value) return
        scope.launch {
            _busy.value = true
            auth.signIn()
                .onSuccess {
                    // Nothing here navigates to People: the auth state flipping is what
                    // swaps the sign-in screen for the app, so there is no window in which
                    // a stale back stack and a live session are both on screen.
                    //
                    // The import offer rides INSIDE afterSignedIn, after its pull. Called
                    // out here it would race the sync, and a returning user on a new phone
                    // would be shoved into an import flow a second before their own people
                    // arrived.
                    afterSignedIn(offerImport = true)
                }
                .onFailure { error ->
                    if (error !is SignInCancelled) {
                        _message.value = error.message ?: "Sign-in failed"
                    }
                }
            _busy.value = false
        }
    }

    /**
     * Everything that has to happen once there is a session: ask for notification permission,
     * register the FCM token, and pull whatever the server already has.
     *
     * Permission is requested here rather than at first launch so the ask lands when the user
     * has just signed in and the reason is obvious (FR-AUTH-05).
     */
    private fun afterSignedIn(offerImport: Boolean = false) {
        scope.launch {
            if (!notifications.hasPermission()) notifications.requestPermission()
            auth.registerDevice().onFailure {
                _message.value = "Could not register for notifications: ${it.message}"
            }
            people.syncNow().onFailure { _message.value = "Sync failed: ${it.message}" }
            // Settings first, so the Settings screen and any local fallback are working from
            // the user's real hours rather than the defaults.
            _settings.value = local.reminderSettings()
            // Then the plan, which also re-arms every alarm. Failure is silent by design:
            // the alarms already on the device keep working, and that is the whole reason
            // they are stored rather than held in memory.
            plan.refresh()
            refreshReliability()
            // Last, and only after the pull above: by now a returning account has its people.
            if (offerImport) offerImportToANewUser()
        }
    }

    // -----------------------------------------------------------------------------------
    // Settings (FR-REM-03, FR-REM-08, FR-REM-09, FR-SET-01)
    // -----------------------------------------------------------------------------------

    /**
     * Saves a settings change locally, then syncs.
     *
     * Local first, always. The screen must respond with the network off — that is the whole
     * architecture — and the server's materialiser re-runs the moment the push lands, so a
     * change made in aeroplane mode takes effect on the device immediately and on the server
     * as soon as it can.
     */
    fun updateSettings(transform: (ReminderSettings) -> ReminderSettings) {
        scope.launch {
            val next = transform(_settings.value)
            _settings.value = next
            local.saveSettingsLocally(next, now().toString())
            people.syncNow()
            // The server may have rewritten every pending instant — a new delivery hour, a
            // new quiet window — so the local plan is stale until it is refetched.
            _settings.value = local.reminderSettings()
            plan.refresh()
        }
    }

    fun refreshReliability() {
        scope.launch {
            val vendor = oem.vendor()
            _reliability.value = ReliabilityState(
                notificationsAllowed = notifications.hasPermission(),
                exactAlarmsAllowed = scheduler.canScheduleExact(),
                batteryUnrestricted = oem.isIgnoringBatteryOptimisations(),
                vendor = vendor,
                instructions = _reliability.value.instructions,
            )
        }
    }

    /**
     * Opens the OEM's own autostart / battery screen (FR-REM-06).
     *
     * The instructions shown afterwards depend on where the user actually LANDED, not on
     * what we hoped would open — telling someone to look for "Autostart" on a screen headed
     * "Battery optimisation" is how a helper becomes noise.
     */
    fun openBatterySettings() {
        scope.launch {
            val vendor = oem.vendor()
            val outcome = oem.openSettings()
            _reliability.value = _reliability.value.copy(
                vendor = vendor,
                instructions = OemInstructions.forVendor(vendor, outcome),
            )
            if (outcome == OemHelperOutcome.NOTHING) {
                _message.value = "Could not open your phone's battery settings — see the steps below"
            }
        }
    }

    fun requestExactAlarms() {
        scheduler.requestExactAlarmPermission()
    }

    /**
     * Pull to refresh, and everything that word has to mean now.
     *
     * Records are only half of it. The reminder plan is what the device's own alarms are
     * armed from, so a sync that updates the people list and leaves a stale plan behind
     * produces an app that LOOKS up to date and will fire yesterday's reminders. Settings
     * are re-read for the same reason: the server may have handed down a copy that moved
     * every pending instant.
     */
    fun refresh() {
        scope.launch {
            _busy.value = true
            people.syncNow().onFailure { _message.value = "Sync failed: ${it.message}" }
            _settings.value = local.reminderSettings()
            // Silent on failure, like every other call site: the alarms already armed keep
            // working, which is the whole reason the plan is stored rather than held in
            // memory.
            plan.refresh()
            refreshReliability()
            _busy.value = false
        }
    }

    fun signOut() {
        scope.launch {
            auth.signOut()
            // Every tab, not just this one: the next person to sign in on this handset must
            // not be able to press back into the last one's data.
            navigate(NavEvent.ResetAll)
        }
    }

    /**
     * FR-REM-07: the user proves delivery to themselves, without waiting until 09:00.
     *
     * The endpoint has existed since M0 and it fired and forgot. That is the half that does
     * not help: the point is for the user to end up CONVINCED, and "queued" is not evidence.
     * So the key is held and the device's own de-duplication table is polled — the one place
     * that knows for certain whether a notification was drawn on this handset.
     *
     * A timeout reports "never arrived" rather than failing silently, because that outcome is
     * the whole reason the feature exists: it is what tells a Xiaomi owner that the battery
     * manager is eating their reminders, and it is the cue for the OEM helper above.
     */
    fun sendTestReminder() {
        scope.launch {
            _selfTest.value = SelfTestState.Queueing
            val queued = auth.requestTestReminder()
                .onFailure {
                    _selfTest.value = SelfTestState.Failed(it.message ?: "Could not reach the server")
                }
                .getOrNull() ?: return@launch

            val startedAt = now()
            _selfTest.value = SelfTestState.Waiting(queued.dedupeKey, startedAt)

            repeat(SELF_TEST_POLLS) {
                delay(SELF_TEST_POLL_MS)
                if (local.wasReminderSeen(queued.dedupeKey)) {
                    val seconds = (now() - startedAt).inWholeSeconds
                    _selfTest.value = SelfTestState.Delivered(seconds)
                    return@launch
                }
            }
            _selfTest.value = SelfTestState.NotArrived(queued.dedupeKey)
        }
    }

    fun dismissSelfTest() {
        _selfTest.value = SelfTestState.Idle
    }

    // -----------------------------------------------------------------------------------
    // Things a screen has to get ready before it is navigated to
    // -----------------------------------------------------------------------------------
    //
    // These used to be `openX()` and did two jobs: prepare, then navigate. The navigating
    // half has moved to the call site; what is left is only the preparing, so the names say
    // that. Where the two must not be separated — `openImport`, above, whose whole point is
    // that NO route in can skip the clear — they are still one call.

    fun prepareAddPerson() {
        _duplicates.value = emptyList()
    }

    fun prepareSettings() {
        scope.launch { _settings.value = local.reminderSettings() }
        refreshReliability()
    }

    /**
     * A notification or the widget was tapped: `wishkaro://person/<id>` (FR-WID-02).
     *
     * The navigation is a one-shot event, which is the fix for the shape M3 was bitten by
     * twice. A `Screen` value set here would sit on this Application-scoped object until
     * something overwrote it, and every later Activity — a rotation, a return from the
     * launcher, a cold start — would find it still there and navigate again. A channel
     * element is consumed by the first collector and is then gone.
     *
     * `_focusedPersonId` stays a value, deliberately: it is not a navigation, it is "which
     * row the list should highlight", and that is a thing with a duration.
     */
    fun focusPerson(personId: String?) {
        _focusedPersonId.value = personId
        openFocusedPersonIfSignedIn()
    }

    /**
     * Navigate to whoever a deep link named, once there is a session to do it with.
     *
     * Called from [focusPerson] AND from the end of [start], and the second call site is the
     * bug. On a COLD start the Activity handles the intent in `onCreate`, which runs before
     * composition, which is where `start()` restores the session — so the old code saw
     * `AuthState.Unknown`, decided there was nobody to show the person to, and dropped the
     * navigation on the floor. Tapping a reminder with the app not running landed you on the
     * list. That has been true since M2 shipped notification deep links; it only surfaced
     * now because M4 is the first milestone to drive a deep link into a cold process.
     *
     * Safe to call twice: `_focusedPersonId` only holds a value when a deep link arrived in
     * THIS process, and the navigation itself is a one-shot channel send.
     */
    private fun openFocusedPersonIfSignedIn() {
        val personId = _focusedPersonId.value ?: return
        if (auth.state.value !is AuthState.SignedIn) return
        navigate(NavEvent.ResetTo(listOf(Screen.People, Screen.PersonDetail(personId))))
    }

    // -----------------------------------------------------------------------------------
    // Filtering
    // -----------------------------------------------------------------------------------

    fun setQuery(value: String) = _filter.update { it.copy(query = value) }
    fun setRelationFilter(value: String?) = _filter.update { it.copy(relation = value) }
    fun setMonthFilter(value: Int?) = _filter.update { it.copy(month = value) }
    fun clearFilter() { _filter.value = PeopleFilter() }

    private inline fun MutableStateFlow<PeopleFilter>.update(block: (PeopleFilter) -> PeopleFilter) {
        value = block(value)
    }

    // -----------------------------------------------------------------------------------
    // People
    // -----------------------------------------------------------------------------------

    /**
     * Checks for duplicates before saving (FR-PPL-06). Returns true when the caller should
     * stop and show the prompt instead of saving.
     */
    suspend fun checkDuplicates(displayName: String, date: EventDate): Boolean {
        val found = people.findDuplicates(displayName, date)
        _duplicates.value = found
        return found.isNotEmpty()
    }

    fun dismissDuplicates() { _duplicates.value = emptyList() }

    fun addPerson(
        displayName: String,
        relation: String?,
        type: EventType,
        label: String?,
        date: EventDate,
    ) {
        scope.launch {
            val id = people.addPerson(displayName, relation, type, label, date)
            _duplicates.value = emptyList()
            // The list underneath, the new person on top: back from here goes to the list,
            // not back into the empty Add form that just succeeded.
            navigate(NavEvent.ResetTo(listOf(Screen.People, Screen.PersonDetail(id))))
            _message.value = "$displayName saved"
        }
    }

    fun savePerson(
        personId: String,
        displayName: String,
        relation: String?,
        gender: String?,
        phone: String?,
        email: String?,
        notes: String?,
        isDeceased: Boolean,
    ) {
        scope.launch {
            people.updatePerson(personId, displayName, relation, gender, phone, email, notes, isDeceased)
            navigate(NavEvent.Back)
            _message.value = "Saved"
        }
    }

    fun deletePerson(personId: String, displayName: String) {
        scope.launch {
            people.deletePerson(personId)
            // Not Back: the person behind that entry no longer exists, and an editor for a
            // deleted record is a screen with nothing to show.
            navigate(NavEvent.ResetTo(listOf(Screen.People)))
            // FR-PPL-08: the window is the feature, so the confirmation states it.
            _message.value = "$displayName removed. Restorable for 30 days."
        }
    }

    fun restorePerson(personId: String) {
        scope.launch {
            people.restorePerson(personId)
            _message.value = "Restored"
        }
    }

    // -----------------------------------------------------------------------------------
    // Events
    // -----------------------------------------------------------------------------------

    fun saveEvent(
        personId: String,
        eventId: String?,
        type: EventType,
        label: String?,
        date: EventDate,
    ) {
        scope.launch {
            if (eventId == null) {
                people.addEvent(personId, type, label, date)
            } else {
                people.updateEvent(eventId, type, label, date)
            }
            navigate(NavEvent.Back)
            // The person's tone can change as a result of this write (FR-EVT-04), but the
            // server owns that decision — so the message says what was saved, not what it
            // implies. The screen will show the real answer after the next sync.
            _message.value = if (eventId == null) "Date added" else "Date updated"
        }
    }

    fun setEventMuted(eventId: String, muted: Boolean) {
        scope.launch { people.setEventMuted(eventId, muted) }
    }

    fun deleteEvent(eventId: String) {
        scope.launch {
            people.deleteEvent(eventId)
            _message.value = "Date removed"
        }
    }

    fun restoreEvent(eventId: String) {
        scope.launch { people.restoreEvent(eventId) }
    }

    // -----------------------------------------------------------------------------------
    // Traits
    // -----------------------------------------------------------------------------------

    fun addTrait(personId: String, kind: TraitKind, value: String) {
        if (value.isBlank()) return
        scope.launch { people.addTrait(personId, kind, value) }
    }

    fun removeTrait(traitId: String) {
        scope.launch { people.removeTrait(traitId) }
    }

    fun clearMessage() {
        _message.value = null
    }
}

/** Two minutes of waiting plus a margin, polled often enough to feel live. */
private const val SELF_TEST_POLLS = 40
private const val SELF_TEST_POLL_MS = 5_000L
