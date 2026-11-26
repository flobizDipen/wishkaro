package `in`.wishkaro.app.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertTrue

/**
 * FR-VIEW-07's hinge rule: *in book posture the hinge is treated as a pane separator rather
 * than being drawn over.*
 *
 * This is the one claim in M4's adaptive work that cannot be checked by looking at a device
 * here — the only foldable available is an emulator, and reaching the two-pane layout on it
 * needs a signed-in session it does not have. What CAN be checked, deterministically and
 * anywhere, is the arithmetic: given a hinge at a known place, does the list pane stop
 * before it, and does the detail pane start after it?
 *
 * So the posture plumbing (`currentWindowAdaptiveInfo().windowPosture`) is exercised on the
 * emulator, which folds and unfolds without crashing and flips the window between 411dp and
 * 841dp; and the sizing the posture feeds is pinned here. `M4_VERIFY.md` says which is which
 * rather than implying one picture showed both.
 */
class TwoPaneHingeTest {

    @get:Rule
    val rule = createComposeRule()

    private object ListKey : NavKey
    private object DetailKey : NavKey

    private fun entry(key: NavKey, tag: String): NavEntry<NavKey> =
        NavEntry(key = key, metadata = emptyMap()) {
            Box(Modifier.fillMaxSize().testTag(tag))
        }

    @Test
    fun theListStopsBeforeTheHingeAndTheDetailStartsAfterIt() {
        val hingeStart = 500f
        val hingeWidth = 40f

        rule.setContent {
            val scene = TwoPaneScene(
                key = "hinged",
                previousEntries = emptyList(),
                listEntry = entry(ListKey, "pane_list"),
                detailEntry = entry(DetailKey, "pane_detail"),
                hingeStartPx = hingeStart,
                hingeWidthPx = hingeWidth,
            )
            Box(Modifier.size(1000.dp)) { scene.content() }
        }

        val density = rule.density
        val list = rule.onNodeWithTag("pane_list").getBoundsInRoot()
        val detail = rule.onNodeWithTag("pane_detail").getBoundsInRoot()

        val hingeStartDp = with(density) { hingeStart.toDp() }
        val hingeEndDp = with(density) { (hingeStart + hingeWidth).toDp() }

        // The list ends at the fold, give or take a rounding pixel.
        assertTrue(
            abs((list.right - hingeStartDp).value) < 1f,
            "the list pane should end at the hinge (${list.right}) but the hinge starts at $hingeStartDp",
        )
        // And the detail begins on the far side of it — nothing is drawn across the crease.
        assertTrue(
            detail.left >= hingeEndDp - 1.dp,
            "the detail pane starts at ${detail.left}, which is inside the hinge ending at $hingeEndDp",
        )
    }

    @Test
    fun withNoHingeTheTwoPanesMeetAndTheDetailIsTheWiderOne() {
        rule.setContent {
            val scene = TwoPaneScene(
                key = "flat",
                previousEntries = emptyList(),
                listEntry = entry(ListKey, "pane_list"),
                detailEntry = entry(DetailKey, "pane_detail"),
                hingeStartPx = null,
                hingeWidthPx = null,
            )
            Box(Modifier.size(1000.dp)) { scene.content() }
        }

        val list = rule.onNodeWithTag("pane_list").getBoundsInRoot()
        val detail = rule.onNodeWithTag("pane_detail").getBoundsInRoot()

        // A flat or ordinary wide screen: no gap, and the detail gets the larger share,
        // because a 50/50 split spends a tablet on a column of names.
        assertTrue(detail.left >= list.right, "the panes must not overlap")
        assertTrue(
            (detail.right - detail.left) > (list.right - list.left),
            "the detail (${detail.right - detail.left}) should be wider than the list (${list.right - list.left})",
        )
    }

    @Test
    fun withNothingSelectedTheSecondPaneHoldsAPrompt() {
        rule.setContent {
            val scene = TwoPaneScene<NavKey>(
                key = "empty",
                previousEntries = emptyList(),
                listEntry = entry(ListKey, "pane_list"),
                detailEntry = null,
                hingeStartPx = null,
                hingeWidthPx = null,
            )
            Box(Modifier.size(1000.dp)) { scene.content() }
        }

        // The pane still exists and still takes its share. Letting the list stretch across
        // the whole window and snap back on the first tap is the "stretched phone UI"
        // FR-VIEW-09 is written against.
        rule.onNodeWithTag("pane_list").assertIsDisplayed()
        rule.onNodeWithTag("detail_placeholder").assertIsDisplayed()

        val list = rule.onNodeWithTag("pane_list").getBoundsInRoot()
        assertTrue((list.right - list.left) < 1000.dp, "the list must not take the whole window")
    }
}
