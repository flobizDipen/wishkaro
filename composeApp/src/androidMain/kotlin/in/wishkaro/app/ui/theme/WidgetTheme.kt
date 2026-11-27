package `in`.wishkaro.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.glance.GlanceTheme
import androidx.glance.material3.ColorProviders

/**
 * The home-screen widget, in WishKaro's own colours.
 *
 * ## What it replaces, and why it counts as a defect rather than a gap
 *
 * `UpcomingWidget` called `GlanceTheme { … }` with no arguments. On Android 12 and later that
 * resolves to **dynamic colour** — the user's wallpaper palette. So the one piece of this app
 * that sits permanently on somebody's home screen, next to every other app they own, was the
 * only part of it that had a colour scheme at all, and it was not WishKaro's. On Android 11 and
 * earlier it fell back to Material's baseline purple, the same purple the rest of the app had.
 *
 * M7 item 9 names the widget alongside the fourteen screens, so this is not a bonus: a dark
 * theme that stops at the app's own window is a dark theme with a hole in it.
 *
 * ## Why this file is in `ui/theme/` on `androidMain`
 *
 * Two reasons, and the second is the useful one.
 *
 * Glance is Android-only and androidx-only by nature — `GlanceTheme` and `ColorProviders` have
 * no multiplatform equivalent — so this cannot live in `commonMain`. But it reads
 * [WishKaroPalette], which does, so there is still exactly one definition of every colour in
 * the product. The widget and the list cannot drift apart, in the same way `M4_PLAN` §3 made
 * sure they cannot disagree about what is coming up.
 *
 * And `ui/theme/` is the one directory `tools/check-no-raw-colors.sh` exempts. Putting the
 * widget's colours anywhere else would mean either a guard violation or an exemption; putting
 * them here means the rule holds without an argument.
 *
 * ## Day and night
 *
 * `ColorProviders(light, dark)` — Glance resolves which at render time from the system's
 * `uiMode`, and a widget re-renders on a configuration change, so switching the phone to dark
 * mode repaints the home screen without the app being opened.
 */
@Composable
fun WishKaroGlanceTheme(content: @Composable () -> Unit) {
    GlanceTheme(colors = WishKaroGlanceColors, content = content)
}

private val WishKaroGlanceColors = ColorProviders(
    light = WishKaroLightColors,
    dark = WishKaroDarkColors,
)

/**
 * The accent a widget row should use for an occasion, desaturated for a remembrance.
 *
 * The app does this with a composition local (`MaterialTheme.accent` inside a `SolemnScope`),
 * which Glance cannot use: `GlanceTheme.colors` is a `ColorProviders`, there is no
 * `MaterialTheme` in a widget's composition, and a widget's tree is rebuilt from scratch on
 * every update rather than recomposed in place.
 *
 * So the widget reads the palette directly, through this one function, rather than each row
 * writing its own `if`. The VALUES are the same two the app uses — same file, same constants —
 * which is what keeps "a death anniversary is never drawn in the warm accent" one rule rather
 * than two implementations of one rule. `MILESTONE_CHECKLIST.md`, bought by M6: *"a rule
 * implemented on two surfaces drifts into two different rules unless each surface derives its
 * inputs from the thing it is protecting."*
 */
fun widgetAccent(solemn: Boolean, dark: Boolean): Color {
    val palette = if (dark) WishKaroPalette.DARK else WishKaroPalette.LIGHT
    return Color(if (solemn) palette.solemnAccent else palette.primary)
}
