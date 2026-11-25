package `in`.wishkaro.app.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.wishkaro.app.domain.BirthdayMath
import `in`.wishkaro.app.domain.PersonCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleListScreen(
    cards: List<PersonCard>,
    focusedPersonId: String?,
    busy: Boolean,
    onAddPerson: () -> Unit,
    onRefresh: () -> Unit,
    onTestReminder: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("People") },
                actions = {
                    TextButton(onClick = onRefresh, enabled = !busy) { Text("Sync") }
                    TextButton(onClick = onSignOut) { Text("Sign out") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAddPerson, text = { Text("Add person") }, icon = {})
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // FR-REM-07. In M0 it lives on the list screen because there is no Settings yet.
            TextButton(
                onClick = onTestReminder,
                enabled = !busy,
                modifier = Modifier.padding(horizontal = 16.dp),
            ) {
                Text("Send me a test reminder in 2 minutes")
            }

            if (cards.isEmpty()) {
                EmptyState(modifier = Modifier.fillMaxSize())
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(cards, key = { it.person.id }) { card ->
                        PersonRow(card = card, highlighted = card.person.id == focusedPersonId)
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonRow(card: PersonCard, highlighted: Boolean) {
    val event = card.person.events.firstOrNull()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (highlighted) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    card.person.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    buildString {
                        append(card.person.relationToOwner ?: "—")
                        if (event != null) {
                            append(" · ")
                            append(BirthdayMath.formatDayMonth(event.date.day, event.date.month))
                        }
                        // No age at all when the birth year is unknown (FR-EVT-02).
                        card.turningAge?.let { append(" · turning $it") }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                card.countdown ?: "no date",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
        Text(
            "Nobody here yet.\nAdd the first person whose birthday you keep forgetting.",
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(32.dp),
        )
    }
}
