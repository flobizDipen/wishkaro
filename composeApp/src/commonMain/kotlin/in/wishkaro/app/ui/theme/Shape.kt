package `in`.wishkaro.app.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * 12dp cards, 4dp chips — *"soft rather than pill-round"*.
 *
 * Two of the five values differ from Material's scale, and both differences are the direction:
 *
 *  - **`small` is 4dp, not 8dp.** Every chip in Material 3 takes its shape from `shapes.small`
 *    — `FilterChip`, `AssistChip`, `InputChip`, `SuggestionChip` — and this app has chip rows on
 *    nine screens: relations, months, event types, tones, languages, delivery hours, poster
 *    formats, import columns and the date-order toggle. Setting it here is one line; setting
 *    `shape =` at every chip is forty call sites and a fortieth one missed. Nothing else in this
 *    app reads `small`.
 *  - **`extraLarge` is 24dp, not 28dp.** 28 on a 400dp-wide side sheet (`AdaptiveDialog` at
 *    expanded width) reads as a lozenge rather than as a sheet.
 *
 * `medium` stays at Material's 12dp, which is already the direction's card radius — the
 * difference is that this app now states it rather than inheriting it and hoping.
 */
val WishKaroShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)
