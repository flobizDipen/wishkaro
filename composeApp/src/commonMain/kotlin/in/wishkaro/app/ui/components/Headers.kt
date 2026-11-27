package `in`.wishkaro.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import `in`.wishkaro.app.ui.theme.accent

/**
 * A heading over a group of things, with an optional count beside it.
 *
 * There were **two** of these on `main` and they did not agree. `components/Fields.kt` had one
 * at `titleSmall` in the accent, used on seven screens; `UpcomingListScreen` had a private one
 * at `titleMedium` SemiBold in the accent with a count on the right, used on one. Two section
 * headers is how a design system fails before it exists.
 *
 * This is the union: the `Fields.kt` call shape, plus a trailing slot for the count. It keeps
 * the accent — section headers are the main place WishKaro's colour appears in text, and it is
 * the reason the accent had to pass 4.5:1 rather than being allowed the large-text 3:1
 * (`ThemeContrastTest`, and `docs/M7_PLAN.md` §1.1).
 */
@Composable
fun SectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    tag: String? = null,
) {
    Column(modifier = modifier.padding(top = 16.dp, bottom = 4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.accent,
                modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
            )
            if (trailing != null) {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The bar on a screen you got to from somewhere and will go back from.
 *
 * Seven screens wrote their own: `PersonDetailScreen`, `EditPersonScreen`, `EventEditorScreen`,
 * `DeletedPeopleScreen`, `AddPersonScreen`, `WishComposerScreen` and `PosterScreen`. Four used a
 * `TextButton` reading "Back", two used an `IconButton` containing the character `←`, one used
 * "Cancel" — and the composer's and the studio's arrows are the two that a screen reader
 * announces as the Unicode name of a left arrow.
 *
 * One bar, one back affordance, one word. `backLabel` exists because "Cancel" on a half-filled
 * form says something "Back" does not: that leaving discards what you typed. That is a real
 * distinction and it stays a parameter rather than being flattened.
 *
 * `centered = false`: Material's small top bar is leading-aligned, and a centred title with a
 * back button on the left and an action on the right is the layout that squeezes the title
 * first. Names are long in this app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailTopBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    backLabel: String = "Back",
    backTag: String? = null,
    actions: @Composable () -> Unit = {},
) {
    TopAppBar(
        title = { Text(title, maxLines = 1) },
        modifier = modifier,
        navigationIcon = {
            TextButton(
                onClick = onBack,
                modifier = if (backTag != null) Modifier.testTag(backTag) else Modifier,
            ) { Text(backLabel) }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            // The ground, not a tinted container. "Warm paper" means the bar is the same sheet
            // the content is on; a separate surface behind the title is the Material default
            // and is what made every screen in this app look like it had a header pasted on.
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            navigationIconContentColor = MaterialTheme.accent,
            actionIconContentColor = MaterialTheme.accent,
        ),
    )
}
