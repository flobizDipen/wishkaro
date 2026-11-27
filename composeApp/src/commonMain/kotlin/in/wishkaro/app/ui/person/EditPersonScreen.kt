package `in`.wishkaro.app.ui.person

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import `in`.wishkaro.app.domain.EventType
import `in`.wishkaro.app.domain.FieldLimits
import `in`.wishkaro.app.domain.Person
import `in`.wishkaro.app.ui.components.RelationPicker
import `in`.wishkaro.app.ui.components.BackAffordance
import `in`.wishkaro.app.ui.components.DetailTopBar
import `in`.wishkaro.app.ui.components.SectionHeader
import `in`.wishkaro.app.ui.components.ToggleRow

/**
 * Edit every field of a person (FR-PPL-04). Nothing here is required but the name.
 *
 * The deceased switch is the one control with teeth: turning it on makes every event this
 * person owns SOLEMN and silences their birthday. The server owns that derivation, so this
 * screen sends the flag and says what will happen rather than pretending to do it itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPersonScreen(
    person: Person?,
    onBack: () -> Unit,
    onSave: (
        name: String,
        relation: String?,
        gender: String?,
        phone: String?,
        email: String?,
        notes: String?,
        isDeceased: Boolean,
    ) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (person == null) {
        onBack()
        return
    }

    // Keyed on the person AND saveable. The key resets the form when a different person is
    // edited; saveable keeps the user's unsaved edits when the Activity is rebuilt under
    // them. Before M4 only the first of those was true, because the Activity was never
    // rebuilt — see AndroidManifest.xml.
    var name by rememberSaveable(person.id) { mutableStateOf(person.displayName) }
    var relation by rememberSaveable(person.id) { mutableStateOf(person.relationToOwner) }
    var gender by rememberSaveable(person.id) { mutableStateOf(person.gender.orEmpty()) }
    var phone by rememberSaveable(person.id) { mutableStateOf(person.phone.orEmpty()) }
    var email by rememberSaveable(person.id) { mutableStateOf(person.email.orEmpty()) }
    var notes by rememberSaveable(person.id) { mutableStateOf(person.notes.orEmpty()) }
    var deceased by rememberSaveable(person.id) { mutableStateOf(person.isDeceased) }

    val hasDeathAnniversary = person.events.any { it.type == EventType.DEATH_ANNIVERSARY }

    Scaffold(
        modifier = modifier,
        topBar = {
            DetailTopBar(title = "Edit", onBack = onBack, back = BackAffordance.CLOSE)
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
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(FieldLimits.NAME) },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionHeader("Relation")
            RelationPicker(selected = relation, onSelect = { relation = it })

            SectionHeader("Optional")
            OutlinedTextField(
                value = gender,
                onValueChange = { gender = it.take(FieldLimits.GENDER) },
                label = { Text("Gender") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it.take(FieldLimits.PHONE) },
                label = { Text("Phone") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = email,
                onValueChange = { email = it.take(FieldLimits.EMAIL) },
                label = { Text("Email") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it.take(FieldLimits.NOTES) },
                label = { Text("Notes") },
                // Shown only near the limit, so it is a warning rather than clutter. The
                // field stops accepting input at the cap, and a silent stop with nothing on
                // screen is its own small mystery.
                supportingText = if (FieldLimits.showCounter(notes.length, FieldLimits.NOTES)) {
                    { Text("${notes.length} / ${FieldLimits.NOTES}") }
                } else {
                    null
                },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            SectionHeader("Remembrance")
            ToggleRow(
                title = "This person has passed away",
                checked = deceased,
                // Turning it off is refused by the server while a death anniversary is live,
                // so the switch does not offer the action at all rather than letting the user
                // make it and be told no.
                enabled = !hasDeathAnniversary || !deceased,
                onCheckedChange = { deceased = it },
                subtitle = when {
                    hasDeathAnniversary ->
                        "They have a death anniversary recorded. Remove that date first if " +
                            "this was a mistake."
                    deceased ->
                        "Every date for this person becomes quiet, and their birthday " +
                            "reminder is switched off unless you turn it back on."
                    else -> null
                },
            )

            Button(
                onClick = { onSave(name, relation, gender, phone, email, notes, deceased) },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            ) { Text("Save") }
        }
    }
}
