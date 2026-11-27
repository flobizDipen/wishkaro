package `in`.wishkaro.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import `in`.wishkaro.app.ui.theme.LocalSolemn
import `in`.wishkaro.app.ui.theme.accent

/**
 * A person, as a circle with their initials in it.
 *
 * ## Why this exists at all
 *
 * It did not before M7. Every list in this app — upcoming, people, the deleted list, the
 * calendar's day list — was a column of text with another column of text beside it, and that is
 * most of why a 575-person directory read as a spreadsheet. An avatar gives a row an anchor the
 * eye can find without reading, which is what makes a long list scannable.
 *
 * ## The solemn rule, and the one place it is not just the accent
 *
 * Inside a `SolemnScope` the ring and the letters take the desaturated accent, like everything
 * else — one token, as `Solemn.kt` promises. The fill is the difference: a remembrance avatar
 * is **outlined rather than filled**, because a solid warm disc beside a death anniversary is a
 * small celebration, and FR-EVT-05 is about exactly these details. That is a shape decision
 * rather than a second palette, and it is the only one in this component.
 *
 * ## Accessibility
 *
 * `clearAndSetSemantics {}` — empty, deliberately. The initials are a *visual* restatement of
 * the name that is already in the row beside them, so a screen reader announcing "A P" before
 * "Aarav Pillai" is noise. Decorative, and marked as such rather than left to be read out.
 */
@Composable
fun PersonAvatar(
    displayName: String,
    modifier: Modifier = Modifier,
    size: Dp = AvatarDefaults.Size,
) {
    val solemn = LocalSolemn.current
    val accent = MaterialTheme.accent
    val initials = initialsOf(displayName).uppercase()

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .then(
                if (solemn) {
                    Modifier.border(1.5.dp, accent, CircleShape)
                } else {
                    Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                },
            )
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            // Scaled off the circle rather than fixed, so the same component works at 32dp in
            // a dense list and at 64dp on the person screen without a second set of styles.
            style = MaterialTheme.typography.titleMedium.copy(fontSize = (size.value * 0.36f).sp),
            color = if (solemn) accent else MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

object AvatarDefaults {
    /** List rows. Large enough to carry two letters, small enough to leave the name room. */
    val Size: Dp = 44.dp

    /** The person screen's header. */
    val Large: Dp = 64.dp
}
