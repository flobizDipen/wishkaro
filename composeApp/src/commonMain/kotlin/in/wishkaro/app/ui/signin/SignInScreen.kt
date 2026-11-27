package `in`.wishkaro.app.ui.signin

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.wishkaro.app.ui.components.Note

/**
 * Sign-in is mandatory before the app (FR-AUTH-02).
 *
 * It used to carry a comment reading *"Deliberately plain: M0 proves the chain, not the
 * brand."* That was true in M0 and stopped being true somewhere around M4; by M7 this was the
 * second screen a new user saw — after a carousel that did not exist — and it was a
 * `displaySmall` word and a sentence in Material purple.
 *
 * Now it is the wordmark in the serif, the same diya the launcher icon carries, and one line
 * that says what the app does. The diya is drawn here rather than loaded from
 * `R.drawable.ic_launcher_foreground`, because that is an Android resource and this file is
 * `commonMain` — and the two are checked against each other by eye in `M7_VERIFY.md` rather
 * than being allowed to drift silently.
 *
 * The third line is the privacy claim, and it is on this screen deliberately: it is the one
 * moment the user is being asked for something, and "what happens to my contacts" is the
 * question a reminder app has to answer before it is granted an account.
 */
@Composable
fun SignInScreen(busy: Boolean, onSignIn: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        // Not in a Scaffold — this is what the app shows instead of one — so it insets
        // itself. Without this the button sits under the navigation bar on a gesture-nav
        // phone, which is every phone this product is for.
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 32.dp)
            .testTag("sign_in"),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        DiyaMark()
        Spacer(Modifier.height(28.dp))

        Text(
            "WishKaro",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Never miss a birthday again.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(48.dp))

        Button(
            onClick = onSignIn,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().testTag("sign_in_google"),
        ) {
            if (busy) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Continue with Google")
            }
        }

        Spacer(Modifier.height(16.dp))
        Note(
            "Your people stay on your phone and in your own account. WishKaro never posts " +
                "anything and never sends a message for you.",
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The launcher icon's diya, drawn in Compose.
 *
 * The same two shapes as `res/drawable/ic_launcher_foreground.xml`, in the same proportions:
 * a teardrop flame over a shallow bowl. Drawn rather than referenced because a `commonMain`
 * composable cannot reach an Android drawable resource, and bundling it a second time as a
 * Compose resource would be two copies of one mark that can diverge without anything noticing.
 *
 * The flame takes the accent and the bowl takes ink, so the mark is the same object the
 * launcher shows rather than a monochrome echo of it.
 */
@Composable
private fun DiyaMark() {
    val flame = MaterialTheme.colorScheme.primary
    val bowl = MaterialTheme.colorScheme.onSurface

    Canvas(Modifier.size(84.dp)) {
        // The drawable's 108x108 coordinate space, scaled to whatever this canvas is.
        val u = size.minDimension / 108f
        fun p(x: Float, y: Float) = Offset(x * u, y * u)

        drawPath(
            path = Path().apply {
                moveTo(p(54f, 29f).x, p(54f, 29f).y)
                cubicTo(p(43f, 42f).x, p(43f, 42f).y, p(43f, 50.5f).x, p(43f, 50.5f).y, p(43f, 50.5f).x, p(43f, 50.5f).y)
                cubicTo(p(43f, 57.2f).x, p(43f, 57.2f).y, p(47.9f, 62f).x, p(47.9f, 62f).y, p(54f, 62f).x, p(54f, 62f).y)
                cubicTo(p(60.1f, 62f).x, p(60.1f, 62f).y, p(65f, 57.2f).x, p(65f, 57.2f).y, p(65f, 50.5f).x, p(65f, 50.5f).y)
                cubicTo(p(65f, 42f).x, p(65f, 42f).y, p(54f, 29f).x, p(54f, 29f).y, p(54f, 29f).x, p(54f, 29f).y)
                close()
            },
            color = flame,
        )

        drawPath(
            path = Path().apply {
                moveTo(p(28f, 64f).x, p(28f, 64f).y)
                lineTo(p(80f, 64f).x, p(80f, 64f).y)
                cubicTo(p(80f, 74f).x, p(80f, 74f).y, p(68.4f, 81f).x, p(68.4f, 81f).y, p(54f, 81f).x, p(54f, 81f).y)
                cubicTo(p(39.6f, 81f).x, p(39.6f, 81f).y, p(28f, 74f).x, p(28f, 74f).y, p(28f, 64f).x, p(28f, 64f).y)
                close()
            },
            color = bowl,
        )
    }
}
