package `in`.wishkaro.app.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator

/**
 * One back stack per top-level destination, all of them in Compose saved state.
 *
 * Built on the documented Navigation 3 multiple-back-stacks pattern. Two things about it
 * are decisions rather than copying:
 *
 *  - **Exit through home.** The start route's entries are always at the bottom of the list,
 *    so backing out of any tab lands on People rather than closing the app. The user always
 *    leaves the same way they came in.
 *  - **Every non-root state has a back handler.** `NavDisplay` only calls `onBack` when
 *    there is something to pop, so system back closes the app exactly when the user is
 *    standing on the start route and not before. M3 shipped a report screen with no
 *    `BackHandler` at all, where system back finished the Activity while an
 *    Application-scoped store kept its state — and the user was then bounced straight back
 *    into the report they had just read. That cannot be built here.
 */
class WishKaroNavigation(
    /** In order, so the selected one can be saved as an index. The first is the start route. */
    private val topLevelRoutes: List<Screen>,
    selectedTab: MutableState<Int>,
    private val stacks: Map<Screen, NavBackStack<NavKey>>,
) {
    val startRoute: Screen get() = topLevelRoutes.first()

    /**
     * Which tab is showing, saved as its INDEX.
     *
     * An index rather than the key itself, because saving the key means serializing a
     * `NavKey` polymorphically, and the serializer that does that
     * (`androidx.navigation3.runtime.serialization.NavKeySerializer`) exists on Android and
     * not on Kotlin/Native — the Android target compiled with it and the iOS one did not.
     * The set of tabs is fixed and ordered, so an index says the same thing with no
     * serialization at all.
     */
    private var selectedTab: Int by selectedTab

    var topLevelRoute: Screen
        get() = topLevelRoutes[selectedTab.coerceIn(topLevelRoutes.indices)]
        private set(value) {
            val index = topLevelRoutes.indexOf(value)
            require(index >= 0) { "$value is not a top-level route" }
            selectedTab = index
        }

    private val currentStack: NavBackStack<NavKey>
        get() = stacks[topLevelRoute] ?: error("no stack for $topLevelRoute")

    /** What is on top right now. */
    val current: NavKey get() = currentStack.last()

    /** The same thing as a [Screen], which every key in this app is. */
    val currentScreen: Screen? get() = current as? Screen

    /**
     * Switch tab, or push within the current one.
     *
     * A top-level route is switched to rather than pushed, so tapping People while four
     * levels deep in People does not add a thirteenth entry — it is the same destination.
     */
    fun goTo(screen: Screen) {
        if (screen in stacks) {
            topLevelRoute = screen
        } else {
            currentStack.add(screen)
        }
    }

    fun back() {
        if (currentStack.size > 1) {
            currentStack.removeLastOrNull()
        } else if (topLevelRoute != startRoute) {
            topLevelRoute = startRoute
        }
        // On the start route's root there is nothing left to pop: NavDisplay will not call
        // this, and the system closes the app. That is the correct ending.
    }

    /**
     * Replace a tab's whole history with [screens], and show it.
     *
     * This is what a deep link and a finished write both need, and they need it for the same
     * reason: landing on a person's detail with no way back, or with a half-finished editor
     * underneath, is worse than not landing there at all. `screens.first()` becomes the
     * root, so back from `wishkaro://person/<id>` reaches the list (FR-WID-02).
     */
    fun resetTo(vararg screens: Screen) {
        require(screens.isNotEmpty()) { "resetTo needs at least a root" }
        val root = screens.first()
        val tab = if (root in stacks) root else startRoute
        val stack = stacks[tab] ?: error("no stack for $tab")
        stack.clear()
        stack.add(tab)
        screens.drop(if (root in stacks) 1 else 0).forEach(stack::add)
        topLevelRoute = tab
    }

    /**
     * Throw away every tab's history. Sign-out only.
     *
     * Not "reset the current one": the next person to sign in on this handset must not be
     * able to press back into the last one's data. M1 onward.
     */
    fun resetAll() {
        stacks.forEach { (route, stack) ->
            stack.clear()
            stack.add(route)
        }
        topLevelRoute = startRoute
    }

    /**
     * The entries `NavDisplay` draws, each decorated so its state survives being switched
     * away from.
     *
     * One `SaveableStateHolder` per stack, not one for all of them — that is what keeps a
     * tab's scroll position and a half-typed form while the user is looking at another tab.
     */
    @Composable
    fun decoratedEntries(
        entryProvider: (NavKey) -> NavEntry<NavKey>,
    ): List<NavEntry<NavKey>> {
        val decorated = stacks.mapValues { (_, stack) ->
            rememberDecoratedNavEntries(
                backStack = stack,
                entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
                entryProvider = entryProvider,
            )
        }
        return routesInUse().flatMap { decorated[it].orEmpty() }
    }

    /** Exit through home: the start route is always underneath, and at most one tab above. */
    private fun routesInUse(): List<Screen> =
        if (topLevelRoute == startRoute) listOf(startRoute) else listOf(startRoute, topLevelRoute)

    /**
     * The keys `NavDisplay` will draw, in order.
     *
     * Exists so the back stack can be asserted on without standing up a composition —
     * `decoratedEntries` above is the same list with a `SaveableStateHolder` wrapped round
     * each entry. §D of the checklist: the test drives the seam.
     */
    fun visibleKeys(): List<NavKey> = routesInUse().flatMap { stacks[it].orEmpty() }
}

@Composable
fun rememberWishKaroNavigation(
    topLevelRoutes: List<Screen> = TOP_LEVEL_ROUTES,
): WishKaroNavigation {
    require(topLevelRoutes.isNotEmpty()) { "there has to be somewhere to start" }

    val selectedTab = rememberSaveable { mutableIntStateOf(0) }

    val stacks = topLevelRoutes.associateWith { route ->
        rememberNavBackStack(
            configuration = WISHKARO_SAVED_STATE,
            elements = arrayOf<NavKey>(route),
        )
    }

    return remember(topLevelRoutes) {
        WishKaroNavigation(
            topLevelRoutes = topLevelRoutes,
            selectedTab = selectedTab,
            stacks = stacks,
        )
    }
}
