package `in`.wishkaro.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.runtime.collectAsState
import `in`.wishkaro.app.ui.AppStore
import `in`.wishkaro.app.ui.Screen
import `in`.wishkaro.app.ui.addperson.AddPersonScreen
import `in`.wishkaro.app.ui.importing.ImportContactsScreen
import `in`.wishkaro.app.ui.importing.ImportFileScreen
import `in`.wishkaro.app.ui.importing.ImportResultScreen
import `in`.wishkaro.app.ui.importing.ImportStartScreen
import `in`.wishkaro.app.ui.importing.ImportStore
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
        val screen by store.screen.collectAsState()
        val cards by store.cards.collectAsState()
        val filter by store.filter.collectAsState()
        val relations by store.knownRelations.collectAsState()
        val deleted by store.deletedPeople.collectAsState()
        val selected by store.selectedPerson.collectAsState()
        val duplicates by store.duplicates.collectAsState()
        val busy by store.busy.collectAsState()
        val message by store.message.collectAsState()
        val focused by store.focusedPersonId.collectAsState()
        val settings by store.settings.collectAsState()
        val reliability by store.reliability.collectAsState()
        val selfTest by store.selfTest.collectAsState()
        val blockedRecords by store.blockedRecords.collectAsState()
        val importStore: ImportStore = koinInject()
        val importResult by importStore.result.collectAsState()
        val savedReport by importStore.savedReport.collectAsState()
        val snackbar = remember { SnackbarHostState() }

        LaunchedEffect(Unit) { store.start() }

        LaunchedEffect(message) {
            message?.let {
                snackbar.showSnackbar(it)
                store.clearMessage()
            }
        }

        Scaffold(modifier = modifier, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
            val inner = Modifier.padding(padding)
            when (val current = screen) {
                Screen.SignIn -> SignInScreen(busy = busy, onSignIn = store::signIn, modifier = inner)

                Screen.People -> PeopleListScreen(
                    cards = cards,
                    filter = filter,
                    knownRelations = relations,
                    focusedPersonId = focused,
                    busy = busy,
                    onOpenPerson = store::openPerson,
                    onAddPerson = store::openAddPerson,
                    onQueryChange = store::setQuery,
                    onRelationFilter = store::setRelationFilter,
                    onMonthFilter = store::setMonthFilter,
                    onClearFilter = store::clearFilter,
                    onOpenDeleted = store::openDeleted,
                    onRefresh = store::refresh,
                    onOpenSettings = store::openSettings,
                    onOpenImport = store::openImport,
                    blockedRecords = blockedRecords,
                    onSignOut = store::signOut,
                    modifier = inner,
                )

                Screen.AddPerson -> AddPersonScreen(
                    duplicates = duplicates,
                    onCheckDuplicates = store::checkDuplicates,
                    onDismissDuplicates = store::dismissDuplicates,
                    onSave = store::addPerson,
                    onOpenExisting = store::openPerson,
                    onCancel = store::back,
                    modifier = inner,
                )

                is Screen.PersonDetail -> PersonDetailScreen(
                    person = selected,
                    onBack = store::back,
                    onEdit = { store.openEditPerson(current.personId) },
                    onAddEvent = { store.openEventEditor(current.personId, null) },
                    onEditEvent = { store.openEventEditor(current.personId, it) },
                    onDeleteEvent = store::deleteEvent,
                    onToggleMute = store::setEventMuted,
                    onDelete = {
                        store.deletePerson(current.personId, selected?.displayName ?: "They")
                    },
                    onAddTrait = { kind, value -> store.addTrait(current.personId, kind, value) },
                    onRemoveTrait = store::removeTrait,
                    modifier = inner,
                )

                is Screen.EditPerson -> EditPersonScreen(
                    person = selected,
                    onBack = store::back,
                    onSave = { name, relation, gender, phone, email, notes, deceased ->
                        store.savePerson(
                            current.personId, name, relation, gender, phone, email, notes, deceased,
                        )
                    },
                    modifier = inner,
                )

                is Screen.EventEditor -> EventEditorScreen(
                    person = selected,
                    eventId = current.eventId,
                    onBack = store::back,
                    onSave = { type, label, date ->
                        store.saveEvent(current.personId, current.eventId, type, label, date)
                    },
                    modifier = inner,
                )

                Screen.Settings -> SettingsScreen(
                    settings = settings,
                    reliability = reliability,
                    selfTest = selfTest,
                    onBack = store::back,
                    onChange = store::updateSettings,
                    onSendTest = store::sendTestReminder,
                    onDismissTest = store::dismissSelfTest,
                    onOpenBatterySettings = store::openBatterySettings,
                    onRequestExactAlarms = store::requestExactAlarms,
                    modifier = inner,
                )

                // ---- M3: import ----

                Screen.ImportStart -> ImportStartScreen(
                    onContacts = store::openImportContacts,
                    onFile = store::openImportFile,
                    onBack = store::back,
                    modifier = inner,
                )

                Screen.ImportContacts -> ImportContactsScreen(
                    store = importStore,
                    onBack = store::back,
                    onDone = store::openImportResult,
                    modifier = inner,
                )

                Screen.ImportFile -> ImportFileScreen(
                    store = importStore,
                    today = Clock.System.todayIn(TimeZone.currentSystemDefault()),
                    onBack = store::back,
                    onDone = store::openImportResult,
                    modifier = inner,
                )

                Screen.ImportResult -> importResult?.let {
                    ImportResultScreen(
                        result = it,
                        savedReport = savedReport,
                        busy = busy,
                        onSaveFailures = importStore::saveFailedRows,
                        onDone = {
                            importStore.clearResult()
                            store.finishImport()
                        },
                        modifier = inner,
                    )
                } ?: Unit

                Screen.Deleted -> DeletedPeopleScreen(
                    people = deleted,
                    onBack = store::back,
                    onRestore = store::restorePerson,
                    modifier = inner,
                )
            }
        }
    }
}
