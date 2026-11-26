package `in`.wishkaro.app.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavMetadataKey
import androidx.navigation3.runtime.contains
import androidx.navigation3.runtime.metadata
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_EXPANDED_LOWER_BOUND

/**
 * FR-VIEW-06 and OD-15 — list and detail on screen at once, on expanded widths.
 *
 * This is the requirement the old navigator could not express at all: one `Screen` value
 * cannot say "the People list AND this person", which is why M4 replaced it (see
 * `docs/M4_PLAN.md` §2.1). A Navigation 3 `Scene` can, because a Scene reads more than one
 * entry off the back stack and decides how to draw them together.
 *
 * Written here rather than taken from Material 3 Adaptive's own `adaptive-navigation3`,
 * which first appears in the 1.3.0 line and needs AGP 9.1 and compileSdk 37 — a toolchain
 * this project deliberately does not take, because AGP 9 forbids the Kotlin Multiplatform
 * plugin and the Android application plugin in one subproject (`M0_PLAN.md`,
 * `docs/M4_PLAN.md` §2.4a). The window-size and posture reporting underneath is still
 * Material 3 Adaptive; only the ~80 lines that glue it to the back stack are ours.
 */
data class TwoPaneScene<T : Any>(
    override val key: Any,
    override val previousEntries: List<NavEntry<T>>,
    val listEntry: NavEntry<T>,
    /**
     * Null when the list is showing and nothing is selected yet.
     *
     * The pane still exists and still holds its share of the width. Letting the list stretch
     * across the whole window and then snap to a third of it the moment somebody is tapped
     * is the "stretched phone UI" FR-VIEW-09 names when it asks for Play's large-screen
     * quality tier — and it makes the first tap feel like a bug.
     */
    val detailEntry: NavEntry<T>?,
    /**
     * Where the fold is, in pixels from the left, or null when there is no hinge in the way.
     *
     * FR-VIEW-07: in book posture the hinge separates the panes rather than being drawn
     * over. A detail screen with a physical crease down the middle of it is the thing that
     * requirement exists to prevent.
     */
    val hingeStartPx: Float?,
    val hingeWidthPx: Float?,
) : Scene<T> {

    override val entries: List<NavEntry<T>> = listOfNotNull(listEntry, detailEntry)

    override val content: @Composable (() -> Unit) = {
        val density = LocalDensity.current
        Row(Modifier.fillMaxSize()) {
            if (hingeStartPx != null && hingeWidthPx != null) {
                // Panes sized to the hinge: the list takes everything left of the fold, the
                // gap is left empty, and the detail takes the rest. Nothing is drawn under
                // the crease.
                val listWidth = with(density) { hingeStartPx.toDp() }
                val gap = with(density) { hingeWidthPx.toDp() }
                Box(Modifier.width(listWidth).fillMaxHeight()) { listEntry.Content() }
                Box(Modifier.width(gap).fillMaxHeight())
                Box(Modifier.fillMaxSize()) { DetailOrPlaceholder(detailEntry) }
            } else {
                Box(Modifier.weight(LIST_WEIGHT).fillMaxHeight()) { listEntry.Content() }
                VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Box(Modifier.weight(DETAIL_WEIGHT).fillMaxHeight()) {
                    DetailOrPlaceholder(detailEntry)
                }
            }
        }
    }

    companion object {
        /**
         * The list is narrower than the detail. A 50/50 split wastes a tablet on a column of
         * names, and the detail is where the content actually is.
         */
        private const val LIST_WEIGHT = 0.38f
        private const val DETAIL_WEIGHT = 0.62f

        /**
         * Marks an entry as willing to share the screen.
         *
         * Opt-in, not opt-out, and FR-VIEW-08 is the reason: the composer and the generation
         * flows stay single-pane whatever the width. Those are M5 and M6, so this is a rule
         * being established now rather than a screen being built — and a rule that works by
         * omission is one a future screen gets right by default.
         */
        fun listPane() = metadata { put(ListPaneKey, true) }

        fun detailPane() = metadata { put(DetailPaneKey, true) }
    }
}

// Top level rather than nested in TwoPaneScene's companion: the class is generic, so
// `TwoPaneScene.ListPaneKey` makes the compiler ask for a type argument it does not need.
object ListPaneKey : NavMetadataKey<Boolean>

object DetailPaneKey : NavMetadataKey<Boolean>

@Composable
private fun <T : Any> DetailOrPlaceholder(entry: NavEntry<T>?) {
    if (entry != null) {
        entry.Content()
        return
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            "Pick someone to see their dates.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("detail_placeholder"),
        )
    }
}

/**
 * Two panes whenever the window is expanded and the top of the stack is a list that agreed
 * to share — with the detail beside it when there is one, and a placeholder when there is
 * not.
 */
class TwoPaneSceneStrategy<T : Any>(
    private val windowSizeClass: WindowSizeClass,
    private val hingeStartPx: Float?,
    private val hingeWidthPx: Float?,
) : SceneStrategy<T> {

    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
        // Expanded only. At medium width two panes leaves both too narrow to read, which is
        // why FR-VIEW-06 asks for a rail there and panes only beyond it.
        if (!windowSizeClass.isWidthAtLeastBreakpoint(WIDTH_DP_EXPANDED_LOWER_BOUND)) return null

        val top = entries.lastOrNull() ?: return null

        // Case 1: a list on top with nothing selected — one pane of content, one of prompt.
        if (ListPaneKey in top.metadata) {
            return TwoPaneScene(
                key = top.contentKey to null,
                previousEntries = entries.dropLast(1),
                listEntry = top,
                detailEntry = null,
                hingeStartPx = hingeStartPx,
                hingeWidthPx = hingeWidthPx,
            )
        }

        // Case 2: a detail on top of a list that agreed to share.
        val list = entries.getOrNull(entries.lastIndex - 1) ?: return null
        if (DetailPaneKey !in top.metadata) return null
        if (ListPaneKey !in list.metadata) return null

        return TwoPaneScene(
            // Keyed on both entries, so moving from one person to another is a content
            // change inside the same Scene rather than a whole new Scene animating in.
            key = list.contentKey to top.contentKey,
            // What is left when this Scene is popped: everything up to and including the
            // list. Without it, backing out of a two-pane layout would unwind further than
            // the user asked.
            previousEntries = entries.dropLast(1),
            listEntry = list,
            detailEntry = top,
            hingeStartPx = hingeStartPx,
            hingeWidthPx = hingeWidthPx,
        )
    }
}

@Composable
fun <T : Any> rememberTwoPaneSceneStrategy(): TwoPaneSceneStrategy<T> {
    val info = currentWindowAdaptiveInfo()
    val windowSizeClass = info.windowSizeClass

    // A vertical hinge that actually separates the window — a flat (fully open) device
    // reports no occluding hinge, and a horizontal one is a different layout problem that
    // two side-by-side panes do not have.
    val separating = info.windowPosture.hingeList.firstOrNull {
        it.isVertical && it.isSeparating
    }

    return remember(windowSizeClass, separating) {
        TwoPaneSceneStrategy(
            windowSizeClass = windowSizeClass,
            hingeStartPx = separating?.bounds?.left?.toFloat(),
            hingeWidthPx = separating?.bounds?.let { (it.right - it.left).toFloat() },
        )
    }
}
