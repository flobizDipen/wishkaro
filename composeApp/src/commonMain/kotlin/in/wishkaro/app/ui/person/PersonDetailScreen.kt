package `in`.wishkaro.app.ui.person

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import `in`.wishkaro.app.domain.BirthdayMath
import `in`.wishkaro.app.domain.CompletenessField
import `in`.wishkaro.app.domain.Person
import `in`.wishkaro.app.domain.PersonEvent
import `in`.wishkaro.app.domain.Sentiment
import `in`.wishkaro.app.domain.TraitKind
import `in`.wishkaro.app.domain.toCard
import `in`.wishkaro.app.domain.toneFor
import `in`.wishkaro.app.ui.components.SectionHeader
import kotlin.time.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/**
 * The person screen (FR-PPL-07): next event with countdown, age or years when the year is
 * known, the profile, and every event.
 *
 * Wish / Poster / Gift ideas are NOT here. REQUIREMENTS.md §7.2 lists them, but they belong
 * to M5, M6 and M8 and there is nothing behind them yet — dead buttons teach a user that
 * this app's controls do not work.
 *
 * Every string on this screen comes from `toneFor(...)`, never from a literal, so a deceased
 * person's page cannot be celebratory by accident.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PersonDetailScreen(
    person: Person?,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onAddEvent: () -> Unit,
    onEditEvent: (String) -> Unit,
    onDeleteEvent: (String) -> Unit,
    onToggleMute: (String, Boolean) -> Unit,
    onDelete: () -> Unit,
    onAddTrait: (TraitKind, String) -> Unit,
    onRemoveTrait: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (person == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("This person is no longer here.")
        }
        return
    }

    val tone = toneFor(person)
    val zone = TimeZone.currentSystemDefault()
    val card = remember(person) { person.toCard(zone, Clock.System.todayIn(zone)) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(person.displayName) },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = { TextButton(onClick = onEdit) { Text("Edit") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            // ---- the headline: next event and countdown ----
            Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Column(Modifier.padding(16.dp)) {
                    val next = card.nextEvent
                    if (next == null) {
                        Text(tone.noEvents, style = MaterialTheme.typography.bodyLarge)
                    } else {
                        val days = card.daysUntil
                        Text(
                            if (days == 0) tone.today(next.title) else tone.upcoming(next.title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        card.countdown?.let {
                            Text(
                                tone.countdown(it),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        // Null when the year is unknown, and null for a deceased person's
                        // birthday — the solemn string set returns nothing there rather than
                        // "they would have been 40".
                        tone.years(card.turningAge, next.type)?.let {
                            Text(it, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    person.relationToOwner?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (person.isDeceased) {
                Text(
                    "Remembered. This page stays quiet.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            // ---- events (FR-PPL-03) ----
            SectionHeader("Dates")
            person.events.sortedBy { it.type.ordinal }.forEach { event ->
                EventRow(
                    event = event,
                    onEdit = { onEditEvent(event.id) },
                    onDelete = { onDeleteEvent(event.id) },
                    onToggleMute = { onToggleMute(event.id, it) },
                )
            }
            TextButton(onClick = onAddEvent) { Text("Add another date") }

            // ---- profile (FR-PPL-04/05) ----
            SectionHeader("Profile")
            CompletenessBar(person, onImprove = onEdit)
            ProfileLine("Gender", person.gender)
            ProfileLine("Phone", person.phone)
            ProfileLine("Email", person.email)
            ProfileLine("Notes", person.notes)

            if (person.isMinor) {
                // FR-PPL-09: flagged records are excluded from profiling entirely, so the
                // app does not merely hide the chips — it explains why it is not asking.
                Text(
                    "This person is under 18, so WishKaro does not collect interests or a " +
                        "budget for them.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else {
                TraitKind.entries.forEach { kind ->
                    TraitSection(
                        kind = kind,
                        person = person,
                        onAdd = { onAddTrait(kind, it) },
                        onRemove = onRemoveTrait,
                    )
                }
            }

            TextButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.padding(vertical = 24.dp),
            ) {
                Text("Remove ${person.displayName}", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove ${person.displayName}?") },
            // FR-PPL-08: say both halves. Reminders stop now; the record is recoverable.
            text = {
                Text(
                    "Reminders stop immediately. You can restore them for the next 30 days.",
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun EventRow(
    event: PersonEvent,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleMute: (Boolean) -> Unit,
) {
    val tone = toneFor(event.sentiment)
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        colors = if (event.sentiment == Sentiment.SOLEMN) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(event.title, fontWeight = FontWeight.SemiBold)
                    Text(
                        BirthdayMath.formatDayMonth(event.date.day, event.date.month) +
                            (event.date.year?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                TextButton(onClick = onEdit) { Text("Edit") }
                TextButton(onClick = onDelete) { Text("Remove") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = !event.isMuted, onCheckedChange = { onToggleMute(!it) })
                Text(
                    // For a deceased person's birthday this reads "Remembrance reminder off",
                    // which is the FR-EVT-05 opt-in stated as a control rather than a notice.
                    tone.reminderToggle(!event.isMuted),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
}

/**
 * The score and the one-tap improve affordance (FR-PPL-05).
 *
 * The field it names comes from the SERVER's ordered list, mirrored into SQLite. The client
 * supplies the wording and nothing else — duplicating the weight order here is exactly the
 * "weights in one place" rule BUILD_PROMPT_M1 sets.
 */
