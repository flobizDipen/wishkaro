package `in`.wishkaro.app.ui.adaptive

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_MEDIUM_LOWER_BOUND
import `in`.wishkaro.app.ui.nav.Screen
import `in`.wishkaro.app.ui.nav.TOP_LEVEL_ROUTES
import `in`.wishkaro.app.ui.nav.TopLevelGlyph
import `in`.wishkaro.app.ui.nav.lookOf

/**
 * Which navigation the window is wide enough for (FR-VIEW-06).
 *
 * Two values rather than three, because only two things change: whether navigation runs
 * along the bottom or down the side. Whether a second PANE appears is a separate question
 * with a separate breakpoint, answered by [TwoPaneSceneStrategy] — medium is wide enough
 * for a rail and not wide enough for two columns of text.
 */
enum class NavigationKind { BOTTOM_BAR, RAIL }

@Composable
fun rememberNavigationKind(): NavigationKind {
    val widthClass: WindowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    return if (widthClass.isWidthAtLeastBreakpoint(WIDTH_DP_MEDIUM_LOWER_BOUND)) {
        NavigationKind.RAIL
    } else {
        NavigationKind.BOTTOM_BAR
    }
}

/**
 * The medium/expanded navigation: the same four destinations, down the side.
 *
 * Deliberately the same `lookOf` and the same glyphs as the bottom bar. A rail that renames
 * or reorders the destinations is a second navigation model for the user to learn on the
 * same app.
 */
@Composable
fun WishKaroNavigationRail(
    current: Screen,
    onSelect: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationRail(modifier = modifier.testTag("nav_rail")) {
        TOP_LEVEL_ROUTES.forEach { route ->
            val look = lookOf(route)
            val selected = route == current
            NavigationRailItem(
                selected = selected,
                onClick = { onSelect(route) },
                icon = {
                    TopLevelGlyph(
                        screen = route,
                        tint = if (selected) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                },
                label = { Text(look.label) },
                modifier = Modifier.testTag("rail_${look.tag}"),
            )
        }
    }
}

/** Puts the rail beside [content] when the window calls for one, and nothing when it does not. */
@Composable
fun RailOrNot(
    kind: NavigationKind,
    current: Screen,
    showNavigation: Boolean,
    onSelect: (Screen) -> Unit,
    content: @Composable () -> Unit,
) {
    if (kind != NavigationKind.RAIL || !showNavigation) {
        content()
        return
    }
    Row(Modifier.fillMaxSize()) {
        WishKaroNavigationRail(current = current, onSelect = onSelect)
        content()
    }
}
