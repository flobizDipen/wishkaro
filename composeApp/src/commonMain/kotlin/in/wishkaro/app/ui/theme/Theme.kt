package `in`.wishkaro.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/**
 * "Warm paper" — colour, type, shape and motion, in both themes.
 *
 * This file used to be ten lines calling `MaterialTheme()` with no arguments, under a comment
 * reading *"Design arrives with the features that need it, not in the skeleton."* It never did:
 * nine milestones built fourteen-plus screens against Material's baseline purple, and TBD-5 sat
 * open from M4 until Dipen answered it on 2026-11-27. This is that answer.
 *
 * Four things the signature says that the old one could not:
 *
 *  - **`isSystemInDarkTheme()` is honoured**, which is the whole of dark-mode support in an app
 *    whose 210 colour reads already go through the scheme. It appeared nowhere in this
 *    repository before M7.
 *  - **The `darkTheme` parameter is overridable**, so the screenshot harness can render every
 *    screen in both themes without changing a device setting, and so a future in-app theme
 *    switch has somewhere to go. Nothing in the app passes it today.
 *  - **A `Surface` wraps the content**, so the window has WishKaro's ground under it rather than
 *    whatever the platform's default happens to be. That is also half of the no-white-flash
 *    answer; the other half is `android:windowBackground`, in `themes.xml`.
 *  - **The solemn accent is provided here**, so `MaterialTheme.accent` resolves the right
 *    desaturated value for whichever theme is live without any call site knowing.
 *
 * Dynamic colour is deliberately absent. See `Color.kt`.
 */
@Composable
fun WishKaroTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) WishKaroPalette.DARK else WishKaroPalette.LIGHT
    val colors = if (darkTheme) WishKaroDarkColors else WishKaroLightColors

    MaterialTheme(
        colorScheme = colors,
        typography = wishKaroTypography(),
        shapes = WishKaroShapes,
    ) {
        CompositionLocalProvider(LocalSolemnAccent provides palette) {
            // `color` explicitly rather than by default: Surface's default IS
            // colorScheme.surface, but saying it here is what makes the next reader check it
            // against the splash's window background, which has to be the same ivory or the
            // hand-off flashes. See androidMain/res/values/themes.xml.
            Surface(color = MaterialTheme.colorScheme.surface, content = content)
        }
    }
}
