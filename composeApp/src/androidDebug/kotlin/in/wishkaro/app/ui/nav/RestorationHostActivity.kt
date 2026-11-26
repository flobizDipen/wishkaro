package `in`.wishkaro.app.ui.nav

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable

/**
 * An Activity that sets its own content, so destroying and rebuilding it is a real test.
 *
 * `ComposeTestRule.setContent` attaches content to the Activity instance that exists when
 * the test calls it. `ActivityScenario.recreate()` then produces a NEW instance which never
 * had setContent called on it, and every assertion afterwards fails with "No compose
 * hierarchies found in the app" — which looks like a restoration failure and is not one.
 *
 * Putting the content in onCreate is how the real app works anyway (see MainActivity), so
 * this host is the honest shape for the question being asked.
 */
class RestorationHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { content() }
    }

    companion object {
        /**
         * What to draw. Set by the test BEFORE launching, because onCreate reads it on every
         * creation — including the one that `recreate()` causes.
         */
        var content: @Composable () -> Unit = {}
    }
}
