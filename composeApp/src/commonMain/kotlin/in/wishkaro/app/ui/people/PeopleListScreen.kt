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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import `in`.wishkaro.app.domain.BirthdayMath
import `in`.wishkaro.app.domain.Person
import `in`.wishkaro.app.domain.PersonCard
import `in`.wishkaro.app.domain.Sentiment
import `in`.wishkaro.app.domain.toneFor
import `in`.wishkaro.app.ui.PeopleFilter
import `in`.wishkaro.app.ui.components.EmptyState as SharedEmptyState
import `in`.wishkaro.app.ui.components.CloseGlyph
import `in`.wishkaro.app.ui.components.PersonAvatar
import `in`.wishkaro.app.ui.components.WishKaroCard
import `in`.wishkaro.app.ui.components.WishKaroTopBar
import `in`.wishkaro.app.ui.adaptive.AdaptiveDialog
import `in`.wishkaro.app.ui.theme.SolemnScope
import `in`.wishkaro.app.ui.theme.accent

/**
 * The month filter chips.
 *
 * Was a third copy of this list — `BirthdayMath` kept one privately and M7's `DateBadge`
 * wanted one — so `BirthdayMath.shortMonthName` is public now and this reads it. Three copies
 * of twelve strings is how "March" and "Mar" come to disagree on two screens.
 */
private val MONTHS = (1..12).map(BirthdayMath::shortMonthName)

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
            // Shared with every other top-level view, so Import, Settings, Recently removed
            // and Sign out are one tap from all four rather than from this one.
            WishKaroTopBar(
                title = "People",
                busy = busy,
                onRefresh = onRefresh,
                onOpenSettings = onOpenSettings,
                onOpenImport = onOpenImport,
                onOpenDeleted = onOpenDeleted,
                onSignOut = onSignOut,
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
        val keyboard = LocalSoftwareKeyboardController.current
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // ---- search and filter (FR-PPL-10) ----
            OutlinedTextField(
                value = filter.query,
                onValueChange = onQueryChange,
                label = { Text("Search by name") },
                singleLine = true,
                // Search, not Done: the list filters as you type, so the keyboard's action
                // key should close the keyboard and show the results rather than suggest
                // there is something left to submit.
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                // A way out that is not "select all and delete". With 575 people a stale
                // query is the difference between an empty list and an app that looks broken,
                // and M4 found that a swipe-to-scroll with this field focused types into it.
                trailingIcon = {
                    if (filter.query.isNotEmpty()) {
                        IconButton(
                            onClick = { onQueryChange("") },
                            modifier = Modifier
                                .semantics { contentDescription = "Clear search" }
                                .testTag("people_search_clear"),
                        ) { CloseGlyph(MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                },
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

            // Clear filters stays on the screen that has the filters. Settings, Import
            // and Recently removed moved to the shared top bar above — they belong to the
            // app, not to this list, and M4 gives the app three more top-level views that
            // would otherwise never show them.
            if (filter.isActive) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.Start,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = onClearFilter,
                        modifier = Modifier.testTag("clear_filters"),
                    ) { Text("Clear filters") }
                }
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

    // One scope for the row. The avatar's ring and the countdown both read
    // MaterialTheme.accent and desaturate together; before M7 the countdown carried its own
    // `if (person.isSolemn)` branch and nothing else on the row carried one at all.
    SolemnScope(person.isSolemn) {
        WishKaroCard(solemn = person.isSolemn, highlighted = highlighted, onClick = onClick) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PersonAvatar(person.displayName)
                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        person.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        buildString {
                            append(person.relationToOwner ?: "\u2014")
                            if (event != null) {
                                append(" \u00B7 ")
                                append(event.title)
                                append(" ")
                                append(BirthdayMath.formatDayMonth(event.date.day, event.date.month))
                            }
                            // No age when the year is unknown, and none at all for someone who
                            // has died — the solemn string set returns null for both.
                            tone.years(card.turningAge, event?.type ?: return@buildString)?.let {
                                append(" \u00B7 ")
                                append(it)
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (person.events.size > 1) {
                        Text(
                            "${person.events.size} dates",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        card.countdown?.let(tone::countdown) ?: "no date",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.accent,
                    )
                    if (event?.isMuted == true) {
                        Text(
                            tone.reminderToggle(false),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (event?.sentiment == Sentiment.SOLEMN && !person.isSolemn) {
                        // Belt and braces: a SOLEMN event on a person the client thinks is
                        // alive means the local mirror is mid-sync. Show the quiet label.
                        Text(
                            "quiet",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    CompletenessIndicator(person)
                }
            }
        }
    }
}

/**
 * FR-VIEW-04's completeness indicator.
 *
 * The score is the SERVER'S and is only mirrored here — the client never computes it, which
 * is an engineering rule from M0 and the reason `COMPLETENESS_WEIGHTS` lives in one place.
 * What is decided locally is only whether to draw anything: a full profile gets no badge,
 * because a row of green ticks down a directory of three hundred people is decoration, and
 * the point of the indicator is to show which records are thin.
 */
@Composable
private fun CompletenessIndicator(person: Person) {
    if (person.completenessScore >= COMPLETE_ENOUGH) return

    Text(
        "${person.completenessScore}%",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("completeness_${person.id}"),
    )
}

/**
 * Above this, a record is not worth nagging about.
 *
 * A threshold rather than a weighting: the weighting is the server's and duplicating it here
 * is exactly what BUILD_PROMPT_M1 ruled out. This only decides whether the badge is drawn.
 */
private const val COMPLETE_ENOUGH = 80

@Composable
private fun EmptyState(
    filtered: Boolean,
    onOpenImport: () -> Unit,
    onAddPerson: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (filtered) {
        // A filtered list that matches nothing leads back to the list, not to import: the
        // user HAS people, they just cannot see them, and the way out is the chip row above.
        SharedEmptyState(
            title = "Nobody matches that",
            body = "Clear the filters above to see everyone again.",
            modifier = modifier,
        )
        return
    }

    // FR-VIEW-05: an empty state leads somewhere. Until M3 this said "Add the first person
    // whose birthday you keep forgetting" and led nowhere at all — which, for a user who has
    // three hundred of them in their contacts, is the longest possible way into the app.
    SharedEmptyState(
        title = "Nobody here yet",
        body = "Bring in the birthdays already on your phone, or from a spreadsheet \u2014 " +
            "you choose who comes in.",
        primaryAction = "Import people" to onOpenImport,
        secondaryAction = "Add one by hand" to onAddPerson,
        modifier = modifier,
    )
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
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                "They are safe on this phone, but they are not syncing and will not get " +
                    "reminders. Tap to see why.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }

    if (open) {
        AdaptiveDialog(
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
