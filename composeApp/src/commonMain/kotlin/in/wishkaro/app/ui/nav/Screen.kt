package `in`.wishkaro.app.ui.nav

import androidx.navigation3.runtime.NavKey
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.Serializable
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass

/**
 * Every place the user can be.
 *
 * Until M4 this was a `Screen` sealed interface on `AppStore`, a Koin singleton on the
 * Application, read through a `StateFlow` and dispatched on with a `when` in `App.kt`. That
 * had three properties M4 could not live with: one value cannot say "list and detail at the
 * same time" (FR-VIEW-06), nothing was saved across process death, and UI state on an
 * Application-scoped object outlives the Activity — which is the shape of two of M3's seven
 * findings. See `docs/M4_PLAN.md` §2.
 *
 * Now these are `NavKey`s on a Navigation 3 back stack that lives in Compose saved state.
 * The rule that comes with that, and it is the whole point: **nothing that drives navigation
 * lives on `AppStore` any more.**
 */
@Serializable
sealed interface Screen : NavKey {

    @Serializable
    data object SignIn : Screen

    /**
     * FR-VIEW-01. Upcoming events grouped Today / This week / This month / Later, and the
     * app's default — what a user opening WishKaro wants first is who is next, not an
     * alphabet.
     */
    @Serializable
    data object Upcoming : Screen

    /** FR-VIEW-04. The directory of people, as opposed to the list of events. */
    @Serializable
    data object People : Screen

    @Serializable
    data object AddPerson : Screen

    @Serializable
    data class PersonDetail(val personId: String) : Screen

    @Serializable
    data class EditPerson(val personId: String) : Screen

    /** `eventId == null` means a new event on that person. */
    @Serializable
    data class EventEditor(val personId: String, val eventId: String?) : Screen

    @Serializable
    data object Deleted : Screen

    @Serializable
    data object Settings : Screen

    // ---- M3: import (FR-IMP-01..08, FR-VIEW-05, FR-AUTH-05) ----

    @Serializable
    data object ImportStart : Screen

    @Serializable
    data object ImportContacts : Screen

    @Serializable
    data object ImportFile : Screen

    /** What happened, with a reason for every row that did not come in (FR-IMP-07). */
    @Serializable
    data object ImportResult : Screen
}

/**
 * The destinations that own a back stack of their own.
 *
 * [Screen.SignIn] is deliberately not here: it is not somewhere you navigate to, it is what
 * the app shows while there is no session, and it must not be reachable by pressing back
 * from inside a signed-in app. `App.kt` branches on the auth state instead.
 *
 * Ordered, and the first is the start route — the one the user always exits through. The
 * order is also what the selected tab is saved as, so inserting a route in the middle later
 * changes what a saved index means. Append rather than insert.
 */
val TOP_LEVEL_ROUTES: List<Screen> = listOf(Screen.Upcoming, Screen.People)

/**
 * Whether the bottom navigation stays on screen here.
 *
 * It does almost everywhere, and that is the point of per-tab back stacks: open somebody
 * from the People tab, go and look at Upcoming, come back, and they are still there. Hiding
 * the bar on a detail screen makes that history unreachable, which is a back stack per tab
 * that nobody can use.
 *
 * It goes away for the things that are TASKS rather than places — a form with a Cancel
 * button, or the import flow. Those have one way out, they own the screen while they are
 * open, and offering to switch tabs mid-way through typing somebody's birthday is offering
 * to lose it.
 */
val Screen.showsTabs: Boolean
    get() = when (this) {
        Screen.SignIn,
        Screen.AddPerson,
        is Screen.EditPerson,
        is Screen.EventEditor,
        Screen.ImportStart,
        Screen.ImportContacts,
        Screen.ImportFile,
        Screen.ImportResult,
        -> false

        else -> true
    }

/**
 * How the back stack is written into saved state.
 *
 * `rememberNavBackStack` supplies a default configuration on Android and **none** on
 * Kotlin/Native, so common code has to pass one. The serializers module below is what makes
 * a `Screen` survive process death.
 *
 * Every destination has to be registered by hand, and a missing one is a nasty failure
 * mode: it compiles, it installs, it navigates, and then it silently fails to RESTORE. So
 * `NavKeyRegistrationTest` enumerates `Screen`'s sealed subclasses and fails the build when
 * one of them is not in this list. Adding a destination without reading this file is
 * therefore a red test rather than a field report.
 */
val WISHKARO_SAVED_STATE: SavedStateConfiguration = SavedStateConfiguration {
    serializersModule = SerializersModule {
        polymorphic(NavKey::class) {
            subclass(Screen.SignIn::class)
            subclass(Screen.Upcoming::class)
            subclass(Screen.People::class)
            subclass(Screen.AddPerson::class)
            subclass(Screen.PersonDetail::class)
            subclass(Screen.EditPerson::class)
            subclass(Screen.EventEditor::class)
            subclass(Screen.Deleted::class)
            subclass(Screen.Settings::class)
            subclass(Screen.ImportStart::class)
            subclass(Screen.ImportContacts::class)
            subclass(Screen.ImportFile::class)
            subclass(Screen.ImportResult::class)
        }
    }
}
