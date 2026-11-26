package `in`.wishkaro.app.ui.people

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import `in`.wishkaro.app.data.local.BlockedRow
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import `in`.wishkaro.app.domain.BirthdayMath
import `in`.wishkaro.app.domain.PersonCard
import `in`.wishkaro.app.domain.Sentiment
import `in`.wishkaro.app.domain.toneFor
import `in`.wishkaro.app.ui.PeopleFilter

private val MONTHS = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeopleListScreen(
    cards: List<PersonCard>,
    filter: PeopleFilter,
    knownRelations: List<String>,
    focusedPersonId: String?,
    busy: Boolean,
    onOpenPerson: (String) -> Unit,
    onAddPerson: () -> Unit,
    onQueryChange: (String) -> Unit,
    onRelationFilter: (String?) -> Unit,
    onMonthFilter: (Int?) -> Unit,
    onClearFilter: () -> Unit,
    onOpenDeleted: () -> Unit,
    onRefresh: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenImport: () -> Unit,
    blockedRecords: List<BlockedRow>,
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
            // testTag, here and below, is not decoration. Compose exposes a clickable node
            // to uiautomator with NO text and NO content-description — the label sits in a
            // separate child node — so this button is literally unaddressable from the view
            // tree without one, and MainActivity's testTagsAsResourceId turns the tag into a
            // resource-id that tools/device/ui.sh can resolve and tap. Three blind taps
            // across M2 and M3 went into the wrong app; this is what replaces them.
            ExtendedFloatingActionButton(
                onClick = onAddPerson,
                text = { Text("Add person") },
                icon = {},
                modifier = Modifier.testTag("add_person_fab"),
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // ---- search and filter (FR-PPL-10) ----
            OutlinedTextField(
                value = filter.query,
                onValueChange = onQueryChange,
                label = { Text("Search by name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .testTag("people_search"),
            )

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Only relations actually in use — a chip row of seventeen options the user
                // has never applied is a worse filter than none.
                knownRelations.forEach { relation ->
                    FilterChip(
                        selected = filter.relation == relation,
                        onClick = { onRelationFilter(if (filter.relation == relation) null else relation) },
                        label = { Text(relation) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                MONTHS.forEachIndexed { index, name ->
                    val month = index + 1
                    FilterChip(
                        selected = filter.month == month,
                        onClick = { onMonthFilter(if (filter.month == month) null else month) },
                        label = { Text(name) },
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (filter.isActive) {
                    TextButton(onClick = onClearFilter) { Text("Clear filters") }
                } else {
                    // The self-test moved to Settings in M2, where it belongs — it now
                    // reports whether the reminder actually arrived rather than firing and
                    // forgetting, and it sits next to the battery helper that fixes the case
                    // where it does not (FR-REM-06, FR-REM-07).
                    TextButton(onClick = onOpenSettings, enabled = !busy) {
                        Text("Settings")
                    }
                }
                // Import has to be reachable from a list that is NOT empty.
                //
                // FR-VIEW-05 puts it on the empty state and FR-AUTH-05 offers it at first
                // run, and both are built — but between them they only ever reach somebody
                // with nobody. A user who added three people by hand last week and now
                // wants the other three hundred off their phone had no route at all, which
                // makes import a feature you can use once, by accident, on your first day.
                TextButton(onClick = onOpenImport) { Text("Import") }
                TextButton(onClick = onOpenDeleted) { Text("Recently removed") }
            }

            // Records the server refused and this device has set aside. Shown here, above
            // the list, because the consequence is invisible otherwise: those people stop
            // syncing and never get a server-scheduled reminder, and nothing else in the app
            // would ever mention it.
            if (blockedRecords.isNotEmpty()) {
                BlockedBanner(blockedRecords)
            }

            if (cards.isEmpty()) {
                EmptyState(
                    filtered = filter.isActive,
                    onOpenImport = onOpenImport,
                    onAddPerson = onAddPerson,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(cards, key = { it.person.id }) { card ->
                        PersonRow(
                            card = card,
                            highlighted = card.person.id == focusedPersonId,
                            onClick = { onOpenPerson(card.person.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonRow(card: PersonCard, highlighted: Boolean, onClick: () -> Unit) {
    val person = card.person
    val event = card.nextEvent
    // Tone comes from the person, never from the event type: a deceased person's birthday is
    // a SOLEMN event whose type is BIRTHDAY (FR-EVT-05).
    val tone = toneFor(person)

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = when {
            highlighted -> CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            )
            person.isSolemn -> CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            else -> CardDefaults.cardColors()
        },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    person.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    buildString {
                        append(person.relationToOwner ?: "—")
                        if (event != null) {
                            append(" · ")
                            append(event.title)
                            append(" ")
                            append(BirthdayMath.formatDayMonth(event.date.day, event.date.month))
                        }
                        // No age when the year is unknown, and none at all for someone who
                        // has died — the solemn string set returns null for both.
                        tone.years(card.turningAge, event?.type ?: return@buildString)?.let {
                            append(" · ")
                            append(it)
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (person.events.size > 1) {
                    Text(
                        "${person.events.size} dates",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    card.countdown?.let(tone::countdown) ?: "no date",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (person.isSolemn) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                if (event?.isMuted == true) {
                    Text(
                        tone.reminderToggle(false),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (event?.sentiment == Sentiment.SOLEMN && !person.isSolemn) {
                    // Belt and braces: a SOLEMN event on a person the client thinks is alive
                    // means the local mirror is mid-sync. Show the quiet label, not the loud one.
                    Text("quiet", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    filtered: Boolean,
    onOpenImport: () -> Unit,
    onAddPerson: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center,
    ) {
        if (filtered) {
            Text(
                "Nobody matches that.",
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge,
            )
        } else {
            // FR-VIEW-05: an empty state leads somewhere. Until M3 this said "Add the first
            // person whose birthday you keep forgetting" and led nowhere at all — which, for
            // a user who has three hundred of them in their contacts, is the longest possible
            // way into the app.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(32.dp),
            ) {
                Text(
                    "Nobody here yet.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "Bring in the birthdays already on your phone, or from a spreadsheet — " +
                        "you choose who comes in.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onOpenImport) { Text("Import people") }
                TextButton(onClick = onAddPerson) { Text("Add one by hand") }
            }
        }
    }
}

/**
 * "2 records could not be saved to the server" — and, on a tap, which and why.
 *
 * The reason shown is the SERVER'S own message, passed through rather than reworded. It is
 * the only thing that tells the user what to change, and a friendlier paraphrase of a
 * message we did not write would be a guess.
 */
@Composable
private fun BlockedBanner(blocked: List<BlockedRow>) {
    var open by rememberSaveable { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable { open = true },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                if (blocked.size == 1) {
                    "1 record could not be saved to the server"
                } else {
                    "${blocked.size} records could not be saved to the server"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                "They are safe on this phone, but they are not syncing and will not get " +
                    "reminders. Tap to see why.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Not saved to the server") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Editing one of these people re-queues it, so fixing what the " +
                            "server objected to is enough to get them syncing again.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(12.dp))
                    for (row in blocked) {
                        Text(row.personName, style = MaterialTheme.typography.titleSmall)
                        Text(row.reason, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(8.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Close") } },
        )
    }
}
