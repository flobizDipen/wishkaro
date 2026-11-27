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
 * scope**. Same composable, same layout, same call tree.
 *
 * ## How to use it
 *
 * Wrap the part of the tree that is about a person — usually a whole screen, sometimes one row
 * — in [SolemnScope], and read the accent through [accent] instead of `colorScheme.primary`:
 *
 * ```kotlin
 * SolemnScope(person.isSolemn) {
 *     Text(card.countdown, color = MaterialTheme.accent)
 * }
 * ```
 *
 * ## It swaps the SCHEME, not only a local — and that was learned from a screenshot
 *
 * The first version of this file provided a composition local and nothing else, with a comment
 * arguing that a filled button should keep its accent fill because *"a remembrance screen with
 * no affordances is not quieter, it is broken"*.
 *
 * Then `M7_VERIFY.md` item 9's screenshot pair was taken, and the argument did not survive it.
 * Everything the app controlled by hand — the countdown, the avatar, the section headers, the
 * back affordance — went correctly to stone. And the single largest object on the remembrance
 * page, a full-width **saffron** "Send a message" button, did not, because `Button` reads
 * `colorScheme.primary` and knows nothing about any of this. Nor did the reminder switch. The
 * loudest, warmest things on a death anniversary's screen were the two this file had exempted.
 *
 * So [SolemnScope] now replaces the primary family in the `ColorScheme` itself. Every Material
 * component that takes its colour from `primary` — `Button`, `Switch`, `FilterChip`'s selected
 * state, `LinearProgressIndicator`, `CircularProgressIndicator`, the text cursor — desaturates
 * without being told, and without a single call site passing a `colors = …`. That is what "one
 * token" has to mean to be true rather than nearly true.
 *
 * `MaterialTheme`'s other parameters default to the current theme's, so typography, shapes and
 * every non-primary role pass through untouched.
 *
 * ## What it is NOT
 *
 * It is not where tone comes from. M5 built the solemn ladder, the restricted tone list and the
 * two separate string sets in `domain/Tone.kt`, and `M5_VERIFY.md` records why they are two
 * string SETS rather than one template with a conditional: *"a conditional is a thing someone
 * can forget; a set you have to pick is a thing the compiler makes you pick."* None of that is
 * touched here. M7 themes what M5 built.
 *
 * It is also not the card tint. `isSolemn -> surfaceVariant` on the list rows predates this
 * milestone and stays: that is a *surface* distinction, and this is an accent one.
 *
 * ## Why `staticCompositionLocalOf`
 *
 * A screen's solemnity does not change while the screen is open — it is a property of the
 * person it is about. A static local does not track reads, so nothing recomposes on it;
 * changing it recomposes the whole content lambda, which is exactly what should happen on the
 * one occasion it changes, which is navigating to a different person.
 */
val LocalSolemn: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

/**
 * The accent, read through the solemn local.
 *
 * Everywhere `main` wrote `MaterialTheme.colorScheme.primary` *as an accent* now writes
 * `MaterialTheme.accent`. Inside a [SolemnScope] the two are the same value anyway — the scope
 * swaps the scheme — so this is a readability affordance rather than a mechanism: a call site
 * that says `accent` is saying "this colour is the occasion's", which is a different statement
 * from "this colour is the brand's", and only the first one should desaturate.
 */
val MaterialTheme.accent: Color
    @Composable
    @ReadOnlyComposable
    get() = colorScheme.primary

/**
 * The desaturated accent for the current theme, provided by `WishKaroTheme`.
 *
 * Provided rather than read from a global so that the dark theme gets `#B9B2A3` and the light
 * theme `#67635A` without [SolemnScope] knowing which theme it is in.
 */
val LocalSolemnAccent: ProvidableCompositionLocal<WarmPaper> =
    staticCompositionLocalOf { WishKaroPalette.LIGHT }

/**
 * Marks everything inside as being about a person who has died.
 *
 * `solemn = false` is a real call rather than a no-op: a screen that passes `person.isSolemn`
 * straight through is a screen that cannot forget to turn the scope OFF when it moves from one
 * person to another.
 */
@Composable
fun SolemnScope(solemn: Boolean, content: @Composable () -> Unit) {
    if (!solemn) {
        CompositionLocalProvider(LocalSolemn provides false, content = content)
        return
    }

    val palette = LocalSolemnAccent.current
    val quiet = MaterialTheme.colorScheme.copy(
        primary = Color(palette.solemnAccent),
        onPrimary = Color(palette.onSolemnAccent),
        primaryContainer = Color(palette.solemnContainer),
        onPrimaryContainer = Color(palette.onSolemnContainer),
        // The elevation tint, which Material mixes into raised surfaces. Left as saffron it
        // puts a warm cast on every card on the screen — faint, and on exactly the screen
        // where faint warmth is the thing being avoided.
        surfaceTint = Color(palette.solemnAccent),
    )

    CompositionLocalProvider(LocalSolemn provides true) {
        MaterialTheme(colorScheme = quiet, content = content)
    }
}
