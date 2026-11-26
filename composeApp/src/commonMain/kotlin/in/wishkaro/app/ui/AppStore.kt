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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

sealed interface Screen {
    data object SignIn : Screen
    data object People : Screen
    data object AddPerson : Screen
    data class PersonDetail(val personId: String) : Screen
    data class EditPerson(val personId: String) : Screen

    /** `eventId == null` means a new event on that person. */
    data class EventEditor(val personId: String, val eventId: String?) : Screen
    data object Deleted : Screen

    /** M2 creates this screen; the app had no settings at all before now (FR-SET-01). */
    data object Settings : Screen

    // ---- M3: import (FR-IMP-01..08, FR-VIEW-05, FR-AUTH-05) ----

    /** Contacts or a file. */
    data object ImportStart : Screen
    data object ImportContacts : Screen
    data object ImportFile : Screen

    /** What happened, with a reason for every row that did not come in (FR-IMP-07). */
    data object ImportResult : Screen
}

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
 * The whole app's state, in one place.
 *
 * M1 has seven screens, so there is now a real back stack — a list rather than a navigation
 * library, because seven screens and one linear history is not yet an argument for a
 * dependency. The moment it needs deep links into a nested graph, it gets one.
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
    private val backStack = ArrayDeque<Screen>()

    private val _screen = MutableStateFlow<Screen>(Screen.SignIn)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

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

    private val allCards: StateFlow<List<PersonCard>> = people.observePeople()
        .map { list ->
            val zone = TimeZone.currentSystemDefault()
            val today = Clock.System.todayIn(zone)
            list.map { it.toCard(zone, today) }
                .sortedWith(compareBy({ it.daysUntil ?: Int.MAX_VALUE }, { it.person.displayName }))
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

    /** The person behind whichever detail-ish screen is open. Null when none is. */
    val selectedPerson: StateFlow<Person?> = combine(_screen, allCards) { screen, list ->
        val id = when (screen) {
            is Screen.PersonDetail -> screen.personId
            is Screen.EditPerson -> screen.personId
            is Screen.EventEditor -> screen.personId
            else -> null
        }
        id?.let { pid -> list.firstOrNull { it.person.id == pid }?.person }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

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
        go(Screen.ImportStart)
    }

    fun openImportContacts() = go(Screen.ImportContacts)

    fun openImportFile() = go(Screen.ImportFile)

    /**
     * The import finished; show the report.
     *
     * The result screen REPLACES the flow rather than stacking on it: backing out of a
     * report onto the review screen that produced it would invite the user to press Import
     * a second time, which is safe — it is idempotent — but says the opposite of what just
     * happened.
     */
    fun openImportResult() {
        backStack.clear()
        backStack.addLast(Screen.People)
        _screen.value = Screen.ImportResult
    }

    fun finishImport() {
        backStack.clear()
        _screen.value = Screen.People
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
        if (people.findAnyPerson() == null) _screen.value = Screen.ImportStart
    }

    fun start() {
        auth.restore()
        _screen.value = if (auth.state.value is AuthState.SignedIn) Screen.People else Screen.SignIn
        if (auth.state.value is AuthState.SignedIn) afterSignedIn()
    }

    fun signIn() {
        if (_busy.value) return
        scope.launch {
            _busy.value = true
            auth.signIn()
                .onSuccess {
                    _screen.value = Screen.People
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
            backStack.clear()
            _screen.value = Screen.SignIn
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
    // Navigation
    // -----------------------------------------------------------------------------------

    private fun go(screen: Screen) {
        backStack.addLast(_screen.value)
        _screen.value = screen
    }

    fun openAddPerson() {
        _duplicates.value = emptyList()
        go(Screen.AddPerson)
    }

    fun openPerson(personId: String) = go(Screen.PersonDetail(personId))
    fun openEditPerson(personId: String) = go(Screen.EditPerson(personId))
    fun openEventEditor(personId: String, eventId: String?) = go(Screen.EventEditor(personId, eventId))
    fun openDeleted() = go(Screen.Deleted)

    fun openSettings() {
        scope.launch { _settings.value = local.reminderSettings() }
        refreshReliability()
        go(Screen.Settings)
    }

    fun back() {
        val previous = backStack.removeLastOrNull()
        _screen.value = when {
            previous != null -> previous
            auth.state.value is AuthState.SignedIn -> Screen.People
            else -> Screen.SignIn
        }
    }

    /** Called when a notification is tapped (deep link `wishkaro://person/<id>`). */
    fun focusPerson(personId: String?) {
        _focusedPersonId.value = personId
        if (personId != null && auth.state.value is AuthState.SignedIn) {
            backStack.clear()
            backStack.addLast(Screen.People)
            _screen.value = Screen.PersonDetail(personId)
        }
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
            backStack.clear()
            backStack.addLast(Screen.People)
            _screen.value = Screen.PersonDetail(id)
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
            back()
            _message.value = "Saved"
        }
    }

    fun deletePerson(personId: String, displayName: String) {
        scope.launch {
            people.deletePerson(personId)
            backStack.clear()
            _screen.value = Screen.People
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
            back()
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
