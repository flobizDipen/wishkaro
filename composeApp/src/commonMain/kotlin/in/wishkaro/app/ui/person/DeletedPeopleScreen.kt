package `in`.wishkaro.app.ui.person

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.wishkaro.app.domain.Person
import `in`.wishkaro.app.ui.components.DetailTopBar
import `in`.wishkaro.app.ui.components.EmptyState
import `in`.wishkaro.app.ui.components.PersonAvatar
import `in`.wishkaro.app.ui.components.WishKaroCard
import `in`.wishkaro.app.ui.theme.SolemnScope

/**
 * The 30-day restore window, made visible (FR-PPL-08).
 *
 * A soft delete nobody can see is indistinguishable from a hard one, and the window is the
 * whole point of the requirement — so it gets a screen rather than an undo snackbar that
 * disappears after four seconds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeletedPeopleScreen(
    people: List<Person>,
    onBack: () -> Unit,
    onRestore: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            DetailTopBar(title = "Recently removed", onBack = onBack)
        },
    ) { padding ->
        if (people.isEmpty()) {
            EmptyState(
                title = "Nothing removed",
                body = "Anybody you remove stays here for 30 days, so you can put them back.",
                modifier = Modifier.fillMaxSize().padding(padding),
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(people, key = { it.id }) { person ->
                SolemnScope(person.isSolemn) {
                    WishKaroCard(solemn = person.isSolemn) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PersonAvatar(person.displayName)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    person.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    person.relationToOwner ?: "\u2014",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onRestore(person.id) }) { Text("Restore") }
                        }
                    }
                }
            }
        }
    }
}
