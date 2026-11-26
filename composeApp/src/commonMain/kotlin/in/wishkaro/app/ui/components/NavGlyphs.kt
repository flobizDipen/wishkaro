package `in`.wishkaro.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * The bottom navigation's icons, drawn rather than imported.
 *
 * `androidx.compose.material:material-icons-core` is Android-only, and the multiplatform
 * `org.jetbrains.compose.material:material-icons-core` stopped at 1.7.3 — this project is on
 * Compose Multiplatform 1.11.1. The remaining option is `materialIconsExtended`, which is
 * several megabytes of vectors to use four of.
 *
 * Four shapes is less code than the workaround would be, and they are simple on purpose:
 * a navigation bar's icons are read at a glance at 24dp, where a list is three bars and a
 * calendar is a grid. The labels carry the meaning; these carry the scanning.
 */

private const val STROKE_DP = 1.8f

@Composable
fun UpcomingGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp)) {
        val stroke = Stroke(width = STROKE_DP.dp.toPx())
        val left = size.width * 0.18f
        val right = size.width * 0.82f
        val dotX = size.width * 0.12f
        // Three rows, each a dot and a rule: a list.
        listOf(0.3f, 0.5f, 0.7f).forEach { fraction ->
            val y = size.height * fraction
            drawCircle(tint, radius = 1.6.dp.toPx(), center = Offset(dotX, y))
            drawLine(tint, Offset(left + 2.dp.toPx(), y), Offset(right, y), strokeWidth = stroke.width)
        }
    }
}

@Composable
fun CalendarGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp)) {
        val stroke = STROKE_DP.dp.toPx()
        val inset = size.width * 0.14f
        val top = size.height * 0.22f
        val box = Size(size.width - inset * 2, size.height - top - inset)
        drawRoundRect(
            tint,
            topLeft = Offset(inset, top),
            size = box,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()),
            style = Stroke(width = stroke),
        )
        // The header rule, and two hanger marks above it.
        val headerY = top + box.height * 0.3f
        drawLine(tint, Offset(inset, headerY), Offset(inset + box.width, headerY), strokeWidth = stroke)
        listOf(0.3f, 0.7f).forEach { f ->
            val x = inset + box.width * f
            drawLine(tint, Offset(x, top - 3.dp.toPx()), Offset(x, top + 1.dp.toPx()), strokeWidth = stroke)
        }
        // One filled day, so it reads as a date rather than a window.
        drawCircle(
            tint,
            radius = 1.8.dp.toPx(),
            center = Offset(inset + box.width * 0.33f, headerY + box.height * 0.42f),
        )
    }
}

@Composable
fun TimelineGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp)) {
        val stroke = STROKE_DP.dp.toPx()
        val x = size.width * 0.28f
        drawLine(
            tint,
            Offset(x, size.height * 0.18f),
            Offset(x, size.height * 0.82f),
            strokeWidth = stroke,
        )
        // Three moments hanging off one year.
        listOf(0.26f, 0.5f, 0.74f).forEach { f ->
            val y = size.height * f
            drawCircle(tint, radius = 2.2.dp.toPx(), center = Offset(x, y))
            drawLine(
                tint,
                Offset(x + 4.dp.toPx(), y),
                Offset(size.width * 0.86f, y),
                strokeWidth = stroke,
            )
        }
    }
}

@Composable
fun PeopleGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp)) {
        val stroke = Stroke(width = STROKE_DP.dp.toPx())
        // One head and shoulders in front, a second behind it.
        drawCircle(tint, radius = 3.4.dp.toPx(), center = Offset(size.width * 0.40f, size.height * 0.34f), style = stroke)
        drawArc(
            tint,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(size.width * 0.16f, size.height * 0.52f),
            size = Size(size.width * 0.48f, size.height * 0.40f),
            style = stroke,
        )
        drawCircle(tint, radius = 2.6.dp.toPx(), center = Offset(size.width * 0.70f, size.height * 0.36f), style = stroke)
        drawArc(
            tint,
            startAngle = 200f,
            sweepAngle = 140f,
            useCenter = false,
            topLeft = Offset(size.width * 0.54f, size.height * 0.54f),
            size = Size(size.width * 0.38f, size.height * 0.34f),
            style = stroke,
        )
    }
}
