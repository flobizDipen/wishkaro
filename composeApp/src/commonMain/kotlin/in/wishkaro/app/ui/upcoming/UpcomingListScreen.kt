package `in`.wishkaro.app.ui.upcoming

import `in`.wishkaro.app.domain.BirthdayMath
import `in`.wishkaro.app.domain.UpcomingEntry
import `in`.wishkaro.app.domain.UpcomingSection
import `in`.wishkaro.app.domain.toneFor
import `in`.wishkaro.app.ui.components.DateBadge
import `in`.wishkaro.app.ui.components.EmptyState
import `in`.wishkaro.app.ui.components.LoadingState
import `in`.wishkaro.app.ui.components.PersonAvatar
import `in`.wishkaro.app.ui.components.SectionHeader
import `in`.wishkaro.app.ui.components.WishKaroCard
import `in`.wishkaro.app.ui.theme.SolemnScope
import `in`.wishkaro.app.ui.theme.accent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * FR-VIEW-01 — upcoming events grouped Today / This week / This month / Later.
 *
 * The app's default view, and the one a user with three hundred people judges it on. It is a
 * list of EVENTS, not of people: somebody with a birthday in March and an anniversary in
 * June appears under both headings, because "what is coming up" is a question about dates.
 *
 * Where the groups come from, and why they are rolling rather than calendar windows, is in
 * `domain/Upcoming.kt`. Nothing here computes a date.
 *
 * **M7 changed how this reads, not what it says.** Three things, and the first is the one the
 * build prompt asked for by name:
 *
 *  - **The date is the hero.** It was a fragment of a `buildString`, glued to the event title
 *    with a `·` at `bodyMedium` — the same size and weight as everything else in the row. It is
 *    now a [DateBadge] on the leading edge, in the serif, and a column of them lines up down the
 *    list.
 *  - **Every row is inside a [SolemnScope].** A remembrance row's countdown, avatar and date
 *    are drawn in the desaturated accent without any of those three composables knowing what a
 *    remembrance is. This replaces the `if (person.isSolemn) onSurfaceVariant else primary`
 *    that was written out at each call site — one of which, on this screen, had been missed:
 *    the countdown here was unconditionally `primary`, so a death anniversary five days away
 *    was drawn in the same warm accent as a birthday.
 *  - **An avatar**, so a long list can be scanned rather than read.
 */
@Composable
fun UpcomingListScreen(
    /** null while the first database read is still in flight. Not the same as empty. */
    sections: List<UpcomingSection>?,
    focusedPersonId: String?,
    onOpenPerson: (String) -> Unit,
    /** FR-WSH-01's second entry point: straight from the card to the composer. */
    onSendWish: (String, String) -> Unit = { _, _ -> },
    onAddPerson: () -> Unit,
    onOpenImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (sections == null) {
        // Still reading. Offering "add your first person" to somebody who has 575 of them,
        // for the one frame before the query answers, is worse than a spinner: it is the
        // app telling the user their data is gone.
        LoadingState(modifier = modifier, tag = "upcoming_loading")
        return
    }

    if (sections.isEmpty()) {
        // FR-VIEW-05: every view's empty state leads somewhere. Never a blank screen.
        // Import first: M3's checklist item is to ask who can reach a feature a week after
        // they start, and somebody staring at an empty list is exactly who import was built for.
        EmptyState(
            title = "Nothing coming up yet",
            body = "Bring your people in and WishKaro will tell you whose day is next.",
            titleTag = "upcoming_empty",
            primaryAction = "Import from contacts or a file" to onOpenImport,
            secondaryAction = "Add someone by hand" to onAddPerson,
            primaryTag = "empty_import",
            secondaryTag = "empty_add",
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
                SectionHeader(
                    text = section.bucket.label,
                    trailing = section.entries.size.toString(),
                    tag = "section_${section.bucket.name}",
                )
            }
            items(section.entries, key = { it.event.id }) { entry ->
                UpcomingRow(
                    entry = entry,
                    highlighted = entry.personId == focusedPersonId,
                    onClick = { onOpenPerson(entry.personId) },
                    onSendWish = { onSendWish(entry.personId, entry.event.id) },
                )
            }
        }
    }
}

@Composable
private fun UpcomingRow(
    entry: UpcomingEntry,
    highlighted: Boolean,
    onClick: () -> Unit,
    onSendWish: () -> Unit = {},
) {
    // Tone comes from the PERSON, never from the event type: a deceased person's birthday is
    // a SOLEMN event whose type is still BIRTHDAY (FR-EVT-05).
    val tone = toneFor(entry.person)

    // One scope around the whole row. Everything inside that reads MaterialTheme.accent —
    // the date, the avatar's ring, the countdown — desaturates together, and none of them
    // has to be told why.
    SolemnScope(entry.isSolemn) {
        WishKaroCard(solemn = entry.isSolemn, highlighted = highlighted, onClick = onClick) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DateBadge(
                    day = entry.event.date.day,
                    month = entry.event.date.month,
                )
                Spacer(Modifier.width(12.dp))
                PersonAvatar(entry.displayName)
                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        entry.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        buildString {
                            append(entry.title)
                            // No age when the year is unknown, and none at all for someone
                            // who has died — the solemn string set returns null for both.
                            tone.years(entry.turningAge, entry.event.type)?.let {
                                append(" · ")
                                append(it)
                            }
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    entry.person.relationToOwner?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        entry.countdown,
                        style = MaterialTheme.typography.labelLarge,
                        // The accent, read through the solemn local. This line used to be an
                        // unconditional `colorScheme.primary` — so a remembrance five days
                        // away was drawn in the same warm colour as a fifth birthday, on the
                        // app's default screen.
                        color = MaterialTheme.accent,
                        textAlign = TextAlign.End,
                    )
                    /**
                     * FR-WSH-01 from the event card, and the reason it is only on the near ones.
                     *
                     * The question M3 §6.1 bought: who can reach this a week after they start
                     * using the app? With 575 people the list runs to hundreds of rows, and a
                     * send button on every one of them is a wall of buttons for occasions
                     * months away. Within a week is when somebody actually wants to write
                     * something, and the person screen is the route for everything else.
                     *
                     * The LABEL is tone-split, like the notification action and the person
                     * screen: "Send" on a birthday, "Message" on a remembrance.
                     */
                    if (entry.daysUntil in 0..7) {
                        TextButton(
                            onClick = onSendWish,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            // Per EVENT, not a shared tag. M4: anything a device sequence has
                            // to touch must be addressable AS IT IS WRITTEN — and the first
                            // version of this line escaped the template wrongly, so every row
                            // carried the literal string "upcoming_send_${entry.event.id}" and
                            // none could be resolved. Found by dumping the tree on the device.
                            modifier = Modifier
                                .width(IntrinsicSendWidth)
                                .testTag("upcoming_send_" + entry.event.id),
                        ) {
                            Text(if (entry.isSolemn) "Message" else "Send")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Fixed, so a row with a send button and a row without are the same height.
 *
 * Without it the countdown column changes width between "Send" and "Message", and a list of
 * them visibly ripples where a remembrance sits among birthdays.
 */
private val IntrinsicSendWidth = 76.dp
