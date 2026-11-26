package `in`.wishkaro.app

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import `in`.wishkaro.app.data.repository.AuthState
import `in`.wishkaro.app.data.repository.UpcomingRepository
import `in`.wishkaro.app.domain.Person
import `in`.wishkaro.app.domain.groupUpcoming
import `in`.wishkaro.app.ui.AppStore
import `in`.wishkaro.app.ui.addperson.AddPersonScreen
import `in`.wishkaro.app.ui.importing.ImportContactsScreen
import `in`.wishkaro.app.ui.importing.ImportFileScreen
import `in`.wishkaro.app.ui.importing.ImportResultScreen
import `in`.wishkaro.app.ui.importing.ImportStartScreen
import `in`.wishkaro.app.ui.importing.ImportStore
import `in`.wishkaro.app.ui.nav.NavEvent
import `in`.wishkaro.app.ui.nav.Screen
import `in`.wishkaro.app.ui.nav.showsTabs
import `in`.wishkaro.app.ui.nav.WishKaroNavigation
import `in`.wishkaro.app.ui.components.WishKaroTopBar
import `in`.wishkaro.app.ui.nav.WishKaroNavigationBar
import `in`.wishkaro.app.ui.nav.rememberWishKaroNavigation
import `in`.wishkaro.app.ui.upcoming.UpcomingListScreen
import `in`.wishkaro.app.ui.people.PeopleListScreen
import `in`.wishkaro.app.ui.person.DeletedPeopleScreen
import `in`.wishkaro.app.ui.person.EditPersonScreen
import `in`.wishkaro.app.ui.person.EventEditorScreen
import `in`.wishkaro.app.ui.person.PersonDetailScreen
import `in`.wishkaro.app.ui.settings.SettingsScreen
import `in`.wishkaro.app.ui.signin.SignInScreen
import `in`.wishkaro.app.ui.theme.WishKaroTheme
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import org.koin.compose.koinInject

@Composable
fun App(modifier: Modifier = Modifier) {
    WishKaroTheme {
        val store: AppStore = koinInject()
        val importStore: ImportStore = koinInject()
        val nav = rememberWishKaroNavigation()

        val authState by store.authState.collectAsState()
        val busy by store.busy.collectAsState()
        val message by store.message.collectAsState()
        val snackbar = remember { SnackbarHostState() }

        LaunchedEffect(Unit) { store.start() }

        /**
         * One-shot navigations, consumed once.
         *
         * Keyed on [nav] rather than Unit so that a new navigator — there is one per
         * composition root — always has a live collector. Anything the store emitted while
         * nobody was collecting is buffered in the channel and arrives here; anything
         * already handled is gone for good, which is the point. See
         * `MILESTONE_CHECKLIST.md`, "a one-shot navigation needs a one-shot signal".
         */
        LaunchedEffect(nav) {
            store.navEvents.collect { event ->
                when (event) {
                    NavEvent.Back -> nav.back()
                    is NavEvent.Go -> nav.goTo(event.screen)
                    is NavEvent.ResetTo -> nav.resetTo(*event.screens.toTypedArray())
                    NavEvent.ResetAll -> nav.resetAll()
                }
            }
        }

        LaunchedEffect(message) {
            message?.let {
                snackbar.showSnackbar(it)
                store.clearMessage()
            }
        }

        Scaffold(
            modifier = modifier,
            snackbarHost = { SnackbarHost(snackbar) },
            // Zero, deliberately. Every screen below has its own Scaffold with its own top
            // bar, and that one already insets itself for the status bar. Letting this
            // outer Scaffold inset as well counts the status bar TWICE, which is the dead
            // band of empty purple that has been sitting above the title since M0 — visible
            // in every device screenshot in M1, M2 and M3 and never named.
            //
            // The bottom bar is not affected: its height still comes through `padding`
            // below, so a list still stops above the navigation bar rather than under it.
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            bottomBar = {
                // FR-VIEW-06, compact: single pane with bottom navigation. It stays
                // visible on detail screens, because that is what makes per-tab history
                // worth having — open somebody from People, look at Upcoming, come back,
                // and they are still there. It goes away only for the flows that are tasks
                // rather than places (`Screen.showsTabs`).
                // Medium and expanded widths get a rail instead; that is the next commit.
                if (authState is AuthState.SignedIn && nav.currentScreen?.showsTabs == true) {
                    WishKaroNavigationBar(
                        current = nav.topLevelRoute,
                        onSelect = nav::goTo,
                    )
                }
            },
        ) { padding ->
            val inner = Modifier.padding(padding)

            // Sign-in is NOT a destination on the back stack. It is what the app shows while
            // there is no session, which makes two things structurally impossible: pressing
            // back out of a signed-in app onto a sign-in screen, and a sign-out leaving the
            // previous user's history standing behind it.
            if (authState !is AuthState.SignedIn) {
                SignInScreen(busy = busy, onSignIn = store::signIn, modifier = inner)
            } else {
                val entries = nav.decoratedEntries(wishKaroEntryProvider(store, importStore, nav, inner))
                NavDisplay(entries = entries, onBack = { nav.back() })
            }
        }
    }
}

