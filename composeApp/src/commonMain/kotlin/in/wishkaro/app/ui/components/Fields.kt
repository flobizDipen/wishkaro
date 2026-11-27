package `in`.wishkaro.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import `in`.wishkaro.app.domain.EventDate
import `in`.wishkaro.app.domain.EventType
import `in`.wishkaro.app.domain.FieldLimits
import `in`.wishkaro.app.domain.MONTH_FULL_NAMES
import `in`.wishkaro.app.domain.RELATION_CHIPS

/**
 * Everything a form field needs to survive the Activity being destroyed and rebuilt.
 *
 * Until M4 the manifest declared `configChanges` for orientation, screen size and the rest,
 * so the Activity was never recreated and no form in this app had ever been asked to
 * restore itself. Not one of them used `rememberSaveable`. Removing that declaration —
 * which is what makes FR-VIEW-07 a real claim rather than a tautology — means every half
 * typed name, date and note now has to be written into saved state by hand.
 *
 * Savers rather than `rememberSaveable`'s automatic one: the automatic saver can only
 * handle what the platform's own state bundle understands, and these are a data class and
 * an enum. Storing them as a list of strings and a name works the same on Android and on
 * Kotlin/Native.
 */
val DateFieldsSaver: Saver<DateFieldsState, Any> = listSaver(
    save = { listOf(it.day, it.month, it.year, it.yearUnknown) },
    restore = {
        DateFieldsState(
            day = it[0] as String,
            month = it[1] as String,
            year = it[2] as String,
            yearUnknown = it[3] as Boolean,
        )
    },
)

/** An enum by name, so the saved form is readable and survives a reordering of the enum. */
inline fun <reified T : Enum<T>> enumSaver(): Saver<T, String> =
    Saver(save = { it.name }, restore = { enumValueOf<T>(it) })

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

/**
 * A recurring date: day, month, year — and "I don't know the year".
 *
 * ## The month is a dropdown now, and that is the biggest change on this form
 *
 * It was three numeric text fields side by side, so entering a birthday meant deciding whether
 * `06` in the middle box was June, and the only feedback was the import preview's warning on a
 * screen the user may never see. `ImportDates` has a whole date-order toggle, and a paragraph
 * explaining that WishKaro *"never guesses this from the file"*, because exactly this ambiguity
 * costs real data. The manual form had the same ambiguity and none of the explanation.
 *
 * A month you pick by NAME cannot be ambiguous. The day and year stay numeric, where they are
 * unambiguous.
 *
 * ## What did NOT change
 *
 * `DateFieldsState` still holds strings, a half-typed year is still legal, and 29 February
 * with no year is still valid — it is a real birthday and FR-EVT-09 is the rule for showing
 * it, not a reason to refuse it. The saver, the validation and `toEventDate()` are untouched.
 */
@Composable
fun EventDateFields(
    state: DateFieldsState,
    onChange: (DateFieldsState) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WishKaroDropdown(
            label = "Month",
            options = (1..12).map { it.toString() },
            selected = state.monthInt?.takeIf { it in 1..12 }?.toString(),
            placeholder = "Which month?",
            tag = "event_month",
            optionLabel = { MONTH_FULL_NAMES[it.toInt() - 1] },
            onSelect = { onChange(state.copy(month = it)) },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.day,
                onValueChange = { onChange(state.copy(day = it.filter(Char::isDigit).take(2))) },
                label = { Text("Day") },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Next,
                ),
                singleLine = true,
                modifier = Modifier.weight(1f).testTag("event_day"),
            )
            OutlinedTextField(
                value = if (state.yearUnknown) "" else state.year,
                onValueChange = { onChange(state.copy(year = it.filter(Char::isDigit).take(4))) },
                label = { Text("Year") },
                placeholder = { Text("optional") },
                enabled = !state.yearUnknown,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                singleLine = true,
                modifier = Modifier.weight(1.3f).testTag("event_year"),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = state.yearUnknown,
                onCheckedChange = { onChange(state.copy(yearUnknown = it)) },
                modifier = Modifier.testTag("year_unknown"),
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
 * The curated list (FR-PPL-02), as a dropdown, with free text behind "Other".
 *
 * ## Why this stopped being chips
 *
 * It was a `FlowRow` of seventeen `FilterChip`s with a free-text field under it, and on a
 * phone that is four rows of chips — most of a screen, on a form whose other three fields the
 * user still has to reach. Worse, the two controls disagreed: picking a chip and typing in the
 * box both set the same value, so the screen had two places showing one thing.
 *
 * One dropdown, and the box appears only when it is needed.
 *
 * ## What did NOT change
 *
 * `RELATION_CHIPS` is untouched, including the fact that it already ends with "Other" — which
 * is what made this a presentation change rather than a vocabulary one. The stored value is
 * still free text, which is the point FR-PPL-02 is making: *"an enum of relations would make
 * 'Mama' or 'Chachi' unrepresentable in an India-first app."* Choosing "Other" is how you
 * reach that, and it is now a visible route rather than a second field somebody might not
 * notice.
 *
 * ## The one case this has to get right
 *
 * A person whose relation was already "Chachi" must open with the dropdown on **Other** and
 * "Chachi" still in the box — not with an empty picker that quietly discards it on the next
 * save. [RelationPicker] decides that from the stored value on every composition rather than
 * from remembered state, so it is also correct when the screen is recomposed for a different
 * person.
 */
@Composable
fun RelationPicker(
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
    resetKey: Any? = null,
) {
    DropdownWithOther(
        label = "Relation",
        options = RELATION_CHIPS,
        otherOption = OTHER_RELATION,
        value = selected,
        onValueChange = onSelect,
        // Deliberately not "Relation" again. The first version of this named both fields
        // after the thing rather than after what they are for, and the screenshot showed two
        // boxes labelled "Relation" stacked on each other.
        customLabel = "What do you call them?",
        maxLength = FieldLimits.RELATION,
        placeholder = "How do you know them?",
        tag = "relation",
        resetKey = resetKey,
        modifier = modifier,
    )
}

/** The entry in [RELATION_CHIPS] that means "not on this list". Never stored. */
private const val OTHER_RELATION = "Other"

// `SectionHeader` moved to `Headers.kt` in M7, with the second, different one that
// `UpcomingListScreen` had been keeping privately. Same package, so no call site changed.
