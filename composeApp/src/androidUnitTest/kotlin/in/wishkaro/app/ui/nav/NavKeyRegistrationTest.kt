package `in`.wishkaro.app.ui.nav

import androidx.navigation3.runtime.NavKey
import kotlin.test.Test
import kotlin.test.fail

/**
 * Every destination is registered for saved state.
 *
 * This exists because of the nastiest failure mode Navigation 3 brings with it. The back
 * stack is written into saved state by serializing each `NavKey` polymorphically, and a
 * subclass that is not in the serializers module in `Screen.kt` **compiles, installs, and
 * navigates perfectly well**. It only fails when the stack is RESTORED — after a rotation,
 * a fold, or the system killing the process — and it fails there as a crash or a silently
 * emptied stack, on a device, in whichever milestone happens to add the destination.
 *
 * That is exactly the shape `MILESTONE_CHECKLIST.md` §D warns about: the thing that compiles
 * and passes every test and then throws on the first launch. So this test does not take a
 * list of destinations as input — it ASKS the sealed hierarchy what it contains, which means
 * adding a `Screen` and forgetting the registration is a red test rather than a field report.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
class NavKeyRegistrationTest {

    @Test
    fun `every Screen is registered in the saved-state serializers module`() {
        val module = WISHKARO_SAVED_STATE.serializersModule
        val subclasses = Screen::class.sealedSubclasses

        if (subclasses.isEmpty()) {
            fail("reflection found no Screen subclasses — this test is not testing anything")
        }

        val missing = subclasses.filter { subclass ->
            val serialName = subclass.qualifiedName
                ?: fail("$subclass has no qualified name")
            module.getPolymorphic(NavKey::class, serialName) == null
        }

        if (missing.isNotEmpty()) {
            fail(
                "These destinations are not in WISHKARO_SAVED_STATE's serializers module, so " +
                    "the back stack will not restore when one of them is on it:\n" +
                    missing.joinToString("\n") { "    subclass(${it.simpleName}::class)" } +
                    "\nAdd them to Screen.kt.",
            )
        }
    }

    /**
     * And the other direction: that the lookup above is actually capable of failing.
     *
     * A test that passes because `getPolymorphic` always returns something would be worse
     * than no test, so this pins a name that is deliberately not registered.
     */
    @Test
    fun `an unregistered name is not found`() {
        val module = WISHKARO_SAVED_STATE.serializersModule
        val found = module.getPolymorphic(NavKey::class, "in.wishkaro.app.ui.nav.Screen.NotAThing")
        if (found != null) {
            fail("getPolymorphic answers for anything, so the test above proves nothing")
        }
    }
}
