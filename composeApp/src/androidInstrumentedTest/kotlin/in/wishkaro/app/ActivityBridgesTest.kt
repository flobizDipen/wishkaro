package `in`.wishkaro.app

import androidx.test.core.app.ActivityScenario
import `in`.wishkaro.app.platform.ActivityHolder
import `in`.wishkaro.app.platform.ContactsPermissionBridge
import `in`.wishkaro.app.platform.FilePickerBridge
import `in`.wishkaro.app.platform.PermissionBridge
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The seam M4 made far more dangerous, held to a test rather than a hope.
 *
 * `docs/M4_PLAN.md` §2.5 said this out loud before the manifest changed: dropping
 * `configChanges` means the Activity is rebuilt on every rotation, fold and resize, and the
 * `CompletableDeferred` bridges in `MainActivity` are exactly the shape that produced M3's
 * seventh finding — the import flow locking itself shut, with the picker silently refusing
 * to open and a force-stop the only cure.
 *
 * Two different failures live here and they need separate tests.
 *
 * 1. **A new Activity installs the bridges and the outgoing one nulls them.** The ordinary
 *    configuration-change sequence runs the old `onDestroy` first, so this is survivable —
 *    until `launchMode="singleTask"` plus a deep link from a notification or M4's widget
 *    makes the order something else. That is [bridgesPointAtTheLiveActivityAfterRecreation].
 *
 * 2. **The coroutine awaiting a pick belongs to an Application-scoped store, not to the
 *    Activity.** So the Activity can die and be rebuilt underneath it, and whatever
 *    completes the deferred afterwards has to be able to reach the SAME deferred. That is
 *    [aPendingPickSurvivesTheActivityBeingRebuilt].
 *
 * Device-only, like the rest of the instrumented suite.
 */
class ActivityBridgesTest {

    @Test
    fun bridgesPointAtTheLiveActivityAfterRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var first: Any
            scenario.onActivity { activity ->
                first = activity
                assertSame(activity, ActivityHolder.current, "the holder should point at this one")
                assertNotNull(FilePickerBridge.opener, "picker opener installed")
                assertNotNull(FilePickerBridge.creator, "picker creator installed")
                assertNotNull(PermissionBridge.requester, "notification permission installed")
                assertNotNull(ContactsPermissionBridge.requester, "contacts permission installed")
            }

            scenario.recreate()

            scenario.onActivity { activity ->
                assertNotSame(first, activity, "recreate() should have produced a new Activity")

                // The one that matters. Without the identity guard in onDestroy, the
                // outgoing Activity sets all four of these to null AFTER the new one has
                // installed its own — and from then on every tap on "Choose a file" does
                // nothing at all, with no error anywhere.
                assertSame(activity, ActivityHolder.current, "the holder follows the new Activity")
                assertNotNull(FilePickerBridge.opener, "picker opener survived recreation")
                assertNotNull(FilePickerBridge.creator, "picker creator survived recreation")
                assertNotNull(PermissionBridge.requester, "notification permission survived")
                assertNotNull(ContactsPermissionBridge.requester, "contacts permission survived")
            }
        }
    }

    @Test
    fun aPendingPickSurvivesTheActivityBeingRebuilt() = runTest {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // What AndroidFileGateway.pickSpreadsheet does: park a deferred where the
            // launcher callback can find it, then await it from a coroutine that is NOT on
            // the Activity.
            val pending = CompletableDeferred<android.net.Uri?>()
            FilePickerBridge.pendingOpen.deferred = pending

            scenario.recreate()

            // The system hands the picker's result to whichever Activity is alive, and its
            // callback calls complete(). The holder is global precisely so that the result
            // reaches the deferred the old Activity's flow is still waiting on.
            scenario.onActivity { FilePickerBridge.pendingOpen.complete(null) }

            // A `finally` cannot help a coroutine that never resumes — M3's seventh finding.
            // So the assertion is that it DOES resume, within a bound.
            withTimeout(5.seconds) { pending.await() }
            assertTrue(pending.isCompleted, "the awaiting coroutine was resumed")
        }
    }
}
