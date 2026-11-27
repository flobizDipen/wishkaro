package `in`.wishkaro.app.ui.components

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * The top bar every top-level view shares.
 *
 * It exists because M4 turns one screen into four, and the five things that used to sit in a
 * row under the People list — Settings, Import, Recently removed, Sync, Sign out — would
 * otherwise live on one tab and be invisible from the other three.
 *
 * That is the M3 checklist item, word for word: *ask of every feature, who can reach it a
 * week after they start using the app?* M3 paid for it when import had no route from a list
 * that was not empty, so it reached only users with nobody. Burying it under one of four
 * tabs would be the same mistake with a different lid on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WishKaroTopBar(
    title: String,
    busy: Boolean,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenDeleted: () -> Unit,
    onSignOut: () -> Unit,
) {
    // Saveable: the Activity is recreated on rotate and fold now, and a menu that closes
    // itself when the phone turns is a menu the user has to find again.
    var menuOpen by rememberSaveable { mutableStateOf(false) }

    TopAppBar(
        title = { Text(title) },
        // The same colours as `DetailTopBar`, for the same reason: "warm paper" means the bar
        // is the same sheet the content is on. Material's default puts a tinted container
        // behind the title, which is what made every screen in this app look like it had a
        // header pasted onto it.
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.primary,
        ),
        actions = {
            TextButton(
                onClick = onRefresh,
                enabled = !busy,
                modifier = Modifier.testTag("action_sync"),
            ) { Text("Sync") }

            TextButton(
                onClick = { menuOpen = true },
                modifier = Modifier.testTag("action_more"),
            ) { Text("More") }

            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Import") },
                    onClick = { menuOpen = false; onOpenImport() },
                    modifier = Modifier.testTag("action_import"),
                )
                DropdownMenuItem(
                    text = { Text("Settings") },
                    onClick = { menuOpen = false; onOpenSettings() },
                    modifier = Modifier.testTag("action_settings"),
                )
                DropdownMenuItem(
                    text = { Text("Recently removed") },
                    onClick = { menuOpen = false; onOpenDeleted() },
                    modifier = Modifier.testTag("action_deleted"),
                )
                DropdownMenuItem(
                    text = { Text("Sign out") },
                    onClick = { menuOpen = false; onSignOut() },
                    modifier = Modifier.testTag("action_sign_out"),
                )
            }
        },
    )
}
