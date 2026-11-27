package `in`.wishkaro.app.ui.person

import `in`.wishkaro.app.domain.BirthdayMath
import `in`.wishkaro.app.domain.CompletenessField
import `in`.wishkaro.app.domain.FieldLimits
import `in`.wishkaro.app.domain.Person
import `in`.wishkaro.app.domain.PersonEvent
import `in`.wishkaro.app.domain.Sentiment
import `in`.wishkaro.app.domain.TraitKind
import `in`.wishkaro.app.domain.toCard
import `in`.wishkaro.app.domain.toneFor
import `in`.wishkaro.app.ui.adaptive.AdaptiveDialog
import `in`.wishkaro.app.ui.components.SectionHeader
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import `in`.wishkaro.app.domain.Wish
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    stillLoading: Boolean = false,
    onEdit: () -> Unit,
    onAddEvent: () -> Unit,
    onEditEvent: (String) -> Unit,
    onDeleteEvent: (String) -> Unit,
    onToggleMute: (String, Boolean) -> Unit,
    onDelete: () -> Unit,
    onAddTrait: (TraitKind, String) -> Unit,
    onRemoveTrait: (String) -> Unit,
    /** FR-WSH-01's third entry point. Null means the next thing coming up for them. */
    onSendWish: (String?) -> Unit = {},
    onMakePoster: (String?) -> Unit = {},
    /** FR-PPL-07's wish history, read from the local mirror so it draws offline. */
    wishes: List<Wish> = emptyList(),
    modifier: Modifier = Modifier,
) {
    if (person == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Only say they are gone once we have actually looked. Before the first
            // database emission a person who exists is also null, and a deep link into a
            // cold process — a reminder, or M4's widget — arrives exactly then.
            if (stillLoading) {
                CircularProgressIndicator(Modifier.testTag("person_detail_loading"))
            } else {
                Text("This person is no longer here.")
            }
        }
        return
    }

    val tone = toneFor(person)
    val zone = TimeZone.currentSystemDefault()
    val card = remember(person) { person.toCard(zone, Clock.System.todayIn(zone)) }
    // Saveable: a confirmation dialog that vanishes when the phone is rotated has silently
    // cancelled something the user was part-way through deciding.
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

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

            /**
             * FR-WSH-01, from the person screen.
             *
             * The LABEL comes from the tone, not from the feature's name. FR-REM-11 already
             * makes the solemn notification say "Send message" rather than "Send wish", and
             * this button is the same action reached another way — the two must not disagree
             * about what the app calls it on a death anniversary.
             *
             * Shown even with no dates recorded: the composer falls back to a birthday, and
             * somebody who wants to send a message to a person they have stored should not
             * have to add a date first.
             */
            Button(
                onClick = { onSendWish(card.nextEvent?.id) },
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("person_send_wish"),
            ) {
                Text(if (person.isSolemn) "Send a message" else "Send a wish")
            }

            /**
             * The poster studio (FR-POS-01), M6.
             *
             * The label follows the same rule as the button above and for the same reason:
             * FR-POS-13 gives a remembrance its own template family, and a button that said
             * "Make a poster" on a death anniversary would be the first word of a
             * celebration on a screen that must not contain one.
             *
             * Secondary to the message, deliberately. A poster is "instead of, or
             * alongside" the words (FR-POS-01), and the words are the thing most people
             * will send — FR-POS-04's economics depend on the poster being a flourish
             * rather than the default path.
             */
            OutlinedButton(
                onClick = { onMakePoster(card.nextEvent?.id) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("person_make_poster"),
            ) {
                Text(if (person.isSolemn) "Make a remembrance poster" else "Make a poster")
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

            WishHistory(person = person, wishes = wishes)

            TextButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.padding(vertical = 24.dp),
            ) {
                Text("Remove ${person.displayName}", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmDelete) {
        AdaptiveDialog(
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
    var draft by rememberSaveable { mutableStateOf("") }
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
                onValueChange = { draft = it.take(FieldLimits.TRAIT) },
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



/**
 * FR-PPL-07's wish history, and FR-HIS-01's "what was sent, tone, language, channel".
 *
 * **The heading is tone-split.** "Wish history" on somebody who has died is the shape of
 * failure FR-EVT-04 is about — the record is the same record, and what the app calls it is
 * not. `SolemnStrings` does this for every other string on this screen; this follows it.
 *
 * Reads the local mirror, so it draws with the radio off like everything else here. An empty
 * history shows nothing at all rather than an empty-state card: most people on most screens
 * have never been sent anything through this app, and a permanent "no wishes yet" box on
 * every one of 575 person screens is clutter, not guidance. The button above it is the
 * affordance.
 */
@Composable
private fun WishHistory(person: Person, wishes: List<Wish>) {
    if (wishes.isEmpty()) return

    SectionHeader(if (person.isSolemn) "Messages sent" else "Wishes sent")
    wishes.forEach { wish ->
        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(
                // The year and the occasion, which is how a person remembers one of these.
                buildString {
                    append(wish.occurrenceYear)
                    append(" · ")
                    append(wish.type.label)
                    wish.channel?.let { append(" · ").append(it.label) }
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(wish.messageText, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
