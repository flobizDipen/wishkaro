package `in`.wishkaro.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/**
 * The quiet line that explains what just happened, or what is about to.
 *
 * This app has about fourteen of them and they are one of the better things about it — the
 * quiet-hours clash note, the composer's provenance line, "posters without a photo are
 * typographic, and still good", "WishKaro never guesses this from the file". They were all
 * `Text(…, bodySmall, onSurfaceVariant)` written out by hand, and about a third of them left
 * the colour off, so they were full-strength ink at 12sp — which reads as small print rather
 * than as a note.
 */
@Composable
fun Note(text: String, modifier: Modifier = Modifier, tag: String? = null) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.then(if (tag != null) Modifier.testTag(tag) else Modifier),
    )
}

/**
 * The same, for something the user should act on.
 *
 * `error` rather than the accent, deliberately — the accent is the occasion's colour and
 * desaturates on a remembrance, which is exactly wrong for "this will not import". A warning is
 * about the app, not about the person.
 */
@Composable
fun WarningNote(text: String, modifier: Modifier = Modifier, tag: String? = null) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier.then(if (tag != null) Modifier.testTag(tag) else Modifier),
    )
}

/**
 * A switch with a label and, usually, a sentence saying what it will do.
 *
 * Three places: the Settings ladder and digest rows, the deceased switch on the edit screen,
 * and the per-event mute toggle on the person screen. All three put the control on a different
 * side of the label, and two of them put the explanation in a different type scale.
 *
 * The label is `bodyLarge` rather than `titleSmall`: these are sentences the user reads
 * ("A week before", "Stay quiet overnight"), not headings, and setting them in the serif made
 * the settings screen look like a table of contents.
 */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    tag: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let { Note(it) }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                // The accent, so a remembrance event's mute toggle desaturates with everything
                // else on the screen rather than staying the one warm thing on it.
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
        )
    }
}
