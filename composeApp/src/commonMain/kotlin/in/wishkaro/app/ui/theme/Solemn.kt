package `in`.wishkaro.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * A remembrance is **the same design with the accent desaturated**. Not a second set of screens.
 *
 * This is the constraint the whole visual direction was chosen for, and `BUILD_PROMPT_M7.md`
 * states it as a test of the implementation rather than as a preference: *"If you find yourself
 * writing a second set of screens for solemn events, you have taken a wrong turn."*
 *
 * So the difference between a five-year-old's birthday card and a death anniversary's is **one
 * composition local**. Same composable, same layout, same call tree, one value.
 *
 * ## How to use it
 *
 * Wrap the part of the tree that is about a person — usually a whole screen, sometimes one row —
 * in [SolemnScope], and read the accent through [accent] instead of `colorScheme.primary`:
 *
 * ```kotlin
 * SolemnScope(person.isSolemn) {
 *     Text(card.countdown, color = MaterialTheme.accent)
 * }
 * ```
 *
 * ## What this is NOT
 *
 * It is not where tone comes from. M5 built the solemn ladder, the restricted tone list and the
 * two separate string sets in `domain/Tone.kt`, and `M5_VERIFY.md` records why they are two
 * string SETS rather than one template with a conditional: *"a conditional is a thing someone
 * can forget; a set you have to pick is a thing the compiler makes you pick."* None of that is
 * touched here. M7 themes what M5 built.
 *
 * It is also not the card tint. `isSolemn -> surfaceVariant` on the list rows predates this
 * milestone and stays: that is a surface distinction, and this is an accent one.
 *
 * ## Why `staticCompositionLocalOf`
 *
 * A screen's solemnity does not change while the screen is open — it is a property of the person
 * it is about. A static local does not track reads, so nothing recomposes on it; changing it
 * recomposes the whole content lambda, which is exactly what should happen on the one occasion
 * it changes, which is navigating to a different person.
 */
val LocalSolemn: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

/**
 * The accent, read through the solemn local.
 *
 * Everywhere `main` wrote `MaterialTheme.colorScheme.primary` *as an accent* now writes
 * `MaterialTheme.accent`. The two are the same value outside a solemn scope — the point is that
 * inside one, every call site is already correct without having been told about remembrance.
 *
 * Note what this is **not** used for: `onPrimary`, `primaryContainer` and the rest of the
 * primary family still come from the scheme. A filled button inside a solemn scope keeps its
 * accent fill, because a remembrance screen with no affordances is not quieter, it is broken.
 * What changes is the colour of *text and marks* that carry the occasion.
 */
val MaterialTheme.accent: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalSolemn.current) LocalSolemnAccent.current else colorScheme.primary

/**
 * The desaturated accent for the current theme.
 *
 * Provided by `WishKaroTheme` rather than read from a global, so the dark theme gets the lighter
 * `#B9B2A3` and the light theme the darker `#67635A` without [accent] knowing which theme it is
 * in. Both are measured against every surface they are drawn on by `ThemeContrastTest`.
 */
val LocalSolemnAccent: ProvidableCompositionLocal<Color> =
    staticCompositionLocalOf { Color(WishKaroPalette.LIGHT.solemnAccent) }

/**
 * Marks everything inside as being about a person who has died.
 *
 * `solemn = false` is a real call rather than a no-op: a screen that passes
 * `person.isSolemn` straight through is a screen that cannot forget to turn the scope OFF when
 * it moves from one person to another.
 */
@Composable
fun SolemnScope(solemn: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSolemn provides solemn, content = content)
}
