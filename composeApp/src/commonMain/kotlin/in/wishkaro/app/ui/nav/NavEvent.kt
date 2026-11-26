package `in`.wishkaro.app.ui.nav

/**
 * A navigation that something finishing asked for.
 *
 * `MILESTONE_CHECKLIST.md`, bought by M3 §10.1: *a one-shot navigation needs a one-shot
 * signal, not a persistent value — state that outlives the screen outlives the Activity
 * too.* M3 fixed two instances of that at the call site. This is the same rule made
 * structural: these events go through a `Channel`, so each one is delivered exactly once and
 * an Activity coming back from the dead cannot replay the last one.
 *
 * Only navigation that happens INSIDE one of `AppStore`'s own coroutines belongs here —
 * after a write lands, after a sign-in completes, when a deep link arrives. Navigation the
 * user asks for by tapping something goes straight to [WishKaroNavigation] from the call
 * site, because a tap is already one-shot.
 */
sealed interface NavEvent {

    /** Up one, the same as pressing back. */
    data object Back : NavEvent

    /** Push, or switch tab if it is a top-level route. */
    data class Go(val screen: Screen) : NavEvent

    /** Replace a tab's history. The first entry becomes its root. */
    data class ResetTo(val screens: List<Screen>) : NavEvent

    /** Every tab back to its root, nothing kept. Sign-out only. */
    data object ResetAll : NavEvent
}
