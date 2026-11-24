package `in`.wishkaro.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import `in`.wishkaro.app.platform.ActivityHolder
import `in`.wishkaro.app.platform.PermissionBridge
import `in`.wishkaro.app.ui.AppStore
import kotlinx.coroutines.CompletableDeferred
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val store: AppStore by inject()

    /** One in-flight permission request at a time; completed by the launcher callback. */
    private var pendingPermission: CompletableDeferred<Boolean>? = null

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingPermission?.complete(granted)
            pendingPermission = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ActivityHolder.current = this
        PermissionBridge.requester = {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                true
            } else {
                val deferred = CompletableDeferred<Boolean>()
                pendingPermission = deferred
                // shouldShowRequestPermissionRationale is true on a second ask; the
                // rationale itself is the sign-in screen's copy plus this timing — the
                // dialog appears right after sign-in, when the reason is self-evident
                // (FR-AUTH-05).
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                deferred.await()
            }
        }

        handleDeepLink(intent)
        setContent { App() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    /** `wishkaro://person/<id>` — tapping a reminder opens the app on that person. */
    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "wishkaro") return
        if (uri.host == "person") {
            uri.pathSegments.firstOrNull()?.let(store::focusPerson)
        }
    }

    override fun onDestroy() {
        if (ActivityHolder.current === this) ActivityHolder.current = null
        PermissionBridge.requester = null
        super.onDestroy()
    }
}
