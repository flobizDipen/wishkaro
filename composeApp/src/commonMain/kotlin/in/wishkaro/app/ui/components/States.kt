package `in`.wishkaro.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.wishkaro.app.ui.theme.accent

/**
 * The shapes a screen takes when it has nothing to show, and the one it takes while it is
 * finding out. Both were written out by hand on nearly every screen in the app.
 *
 * `FR-VIEW-05` is the requirement behind the first: *"Empty states for every view that lead to
 * import or add, never a blank screen."* M4 recorded them as met, and they are — they lead
 * somewhere. What `M4_VERIFY` could not say is that they **look** like they lead somewhere: a
 * centred `bodyMedium` and a `TextButton` is a message, not an invitation.
 */

/**
 * Nothing here yet, and the way out.
 *
 * Eleven places drew a version of this: the upcoming list, the people list twice (filtered and
 * not), the deleted list, the calendar three times, the person screen, the composer, the import
 * result and the widget. They had four different type scales, three different paddings, and two
 * of them offered no action at all.
 *
 * The mark is drawn rather than imported for the reason `NavGlyphs.kt` already gives: the
 * multiplatform Material icon artifact stopped at Compose 1.7.3 and `materialIconsExtended` is
 * several megabytes of vectors to use one of. One circle and one stroke is less code than the
 * workaround.
 */
@Composable
fun EmptyState(
    title: String,
    body: String? = null,
    modifier: Modifier = Modifier,
    titleTag: String? = null,
    primaryAction: Pair<String, () -> Unit>? = null,
    secondaryAction: Pair<String, () -> Unit>? = null,
    primaryTag: String? = null,
    secondaryTag: String? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyMark()
        Spacer(Modifier.height(20.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            modifier = if (titleTag != null) Modifier.testTag(titleTag) else Modifier,
        )
        if (body != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (primaryAction != null || secondaryAction != null) {
            Spacer(Modifier.height(24.dp))
            ActionPair(
                primary = primaryAction,
                secondary = secondaryAction,
                primaryTag = primaryTag,
                secondaryTag = secondaryTag,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * An open ring with a single mark inside it: a day on a calendar with nothing on it.
 *
 * Deliberately drawn in `outlineVariant` rather than in the accent. An empty state is not an
 * occasion, and a warm saffron ring is the app being pleased with itself about having no data.
 */
@Composable
private fun EmptyMark() {
    val ring = MaterialTheme.colorScheme.outlineVariant
    val mark = MaterialTheme.colorScheme.outline
    Canvas(Modifier.size(56.dp)) {
        drawCircle(ring, radius = size.minDimension / 2 - 1.dp.toPx(), style = Stroke(1.5.dp.toPx()))
        drawLine(
            mark,
            Offset(size.width * 0.38f, size.height * 0.5f),
            Offset(size.width * 0.62f, size.height * 0.5f),
            strokeWidth = 1.5.dp.toPx(),
        )
    }
}

/**
 * Still finding out — which is **not** the same as "nothing".
 *
 * This app learned that the hard way and wrote it down: offering "add your first person" to
 * somebody who has 575 of them, for the one frame before the query answers, is the app telling
 * the user their data is gone. Five screens have a null-means-not-read-yet branch for exactly
 * that reason; this is what they all drew.
 */
@Composable
fun LoadingState(modifier: Modifier = Modifier, tag: String? = null) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            color = MaterialTheme.accent,
            modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
        )
    }
}

/**
 * One thing to do, and one quieter thing to do instead.
 *
 * Six screens drew a filled `Button` with a `TextButton` or an `OutlinedButton` under or beside
 * it. Stacked rather than side by side, full width: the primary action reads as the thing to do
 * rather than as the left half of a choice, and at a large font scale two buttons in a row is
 * where labels start wrapping — which is the bug `WishComposerScreen`'s tone chips already
 * bought this project once.
 */
@Composable
fun ActionPair(
    primary: Pair<String, () -> Unit>?,
    secondary: Pair<String, () -> Unit>?,
    modifier: Modifier = Modifier,
    primaryTag: String? = null,
    secondaryTag: String? = null,
    secondaryOutlined: Boolean = false,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        primary?.let { (label, onClick) ->
            Button(
                onClick = onClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (primaryTag != null) Modifier.testTag(primaryTag) else Modifier),
            ) { Text(label) }
        }
        secondary?.let { (label, onClick) ->
            val m = Modifier
                .fillMaxWidth()
                .then(if (secondaryTag != null) Modifier.testTag(secondaryTag) else Modifier)
            if (secondaryOutlined) {
                OutlinedButton(onClick = onClick, modifier = m) { Text(label) }
            } else {
                TextButton(onClick = onClick, modifier = m) { Text(label) }
            }
        }
    }
}