/**
 * The person a detail-ish destination is about, resolved from its own key.
 *
 * `AppStore.selectedPerson` used to do this by combining the current screen with the people
 * list, which only worked while the store owned the back stack. It does not any more, and
 * this is better: the destination already knows which person it is about, and nothing has
 * to be kept in sync with it.
 *
 * Reads the UNFILTERED list on purpose — a deep link from a notification or the widget has
 * to open somebody the search box is currently hiding.
 */
@Composable
private fun AppStore.personOf(personId: String): Person? {
    val cards by allCards.collectAsState()
    return remember(cards, personId) { cards.firstOrNull { it.person.id == personId }?.person }
}

/**
 * Every destination's content.
 *
 * Each entry collects the state it needs itself rather than being handed values closed over
 * from `App`. That is deliberate: entries are cached against the back stack, so a lambda
 * that captured a snapshot of the app's state when it was built could hand a screen a stale
 * copy of it. Collecting inside the entry means the subscription belongs to the screen that
 * is actually on screen, and it stops when that screen leaves.
 */
private fun wishKaroEntryProvider(
    store: AppStore,
    importStore: ImportStore,
    nav: WishKaroNavigation,
    inner: Modifier,
): (NavKey) -> NavEntry<NavKey> = entryProvider {

    entry<Screen.Upcoming> {
        // Straight from UpcomingRepository, not through AppStore. The list, the calendar,
        // the timeline and the widget all read the same query, and routing it through a
        // UI store on the Application would put it back where M3 was bitten twice.
        val repository: UpcomingRepository = koinInject()
        val entries by repository.observeUpcoming().collectAsState(emptyList())
        val focused by store.focusedPersonId.collectAsState()
        val busy by store.busy.collectAsState()
        val sections = remember(entries) { groupUpcoming(entries) }

        Scaffold(
            modifier = inner,
            topBar = {
                WishKaroTopBar(
                    title = "Upcoming",
                    busy = busy,
                    onRefresh = store::refresh,
                    onOpenSettings = {
                        store.prepareSettings()
                        nav.goTo(Screen.Settings)
                    },
                    onOpenImport = store::openImport,
                    onOpenDeleted = { nav.goTo(Screen.Deleted) },
                    onSignOut = store::signOut,
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = {
                        store.prepareAddPerson()
                        nav.goTo(Screen.AddPerson)
                    },
                    text = { Text("Add person") },
                    icon = {},
                    modifier = Modifier.testTag("add_person_fab"),
                )
            },
        ) { padding ->
            UpcomingListScreen(
                sections = sections,
                focusedPersonId = focused,
                onOpenPerson = { nav.goTo(Screen.PersonDetail(it)) },
                onAddPerson = {
                    store.prepareAddPerson()
                    nav.goTo(Screen.AddPerson)
                },
                onOpenImport = store::openImport,
                modifier = Modifier.padding(padding),
            )
        }
    }

    entry<Screen.People> {
        val cards by store.cards.collectAsState()
        val filter by store.filter.collectAsState()
        val relations by store.knownRelations.collectAsState()
        val focused by store.focusedPersonId.collectAsState()
        val busy by store.busy.collectAsState()
        val blockedRecords by store.blockedRecords.collectAsState()

        PeopleListScreen(
            cards = cards,
            filter = filter,
            knownRelations = relations,
            focusedPersonId = focused,
            busy = busy,
            onOpenPerson = { nav.goTo(Screen.PersonDetail(it)) },
            onAddPerson = {
                store.prepareAddPerson()
                nav.goTo(Screen.AddPerson)
            },
            onQueryChange = store::setQuery,
            onRelationFilter = store::setRelationFilter,
            onMonthFilter = store::setMonthFilter,
            onClearFilter = store::clearFilter,
            onOpenDeleted = { nav.goTo(Screen.Deleted) },
            onRefresh = store::refresh,
            onOpenSettings = {
                store.prepareSettings()
                nav.goTo(Screen.Settings)
            },
            // Still one call: clearing the previous run's report and entering the flow must
            // not be separable, or a leftover report bounces the user straight back out of
            // import — M3's first review finding.
            onOpenImport = store::openImport,
            blockedRecords = blockedRecords,
            onSignOut = store::signOut,
            modifier = inner,
        )
    }

    entry<Screen.AddPerson> {
        val duplicates by store.duplicates.collectAsState()
        AddPersonScreen(
            duplicates = duplicates,
            onCheckDuplicates = store::checkDuplicates,
            onDismissDuplicates = store::dismissDuplicates,
            onSave = store::addPerson,
            onOpenExisting = { nav.goTo(Screen.PersonDetail(it)) },
            onCancel = nav::back,
            modifier = inner,
        )
    }

    entry<Screen.PersonDetail> { key ->
        val person = store.personOf(key.personId)
        PersonDetailScreen(
            person = person,
            onBack = nav::back,
            onEdit = { nav.goTo(Screen.EditPerson(key.personId)) },
            onAddEvent = { nav.goTo(Screen.EventEditor(key.personId, null)) },
            onEditEvent = { nav.goTo(Screen.EventEditor(key.personId, it)) },
            onDeleteEvent = store::deleteEvent,
            onToggleMute = store::setEventMuted,
            onDelete = { store.deletePerson(key.personId, person?.displayName ?: "They") },
            onAddTrait = { kind, value -> store.addTrait(key.personId, kind, value) },
            onRemoveTrait = store::removeTrait,
            modifier = inner,
        )
    }

    entry<Screen.EditPerson> { key ->
        EditPersonScreen(
            person = store.personOf(key.personId),
            onBack = nav::back,
            onSave = { name, relation, gender, phone, email, notes, deceased ->
                store.savePerson(
                    key.personId, name, relation, gender, phone, email, notes, deceased,
                )
            },
            modifier = inner,
        )
    }

    entry<Screen.EventEditor> { key ->
        EventEditorScreen(
            person = store.personOf(key.personId),
            eventId = key.eventId,
            onBack = nav::back,
            onSave = { type, label, date ->
                store.saveEvent(key.personId, key.eventId, type, label, date)
            },
            modifier = inner,
        )
    }

    entry<Screen.Settings> {
        val settings by store.settings.collectAsState()
        val reliability by store.reliability.collectAsState()
        val selfTest by store.selfTest.collectAsState()
        SettingsScreen(
            settings = settings,
            reliability = reliability,
            selfTest = selfTest,
            onBack = nav::back,
            onChange = store::updateSettings,
            onSendTest = store::sendTestReminder,
            onDismissTest = store::dismissSelfTest,
            onOpenBatterySettings = store::openBatterySettings,
            onRequestExactAlarms = store::requestExactAlarms,
            modifier = inner,
        )
    }

    entry<Screen.Deleted> {
        val deleted by store.deletedPeople.collectAsState()
        DeletedPeopleScreen(
            people = deleted,
            onBack = nav::back,
            onRestore = store::restorePerson,
            modifier = inner,
        )
    }

    // ---- M3: import ----

    entry<Screen.ImportStart> {
        ImportStartScreen(
            onContacts = { nav.goTo(Screen.ImportContacts) },
            onFile = { nav.goTo(Screen.ImportFile) },
            onBack = nav::back,
            modifier = inner,
        )
    }

    entry<Screen.ImportContacts> {
        ImportContactsScreen(
            store = importStore,
            onBack = nav::back,
            onDone = { nav.resetTo(Screen.People, Screen.ImportResult) },
            modifier = inner,
        )
    }

    entry<Screen.ImportFile> {
        ImportFileScreen(
            store = importStore,
            today = Clock.System.todayIn(TimeZone.currentSystemDefault()),
            onBack = nav::back,
            onDone = { nav.resetTo(Screen.People, Screen.ImportResult) },
            modifier = inner,
        )
    }

    // The report REPLACES the flow that produced it rather than stacking on it: backing out
    // of a report onto the review screen would invite the user to press Import a second
    // time. That is safe — it is idempotent — but it says the opposite of what just
    // happened. `resetTo` above is what makes back from here reach the list.
    entry<Screen.ImportResult> {
        val result by importStore.result.collectAsState()
        val savedReport by importStore.savedReport.collectAsState()
        val busy by store.busy.collectAsState()
        result?.let {
            ImportResultScreen(
                result = it,
                savedReport = savedReport,
                busy = busy,
                onSaveFailures = importStore::saveFailedRows,
                onDone = {
                    importStore.clearResult()
                    nav.resetTo(Screen.People)
                },
                modifier = inner,
            )
        }
    }
}
