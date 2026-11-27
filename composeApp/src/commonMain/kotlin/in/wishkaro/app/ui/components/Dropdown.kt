package `in`.wishkaro.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization

/**
 * A field you pick from rather than type into.
 *
 * ## Why this replaces chip rows
 *
 * `main` offered every choice as a `FlowRow` of `FilterChip`s: seventeen relations, twelve
 * months, twenty-four delivery hours. Chips are right for a FILTER, where several can be on at
 * once and seeing the whole set is the point — the People screen's month and relation filters
 * stay chips for exactly that reason. They are wrong for a single-valued FIELD on a form,
 * where seventeen of them is a wall that pushes everything below it off the screen, and where
 * the thing the user wants to know is "what is this set to" rather than "what could it be".
 *
 * Dipen asked for dropdowns on gender and relation. This is that, as one component, so the
 * month picker and anything M8 adds get the same thing rather than a third pattern.
 *
 * ## `readOnly`, not `enabled = false`
 *
 * The anchor is a real `OutlinedTextField` that cannot be typed into. A disabled field is
 * greyed out and skipped by accessibility focus; a read-only one keeps the label, the outline,
 * the focus ring and the screen-reader announcement, and is what `ExposedDropdownMenuBox` is
 * built around.
 *
 * ## State
 *
 * `rememberSaveable` for the open flag, like every other transient bit of UI state in this app
 * since M4 removed the manifest's `configChanges`: a menu that closes itself when the phone
 * turns is a menu the user has to find again (`WishKaroTopBar` says the same thing).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WishKaroDropdown(
    label: String,
    options: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Choose one",
    tag: String? = null,
    /** How an option reads in the open menu. */
    optionLabel: (String) -> String = { it },
    /**
     * How the SELECTED option reads in the closed field, when that differs.
     *
     * The phone's country code is why this exists: the menu needs "+44  United Kingdom" to
     * be choosable, and the closed field is 110dp wide, where the same string truncates to
     * "+44  Unite" — which is worse than useless, because it looks like a bug. The field
     * shows "+44"; the menu shows the country.
     */
    selectedLabel: (String) -> String = optionLabel,
) {
    var open by rememberSaveable { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = open,
        onExpandedChange = { open = it },
        modifier = modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selected?.let(selectedLabel).orEmpty(),
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            textStyle = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .fillMaxWidth()
                // `menuAnchor` is what tells the box where to hang the menu AND what makes
                // the field itself the thing that opens it. Without it the trailing icon is
                // the only hit target, which is a 24dp tap area on a full-width control.
                .menuAnchor(androidx.compose.material3.MenuAnchorType.PrimaryNotEditable)
                .then(if (tag != null) Modifier.testTag(tag) else Modifier),
        )

        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        open = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                    modifier = if (tag != null) Modifier.testTag("${tag}_$option") else Modifier,
                )
            }
        }
    }
}

/**
 * A dropdown whose last entry is "Other", and the box that appears when you choose it.
 *
 * ## Why this is one component rather than two screens doing the same thing
 *
 * Relation and gender are the same problem: a curated list that must not become a closed one.
 * `FR-PPL-02` says so for relations in as many words — *"an enum of relations would make 'Mama'
 * or 'Chachi' unrepresentable in an India-first app"* — and `schema.prisma` says it for gender:
 * *"deliberately not an enum ... a product decision this app has no reason to make."*
 *
 * Written twice, they drifted immediately. The first version of this change shipped both and
 * the screenshot showed what that costs: **two fields labelled "Relation"** stacked on each
 * other, and two labelled "Gender", because each screen named its own text box after the thing
 * rather than after what it is for. It also showed the free-text box on a FRESH form with
 * nothing chosen — two controls for one value, which is the exact thing replacing the chip row
 * was meant to stop.
 *
 * ## When the box appears
 *
 * Only when "Other" is the answer: either because the user picked it, or because the stored
 * value was never on the list — somebody whose relation is already "Chachi" opens with the
 * dropdown on Other and the word still in the box. Never on an untouched form, where the
 * dropdown's own "Other" entry is the route.
 *
 * ## `resetKey`
 *
 * "The user chose Other" is UI state, not data, so it cannot be derived from the value —
 * choosing Other deliberately clears the value, and a cleared value is indistinguishable from
 * an empty form. It therefore has to be remembered, and remembered state has to be reset when
 * the screen is reused for a different person. The caller passes the person's id, exactly as
 * it already does for every `rememberSaveable` on these forms.
 */
@Composable
fun DropdownWithOther(
    label: String,
    options: List<String>,
    otherOption: String,
    value: String?,
    onValueChange: (String?) -> Unit,
    customLabel: String,
    maxLength: Int,
    modifier: Modifier = Modifier,
    resetKey: Any? = null,
    placeholder: String = "Choose one",
    tag: String? = null,
) {
    // True once the user has picked "Other" on this form. See the doc comment.
    var choseOther by rememberSaveable(resetKey) { mutableStateOf(false) }

    val curated = value != null && value in options && value != otherOption
    val custom = !value.isNullOrBlank() && !curated
    val showCustom = custom || choseOther

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        WishKaroDropdown(
            label = label,
            options = options,
            selected = when {
                curated -> value
                showCustom -> otherOption
                else -> null
            },
            placeholder = placeholder,
            tag = tag,
            onSelect = { picked ->
                if (picked == otherOption) {
                    // Clears the value rather than storing the word "Other". "Other" is not a
                    // relation and not a gender — it is the user saying the list has not got
                    // theirs — and storing it would put the literal word on their card.
                    choseOther = true
                    onValueChange(null)
                } else {
                    choseOther = false
                    onValueChange(picked)
                }
            },
        )

        if (showCustom) {
            OutlinedTextField(
                value = if (custom) value.orEmpty() else "",
                onValueChange = { onValueChange(it.take(maxLength).ifBlank { null }) },
                // NOT the same word as the dropdown above it. Two fields labelled "Relation"
                // is what the first version of this did.
                label = { Text(customLabel) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Words,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth()
                    .then(if (tag != null) Modifier.testTag("${tag}_custom") else Modifier),
            )
        }
    }
}
