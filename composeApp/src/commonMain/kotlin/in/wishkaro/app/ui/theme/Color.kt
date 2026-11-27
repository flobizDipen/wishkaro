package `in`.wishkaro.app.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * [WishKaroPalette] as Material 3 schemes.
 *
 * This file does one thing: it maps a named palette field onto the Material role of the same
 * name. There is no derivation, no seed, no `dynamicColorScheme`, and no value written here
 * that is not in the palette — so the colours `ThemeContrastTest` measures are the colours
 * Material is handed.
 *
 * **Dynamic colour is refused deliberately.** On Android 12+ it would repaint the app from the
 * user's wallpaper, which is a pleasant default for an app with no identity and the exact
 * opposite of what this milestone exists to give WishKaro.
 *
 * Every role is filled by hand, including the ones nothing currently draws (`tertiary`,
 * `surfaceDim`, `surfaceBright`). Material fills an omitted role from its own baseline, which
 * is purple — so an unfilled role is not an absent colour, it is a wrong one waiting for the
 * first composable that touches it.
 */
private fun WarmPaper.toColor(value: Long) = Color(value)

internal fun WarmPaper.lightScheme(): ColorScheme = lightColorScheme(
    primary = toColor(primary),
    onPrimary = toColor(onPrimary),
    primaryContainer = toColor(primaryContainer),
    onPrimaryContainer = toColor(onPrimaryContainer),
    inversePrimary = toColor(inversePrimary),
    secondary = toColor(secondary),
    onSecondary = toColor(onSecondary),
    secondaryContainer = toColor(secondaryContainer),
    onSecondaryContainer = toColor(onSecondaryContainer),
    tertiary = toColor(tertiary),
    onTertiary = toColor(onTertiary),
    tertiaryContainer = toColor(tertiaryContainer),
    onTertiaryContainer = toColor(onTertiaryContainer),
    background = toColor(background),
    onBackground = toColor(onBackground),
    surface = toColor(surface),
    onSurface = toColor(onSurface),
    surfaceVariant = toColor(surfaceVariant),
    onSurfaceVariant = toColor(onSurfaceVariant),
    surfaceTint = toColor(primary),
    inverseSurface = toColor(inverseSurface),
    inverseOnSurface = toColor(inverseOnSurface),
    error = toColor(error),
    onError = toColor(onError),
    errorContainer = toColor(errorContainer),
    onErrorContainer = toColor(onErrorContainer),
    outline = toColor(outline),
    outlineVariant = toColor(outlineVariant),
    scrim = toColor(scrim),
    surfaceBright = toColor(surfaceBright),
    surfaceDim = toColor(surfaceDim),
    surfaceContainer = toColor(surfaceContainer),
    surfaceContainerHigh = toColor(surfaceContainerHigh),
    surfaceContainerHighest = toColor(surfaceContainerHighest),
    surfaceContainerLow = toColor(surfaceContainerLow),
    surfaceContainerLowest = toColor(surfaceContainerLowest),
)

internal fun WarmPaper.darkScheme(): ColorScheme = darkColorScheme(
    primary = toColor(primary),
    onPrimary = toColor(onPrimary),
    primaryContainer = toColor(primaryContainer),
    onPrimaryContainer = toColor(onPrimaryContainer),
    inversePrimary = toColor(inversePrimary),
    secondary = toColor(secondary),
    onSecondary = toColor(onSecondary),
    secondaryContainer = toColor(secondaryContainer),
    onSecondaryContainer = toColor(onSecondaryContainer),
    tertiary = toColor(tertiary),
    onTertiary = toColor(onTertiary),
    tertiaryContainer = toColor(tertiaryContainer),
    onTertiaryContainer = toColor(onTertiaryContainer),
    background = toColor(background),
    onBackground = toColor(onBackground),
    surface = toColor(surface),
    onSurface = toColor(onSurface),
    surfaceVariant = toColor(surfaceVariant),
    onSurfaceVariant = toColor(onSurfaceVariant),
    surfaceTint = toColor(primary),
    inverseSurface = toColor(inverseSurface),
    inverseOnSurface = toColor(inverseOnSurface),
    error = toColor(error),
    onError = toColor(onError),
    errorContainer = toColor(errorContainer),
    onErrorContainer = toColor(onErrorContainer),
    outline = toColor(outline),
    outlineVariant = toColor(outlineVariant),
    scrim = toColor(scrim),
    surfaceBright = toColor(surfaceBright),
    surfaceDim = toColor(surfaceDim),
    surfaceContainer = toColor(surfaceContainer),
    surfaceContainerHigh = toColor(surfaceContainerHigh),
    surfaceContainerHighest = toColor(surfaceContainerHighest),
    surfaceContainerLow = toColor(surfaceContainerLow),
    surfaceContainerLowest = toColor(surfaceContainerLowest),
)

/** The two schemes, built once. */
val WishKaroLightColors: ColorScheme = WishKaroPalette.LIGHT.lightScheme()
val WishKaroDarkColors: ColorScheme = WishKaroPalette.DARK.darkScheme()
