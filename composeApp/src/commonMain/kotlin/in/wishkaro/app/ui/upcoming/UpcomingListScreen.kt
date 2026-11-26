package `in`.wishkaro.app.ui.upcoming

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.wishkaro.app.domain.BirthdayMath
import `in`.wishkaro.app.domain.UpcomingEntry
import `in`.wishkaro.app.domain.UpcomingSection
import `in`.wishkaro.app.domain.toneFor

/**
 * FR-VIEW-01 — upcoming events grouped Today / This week / This month / Later.
 *
 * The app's default view, and the one a user with three hundred people judges it on. It is a
 * list of EVENTS, not of people: somebody with a birthday in March and an anniversary in
 * June appears under both headings, because "what is coming up" is a question about dates.
 *
 * Where the groups come from, and why they are rolling rather than calendar windows, is in
 * `domain/Upcoming.kt`. Nothing here computes a date.
 */
@Composable
fun UpcomingListScreen(
    sections: List<UpcomingSection>,
    focusedPersonId: String?,
    onOpenPerson: (String) -> Unit,
    onAddPerson: () -> Unit,
    onOpenImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sections.isEmpty()) {
        // FR-VIEW-05: every view's empty state leads somewhere. Never a blank screen.
        UpcomingEmptyState(
            onAddPerson = onAddPerson,
            onOpenImport = onOpenImport,
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("upcoming_list"),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        sections.forEach { section ->
            // A key per section AND per entry. With 566 people this list is long enough that
            // recomposing rows that have not changed is visible, and an event id is stable
            // across a sync in a way a list index is not.
            item(key = "header-${section.bucket.name}") {
                SectionHeader(section)
            }
            items(section.entries, key = { it.event.id }) { entry ->
                UpcomingRow(
                    entry = entry,
                    highlighted = entry.personId == focusedPersonId,
                    onClick = { onOpenPerson(entry.personId) },
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(section: UpcomingSection) {
    Column(Modifier.padding(top = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                section.bucket.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("section_${section.bucket.name}"),
            )
            Text(
                section.entries.size.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun UpcomingRow(entry: UpcomingEntry, highlighted: Boolean, onClick: () -> Unit) {
    // Tone comes from the PERSON, never from the event type: a deceased person's birthday is
    // a SOLEMN event whose type is still BIRTHDAY (FR-EVT-05).
    val tone = toneFor(entry.person)

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = when {
            highlighted -> CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
            )
            entry.isSolemn -> CardDefaults.cardColors(
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
            Column(Modifier.weight(1f)) {
                Text(
                    entry.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    buildString {
                        append(entry.title)
                        append(" · ")
                        append(BirthdayMath.formatDayMonth(entry.event.date.day, entry.event.date.month))
                        // No age when the year is unknown, and none at all for someone who
                        // has died — the solemn string set returns null for both.
                        tone.years(entry.turningAge, entry.event.type)?.let {
                            append(" · ")
                            append(it)
                        }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                entry.person.relationToOwner?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                entry.countdown,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.End,
            )
        }
    }
}

/**
 * FR-VIEW-05.
 *
 * Two routes out, and import first: the M3 checklist item is to ask who can reach a feature
 * a week after they start, and somebody staring at an empty list is exactly the person
 * import was built for.
 */
@Composable
private fun UpcomingEmptyState(
    onAddPerson: () -> Unit,
    onOpenImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.background(MaterialTheme.colorScheme.surface).padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Nothing coming up yet",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("upcoming_empty"),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Bring your people in and WishKaro will tell you whose day is next.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onOpenImport, modifier = Modifier.testTag("empty_import")) {
            Text("Import from contacts or a file")
        }
        TextButton(onClick = onAddPerson, modifier = Modifier.testTag("empty_add")) {
            Text("Add someone by hand")
        }
    }
}
