package `in`.wishkaro.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import `in`.wishkaro.app.domain.EventDate
import `in`.wishkaro.app.domain.EventType
import `in`.wishkaro.app.domain.FieldLimits
import `in`.wishkaro.app.domain.RELATION_CHIPS

/** The editable state of a recurring date, kept as strings so a half-typed year is legal. */
data class DateFieldsState(
    val day: String = "",
    val month: String = "",
    val year: String = "",
    val yearUnknown: Boolean = false,
) {
    val dayInt: Int? get() = day.toIntOrNull()
    val monthInt: Int? get() = month.toIntOrNull()
    val yearInt: Int? get() = year.toIntOrNull()

    /**
     * Valid only when the day and month exist. 29 February with no year is explicitly legal:
     * it is a real birthday, and FR-EVT-09 is the rule for showing it, not a reason to
     * refuse it.
     */
    val isValid: Boolean
        get() {
            val d = dayInt ?: return false
            val m = monthInt ?: return false
            if (m !in 1..12) return false
            val longest = when (m) {
                2 -> 29
                4, 6, 9, 11 -> 30
                else -> 31
            }
            if (d !in 1..longest) return false
            if (yearUnknown) return true
            val y = yearInt ?: return false
            if (y !in 1900..2200) return false
            // A real 29 February needs a leap year once the year is known.
            if (m == 2 && d == 29) {
                val leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
                if (!leap) return false
            }
            return true
        }

    fun toEventDate(): EventDate? {
        if (!isValid) return null
        return EventDate(dayInt!!, monthInt!!, if (yearUnknown) null else yearInt)
    }

    companion object {
        fun from(date: EventDate?): DateFieldsState = when (date) {
            null -> DateFieldsState()
            else -> DateFieldsState(
                day = date.day.toString(),
                month = date.month.toString(),
                year = date.year?.toString() ?: "",
                yearUnknown = date.year == null,
            )
        }
    }
}

@Composable
fun EventDateFields(
    state: DateFieldsState,
    onChange: (DateFieldsState) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.day,
                onValueChange = { onChange(state.copy(day = it.filter(Char::isDigit).take(2))) },
                label = { Text("Day") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = state.month,
                onValueChange = { onChange(state.copy(month = it.filter(Char::isDigit).take(2))) },
                label = { Text("Month") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = if (state.yearUnknown) "" else state.year,
                onValueChange = { onChange(state.copy(year = it.filter(Char::isDigit).take(4))) },
                label = { Text("Year") },
                enabled = !state.yearUnknown,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.weight(1.3f),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = state.yearUnknown,
                onCheckedChange = { onChange(state.copy(yearUnknown = it)) },
            )
            // Not cosmetic: when ticked, the year is sent as null and NOTHING downstream
            // derives an age, a milestone or a "turning N" from it (FR-EVT-02).
            Text("I don't know the year", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EventTypePicker(
    selected: EventType,
    onSelect: (EventType) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // UNKNOWN is a parse fallback for a type a newer server might send, never an option.
        EventType.entries.filter { it != EventType.UNKNOWN }.forEach { type ->
            FilterChip(
                selected = selected == type,
                onClick = { onSelect(type) },
                label = { Text(type.label) },
            )
        }
    }
}

/**
 * The curated list (FR-PPL-02), plus free text. The chips are a shortcut, not a constraint —
 * an enum of relations would make "Mama" or "Chachi" unrepresentable in an India-first app.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RelationPicker(
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RELATION_CHIPS.forEach { chip ->
                FilterChip(
                    selected = selected == chip,
                    onClick = { onSelect(if (selected == chip) null else chip) },
                    label = { Text(chip) },
                )
            }
        }
        OutlinedTextField(
            value = if (selected != null && selected !in RELATION_CHIPS) selected else "",
            onValueChange = { onSelect(it.take(FieldLimits.RELATION).ifBlank { null }) },
            label = { Text("Or type your own") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 16.dp, bottom = 4.dp),
    )
}
