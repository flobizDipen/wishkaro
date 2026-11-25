package `in`.wishkaro.app.ui.person

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import `in`.wishkaro.app.domain.EventType
import `in`.wishkaro.app.domain.Person
import `in`.wishkaro.app.ui.components.RelationPicker
import `in`.wishkaro.app.ui.components.SectionHeader

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

    var name by remember(person.id) { mutableStateOf(person.displayName) }
    var relation by remember(person.id) { mutableStateOf(person.relationToOwner) }
    var gender by remember(person.id) { mutableStateOf(person.gender.orEmpty()) }
    var phone by remember(person.id) { mutableStateOf(person.phone.orEmpty()) }
    var email by remember(person.id) { mutableStateOf(person.email.orEmpty()) }
    var notes by remember(person.id) { mutableStateOf(person.notes.orEmpty()) }
    var deceased by remember(person.id) { mutableStateOf(person.isDeceased) }

    val hasDeathAnniversary = person.events.any { it.type == EventType.DEATH_ANNIVERSARY }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Edit") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Cancel") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            SectionHeader("Relation")
            RelationPicker(selected = relation, onSelect = { relation = it })

            SectionHeader("Optional")
            OutlinedTextField(
                value = gender,
                onValueChange = { gender = it },
                label = { Text("Gender") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("Phone") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Notes") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            SectionHeader("Remembrance")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = deceased,
                    // Turning it off is refused by the server while a death anniversary is
                    // live, so the switch does not offer the action at all rather than
                    // letting the user make it and be told no.
                    enabled = !hasDeathAnniversary || !deceased,
                    onCheckedChange = { deceased = it },
                )
                Text(
                    "This person has passed away",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Text(
                when {
                    hasDeathAnniversary ->
                        "They have a death anniversary recorded. Remove that date first if " +
                            "this was a mistake."
                    deceased ->
                        "Every date for this person becomes quiet, and their birthday " +
                            "reminder is switched off unless you turn it back on."
                    else -> ""
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )

            Button(
                onClick = { onSave(name, relation, gender, phone, email, notes, deceased) },
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            ) { Text("Save") }
        }
    }
}
