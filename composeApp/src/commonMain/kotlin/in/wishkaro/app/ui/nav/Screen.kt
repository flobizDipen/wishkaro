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

    /** FR-VIEW-02. A month at a glance, with a dot on every day that holds something. */
    @Serializable
    data object Calendar : Screen

    /** FR-VIEW-03. The year ahead, scrollable, empty months included. */
    @Serializable
    data object Timeline : Screen

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

    // ---- M5: wish sending (FR-WSH-01..06) ----

    /**
     * The composer (FR-WSH-01). Reachable from a notification action, an event card and the
     * person screen.
     *
     * `eventId` is nullable because two of the three entry points do not have one: the
     * person screen's button means "the next thing coming up for them", and so does a tap
     * from the widget. Null is resolved to the soonest upcoming event when the screen opens,
     * so the key stays small enough to live in saved state and survive process death.
     *
     * Note what is NOT on this key: the tone and the language. Those are composer state, they
     * change while the screen is open, and putting them in a `NavKey` would mean every tap of
     * a tone chip pushed a new back-stack entry.
     */
    @Serializable
    data class WishComposer(val personId: String, val eventId: String? = null) : Screen

    // ---- M6: AI posters (FR-POS-01..13) ----

    /**
     * The poster studio (FR-POS-01). Reachable from the person screen and from the composer.
     *
     * `eventId` is nullable for the same reason [WishComposer]'s is, and it is resolved the
     * same way: the person screen's button means "the next thing coming up for them".
     *
     * Note what is NOT on this key: the theme and the format. Those are studio state, they
     * change while the screen is open, and putting them in a `NavKey` would push a
     * back-stack entry on every tap of a swatch.
     */
    @Serializable
    data class PosterStudio(val personId: String, val eventId: String? = null) : Screen
}

/**
 * The destinations that own a back stack of their own.
 *
 * [Screen.SignIn] is deliberately not here: it is not somewhere you navigate to, it is what
 * the app shows while there is no session, and it must not be reachable by pressing back
 * from inside a signed-in app. `App.kt` branches on the auth state instead.
 *
 * Ordered, and the first is the start route — the one the user always exits through. The
 * order here is the order of the navigation bar and nothing else: the selected tab is saved
 * by name, so this list can be reordered freely.
 */
val TOP_LEVEL_ROUTES: List<Screen> = listOf(
    Screen.Upcoming,
    Screen.Calendar,
    Screen.Timeline,
    Screen.People,
)

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
        // A task, not a place. It owns the screen while it is open, it has one way out, and
        // offering to switch tabs in the middle of writing to somebody is offering to lose
        // what they wrote.
        is Screen.WishComposer,
        // Same reason as the composer: a task, not a place. It owns the screen while it is
        // open and offering to switch tabs halfway through making somebody's remembrance
        // poster is offering to lose it.
        is Screen.PosterStudio,
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
            subclass(Screen.Calendar::class)
            subclass(Screen.Timeline::class)
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
            subclass(Screen.WishComposer::class)
            subclass(Screen.PosterStudio::class)
        }
    }
}
