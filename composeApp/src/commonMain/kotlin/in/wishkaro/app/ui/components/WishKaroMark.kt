package `in`.wishkaro.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import `in`.wishkaro.app.ui.theme.accent
import androidx.compose.material3.MaterialTheme

/**
 * WishKaro's mark: a calendar page with one day marked.
 *
 * ## Why it is drawn here as well as in `res/drawable`
 *
 * The launcher icon, the splash icon and this are the **same mark from the same numbers**, in
 * three places because three different systems have to draw it: a launcher process, the
 * platform's splash window, and Compose. A `commonMain` composable cannot reach an Android
 * drawable resource, and bundling it a second time as a Compose resource would be two copies of
 * one drawing that drift apart without anything noticing.
 *
 * So the geometry lives in [CalendarMarkGeometry] below, expressed in the adaptive icon's own
 * 108x108 coordinate space, and both this file and `ic_launcher_foreground.xml` lay out from
 * those numbers. They are checked against each other by eye once, in `M7_VERIFY.md`, and by
 * anybody who changes one and reads the other's comment.
 *
 * ## Why a calendar
 *
 * The first version of this was a diya, and Dipen's objection was the right test: *"I want
 * something so by just from icon you can recognise the app and its usage."* A flame says warmth
 * and festivity; it does not say **this app knows when the day is**, which is the product. A
 * calendar does, and the marked day is what the app is for.
 *
 * It is also not invented for the icon. `CalendarScreen` has drawn exactly this since M4 — a
 * grid of days with a dot on the ones that hold something, filled in the accent. The mark is
 * the app's own second tab at 48dp.
 *
 * ## Colour
 *
 * The header and the marked day take `MaterialTheme.accent`, so inside a `SolemnScope` the mark
 * desaturates like everything else. Nothing currently draws it inside one — it appears on
 * sign-in and on the carousel, neither of which is about a person — but a mark that would be
 * wrong there is a mark waiting to be used there.
 */
@Composable
fun WishKaroMark(
    modifier: Modifier = Modifier,
    size: Dp = 84.dp,
    accent: Color = MaterialTheme.accent,
    ink: Color = MaterialTheme.colorScheme.onSurface,
    page: Color = MaterialTheme.colorScheme.surface,
) {
    Canvas(
        modifier
            .size(size)
            // Decorative. The screens that use it say "WishKaro" in words directly underneath.
            .clearAndSetSemantics { },
    ) {
        val g = CalendarMarkGeometry
        /**
         * The drawable's 108x108 space, mapped onto this canvas **by the art's own bounds**
         * rather than by the viewport's.
         *
         * The launcher drawable's geometry fills the adaptive icon's 66x66 safe circle and no
         * more, which is correct there and wrong here: inside a 96dp box with no mask the same
         * numbers draw a calendar occupying half the width, floating in space. Caught on the
         * handset — the mark was right and looked under-set.
         *
         * So the art's bounding box (x 28..80, y 25..78) is scaled to the canvas with a small
         * margin, which is the ONE thing the in-app mark does differently from the icon. The
         * shapes and their proportions are identical.
         */
        val u = this.size.minDimension / g.artExtent
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        fun p(x: Float, y: Float) =
            Offset(cx + (x - g.artCentreX) * u, cy + (y - g.artCentreY) * u)
        fun roundRect(x0: Float, y0: Float, x1: Float, y1: Float, r: Float) =
            RoundRect(Rect(p(x0, y0), p(x1, y1)), CornerRadius(r * u, r * u))

        // Hangers, behind the page so they tuck under its top edge.
        g.hangerCentres.forEach { cx ->
            drawPath(
                Path().apply {
                    addRoundRect(
                        roundRect(
                            cx - g.hangerHalfWidth,
                            g.hangerTop,
                            cx + g.hangerHalfWidth,
                            g.hangerBottom,
                            g.hangerHalfWidth,
                        ),
                    )
                },
                ink,
            )
        }

        // The frame, then the page inside it. A fill-inside-a-fill rather than a stroke: a
        // stroke centres itself on the path, so half of it would land outside the geometry the
        // safe-zone arithmetic in `ic_launcher_foreground.xml` was done on.
        drawPath(
            Path().apply {
                addRoundRect(roundRect(g.frameLeft, g.frameTop, g.frameRight, g.frameBottom, 9f))
            },
            ink,
        )
        drawPath(
            Path().apply {
                addRoundRect(roundRect(g.pageLeft, g.pageTop, g.pageRight, g.pageBottom, 6f))
            },
            page,
        )

        // The header band: the page's own rounded top, cut off at the band's height.
        //
        // Intersecting the page's shape with a plain rectangle, rather than drawing a
        // separate rounded-top shape: the band then follows whatever radius the page has, so
        // changing `6f` in one place cannot leave a band whose corners no longer fit it.
        val headerClip = Path().apply {
            addRect(
                Rect(
                    p(g.pageLeft, g.pageTop),
                    p(g.pageRight, g.headerBottom),
                ),
            )
        }
        clipPath(headerClip, ClipOp.Intersect) {
            drawPath(
                Path().apply {
                    addRoundRect(roundRect(g.pageLeft, g.pageTop, g.pageRight, g.pageBottom, 6f))
                },
                accent,
            )
        }

        // Seven ordinary days, and the one that matters.
        g.dayCentres.forEach { (x, y) ->
            drawCircle(ink, radius = g.dayRadius * u, center = p(x, y))
        }
        drawCircle(accent, radius = g.markedRadius * u, center = p(g.markedX, g.markedY))
    }
}

/**
 * The mark's numbers, in the adaptive icon's 108x108 space.
 *
 * Kept as named values rather than inline so that the one place they can go wrong — somebody
 * nudging the Compose version and not the drawable — is at least visible as a diff in a file
 * whose whole content is those numbers.
 *
 * **Everything here sits inside the 66x66 safe circle** a launcher may crop to. The page's
 * rounded corners put their arc centres 22.7 units from the middle and add 9 of radius, giving
 * 31.7; the hanger tops are at 32.4. Both inside 33. Worked out rather than eyeballed, because
 * a circular launcher mask taking the hangers off a calendar is exactly the kind of thing that
 * looks fine in a preview.
 */
object CalendarMarkGeometry {
    const val hangerHalfWidth = 2.5f
    const val hangerTop = 25f
    const val hangerBottom = 36f
    val hangerCentres = listOf(42f, 66f)

    const val frameLeft = 28f
    const val frameTop = 30f
    const val frameRight = 80f
    const val frameBottom = 78f

    const val pageLeft = 32f
    const val pageTop = 34f
    const val pageRight = 76f
    const val pageBottom = 74f
    const val headerBottom = 47f

    const val dayRadius = 3f
    const val markedRadius = 6.5f
    const val markedX = 60f
    const val markedY = 66f

    /**
     * The art's own bounding box, for the in-app mark.
     *
     * x 28..80 and y 25..78, plus 3 units of margin so the hangers do not touch the edge of
     * whatever box the mark is drawn in. The launcher drawable does NOT use these — it lays
     * out in the raw 108x108 space, because that is what an adaptive icon's safe zone is
     * measured in.
     */
    const val artExtent = 59f
    const val artCentreX = 54f
    const val artCentreY = 51.5f

    /** Two rows of four, less the one the accent takes. */
    val dayCentres: List<Pair<Float, Float>> = buildList {
        for (y in listOf(56f, 66f)) {
            for (x in listOf(36f, 48f, 60f, 72f)) {
                if (!(x == markedX && y == markedY)) add(x to y)
            }
        }
    }
}
