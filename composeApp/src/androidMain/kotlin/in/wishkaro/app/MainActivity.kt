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
import `in`.wishkaro.app.platform.ContactsPermissionBridge
import `in`.wishkaro.app.platform.FilePickerBridge
import `in`.wishkaro.app.platform.PermissionOutcome
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

    private var pendingContactsPermission: CompletableDeferred<Boolean>? = null

    /**
     * ACTION_OPEN_DOCUMENT for the spreadsheet, ACTION_CREATE_DOCUMENT for the corrected CSV
     * (FR-IMP-04, FR-IMP-07).
     *
     * The MIME list is deliberately wide. `text/csv` is correct and plenty of files are
     * handed over as `text/comma-separated-values`, `application/csv` or `text/plain`
     * instead, depending on which app wrote them — and a picker that greys out the user's
     * own export is indistinguishable, from the other side of the screen, from the feature
     * not working.
     */
    private val openDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            FilePickerBridge.pendingOpen.complete(uri)
        }

    private val createDocumentLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            FilePickerBridge.pendingCreate.complete(uri)
        }

    private val contactsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingContactsPermission?.complete(granted)
            pendingContactsPermission = null
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

        /**
         * READ_CONTACTS (FR-IMP-01), which needs a three-way answer rather than a boolean.
         *
         * "Denied permanently" is not a stronger DENIED — it is a different situation. From
         * then on the system dialog never appears again, so a button offering to ask again
         * would do nothing at all, and the only route left is this app's page in Settings.
         *
         * Detecting it: `shouldShowRequestPermissionRationale` is false BEFORE the first ask
         * as well as after a permanent denial, so it cannot be read on its own. The "we have
         * asked once" flag is what tells those two apart, and it has to survive the process,
         * because the activity is routinely destroyed while the system dialog is up.
         */
        ContactsPermissionBridge.requester = {
            val prefs = getSharedPreferences(PERMISSION_PREFS, MODE_PRIVATE)
            val askedBefore = prefs.getBoolean(ASKED_FOR_CONTACTS, false)

            val deferred = CompletableDeferred<Boolean>()
            pendingContactsPermission = deferred
            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
            val granted = deferred.await()

            prefs.edit().putBoolean(ASKED_FOR_CONTACTS, true).apply()
            when {
                granted -> PermissionOutcome.GRANTED
                askedBefore && !shouldShowRequestPermissionRationale(Manifest.permission.READ_CONTACTS) ->
                    PermissionOutcome.DENIED_PERMANENTLY
                else -> PermissionOutcome.DENIED
            }
        }

        FilePickerBridge.opener = {
            val deferred = CompletableDeferred<android.net.Uri?>()
            FilePickerBridge.pendingOpen.deferred = deferred
            openDocumentLauncher.launch(
                arrayOf(
                    "text/csv",
                    "text/comma-separated-values",
                    "application/csv",
                    "text/plain",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                    "application/vnd.ms-excel",
                    "application/octet-stream",
                ),
            )
            deferred.await()
        }
        FilePickerBridge.creator = { suggestedName ->
            val deferred = CompletableDeferred<android.net.Uri?>()
            FilePickerBridge.pendingCreate.deferred = deferred
            createDocumentLauncher.launch(suggestedName)
            deferred.await()
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
        ContactsPermissionBridge.requester = null
        FilePickerBridge.opener = null
        FilePickerBridge.creator = null
        super.onDestroy()
    }

    private companion object {
        const val PERMISSION_PREFS = "wishkaro.permissions"
        const val ASKED_FOR_CONTACTS = "asked_for_contacts"
    }
}
