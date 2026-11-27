package `in`.wishkaro.app.ui.signin

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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import `in`.wishkaro.app.ui.components.Note
import `in`.wishkaro.app.ui.components.WishKaroMark

/**
 * Sign-in is mandatory before the app (FR-AUTH-02).
 *
 * It used to carry a comment reading *"Deliberately plain: M0 proves the chain, not the
 * brand."* That was true in M0 and stopped being true somewhere around M4; by M7 this was the
 * second screen a new user saw — after a carousel that did not exist — and it was a
 * `displaySmall` word and a sentence in Material purple.
 *
 * Now it is the wordmark in the serif, the app's own mark above it, and one line that says
 * what the app does. The mark is `components/WishKaroMark` — the same calendar the launcher
 * icon and the splash draw, from the same numbers, because a `commonMain` composable cannot
 * reach an Android drawable resource.
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
        WishKaroMark()
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
