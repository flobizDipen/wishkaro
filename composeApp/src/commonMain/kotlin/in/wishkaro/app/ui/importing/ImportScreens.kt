package `in`.wishkaro.app.ui.importing

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `in`.wishkaro.app.domain.BirthdayMath
import `in`.wishkaro.app.domain.ContactChoice
import `in`.wishkaro.app.domain.ContactsImport
import `in`.wishkaro.app.domain.DateOrder
import `in`.wishkaro.app.domain.DateParse
import `in`.wishkaro.app.domain.EventDate
import `in`.wishkaro.app.domain.ImportDates
import `in`.wishkaro.app.ui.components.DateFieldsState
import `in`.wishkaro.app.ui.components.EventDateFields
import `in`.wishkaro.app.ui.components.RelationPicker
import `in`.wishkaro.app.ui.components.SectionHeader
import kotlinx.datetime.LocalDate

/**
 * Where an import starts: contacts, or a file (FR-IMP-01, FR-IMP-04).
 *
 * Deliberately two plain choices rather than one clever one. They behave differently enough
 * — one needs a permission, the other needs a column mapping — that merging them would mean
 * a screen that changes shape after the user has committed to it.
 */
@Composable
fun ImportStartScreen(
    onContacts: () -> Unit,
    onFile: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Bring people in", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Nothing is saved until you have looked at it and said yes.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))

        Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onContacts)) {
            Column(Modifier.padding(16.dp)) {
                Text("From your contacts", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Reads the birthdays already on this phone. Everything stays on the " +
                        "phone, and only the people you tick are saved.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onFile)) {
            Column(Modifier.padding(16.dp)) {
                Text("From a spreadsheet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "A .csv or .xlsx file. You choose which column is which, and you see " +
                        "the first few rows before anything is saved.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        TextButton(onClick = onBack) { Text("Not now") }
    }
}

// =====================================================================================
// Contacts
// =====================================================================================

@Composable
fun ImportContactsScreen(
    store: ImportStore,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by store.contactsState.collectAsState()
    val busy by store.busy.collectAsState()
    val result by store.result.collectAsState()

    LaunchedEffect(Unit) { store.startContacts() }
    LaunchedEffect(result) { if (result != null) onDone() }

    when (val current = state) {
        ContactsState.AskingPermission -> PermissionRationale(
            busy = busy,
            onAsk = store::askForContactsPermission,
            onBack = onBack,
            modifier = modifier,
        )

        ContactsState.Denied -> PermissionRefused(
            canAskAgain = true,
            onAsk = store::askForContactsPermission,
            onSettings = store::openAppSettings,
            onRecheck = store::startContacts,
            onBack = onBack,
            modifier = modifier,
        )

        ContactsState.DeniedPermanently -> PermissionRefused(
            canAskAgain = false,
            onAsk = store::askForContactsPermission,
            onSettings = store::openAppSettings,
            onRecheck = store::startContacts,
            onBack = onBack,
            modifier = modifier,
        )

        ContactsState.Reading -> Column(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Reading your contacts…")
        }

        is ContactsState.Review -> ContactsReview(
            choices = current.choices,
            busy = busy,
            store = store,
            onBack = onBack,
            modifier = modifier,
        )
    }
}

/**
 * The rationale, shown BEFORE the system dialog (FR-IMP-01).
 *
 * It says what is read and what is not, because "allow WishKaro to access your contacts?" is
 * a frightening question with no detail in it, and the honest answer — the list never leaves
 * the phone — is the one thing the system dialog cannot say.
 */
@Composable
private fun PermissionRationale(
    busy: Boolean,
    onAsk: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Read your contacts?", style = MaterialTheme.typography.headlineSmall)
        Text(
            "WishKaro looks for birthdays already saved against your contacts, so you do " +
                "not have to type them again.",
        )
        Text(
            "• It all happens on this phone.\n" +
                "• You pick who comes in — nothing is saved until you tap Import.\n" +
                "• The people you do not pick are never saved, never sent anywhere, and " +
                "never written down.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(onClick = onAsk, enabled = !busy) { Text("Continue") }
        TextButton(onClick = onBack) { Text("Not now") }
    }
}

/**
 * Denied, and denied permanently — which are different situations and get different screens.
 *
 * When Android has stopped asking, an "Ask again" button does nothing at all when tapped,
 * which is worse than not offering it: the user presses it, the screen does not change, and
 * the app looks broken rather than blocked.
 */
@Composable
private fun PermissionRefused(
    canAskAgain: Boolean,
    onAsk: () -> Unit,
    onSettings: () -> Unit,
    onRecheck: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("No contacts, then", style = MaterialTheme.typography.headlineSmall)
        if (canAskAgain) {
            Text("That is fine. You can add people by hand, or import a spreadsheet instead.")
            Button(onClick = onAsk) { Text("Actually, let me pick contacts") }
        } else {
            Text(
                "Android will not ask again from inside the app. If you change your mind, " +
                    "the switch is in Settings → Permissions → Contacts.",
            )
            Button(onClick = onSettings) { Text("Open Settings") }
            // Found on the device: tapping Open Settings, granting the permission there and
            // coming back left this screen exactly as it was, still saying no. The screen
            // only reads the permission when it first opens, and returning from Settings is
            // not opening it — so the one route the copy sends you down appears to do
            // nothing. This is the way back in.
            OutlinedButton(onClick = onRecheck) { Text("I've turned it on") }
        }
        OutlinedButton(onClick = onBack) { Text("Add people another way") }
    }
}

/**
 * The review list (FR-IMP-02, FR-IMP-03).
 *
 * Dated contacts first and pre-ticked, the rest after and untouched. On a real phone this
 * list is 500 long with three dates in it, so the bulk relation action and the counts at the
 * top are not polish — they are the difference between a usable screen and a scroll.
 */
@Composable
private fun ContactsReview(
    choices: List<ContactChoice>,
    busy: Boolean,
    store: ImportStore,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = choices.count { it.selected }
    val missingRelations = ContactsImport.missingRelations(choices)
    var bulkRelationOpen by remember { mutableStateOf(false) }
    var datingContact by remember { mutableStateOf<ContactChoice?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        Column(Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp)) {
            Text("Choose who comes in", style = MaterialTheme.typography.titleLarge)
            Text(
                "$selected of ${choices.size} selected" +
                    if (missingRelations > 0) " · $missingRelations still need a relation" else "",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                // A button the user presses, never a default (the engineering rules).
                TextButton(onClick = store::selectAllWithDates) { Text("Select all with dates") }
                TextButton(onClick = store::selectNone) { Text("Clear") }
                TextButton(
                    onClick = { bulkRelationOpen = true },
                    enabled = selected > 0,
                ) { Text("Set relation") }
            }
        }
        Divider()

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            items(choices, key = { it.contact.id }) { choice ->
                ContactRow(
                    choice = choice,
                    onToggle = { store.toggleContact(choice.contact.id) },
                    onAddDate = { datingContact = choice },
                )
            }
        }

        Divider()
        Column(Modifier.padding(16.dp)) {
            Button(
                onClick = store::importSelectedContacts,
                enabled = !busy && selected > 0 && missingRelations == 0,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (selected == 0) "Pick someone first" else "Import $selected")
            }
            if (missingRelations > 0) {
                Text(
                    "$missingRelations of the people you picked still need a relation. " +
                        "Use \"Set relation\" to do them all at once.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            TextButton(onClick = onBack) { Text("Cancel") }
        }
    }

    if (bulkRelationOpen) {
        BulkRelationDialog(
            count = selected,
            onPick = {
                store.setRelationForSelected(it)
                bulkRelationOpen = false
            },
            onDismiss = { bulkRelationOpen = false },
        )
    }

    datingContact?.let { choice ->
        AddDateDialog(
            name = choice.contact.displayName,
            onSave = { date ->
                store.setContactDate(choice.contact.id, date)
                store.toggleContact(choice.contact.id)
                datingContact = null
            },
            onDismiss = { datingContact = null },
        )
    }
}

@Composable
private fun ContactRow(choice: ContactChoice, onToggle: () -> Unit, onAddDate: () -> Unit) {
    val date = choice.effectiveDate
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(enabled = choice.hasDate, onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = choice.selected, onCheckedChange = { onToggle() }, enabled = choice.hasDate)
        Column(Modifier.weight(1f)) {
            Text(
                choice.contact.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (choice.selected) FontWeight.Medium else FontWeight.Normal,
            )
            Text(
                buildString {
                    if (date != null) {
                        append(BirthdayMath.formatDayMonth(date.day, date.month))
                        date.year?.let { append(" $it") }
                    } else {
                        append("No date on this contact")
                    }
                    choice.relation?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (date == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
        if (!choice.hasDate) {
            TextButton(onClick = onAddDate) { Text("Add date") }
        }
    }
}

@Composable
private fun BulkRelationDialog(count: Int, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var custom by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How do you know these $count?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    "This sets the same relation on everyone you have ticked. You can change " +
                        "any of them afterwards.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                // The existing curated chip list (FR-PPL-02) — a new interaction over an
                // existing vocabulary, not a new vocabulary.
                RelationPicker(selected = custom, onSelect = { custom = it })
            }
        },
        confirmButton = {
            TextButton(
                onClick = { custom?.takeIf { it.isNotBlank() }?.let(onPick) },
                enabled = !custom.isNullOrBlank(),
            ) { Text("Set for all $count") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun AddDateDialog(name: String, onSave: (EventDate) -> Unit, onDismiss: () -> Unit) {
    var fields by remember { mutableStateOf(DateFieldsState()) }
    val parsed = fields.toEventDate()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = {
            Column {
                Text(
                    "This contact has no birthday saved on the phone. Add one and they can " +
                        "come in with the rest.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                EventDateFields(state = fields, onChange = { fields = it })
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onSave) }, enabled = parsed != null) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// =====================================================================================
// File import
// =====================================================================================

@Composable
fun ImportFileScreen(
    store: ImportStore,
    today: LocalDate,
    onBack: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by store.fileState.collectAsState()
    val busy by store.busy.collectAsState()
    val result by store.result.collectAsState()

    LaunchedEffect(result) { if (result != null) onDone() }

    when (val current = state) {
        FileState.Choosing -> Column(
            modifier = modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Import a spreadsheet", style = MaterialTheme.typography.headlineSmall)
            Text(
                "A .csv or .xlsx with one person per row. It is read on this phone — the " +
                    "file is not uploaded anywhere.",
            )
            Button(onClick = store::pickFile, enabled = !busy) { Text("Choose a file") }
            TextButton(onClick = onBack) { Text("Back") }
        }

        is FileState.Unreadable -> Column(
            modifier = modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("That file did not open", style = MaterialTheme.typography.headlineSmall)
            Text(current.fileName, style = MaterialTheme.typography.bodySmall)
            Text(current.reason)
            Button(onClick = store::pickFile) { Text("Try another file") }
            TextButton(onClick = onBack) { Text("Back") }
        }

        is FileState.Mapping -> MappingScreen(
            state = current,
            busy = busy,
            today = today,
            store = store,
            onBack = onBack,
            modifier = modifier,
        )
    }
}

/**
 * The column-mapping screen with its live five-row preview (FR-IMP-05).
 *
 * The preview is not polish. It is the only thing between a wrong mapping and a database
 * full of people whose relation is a phone number — and it is the only place the dd/MM
 * toggle becomes visible, because flipping it changes what the dates say on screen while
 * the user is looking at them.
 */
@Composable
private fun MappingScreen(
    state: FileState.Mapping,
    busy: Boolean,
    today: LocalDate,
    store: ImportStore,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = state.columnLabels
    var relationPickerOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(state.fileName, style = MaterialTheme.typography.titleMedium)
        Text("${state.rows.size} rows", style = MaterialTheme.typography.bodySmall)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = state.settings.hasHeaderRow,
                onCheckedChange = store::setHasHeaderRow,
            )
            Text("The first row is column headings")
        }

        SectionHeader("Which column is which")
        for (field in ImportField.entries) {
            Column {
                Text(
                    field.label + if (field == ImportField.NAME || field == ImportField.DATE) " (needed)" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
                // Scrolls sideways: a file with twelve columns does not fit, and a chip
                // the user cannot reach is a column they cannot map.
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = field.get(state.mapping) == null,
                        onClick = { store.setColumn(field, null) },
                        label = { Text("—") },
                    )
                    labels.forEachIndexed { index, label ->
                        FilterChip(
                            selected = field.get(state.mapping) == index,
                            onClick = { store.setColumn(field, index) },
                            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        }

        SectionHeader("Dates like 05/06/1992")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.settings.order == DateOrder.DAY_FIRST,
                onClick = { store.setDateOrder(DateOrder.DAY_FIRST) },
                label = { Text("Day first (5 June)") },
            )
            FilterChip(
                selected = state.settings.order == DateOrder.MONTH_FIRST,
                onClick = { store.setDateOrder(DateOrder.MONTH_FIRST) },
                label = { Text("Month first (6 May)") },
            )
        }
        Text(
            "WishKaro never guesses this from the file. A spreadsheet where every date is " +
                "under the 13th reads perfectly well both ways and only one of them is yours.",
            style = MaterialTheme.typography.bodySmall,
        )

        SectionHeader("Relation")
        Text(
            state.settings.defaultRelation?.let { "Rows with no relation of their own: $it" }
                ?: "Rows with no relation of their own cannot be imported (every person needs one).",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(onClick = { relationPickerOpen = true }) {
            Text(state.settings.defaultRelation?.let { "Change" } ?: "Set one for those rows")
        }

        SectionHeader("The first few rows, as WishKaro reads them")
        PreviewTable(state = state, today = today)

        Button(
            onClick = store::importFile,
            enabled = !busy && state.mapping.isUsable,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.mapping.isUsable) "Import" else "Pick a Name and a Date column")
        }
        Row {
            TextButton(onClick = store::restartFilePick) { Text("Choose another file") }
            TextButton(onClick = onBack) { Text("Cancel") }
        }
    }

    if (relationPickerOpen) {
        BulkRelationDialog(
            count = state.rows.size,
            onPick = {
                store.setDefaultRelation(it)
                relationPickerOpen = false
            },
            onDismiss = { relationPickerOpen = false },
        )
    }
}

/**
 * Five rows, as they will actually be stored — not as they appear in the file.
 *
 * Showing the raw cells would prove nothing: the whole question is whether OUR reading of
 * them matches the user's intent, so the dates here are the parsed ones and a row that will
 * not import says why, right next to the values that caused it.
 */
@Composable
private fun PreviewTable(state: FileState.Mapping, today: LocalDate) {
    val dataRows = state.rows.drop(if (state.settings.hasHeaderRow) 1 else 0).take(5)
    if (dataRows.isEmpty()) {
        Text("No rows to show.", style = MaterialTheme.typography.bodySmall)
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in dataRows) {
            fun cell(index: Int?) = index?.let { row.getOrNull(it) }?.trim().orEmpty()
            val name = cell(state.mapping.name)
            val rawDate = cell(state.mapping.date)
            val parsed = ImportDates.parse(rawDate, state.settings.order, today)
            val relation = cell(state.mapping.relation).ifEmpty { state.settings.defaultRelation.orEmpty() }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (parsed is DateParse.Failed || name.isEmpty()) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(name.ifEmpty { "(no name in this row)" }, fontWeight = FontWeight.Medium)
                    when (parsed) {
                        is DateParse.Parsed -> {
                            val date = parsed.date
                            Text(
                                BirthdayMath.formatDayMonth(date.day, date.month) +
                                    (date.year?.let { " $it" } ?: " · year unknown") +
                                    // Marked only when flipping the toggle would actually
                                    // change it, so the warning means something.
                                    if (parsed.ambiguous) "  ⟷ could be read either way" else "",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        is DateParse.Failed -> Text(
                            parsed.reason,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        relation.ifEmpty { "no relation — this row will not import" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

// =====================================================================================
// The result (FR-IMP-07)
// =====================================================================================

/**
 * What actually happened, with a reason for every row that did not come in.
 *
 * This screen is the thing that makes the rest of the milestone honest. "Imported!" over a
 * database that is wrong in a way the user cannot see is the failure mode the whole feature
 * is written against, so every number here is clickable-through to its reasons, and the
 * failures can leave as a CSV the user fixes and re-runs.
 */
@Composable
fun ImportResultScreen(
    result: ImportResult,
    savedReport: Boolean?,
    busy: Boolean,
    onSaveFailures: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            when {
                result.imported > 0 -> "${result.imported} added"
                result.skipped.isNotEmpty() && result.failed.isEmpty() -> "Nothing new"
                else -> "Nothing was added"
            },
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            listOfNotNull(
                "${result.imported} imported",
                result.skipped.size.takeIf { it > 0 }?.let { "$it already here" },
                result.failed.size.takeIf { it > 0 }?.let { "$it could not be read" },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
        )

        if (result.imported == 0 && result.skipped.isNotEmpty() && result.failed.isEmpty()) {
            Text(
                "You have imported this before. Running it again changed nothing, which is " +
                    "what should happen.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (result.warnings.isNotEmpty()) {
            SectionHeader("Changed to fit")
            for ((name, warning) in result.warnings) {
                Text("$name — $warning", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (result.skipped.isNotEmpty()) {
            SectionHeader("Already here")
            Text(
                "Matched on name and date. Nothing was merged or changed.",
                style = MaterialTheme.typography.bodySmall,
            )
            for (duplicate in result.skipped.take(50)) {
                Text(
                    "Row ${duplicate.row.number}: ${duplicate.ready.name} — " +
                        if (duplicate.withinThisFile) {
                            "appears twice in this import"
                        } else {
                            "already in WishKaro as ${duplicate.matchName}"
                        },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (result.skipped.size > 50) {
                Text("…and ${result.skipped.size - 50} more", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (result.failed.isNotEmpty()) {
            SectionHeader("Could not be read")
            for (failure in result.failed.take(50)) {
                Text(
                    "Row ${failure.row.number}: ${failure.reason}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (result.failed.size > 50) {
                Text("…and ${result.failed.size - 50} more", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(4.dp))
            Button(onClick = onSaveFailures, enabled = !busy) {
                Text("Save these ${result.failed.size} rows as a file to fix")
            }
            Text(
                "Your own columns and values, with a column saying what was wrong. Fix them " +
                    "in whatever you already use, import the file again, and the rows that " +
                    "worked the first time will not come in twice.",
                style = MaterialTheme.typography.bodySmall,
            )
            savedReport?.let {
                Text(
                    if (it) "Saved." else "That did not save.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            }
        }

        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("Done") }
    }
}

/** Turns the shared date fields into an [EventDate], or null while they are incomplete. */
private fun DateFieldsState.toEventDate(): EventDate? {
    val d = day.toIntOrNull() ?: return null
    val m = month.toIntOrNull() ?: return null
    if (!BirthdayMath.isRealDayMonth(d, m)) return null
    val y = if (yearUnknown) null else year.toIntOrNull()
    if (!yearUnknown && y == null) return null
    if (y != null && (y < ImportDates.MIN_REAL_YEAR || y > ImportDates.MAX_REAL_YEAR)) return null
    if (y != null && d == 29 && m == 2 && !BirthdayMath.isLeapYear(y)) return null
    return EventDate(d, m, y)
}

