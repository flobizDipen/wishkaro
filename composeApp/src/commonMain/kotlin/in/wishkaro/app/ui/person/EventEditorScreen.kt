package `in`.wishkaro.app.ui.person

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import `in`.wishkaro.app.domain.EventDate
import `in`.wishkaro.app.domain.EventType
import `in`.wishkaro.app.domain.FieldLimits
import `in`.wishkaro.app.domain.Person
import `in`.wishkaro.app.ui.components.DateFieldsSaver
import `in`.wishkaro.app.ui.components.enumSaver
import `in`.wishkaro.app.ui.components.DateFieldsState
import `in`.wishkaro.app.ui.components.EventDateFields
import `in`.wishkaro.app.ui.components.EventTypePicker
import `in`.wishkaro.app.ui.components.BackAffordance
import `in`.wishkaro.app.ui.components.DetailTopBar
import `in`.wishkaro.app.ui.components.Note
import `in`.wishkaro.app.ui.components.SectionHeader

/**
 * Add or edit one event. A person may have as many as they like (FR-PPL-03).
 *
 * Two rules the form enforces rather than explains afterwards:
 *   - a CUSTOM event cannot be saved without a label, because it has no other name
 *     (FR-EVT-06), and
 *   - choosing DEATH_ANNIVERSARY says plainly what it will do to the rest of the person's
 *     record, because FR-EVT-04 makes it the most consequential choice on this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditorScreen(
    person: Person?,
    eventId: String?,
    onBack: () -> Unit,
    onSave: (type: EventType, label: String?, date: EventDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (person == null) {
        onBack()
        return
    }

    val existing = eventId?.let { id -> person.events.firstOrNull { it.id == id } }
    var type by rememberSaveable(eventId, stateSaver = enumSaver<EventType>()) {
        mutableStateOf(existing?.type?.takeIf { it != EventType.UNKNOWN } ?: EventType.BIRTHDAY)
    }
    var label by rememberSaveable(eventId) { mutableStateOf(existing?.label.orEmpty()) }
    var date by rememberSaveable(eventId, stateSaver = DateFieldsSaver) {
        mutableStateOf(DateFieldsState.from(existing?.date))
    }

    val needsLabel = type == EventType.CUSTOM
    val canSave = date.isValid && (!needsLabel || label.isNotBlank())

    Scaffold(
        modifier = modifier,
        topBar = {
            DetailTopBar(
                title = if (existing == null) "Add a date" else "Edit date",
                onBack = onBack,
                back = BackAffordance.CLOSE,
            )
        },
    ) { padding ->
        Column(
            // `imePadding()` BEFORE `verticalScroll`, which is the order the edge-to-edge
            // guidance calls out: applied after the scroll it pads the scrollable's CONTENT
            // rather than its viewport, and the field still ends up under the keyboard.
            //
            // It is needed at all because this app is edge-to-edge. `adjustResize` in the
            // manifest resizes the WINDOW, and a window that has already been told not to fit
            // system windows has nothing left to resize — so from M4's `enableEdgeToEdge()`
            // onward, every scrolling form in this app could put a focused field behind the
            // keyboard. The Scaffold's default `contentWindowInsets` does not carry the IME.
            modifier = Modifier.fillMaxSize().padding(padding).imePadding()
                .verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            Text(person.displayName, style = MaterialTheme.typography.titleMedium)

            SectionHeader("What is it?")
            EventTypePicker(selected = type, onSelect = { type = it })

            if (needsLabel) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it.take(FieldLimits.RELATION) },
                    label = { Text("Call it what?") },
                    placeholder = { Text("Shop anniversary") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Next,
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }

            if (type == EventType.DEATH_ANNIVERSARY && !person.isDeceased) {
                // Said before the write, not after. FR-EVT-04 makes this irreversible in
                // practice — the server will not let the flag be cleared while this event
                // exists — so the user should know that while they can still change their mind.
                Note(
                    "Saving this marks ${person.displayName} as no longer with us. Every date " +
                        "for them becomes quiet, and their birthday reminder switches off " +
                        "unless you turn it back on.",
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            SectionHeader("When?")
            EventDateFields(state = date, onChange = { date = it })

            Button(
                onClick = {
                    onSave(type, label.ifBlank { null }.takeIf { needsLabel }, date.toEventDate()!!)
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            ) { Text("Save") }
        }
    }
}