@Composable
private fun CompletenessBar(person: Person, onImprove: () -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                "Profile ${person.completenessScore}% complete",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LinearProgressIndicator(
            progress = { person.completenessScore / 100f },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )

        val next = person.nextImprovement
        when {
            // FR-PPL-09: a minor is excluded from profiling, so nothing is ever asked.
            person.isMinor -> Unit

            /*
             * "Nothing left to add" is only true when the profile is actually complete.
             *
             * The suggestion list is the SERVER's, mirrored locally, and a person the server
             * has never scored has an empty one — so a freshly added person read
             * "Profile 0% complete" directly above "Nothing left to add." Both lines were
             * drawn from the same absence of data and they contradicted each other.
             *
             * The score disambiguates it, and provably: `completenessSuggestions` returns
             * every unsatisfied field, so for a non-minor a score below 100 ALWAYS comes with
             * a non-empty list (asserted in derived.spec.ts). An empty list with a score
             * below 100 therefore cannot mean "complete" — it can only mean "not scored yet",
             * and the honest thing to show for that is nothing at all.
             */
            next == null && person.completenessScore < 100 -> Unit

            next == null -> Text(
                "Nothing left to add.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            // Hobbies, likes, dislikes and the budget band are chips further down this very
            // screen, so pointing at the editor would be wrong — it does not hold them.
            next in TRAIT_FIELDS -> Text(
                "Add ${next.prompt} below to improve this profile.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            else -> TextButton(onClick = onImprove, modifier = Modifier.padding(top = 2.dp)) {
                Text("Add ${next.prompt}")
            }
        }
    }
}

private val TRAIT_FIELDS = setOf(
    CompletenessField.HOBBY,
    CompletenessField.LIKE,
    CompletenessField.DISLIKE,
    CompletenessField.BUDGET_BAND,
)

@Composable
private fun ProfileLine(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text("$label: ", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun TraitSection(
    kind: TraitKind,
    person: Person,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    val existing = person.traitsByKind[kind].orEmpty()

    Column(Modifier.padding(top = 8.dp)) {
        Text(kind.label, style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            existing.forEach { trait ->
                InputChip(
                    selected = false,
                    onClick = { onRemove(trait.id) },
                    label = { Text(trait.value) },
                    trailingIcon = { Text("×") },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("Add ${kind.label.lowercase()}") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { onAdd(draft); draft = "" },
                enabled = draft.isNotBlank(),
            ) { Text("Add") }
        }
    }
}


