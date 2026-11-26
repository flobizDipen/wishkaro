package `in`.wishkaro.app.ui.nav

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The back stack, driven directly.
 *
 * `MILESTONE_CHECKLIST.md` §D: *the test drives the seam, not only the pure function.* The
 * seam here is the thing M4's whole navigation decision rests on — what is on the stack
 * after a deep link, after a write finishes, after a sign-out, and after the user switches
 * tab and comes back. All of it is reachable without a composition, because the back stacks
 * are plain `SnapshotStateList`s and the selected tab is an `Int`.
 *
 * Two of M3's seven findings were navigation state outliving the Activity. These tests say
 * what the stack is SUPPOSED to be in each of those situations, so the next change to it has
 * something to be wrong against.
 */
class WishKaroNavigationTest {

    /**
     * Two top-level routes, because one cannot show that a tab keeps its own history.
     *
     * `Screen.Deleted` stands in for the second tab: M4's real second, third and fourth tabs
     * (List, Calendar, Timeline) do not exist yet, and the machinery they will use does.
     * Nothing here depends on which destination it is.
     */
    private val tabs = listOf(Screen.People, Screen.Deleted)

    private fun navigation(routes: List<Screen> = tabs): WishKaroNavigation =
        WishKaroNavigation(
            topLevelRoutes = routes,
            selectedTab = mutableStateOf(routes.first()::class.simpleName.orEmpty()),
            stacks = routes.associateWith { NavBackStack<NavKey>(it) },
        )

    @Test
    fun `a non-top-level destination is pushed onto the current tab`() {
        val nav = navigation()
        nav.goTo(Screen.PersonDetail("p1"))

        assertEquals(Screen.PersonDetail("p1"), nav.current)
        assertEquals(listOf(Screen.People, Screen.PersonDetail("p1")), nav.visibleKeys())
    }

    @Test
    fun `a top-level destination switches tab instead of stacking`() {
        val nav = navigation()
        nav.goTo(Screen.People)
        nav.goTo(Screen.People)

        // Tapping the tab you are already on is not four levels of People.
        assertEquals(listOf(Screen.People), nav.visibleKeys())
    }

    @Test
    fun `each tab keeps its own history while you are looking at the other one`() {
        val nav = navigation()
        nav.goTo(Screen.PersonDetail("p1"))
        nav.goTo(Screen.EditPerson("p1"))

        nav.goTo(Screen.Deleted)
        assertEquals(Screen.Deleted, nav.current)

        nav.goTo(Screen.People)
        assertEquals(
            Screen.EditPerson("p1"),
            nav.current,
            "coming back to a tab lands where you left it",
        )
    }

    @Test
    fun `back pops within a tab, then leaves the tab, then stops`() {
        val nav = navigation()
        nav.goTo(Screen.Deleted)
        nav.goTo(Screen.PersonDetail("p1"))

        nav.back()
        assertEquals(Screen.Deleted, nav.current, "popped within the second tab")

        nav.back()
        assertEquals(Screen.People, nav.current, "exit through home: back leaves for the start tab")

        nav.back()
        assertEquals(
            Screen.People,
            nav.current,
            "on the start route's root there is nothing left — NavDisplay lets the system close the app",
        )
    }

    @Test
    fun `a deep link lands on the person with the list underneath it`() {
        val nav = navigation()
        nav.goTo(Screen.Settings)

        // FR-WID-02 and M2's wishkaro://person/<id>. The synthetic stack is the requirement:
        // arriving from a notification with no way back is worse than not arriving.
        nav.resetTo(Screen.People, Screen.PersonDetail("p9"))

        assertEquals(listOf(Screen.People, Screen.PersonDetail("p9")), nav.visibleKeys())
        nav.back()
        assertEquals(Screen.People, nav.current, "back from a deep link reaches the list")
    }

    @Test
    fun `a second deep link does not pile up behind the first`() {
        val nav = navigation()
        nav.resetTo(Screen.People, Screen.PersonDetail("p1"))
        nav.resetTo(Screen.People, Screen.PersonDetail("p2"))

        assertEquals(
            listOf(Screen.People, Screen.PersonDetail("p2")),
            nav.visibleKeys(),
            "two notifications in a row leave one person on the stack, not two",
        )
    }

    @Test
    fun `a deep link while on another tab brings that tab forward`() {
        val nav = navigation()
        nav.goTo(Screen.Deleted)
        nav.resetTo(Screen.People, Screen.PersonDetail("p1"))

        assertEquals(Screen.People, nav.topLevelRoute)
        assertEquals(Screen.PersonDetail("p1"), nav.current)
    }

    @Test
    fun `resetTo with a non-top-level root puts it on the start tab`() {
        val nav = navigation()
        nav.resetTo(Screen.ImportResult)

        assertEquals(Screen.People, nav.topLevelRoute)
        assertEquals(
            listOf(Screen.People, Screen.ImportResult),
            nav.visibleKeys(),
            "the start route stays underneath, so the report is backed out of, not exited from",
        )
    }

    @Test
    fun `signing out clears every tab, not just the one on screen`() {
        val nav = navigation()
        nav.goTo(Screen.PersonDetail("p1"))
        nav.goTo(Screen.Deleted)
        nav.goTo(Screen.EditPerson("p2"))

        nav.resetAll()

        assertEquals(Screen.People, nav.topLevelRoute)
        assertEquals(listOf(Screen.People), nav.visibleKeys())

        // The one that matters: the OTHER tab must be empty too. The next person to sign in
        // on this handset cannot be one tap away from the last one's data.
        nav.goTo(Screen.Deleted)
        assertEquals(
            listOf(Screen.People, Screen.Deleted),
            nav.visibleKeys(),
            "the second tab was reset as well",
        )
    }

    @Test
    fun `the start route is always underneath a second tab`() {
        val nav = navigation()
        nav.goTo(Screen.Deleted)

        assertTrue(
            nav.visibleKeys().first() == Screen.People,
            "exit through home: the user always leaves the way they came in",
        )
    }
}
