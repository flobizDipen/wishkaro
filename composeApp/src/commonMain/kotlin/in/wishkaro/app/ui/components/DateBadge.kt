package `in`.wishkaro.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.wishkaro.app.domain.BirthdayMath
import `in`.wishkaro.app.ui.theme.accent

/**
 * A day over a month, in the serif. **The date is the hero, not the chrome.**
 *
 * That line is `BUILD_PROMPT_M7.md`'s, and it is a direct answer to what `main` looked like:
 * every date in this app was a fragment of a `buildString` at `bodyMedium`, glued to the event
 * title with a `·`, in the same size and weight as the rest of the row. The thing a reminder
 * app is *about* was the least prominent thing on its own screen.
 *
 * So the date comes out of the sentence and becomes an object: the day in Fraunces at
 * `headlineSmall`, the month under it in a small caps-ish label. It sits on the leading edge of
 * a row, where a reader's eye lands first and where a column of them lines up down the list.
 *
 * The accent is read through [accent], so a remembrance date is desaturated without this
 * component knowing what a remembrance is.
 *
 * Fixed width, not `wrapContentWidth`: the point of a date rail is that the dates line up, and a
 * "9" and a "30" are different widths. 44dp fits two digits at `headlineSmall` plus the month
 * label under it.
 */
@Composable
fun DateBadge(
    day: Int,
    month: Int,
    modifier: Modifier = Modifier,
    emphasised: Boolean = true,
) {
    Column(
        modifier = modifier.width(DateBadgeWidth),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = day.toString(),
            style = MaterialTheme.typography.headlineSmall,
            color = if (emphasised) MaterialTheme.accent else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Text(
            // Uppercased because at 11sp the month is a label rather than a word — it is read
            // as a marker beside the number, not sounded out.
            text = BirthdayMath.shortMonthName(month).uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Wide enough for "30" at `headlineSmall`, so a column of badges aligns. */
val DateBadgeWidth = 44.dp
