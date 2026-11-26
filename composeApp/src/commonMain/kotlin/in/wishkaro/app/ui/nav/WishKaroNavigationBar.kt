package `in`.wishkaro.app.ui.nav

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import `in`.wishkaro.app.ui.components.CalendarGlyph
import `in`.wishkaro.app.ui.components.PeopleGlyph
import `in`.wishkaro.app.ui.components.TimelineGlyph
import `in`.wishkaro.app.ui.components.UpcomingGlyph

/** What a top-level destination is called and how it is drawn, in one place. */
data class TopLevelLook(val label: String, val tag: String)

fun lookOf(screen: Screen): TopLevelLook = when (screen) {
    Screen.Upcoming -> TopLevelLook("Upcoming", "tab_upcoming")
    Screen.Calendar -> TopLevelLook("Calendar", "tab_calendar")
    Screen.Timeline -> TopLevelLook("Timeline", "tab_timeline")
    Screen.People -> TopLevelLook("People", "tab_people")
    else -> TopLevelLook(screen::class.simpleName.orEmpty(), "tab_other")
}

@Composable
fun TopLevelGlyph(screen: Screen, tint: Color, modifier: Modifier = Modifier) {
    when (screen) {
        Screen.Upcoming -> UpcomingGlyph(tint, modifier)
        Screen.Calendar -> CalendarGlyph(tint, modifier)
        Screen.Timeline -> TimelineGlyph(tint, modifier)
        else -> PeopleGlyph(tint, modifier)
    }
}

/**
 * FR-VIEW-06's compact navigation.
 *
 * Tapping the tab you are already on does nothing rather than pushing a second copy of it —
 * [WishKaroNavigation.goTo] treats a top-level route as a switch, not a push.
 */
@Composable
fun WishKaroNavigationBar(
    current: Screen,
    onSelect: (Screen) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier = modifier.testTag("bottom_nav")) {
        TOP_LEVEL_ROUTES.forEach { route ->
            val look = lookOf(route)
            val selected = route == current
            NavigationBarItem(
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
                modifier = Modifier.testTag(look.tag),
            )
        }
    }
}
