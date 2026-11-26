package `in`.wishkaro.app.ui.nav

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import `in`.wishkaro.app.domain.EventType
import `in`.wishkaro.app.ui.components.DateFieldsSaver
import `in`.wishkaro.app.ui.components.DateFieldsState
import `in`.wishkaro.app.ui.components.enumSaver
import org.junit.Rule
import org.junit.Test

/**
 * The Activity really is destroyed and really is rebuilt.
 *
 * `StateRestorationTester` (NavigationRestorationTest) saves and restores the composition's
 * saved state without destroying anything, which checks that the right values are being
 * WRITTEN. It cannot catch state that survives recreation by never being touched by it —
 * and that is the precise failure M4 set out to remove: a back stack on an
 * Application-scoped Koin singleton passes a StateRestorationTester-shaped test and loses
 * everything on process death.
 *
 * So these go through `ActivityScenario.recreate()`, which is onDestroy followed by
 * onCreate on the real thing, and is what the manifest now does on every rotation, fold,
 * resize and multi-window change (`AndroidManifest.xml`, `docs/M4_PLAN.md` §2.5).
 *
 * Device-only. `./gradlew :composeApp:connectedDebugAndroidTest`. CI does not run these.
 */
class ActivityRecreationTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val tabs = listOf(Screen.People, Screen.Deleted)

    @Test
    fun theBackStackSurvivesTheActivityBeingDestroyedAndRebuilt() {
        lateinit var nav: WishKaroNavigation

        RestorationHostActivity.content = {
            val navigation = rememberWishKaroNavigation(topLevelRoutes = tabs)
            remember(navigation) { nav = navigation; navigation }
            Text(
                text = navigation.visibleKeys().joinToString(" > ") {
                    if (it is Screen.PersonDetail) "PersonDetail(${it.personId})"
                    else it::class.simpleName.orEmpty()
                },
                modifier = Modifier.testTag("stack"),
            )
        }

        ActivityScenario.launch(RestorationHostActivity::class.java).use { scenario ->
            compose.runOnIdle { nav.goTo(Screen.PersonDetail("p7")) }
            compose.onNodeWithTag("stack").assertTextEquals("People > PersonDetail(p7)")

            scenario.recreate()

            compose.onNodeWithTag("stack").assertTextEquals("People > PersonDetail(p7)")
        }
    }

    @Test
    fun theSelectedTabAndTheOtherTabsHistoryBothSurviveRecreation() {
        lateinit var nav: WishKaroNavigation

        RestorationHostActivity.content = {
            val navigation = rememberWishKaroNavigation(topLevelRoutes = tabs)
            remember(navigation) { nav = navigation; navigation }
            Text(
                text = navigation.visibleKeys().joinToString(" > ") {
                    if (it is Screen.EditPerson) "EditPerson(${it.personId})"
                    else if (it is Screen.PersonDetail) "PersonDetail(${it.personId})"
                    else it::class.simpleName.orEmpty()
                },
                modifier = Modifier.testTag("stack"),
            )
        }

        ActivityScenario.launch(RestorationHostActivity::class.java).use { scenario ->
            compose.runOnIdle {
                nav.goTo(Screen.PersonDetail("p1"))
                nav.goTo(Screen.Deleted)
                nav.goTo(Screen.EditPerson("p2"))
            }

            scenario.recreate()

            // The second tab is on screen, and the first tab's history is underneath it —
            // if only the visible one came back, backing out would land somewhere the user
            // has never been.
            compose.onNodeWithTag("stack")
                .assertTextEquals("People > PersonDetail(p1) > Deleted > EditPerson(p2)")
        }
    }

    @Test
    fun aHalfFilledFormSurvivesTheActivityBeingDestroyedAndRebuilt() {
        RestorationHostActivity.content = {
            var name by rememberSaveable { mutableStateOf("") }
            var type by rememberSaveable(stateSaver = enumSaver<EventType>()) {
                mutableStateOf(EventType.BIRTHDAY)
            }
            var date by rememberSaveable(stateSaver = DateFieldsSaver) {
                mutableStateOf(DateFieldsState())
            }

            // First composition only, so the recreation below is restoring rather than
            // re-running this.
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

        ActivityScenario.launch(RestorationHostActivity::class.java).use { scenario ->
            compose.onNodeWithTag("form")
                .assertTextEquals("Half Typed|WEDDING_ANNIVERSARY|29/2|true")

            scenario.recreate()

            // Until M4 not one form in this app used rememberSaveable, and the manifest's
            // configChanges list meant nothing ever noticed. The enum and the date go
            // through hand-written Savers; 29 February with no year has to come back as
            // exactly that, because it is a real birthday (FR-EVT-09).
            compose.onNodeWithTag("form")
                .assertTextEquals("Half Typed|WEDDING_ANNIVERSARY|29/2|true")
        }
    }
}
