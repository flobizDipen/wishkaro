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
import `in`.wishkaro.app.ui.people.PeopleListScreen
import `in`.wishkaro.app.ui.signin.SignInScreen
import `in`.wishkaro.app.ui.theme.WishKaroTheme
import org.koin.compose.koinInject

@Composable
fun App(modifier: Modifier = Modifier) {
    WishKaroTheme {
        val store: AppStore = koinInject()
        val screen by store.screen.collectAsState()
        val cards by store.cards.collectAsState()
        val busy by store.busy.collectAsState()
        val message by store.message.collectAsState()
        val focused by store.focusedPersonId.collectAsState()
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
            when (screen) {
                Screen.SignIn -> SignInScreen(busy = busy, onSignIn = store::signIn, modifier = inner)

                Screen.People -> PeopleListScreen(
                    cards = cards,
                    focusedPersonId = focused,
                    busy = busy,
                    onAddPerson = store::openAddPerson,
                    onRefresh = store::refresh,
                    onTestReminder = store::sendTestReminder,
                    onSignOut = store::signOut,
                    modifier = inner,
                )

                Screen.AddPerson -> AddPersonScreen(
                    onSave = store::addPerson,
                    onCancel = store::back,
                    modifier = inner,
                )
            }
        }
    }
}
