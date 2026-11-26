package `in`.wishkaro.app.ui.nav

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import `in`.wishkaro.app.ui.components.DateFieldsSaver
import `in`.wishkaro.app.ui.components.DateFieldsState
import `in`.wishkaro.app.ui.components.enumSaver
import `in`.wishkaro.app.domain.EventType
import org.junit.Rule
import org.junit.Test

/**
 * FR-VIEW-07, and definition-of-done item 5: state restoration has automated tests, not only
 * a device walkthrough.
 *
 * Two different things are proved here and they are not the same claim, so they get separate
 * tests. `StateRestorationTester` saves and restores the composition's saved state without
 * destroying anything — it is the fast check that the right things are being WRITTEN.
 * `scenario.recreate()` destroys and rebuilds the real Activity, which is the only thing
 * that catches what removing the manifest's `configChanges` list exposed (see
 * `AndroidManifest.xml` and `docs/M4_PLAN.md` §2.5).
 *
 * **These do not run in CI.** CI compiles both targets and runs unit tests on the JVM; it
 * has no device and no emulator. This suite is a local gate, run with
 * `./gradlew :composeApp:connectedDebugAndroidTest`, and `M4_VERIFY.md` says so rather than
 * implying item 5 is covered by a green pipeline.
 */
class NavigationRestorationTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** Two tabs, because one cannot show that a tab keeps its own history. */
    private val tabs = listOf(Screen.People, Screen.Deleted)

    /**
     * A host that renders the back stack as text, so an assertion can read it.
     *
     * Deliberately not the real `App()`: that needs Koin, a session and a network, and a
     * test that needs all three to tell you whether a back stack restored is a test that
     * will be deleted the first time it goes flaky for an unrelated reason.
     */
    @Composable
    private fun Host(onReady: (WishKaroNavigation) -> Unit) {
        val nav = rememberWishKaroNavigation(topLevelRoutes = tabs)
        remember(nav) { onReady(nav); nav }
        Text(
            text = nav.visibleKeys().joinToString(" > ") { describe(it) },
            modifier = Modifier.testTag("stack"),
        )
    }

    private fun describe(key: Any): String = when (key) {
        is Screen.PersonDetail -> "PersonDetail(${key.personId})"
        is Screen.EditPerson -> "EditPerson(${key.personId})"
        else -> key::class.simpleName.orEmpty()
    }

    @Test
    fun theBackStackSurvivesSavedStateRestore() {
        val restoration = StateRestorationTester(rule)
        lateinit var nav: WishKaroNavigation
        restoration.setContent { Host { nav = it } }

        rule.runOnIdle {
            nav.goTo(Screen.PersonDetail("p1"))
            nav.goTo(Screen.EditPerson("p1"))
        }
        rule.onNodeWithTag("stack")
            .assertTextEquals("People > PersonDetail(p1) > EditPerson(p1)")

        restoration.emulateSavedInstanceStateRestore()

        // Both entries AND their arguments. A stack that restores its shape but loses the
        // person id lands the user on a detail screen about nobody.
        rule.onNodeWithTag("stack")
            .assertTextEquals("People > PersonDetail(p1) > EditPerson(p1)")
    }

    @Test
    fun eachTabsOwnHistorySurvivesRestore() {
        val restoration = StateRestorationTester(rule)
        lateinit var nav: WishKaroNavigation
        restoration.setContent { Host { nav = it } }

        rule.runOnIdle {
            nav.goTo(Screen.PersonDetail("p1"))
            nav.goTo(Screen.Deleted)
            nav.goTo(Screen.EditPerson("p2"))
        }
        rule.onNodeWithTag("stack")
            .assertTextEquals("People > PersonDetail(p1) > Deleted > EditPerson(p2)")

        restoration.emulateSavedInstanceStateRestore()

        // The selected tab, the tab's own stack, AND the start route underneath it. The
        // People stack is not on screen but has to come back, or backing out of the second
        // tab lands somewhere the user never was.
        rule.onNodeWithTag("stack")
            .assertTextEquals("People > PersonDetail(p1) > Deleted > EditPerson(p2)")
    }

    @Test
    fun aHalfFilledFormSurvivesSavedStateRestore() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            var name by rememberSaveable { mutableStateOf("") }
            var type by rememberSaveable(stateSaver = enumSaver<EventType>()) {
                mutableStateOf(EventType.BIRTHDAY)
            }
            var date by rememberSaveable(stateSaver = DateFieldsSaver) {
                mutableStateOf(DateFieldsState())
            }

            // Set once, on first composition only, so the recreate below is restoring rather
            // than re-running this.
            remember {
                name = "Half Typed"
                type = EventType.WEDDING_ANNIVERSARY
                date = DateFieldsState(day = "29", month = "2", year = "", yearUnknown = true)
                Unit
            }

            Text(
                text = "$name|${type.name}|${date.day}/${date.month}|${date.yearUnknown}",
                modifier = Modifier.testTag("form"),
            )
        }

        rule.onNodeWithTag("form").assertTextEquals("Half Typed|WEDDING_ANNIVERSARY|29/2|true")

        restoration.emulateSavedInstanceStateRestore()

        // The enum and the date went through Savers written by hand (Fields.kt); the string
        // went through the automatic one. All three have to come back, and 29 February with
        // no year has to stay exactly that — it is a real birthday, not a validation error.
        // ActivityRecreationTest asks the same question of a real onDestroy/onCreate.
        rule.onNodeWithTag("form").assertTextEquals("Half Typed|WEDDING_ANNIVERSARY|29/2|true")
    }
}
