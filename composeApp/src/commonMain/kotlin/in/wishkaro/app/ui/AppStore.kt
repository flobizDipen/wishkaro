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
import `in`.wishkaro.app.platform.NotificationGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.time.Clock
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
    private val scope: CoroutineScope,
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
                    afterSignedIn()
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
    private fun afterSignedIn() {
        scope.launch {
            if (!notifications.hasPermission()) notifications.requestPermission()
            auth.registerDevice().onFailure {
                _message.value = "Could not register for notifications: ${it.message}"
            }
            people.syncNow().onFailure { _message.value = "Sync failed: ${it.message}" }
        }
    }

    fun refresh() {
        scope.launch {
            _busy.value = true
            people.syncNow().onFailure { _message.value = "Sync failed: ${it.message}" }
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

    /** FR-REM-07: the user proves delivery to themselves, without waiting until 09:00. */
    fun sendTestReminder() {
        scope.launch {
            _busy.value = true
            auth.requestTestReminder()
                .onSuccess { _message.value = "Test reminder queued — it lands in about 2 minutes" }
                .onFailure { _message.value = "Could not queue a test: ${it.message}" }
            _busy.value = false
        }
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
