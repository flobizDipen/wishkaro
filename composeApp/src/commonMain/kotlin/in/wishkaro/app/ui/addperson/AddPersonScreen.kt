package `in`.wishkaro.app.ui.addperson

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import `in`.wishkaro.app.domain.BirthdayMath
import `in`.wishkaro.app.domain.EventDate
import `in`.wishkaro.app.domain.RELATION_CHIPS

/**
 * Name, date, relation — the three inputs FR-PPL-01 allows, and nothing else.
 *
 * The "year unknown" checkbox is the interesting part: it has to work end to end, so when it
 * is ticked the year field is not merely hidden, it is sent as null and no age is ever
 * derived from it anywhere downstream (FR-EVT-02).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddPersonScreen(
    onSave: (name: String, relation: String?, date: EventDate) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf("") }
    var day by remember { mutableStateOf("") }
    var month by remember { mutableStateOf("") }
    var year by remember { mutableStateOf("") }
    var yearUnknown by remember { mutableStateOf(false) }
    var relation by remember { mutableStateOf<String?>(null) }

    val dayInt = day.toIntOrNull()
    val monthInt = month.toIntOrNull()
    val yearInt = year.toIntOrNull()

    val dateError: String? = when {
        day.isBlank() || month.isBlank() -> null
        dayInt == null || monthInt == null -> "Day and month must be numbers"
        !BirthdayMath.isRealDayMonth(dayInt, monthInt) -> "That date does not exist"
        !yearUnknown && year.isNotBlank() && yearInt == null -> "Year must be a number"
        !yearUnknown && yearInt != null && yearInt !in 1900..2200 -> "Year looks wrong"
        // 29 Feb is a real birthday, but only in a leap year when a year is given.
        !yearUnknown && yearInt != null && monthInt == 2 && dayInt == 29 &&
            !BirthdayMath.isLeapYear(yearInt) -> "$yearInt was not a leap year"
        else -> null
    }

    val canSave = name.isNotBlank() &&
        dayInt != null &&
        monthInt != null &&
        dateError == null &&
        (yearUnknown || year.isBlank() || yearInt != null)

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Add person") },
                navigationIcon = { TextButton(onClick = onCancel) { Text("Cancel") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Birthday", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = day,
                    onValueChange = { day = it.filter(Char::isDigit).take(2) },
                    label = { Text("Day") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = month,
                    onValueChange = { month = it.filter(Char::isDigit).take(2) },
                    label = { Text("Month") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = if (yearUnknown) "" else year,
                    onValueChange = { year = it.filter(Char::isDigit).take(4) },
                    label = { Text("Year") },
                    singleLine = true,
                    enabled = !yearUnknown,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1.3f),
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = yearUnknown, onCheckedChange = { yearUnknown = it })
                Text("I don't know the year")
            }

            dateError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Text("Relation", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RELATION_CHIPS.forEach { chip ->
                    FilterChip(
                        selected = relation == chip,
                        onClick = { relation = if (relation == chip) null else chip },
                        label = { Text(chip) },
                    )
                }
            }

            Button(
                onClick = {
                    onSave(
                        name.trim(),
                        relation,
                        EventDate(
                            day = dayInt ?: return@Button,
                            month = monthInt ?: return@Button,
                            year = if (yearUnknown) null else yearInt,
                        ),
                    )
                },
                enabled = canSave,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save")
            }
        }
    }
}
