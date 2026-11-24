package `in`.wishkaro.app.ui

import `in`.wishkaro.app.data.repository.AuthRepository
import `in`.wishkaro.app.data.repository.AuthState
import `in`.wishkaro.app.data.repository.PeopleRepository
import `in`.wishkaro.app.data.repository.SignInCancelled
import `in`.wishkaro.app.domain.EventDate
import `in`.wishkaro.app.domain.PersonCard
import `in`.wishkaro.app.domain.toCard
import `in`.wishkaro.app.platform.NotificationGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
}

/**
 * The whole app's state, in one place.
 *
 * M0 has three screens and no back stack worth the name, so a sealed `Screen` plus a
 * StateFlow is the honest amount of machinery. A navigation library arrives when there is
 * something to navigate.
 */
class AppStore(
    private val auth: AuthRepository,
    private val people: PeopleRepository,
    private val notifications: NotificationGateway,
    private val scope: CoroutineScope,
) {
    private val _screen = MutableStateFlow<Screen>(Screen.SignIn)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Set when a notification was tapped, so the list can scroll to and highlight them. */
    private val _focusedPersonId = MutableStateFlow<String?>(null)
    val focusedPersonId: StateFlow<String?> = _focusedPersonId.asStateFlow()

    val authState: StateFlow<AuthState> = auth.state

    val cards: StateFlow<List<PersonCard>> = people.observePeople()
        .map { list ->
            val zone = TimeZone.currentSystemDefault()
            val today = Clock.System.todayIn(zone)
            list.map { it.toCard(zone, today) }
                .sortedWith(compareBy({ it.daysUntil ?: Int.MAX_VALUE }, { it.person.displayName }))
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    fun addPerson(displayName: String, relation: String?, date: EventDate) {
        scope.launch {
            people.addPerson(displayName, relation, date)
            _screen.value = Screen.People
            _message.value = "$displayName saved"
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

    fun signOut() {
        scope.launch {
            auth.signOut()
            _screen.value = Screen.SignIn
        }
    }

    fun openAddPerson() {
        _screen.value = Screen.AddPerson
    }

    fun back() {
        _screen.value = if (auth.state.value is AuthState.SignedIn) Screen.People else Screen.SignIn
    }

    /** Called when a notification is tapped (deep link `wishkaro://person/<id>`). */
    fun focusPerson(personId: String?) {
        _focusedPersonId.value = personId
        if (personId != null && auth.state.value is AuthState.SignedIn) {
            _screen.value = Screen.People
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}
