package `in`.wishkaro.app.ui.theme

import kotlin.math.pow

/**
 * WCAG 2.x contrast, in plain Kotlin.
 *
 * Deliberately Compose-free and deliberately not a test helper: `ThemeContrastTest` fails the
 * build on it, and `docs/M7_VERIFY.md`'s table is that test's printed output. A number that
 * lives only in a document is true on the day it is written; this one is true on every build.
 *
 * The maths is WCAG 2.1 §1.4.3 exactly — sRGB components linearised with the 0.04045 /
 * 2.4-gamma piecewise curve, weighted 0.2126 / 0.7152 / 0.0722, and `(L1 + 0.05) / (L2 + 0.05)`.
 * It is written out rather than approximated because a rounded-off version of this function
 * passes pairs that fail.
 *
 * **The alpha channel is ignored**, and that is a real limit rather than an oversight: every
 * value in [WishKaroPalette] is fully opaque, and a ratio against a translucent foreground
 * depends on what is behind it, which a palette cannot know. Anything this app draws at partial
 * alpha — Material's own disabled states, for instance — is outside what this measures, and
 * `M7_VERIFY.md` says so rather than implying the table covers it.
 */
object Contrast {

    /** Relative luminance of an opaque ARGB value. */
    fun luminance(argb: Long): Double {
        val r = channel((argb shr 16 and 0xFF).toInt())
        val g = channel((argb shr 8 and 0xFF).toInt())
        val b = channel((argb and 0xFF).toInt())
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    /** The contrast ratio between two opaque ARGB values, 1.0 to 21.0. */
    fun ratio(a: Long, b: Long): Double {
        val la = luminance(a)
        val lb = luminance(b)
        val lighter = maxOf(la, lb)
        val darker = minOf(la, lb)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun channel(value: Int): Double {
        val c = value / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
}

/** Two decimal places, without pulling a formatter into common code. */
fun Double.toRatioString(): String {
    val hundredths = kotlin.math.round(this * 100).toLong()
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
}
