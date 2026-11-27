package `in`.wishkaro.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import `in`.wishkaro.app.platform.ActivityHolder
import `in`.wishkaro.app.platform.ContactsPermissionBridge
import `in`.wishkaro.app.platform.FilePickerBridge
import `in`.wishkaro.app.platform.PermissionOutcome
import `in`.wishkaro.app.platform.PermissionBridge
import `in`.wishkaro.app.ui.AppStore
import kotlinx.coroutines.CompletableDeferred
import org.koin.android.ext.android.inject

@OptIn(ExperimentalComposeUiApi::class)
class MainActivity : ComponentActivity() {

    private val store: AppStore by inject()

    // The in-flight asks live on the BRIDGES, not here. A field on the Activity is orphaned
    // the moment the Activity is destroyed — the rebuilt one completes its own null field
    // and the coroutine awaiting the old one waits for ever. That is what locked the
    // contacts import shut after a rotation; see M4_VERIFY.md §0.6.
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            PermissionBridge.pending.complete(granted)
        }

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

    /**
     * The system photo picker (M6, FR-PPL-04).
     *
     * `PickVisualMedia` rather than `OpenDocument` with image MIME types, and rather than
     * `READ_MEDIA_IMAGES`: it needs **no permission at all**, it is the gallery UI people
     * expect when they are choosing a picture of somebody, and the app receives only the
     * one image that was chosen. A media permission would hand this app the entire camera
     * roll for the life of the grant, which would be the largest ask in the manifest for
     * the smallest feature in the app.
     */
    private val photoPickerLauncher =
        registerForActivityResult(
            ActivityResultContracts.PickVisualMedia(),
        ) { uri ->
            FilePickerBridge.pendingPhoto.complete(uri)
        }

    private val contactsPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            ContactsPermissionBridge.pending.complete(granted)
        }

    /**
     * Flipped by the first composition, read by the splash screen's keep-on-screen condition.
     *
     * Not a `MutableState`: nothing in Compose reads it, and the only reader is a pre-draw
     * callback on the Android side.
     */
    private var firstComposeFrame = false

    override fun onCreate(savedInstanceState: Bundle?) {
        /**
         * M7, item 5 — and the ORDER here is the whole of it.
         *
         * `installSplashScreen()` must run BEFORE `super.onCreate`, because that is where it
         * swaps the launch theme (`Theme.WishKaro.Starting`) for `postSplashScreenTheme`
         * (`Theme.WishKaro`). Called after, the activity keeps the splash theme for its whole
         * life and the icon stays behind the content as a window background.
         *
         * `setKeepOnScreenCondition` holds the splash until the first composition has run.
         * Without it the splash exits on the first draw of the content VIEW, which for a
         * Compose activity is the empty `ComposeView` — so there is a frame of window
         * background with nothing on it between the icon leaving and the app arriving. One
         * frame of ivory is not a white flash, but it is the seam, and the item is about the
         * hand-off being invisible rather than about the colour being nearly right.
         */
        val splash = installSplashScreen()
        splash.setKeepOnScreenCondition { !firstComposeFrame }

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        ActivityHolder.current = this
        PermissionBridge.requester = {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                true
            } else {
                val deferred = CompletableDeferred<Boolean>()
                PermissionBridge.pending.deferred = deferred
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
            ContactsPermissionBridge.pending.deferred = deferred
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

        FilePickerBridge.photoPicker = {
            val deferred = CompletableDeferred<android.net.Uri?>()
            FilePickerBridge.pendingPhoto.deferred = deferred
            photoPickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
            )
            deferred.await()
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

        // Only a FRESH launch carries a deep link. A recreation — rotation, fold, or the
        // process being rebuilt from Recents — is handed the Activity's ORIGINAL Intent
        // again, data and all, because clearing it below mutates this process's copy and
        // not what the system holds for the task. Without this guard the user is thrown
        // back into the person a notification named every time they turn the phone over,
        // and again when the app is restored days later. `savedInstanceState == null` is
        // exactly the question "is this an arrival or a restoration?".
        if (savedInstanceState == null) handleDeepLink(intent)
        setContent {
            // Makes every `Modifier.testTag("...")` in the shared UI appear as a
            // `resource-id` in a `uiautomator dump`, which is what lets tools/device/ui.sh
            // resolve a tap target from the view tree instead of reading a coordinate off a
            // screenshot. Three blind taps across M2 and M3 went into another app, a Slack
            // reply box and a different person's record; every guard rail those bought
            // checks the state BEFORE the tap. See docs/M4_PLAN.md §4.
            //
            // Android-only API, so it lives here rather than in commonMain's App().
            // The flag the splash is waiting on, flipped once the first composition has
            // run. `LaunchedEffect(Unit)` rather than `onGloballyPositioned`: it fires after
            // composition and before the frame is presented, which is the moment there is
            // something real behind the icon.
            LaunchedEffect(Unit) { firstComposeFrame = true }
            Box(Modifier.semantics { testTagsAsResourceId = true }) { App() }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    /**
     * `wishkaro://person/<id>` — a reminder, or M4's widget (FR-WID-02), opening the app on
     * somebody.
     *
     * **The intent is consumed, not just read.** This is the one-shot rule again, one layer
     * down from where M3 found it. `AppStore` emits navigation through a `Channel` so a
     * returning Activity cannot replay it — but the Intent is itself a persistent value,
     * held by the Activity and handed to every `onCreate`. So a rotation re-read it and
     * navigated again, throwing the user back to the person the notification was about,
     * however far they had moved on.
     *
     * Invisible until M4, because the old manifest's `configChanges` meant rotation never
     * recreated the Activity. Found on the device by rotating after a deep link and landing
     * on "This person is no longer here".
     *
     * Clearing the data is what makes it one-shot: the next `onCreate` finds a plain intent
     * and does nothing.
     */
    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "wishkaro") return
        when (uri.host) {
            "person" -> uri.pathSegments.firstOrNull()?.let(store::focusPerson)
            // M5. `wishkaro://wish/<personId>?event=<eventId>` — a notification's Send wish
            // or Send message action (FR-WSH-01). The event is optional: a key that did not
            // parse, or a widget tap, means "the next thing coming up for them".
            "wish" -> uri.pathSegments.firstOrNull()?.let { personId ->
                store.composeWishFor(personId, uri.getQueryParameter("event"))
            }
        }
        intent.data = null
        setIntent(intent)
    }

    /**
     * Put the bridges down, but only if they are still ours.
     *
     * `ActivityHolder` was already guarded this way and the other four were not. That
     * asymmetry was survivable while the Activity was never recreated — the old manifest's
     * `configChanges` list meant rotating and folding destroyed nothing. M4 removed that
     * list so FR-VIEW-07 has something real to prove, which makes this reachable by turning
     * the phone sideways.
     *
     * What goes wrong without the guard: a new MainActivity runs `onCreate` and installs its
     * own launchers into these globals, then the old one's `onDestroy` runs and sets them to
     * null. Every later tap on "Choose a file" then does nothing at all, with no error
     * anywhere, and force-stopping the app is the only cure. That is M3's seventh finding,
     * word for word (`M3_VERIFY.md` §10).
     *
     * The ordinary configuration-change sequence does run the old `onDestroy` first, so this
     * is belt and braces — but `launchMode="singleTask"` plus a deep link from a
     * notification or the widget is not the ordinary sequence, and M4 adds a second
     * deep-link entry point (FR-WID-02).
     */
    override fun onDestroy() {
        if (ActivityHolder.current === this) {
            ActivityHolder.current = null
            PermissionBridge.requester = null
            ContactsPermissionBridge.requester = null
            FilePickerBridge.opener = null
            FilePickerBridge.creator = null
        }
        super.onDestroy()
    }

    private companion object {
        const val PERMISSION_PREFS = "wishkaro.permissions"
        const val ASKED_FOR_CONTACTS = "asked_for_contacts"
    }
}
