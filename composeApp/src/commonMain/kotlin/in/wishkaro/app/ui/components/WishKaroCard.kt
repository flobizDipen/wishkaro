package `in`.wishkaro.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import `in`.wishkaro.app.ui.theme.accent

/**
 * The card every list row in this app is.
 *
 * ## What it replaces
 *
 * Seven near-identical blocks, each written out by hand:
 *
 * ```kotlin
 * Card(
 *     modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
 *     colors = when {
 *         highlighted -> CardDefaults.cardColors(containerColor = …secondaryContainer)
 *         entry.isSolemn -> CardDefaults.cardColors(containerColor = …surfaceVariant)
 *         else -> CardDefaults.cardColors()
 *     },
 * )
 * ```
 *
 * — in `UpcomingListScreen`, `PeopleListScreen`, `PersonDetailScreen`'s event rows,
 * `DeletedPeopleScreen`, the blocked-records banner, the import preview table and the import
 * start screen. Three of those got the solemn branch; two did not, and nothing said which was
 * correct.
 *
 * ## The border, which is a contrast fix rather than decoration
 *
 * `highlighted` is the state a deep link puts a card into — tap a reminder or the widget and the
 * person you were sent to is tinted `secondaryContainer`. That tint measures **1.22:1** against
 * the ground in light and **1.48:1** in dark. As the *only* carrier of a state, that fails WCAG
 * 1.4.11, which asks 3:1 of anything that tells you what state a component is in.
 *
 * Darkening the tint is not the answer — "warm paper" is pale fills, and a highlight at 3:1
 * against ivory is a block of colour, not a highlight. So the state is carried by a **border in
 * the accent** as well: 5.93:1 in light, 7.81:1 in dark, measured in `ThemeContrastTest`. The
 * tint stays as the soft half of the signal and stops being load-bearing.
 *
 * ## Solemn
 *
 * `solemn` keeps the `surfaceVariant` tint the app has had since M1 — that is a *surface*
 * distinction and it predates this milestone. What M7 adds is that the highlight ring, if both
 * are true at once, is drawn in `MaterialTheme.accent`, which desaturates inside a
 * `SolemnScope`. A deep-linked remembrance is ringed in stone, not in saffron.
 */
@Composable
fun WishKaroCard(
    modifier: Modifier = Modifier,
    solemn: Boolean = false,
    highlighted: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val colors = when {
        highlighted -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        )
        solemn -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        )
        else -> CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        )
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        colors = colors,
        border = if (highlighted) BorderStroke(1.5.dp, MaterialTheme.accent) else null,
        content = { content() },
    )
}
