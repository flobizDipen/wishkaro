package `in`.wishkaro.app.ui.addperson

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import `in`.wishkaro.app.domain.BirthdayMath
import `in`.wishkaro.app.domain.EventDate
import `in`.wishkaro.app.domain.EventType
import `in`.wishkaro.app.domain.FieldLimits
import `in`.wishkaro.app.domain.Person
import `in`.wishkaro.app.ui.components.enumSaver
import `in`.wishkaro.app.ui.components.DateFieldsSaver
import `in`.wishkaro.app.ui.components.DateFieldsState
import `in`.wishkaro.app.ui.components.EventDateFields
import `in`.wishkaro.app.ui.components.EventTypePicker
import `in`.wishkaro.app.ui.components.RelationPicker
import `in`.wishkaro.app.ui.components.SectionHeader
import kotlinx.coroutines.launch

/**
 * Name, date, relation — the three inputs FR-PPL-01 allows. Everything else is added later
 * from the person's own screen, which is what "progressively collected" means.
 *
 * The "year unknown" checkbox is the interesting part: when it is ticked the year is sent as
 * null and no age is ever derived from it anywhere downstream (FR-EVT-02).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddPersonScreen(
    duplicates: List<Person>,
    onCheckDuplicates: suspend (String, EventDate) -> Boolean,
    onDismissDuplicates: () -> Unit,
    onSave: (name: String, relation: String?, type: EventType, label: String?, date: EventDate) -> Unit,
    onOpenExisting: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // rememberSaveable throughout: the Activity is recreated on rotate, fold and resize
    // now (see AndroidManifest.xml), and losing a half-entered person to turning the phone
    // sideways is the failure FR-VIEW-07 is about.
    var name by rememberSaveable { mutableStateOf("") }
    var relation by rememberSaveable { mutableStateOf<String?>(null) }
    var type by rememberSaveable(stateSaver = enumSaver<EventType>()) {
        mutableStateOf(EventType.BIRTHDAY)
    }
    var label by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable(stateSaver = DateFieldsSaver) { mutableStateOf(DateFieldsState()) }
    val scope = rememberCoroutineScope()

    val needsLabel = type == EventType.CUSTOM
    val canSave = name.isNotBlank() && date.isValid && (!needsLabel || label.isNotBlank())

    fun attemptSave() {
        val eventDate = date.toEventDate() ?: return
        scope.launch {
            // FR-PPL-06: check first, and only save if nothing looks like a duplicate. The
            // check runs against the LOCAL database, so it still works with the radio off.
            val clash = onCheckDuplicates(name, eventDate)
            if (!clash) {
                onSave(name, relation, type, label.ifBlank { null }.takeIf { needsLabel }, eventDate)
            }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Add a person") },
                navigationIcon = { TextButton(onClick = onCancel) { Text("Cancel") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(FieldLimits.NAME) },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("add_person_name"),
            )

            SectionHeader("What are you remembering?")
            EventTypePicker(selected = type, onSelect = { type = it })

            if (needsLabel) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Call it what?") },
                    placeholder = { Text("Shop anniversary") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }

            if (type == EventType.DEATH_ANNIVERSARY) {
                Text(
                    "WishKaro will keep this person's page quiet, and will never wish them " +
                        "anything.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            SectionHeader("When?")
            EventDateFields(state = date, onChange = { date = it })

            SectionHeader("Who are they to you?")
            RelationPicker(selected = relation, onSelect = { relation = it })

            Button(
                onClick = ::attemptSave,
                enabled = canSave,
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            ) { Text("Save") }
        }
    }

    if (duplicates.isNotEmpty()) {
        val first = duplicates.first()
        AlertDialog(
            onDismissRequest = onDismissDuplicates,
            title = { Text("Is this the same person?") },
            text = {
                Column {
                    Text("You already have someone with a matching name and date:")
                    duplicates.take(3).forEach { person ->
                        val event = person.events.firstOrNull()
                        Text(
                            buildString {
                                append("• ")
                                append(person.displayName)
                                person.relationToOwner?.let { append(" ($it)") }
                                event?.let {
                                    append(" — ")
                                    append(BirthdayMath.formatDayMonth(it.date.day, it.date.month))
                                }
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onDismissDuplicates(); onOpenExisting(first.id) }) {
                    Text("Open ${first.displayName}")
                }
            },
            dismissButton = {
                // Saving anyway is allowed. Two people really can share a name and a
                // birthday, and a duplicate check that refuses the write is a bug report
                // waiting to happen.
                TextButton(
                    onClick = {
                        onDismissDuplicates()
                        date.toEventDate()?.let { d ->
                            onSave(
                                name,
                                relation,
                                type,
                                label.ifBlank { null }.takeIf { needsLabel },
                                d,
                            )
                        }
                    },
                ) { Text("Add anyway") }
            },
        )
    }
}
