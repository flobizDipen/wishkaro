package `in`.wishkaro.app.ui.adaptive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.window.core.layout.WindowSizeClass.Companion.WIDTH_DP_EXPANDED_LOWER_BOUND

/**
 * FR-VIEW-08 — on expanded widths a dialog becomes a side sheet.
 *
 * One composable rather than a branch at each of the five call sites, because the rule is
 * about the app and not about any one dialog: a confirmation that is a centred box on a
 * phone and a centred box on a tablet is a phone app stretched, which is what
 * FR-VIEW-09's large-screen quality tier is written against.
 *
 * What the side sheet keeps from the dialog: it is modal, tapping outside dismisses it, and
 * the buttons sit where they did. What it changes: it is anchored to the trailing edge and
 * full height, so it does not cover the thing the user is deciding about. That is the whole
 * point on a wide window — you can still see the person while you confirm deleting them.
 *
 * The other half of FR-VIEW-08, "bottom sheets become dialogs", has nothing to convert:
 * **this app has no bottom sheets.** Said out loud rather than passed over, per the build
 * prompt. The rule is established here for whichever milestone first wants one.
 *
 * The composer and the generation flows stay single-pane and un-sheeted whatever the width
 * (M5 and M6); that is enforced by them not opting into [TwoPaneScene] rather than by
 * anything here.
 */
@Composable
fun AdaptiveDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    val expanded = currentWindowAdaptiveInfo().windowSizeClass
        .isWidthAtLeastBreakpoint(WIDTH_DP_EXPANDED_LOWER_BOUND)

    if (!expanded) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            dismissButton = dismissButton,
            title = title,
            text = text,
            modifier = modifier.testTag("adaptive_dialog"),
        )
        return
    }

    // A Dialog window with the content pinned to the trailing edge: modal and dismissable
    // exactly like the AlertDialog it replaces, but shaped like a sheet.
    // usePlatformDefaultWidth = false, or the Dialog window is sized to its content and
    // "anchored to the trailing edge" anchors to the edge of a window that is already
    // centred — which looks exactly like a dialog with sheet corners.
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Surface(
                modifier = modifier.width(SIDE_SHEET_WIDTH).fillMaxHeight()
                    .testTag("adaptive_side_sheet"),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Column(Modifier.fillMaxSize().padding(24.dp)) {
                    title?.let {
                        Box(Modifier.padding(bottom = 16.dp)) { it() }
                    }
                    Box(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        text?.invoke()
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
            }
        }
    }
}

/** Material's side-sheet width. Wide enough to read, narrow enough to leave context visible. */
private val SIDE_SHEET_WIDTH = 400.dp

/** Convenience so a call site can pass a plain string title without ceremony. */
@Composable
fun adaptiveTitle(value: String): @Composable () -> Unit = {
    Text(value, style = MaterialTheme.typography.headlineSmall)
}
