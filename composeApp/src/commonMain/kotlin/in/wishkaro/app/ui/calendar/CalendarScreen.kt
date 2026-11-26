package `in`.wishkaro.app.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.wishkaro.app.domain.BirthdayMath
import `in`.wishkaro.app.domain.MonthCell
import `in`.wishkaro.app.domain.UpcomingEntry
import `in`.wishkaro.app.domain.WEEKDAY_INITIALS
import `in`.wishkaro.app.domain.eventsByDay
import `in`.wishkaro.app.domain.monthGrid
import `in`.wishkaro.app.domain.monthPlus
import `in`.wishkaro.app.domain.monthTitle
import `in`.wishkaro.app.domain.toneFor
import kotlinx.datetime.LocalDate
import kotlinx.datetime.number

/**
 * FR-VIEW-02 — a month grid with event dots; tapping a day shows that day's events.
 *
 * Only the next twelve months are reachable, and that is a statement about the data rather
 * than a limitation of the screen: an event's next occurrence is by definition within a
 * year, so there is nothing to draw beyond that and nothing at all behind today. The arrows
 * stop rather than showing twelve empty grids.
 *
 * Every square comes from `domain/MonthGrid.kt`; nothing here counts days.
 */
@Composable
fun CalendarScreen(
    entries: List<UpcomingEntry>,
    today: LocalDate,
    onOpenPerson: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Saveable: the Activity is rebuilt on rotate and fold, and a calendar that jumps back
    // to this month when the phone turns has thrown away what the user was looking at.
    var monthOffset by rememberSaveable { mutableStateOf(0) }
    var selectedDay by rememberSaveable { mutableStateOf<Int?>(null) }

    val (year, month) = remember(monthOffset, today) {
        monthPlus(today.year, today.month.number, monthOffset)
    }
    val grid = remember(year, month) { monthGrid(year, month) }
    val byDay = remember(entries, year, month) { eventsByDay(entries, year, month) }

    Column(modifier = modifier.fillMaxSize().testTag("calendar_screen")) {
        MonthHeader(
            title = monthTitle(year, month),
            canGoBack = monthOffset > 0,
            canGoForward = monthOffset < MONTHS_AHEAD,
            onBack = { monthOffset -= 1; selectedDay = null },
            onForward = { monthOffset += 1; selectedDay = null },
        )

        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            WEEKDAY_INITIALS.forEach { initial ->
                Text(
                    initial,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))

        grid.weeks.forEach { week ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                week.forEach { cell ->
                    DayCell(
                        cell = cell,
                        today = today,
                        selected = cell.date != null && cell.date.day == selectedDay,
                        count = cell.date?.let { byDay[it.day]?.size } ?: 0,
                        solemnOnly = cell.date?.let { date ->
                            byDay[date.day]?.takeIf { it.isNotEmpty() }?.all { it.isSolemn }
                        } ?: false,
                        onClick = { cell.date?.let { selectedDay = it.day } },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        DaysEvents(
            day = selectedDay,
            year = year,
            month = month,
            entries = selectedDay?.let { byDay[it] }.orEmpty(),
            monthTotal = byDay.values.sumOf { it.size },
            onOpenPerson = onOpenPerson,
        )
    }
}

/** Eleven months forward plus this one: everything a next-occurrence can reach. */
private const val MONTHS_AHEAD = 11

@Composable
private fun MonthHeader(
    title: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = onBack,
            enabled = canGoBack,
            modifier = Modifier.testTag("calendar_prev"),
        ) { Text("Back") }

        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("calendar_month"),
        )

        TextButton(
            onClick = onForward,
            enabled = canGoForward,
            modifier = Modifier.testTag("calendar_next"),
        ) { Text("Next") }
    }
}

@Composable
private fun DayCell(
    cell: MonthCell,
    today: LocalDate,
    selected: Boolean,
    count: Int,
    solemnOnly: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val date = cell.date
    Box(
        modifier = modifier.aspectRatio(1f).padding(2.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (date == null) return@Box

        val isToday = date == today
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(
                    when {
                        selected -> MaterialTheme.colorScheme.primaryContainer
                        isToday -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.surface
                    },
                )
                .clickable(onClick = onClick)
                .testTag("day_${date.day}"),
        ) {
            Text(
                date.day.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
            )
            // A dot, not a number: the grid answers "is there anything that day", and the
            // list underneath answers "what". A tone-neutral colour for a day that holds
            // only remembrances — a bright accent on a death anniversary is the kind of
            // detail FR-EVT-05 exists to prevent.
            if (count > 0) {
                Box(
                    Modifier
                        .padding(top = 2.dp)
                        .size(if (count > 1) 7.dp else 5.dp)
                        .clip(CircleShape)
                        .background(
                            if (solemnOnly) {
                                MaterialTheme.colorScheme.outline
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                        .testTag("dot_${date.day}"),
                )
            }
        }
    }
}

@Composable
private fun DaysEvents(
    day: Int?,
    year: Int,
    month: Int,
    entries: List<UpcomingEntry>,
    monthTotal: Int,
    onOpenPerson: (String) -> Unit,
) {
    // FR-VIEW-05: never a blank screen. Three different nothings, three different sentences
    // — "pick a day", "nothing that day", and "nothing all month" are not the same message.
    if (day == null) {
        Text(
            if (monthTotal == 0) {
                "Nothing in ${monthTitle(year, month)}. Try the next month."
            } else {
                "Tap a day with a dot to see whose it is."
            },
            modifier = Modifier.padding(16.dp).testTag("calendar_hint"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    Text(
        "${BirthdayMath.formatDayMonth(day, month)}",
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )

    if (entries.isEmpty()) {
        Text(
            "Nothing on this day.",
            modifier = Modifier.padding(horizontal = 16.dp).testTag("calendar_day_empty"),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth().testTag("calendar_day_events"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(entries, key = { it.event.id }) { entry ->
            val tone = toneFor(entry.person)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clickable { onOpenPerson(entry.personId) }
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
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
        }
    }
}
