package `in`.wishkaro.app.ui.timeline

import `in`.wishkaro.app.domain.BirthdayMath
import `in`.wishkaro.app.domain.MONTH_FULL_NAMES
import `in`.wishkaro.app.domain.TimelineMonth
import `in`.wishkaro.app.domain.UpcomingEntry
import `in`.wishkaro.app.domain.timelineMonths
import `in`.wishkaro.app.domain.toneFor
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

/**
 * FR-VIEW-03 — a scrollable year.
 *
 * Twelve months starting with this one, in order, every one of them shown whether or not
 * anything falls in it. The empty months are the point: a year view that silently skipped
 * them would be the upcoming list again with different headings, and what this view is for
 * is seeing the SHAPE of the year — that March is crowded and September is empty.
 */
@Composable
fun TimelineScreen(
    entries: List<UpcomingEntry>,
    today: LocalDate,
    onOpenPerson: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The bucketing lives in domain/, next to eventsByDay, because it is the part that was
    // wrong and the part a device check cannot pin: inline in this composable there was no
    // seam and no test, and every event whose day had already gone by this month was being
    // dropped silently. TimelineMonthsTest covers it now.
    val months = remember(entries, today) { timelineMonths(entries, today) }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("timeline_screen"),
        contentPadding = PaddingValues(0.dp, 8.dp, 0.dp, 96.dp),
    ) {
        months.forEach { month ->
            item(key = "m-${month.year}-${month.month}") {
                MonthHeading(
                    month,
                    isFirst = month == months.first(),
                    isLast = months.size > 12 && month == months.last(),
                )
            }

            if (month.entries.isEmpty()) {
                item(key = "empty-${month.year}-${month.month}") {
                    Text(
                        "Nothing this month",
                        modifier = Modifier.padding(start = 72.dp, end = 16.dp, bottom = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                month.entries.forEach { entry ->
                    item(key = entry.event.id) {
                        TimelineRow(entry = entry, onClick = { onOpenPerson(entry.personId) })
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthHeading(month: TimelineMonth, isFirst: Boolean, isLast: Boolean) {
    Column {
        if (!isFirst) HorizontalDivider(Modifier.padding(start = 72.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                MONTH_FULL_NAMES[month.month - 1],
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("timeline_${MONTH_FULL_NAMES[month.month - 1]}"),
            )
            // The year, but only where it changes anything — writing "2026" beside all
            // twelve months is noise, and omitting it entirely is confusing in January.
            // The LAST slot is the exception that needs saying: it repeats the month the
            // list opened on, a year later, and unlabelled it reads as a duplicate heading.
            if (month.month == 1 || isFirst || isLast) {
                Text(
                    " ${month.year}",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (month.entries.isNotEmpty()) {
                Text(
                    "   ${month.entries.size}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TimelineRow(entry: UpcomingEntry, onClick: () -> Unit) {
    val tone = toneFor(entry.person)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The date rail down the left is what makes this read as a timeline rather than a
        // list with headings.
        Text(
            BirthdayMath.formatDayMonth(entry.nextOccurrence.day, entry.nextOccurrence.month.number),
            modifier = Modifier.width(56.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(
                    if (entry.isSolemn) {
                        MaterialTheme.colorScheme.outline
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                ),
        )
        Column(Modifier.padding(start = 12.dp).weight(1f)) {
            Text(entry.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                buildString {
                    append(entry.title)
                    tone.years(entry.turningAge, entry.event.type)?.let {
                        append(" · ")
                        append(it)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            entry.countdown,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
