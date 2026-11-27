package `in`.wishkaro.app.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween

/**
 * *"Slow and settled. ~300ms ease-out. Not springy, not bouncy."*
 *
 * Named, so that "settled" is a token and not a number retyped in forty places. Nothing in this
 * file is a spring: a spring with any visible bounce reads as playful, and this app's list holds
 * death anniversaries beside fifth birthdays.
 */
object WishKaroMotion {

    /** A state that must not look animated at all: a chip selecting, a switch moving. */
    const val Fast: Int = 120

    /** The default. Everything the user asked for and can see arriving. */
    const val Settled: Int = 300

    /** A whole screen's worth of change: the carousel's pages, a theme flip. */
    const val Slow: Int = 450

    /**
     * Decelerate, hard, with no overshoot.
     *
     * `(0.2, 0, 0, 1)` is Material's emphasised-decelerate shape. The last two control points
     * are both 0 and 1, which is what makes it arrive rather than settle *onto* its value — the
     * difference between "settled" and "bouncy" is entirely in this curve.
     */
    val EaseOutSettled: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Both ends eased, for something that moves and then stops in place. */
    val EaseInOutSettled: Easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

    fun <T> settled(durationMillis: Int = Settled): FiniteAnimationSpec<T> =
        tween(durationMillis = durationMillis, easing = EaseOutSettled)

    fun <T> fast(): FiniteAnimationSpec<T> = tween(durationMillis = Fast, easing = EaseOutSettled)
}
