package `in`.wishkaro.app.ui.theme

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The contrast rule, as a build gate rather than as a paragraph.
 *
 * `BUILD_PROMPT_M7.md`: *"Measure every foreground/background pair you ship, in both themes, and
 * record the numbers in M7_VERIFY.md as a table. Do not report 'checked' — report figures. Where
 * a pair fails, darken the token. Do not trust the figure above. Compute it."*
 *
 * A table in a verification document is true on the day it is written and silently false after
 * the next colour change. So the figures live here: this test computes them from
 * [WishKaroPalette] — the same constants `Color.kt` hands Material — fails the build below the
 * threshold, and **prints the table that `M7_VERIFY.md` quotes**. Run it with
 * `./gradlew :composeApp:testDebugUnitTest --tests '*ThemeContrastTest*' -i` to regenerate.
 *
 * ## The thresholds, and why each pair has the one it has
 *
 * | Class | Minimum | Source |
 * |---|---|---|
 * | text on its background | **4.5:1** | WCAG 2.1 §1.4.3 AA, normal text |
 * | UI component boundary | **3.0:1** | WCAG 2.1 §1.4.11 |
 * | decorative divider | **1.5:1** | not an AA obligation; recorded so a change is visible |
 *
 * Nothing in this app is held only to the **large-text** 3:1 allowance, and that is the
 * substantive decision in this file. The prompt proposed restricting the accent to large text;
 * `main` already draws eight pieces of text in `colorScheme.primary`, seven of them below
 * WCAG's large threshold (18.66sp bold / 24sp regular). Honouring the allowance would have
 * meant rewriting those seven to stop using the accent, which removes the identity from every
 * list in the app. Darkening the token instead costs one hex value. `docs/M7_PLAN.md` §1.1.
 *
 * ## What this does NOT measure, stated rather than implied
 *
 * **Alpha.** Every value in the palette is opaque; Material draws some of its own states at
 * partial alpha (disabled content at 38%, for one) and those composites are not covered here.
 * **Gradients.** The poster preview paints a server-supplied gradient from `PosterTheme`, whose
 * colours are the server's and are not in this palette.
 * **Anything drawn by another app.** The share sheet, the system photo picker, WhatsApp.
 */
class ThemeContrastTest {

    private data class Pair(
        val foreground: String,
        val background: String,
        val minimum: Double,
        val why: String,
    )

    /**
     * Every pair this app actually draws.
     *
     * Derived by reading all 17 screens plus the widget rather than by enumerating the scheme:
     * a generated cross-product would be 1,200 pairs, most of which nothing draws, and a table
     * nobody reads is the same as no table. Where a row is here, something on screen puts that
     * foreground on that background — the `why` says what.
     */
    private val pairs = listOf(
        // ---- text on the ground and on cards -------------------------------------------
        Pair("onSurface", "surface", 4.5, "every body line, on paper"),
        Pair("onBackground", "background", 4.5, "the same, outside a Surface"),
        Pair("onSurface", "surfaceContainer", 4.5, "a person's name on a list card"),
        Pair("onSurface", "surfaceContainerLow", 4.5, "text on a low-elevation card"),
        Pair("onSurface", "surfaceContainerHigh", 4.5, "text on the expanded-width side sheet"),
        Pair("onSurface", "surfaceContainerHighest", 4.5, "text on a menu / the highest container"),
        Pair("onSurface", "surfaceVariant", 4.5, "a name on a REMEMBRANCE card"),
        Pair("onSurfaceVariant", "surface", 4.5, "the second line of every list row"),
        Pair("onSurfaceVariant", "surfaceContainer", 4.5, "the same, on a card"),
        Pair("onSurfaceVariant", "surfaceVariant", 4.5, "the same, on a remembrance card"),
        Pair("onSurfaceVariant", "surfaceContainerHighest", 4.5, "secondary text at the top layer"),

        // ---- the accent AS TEXT, which is the pair the whole milestone turns on ---------
        Pair("primary", "surface", 4.5, "section headers, the calendar's day heading"),
        Pair("primary", "surfaceContainer", 4.5, "the countdown on an upcoming card"),
        Pair("primary", "surfaceVariant", 4.5, "an accent line that lands on a remembrance card"),
        Pair("primary", "secondaryContainer", 4.5, "the countdown on a deep-linked, highlighted card"),
        Pair("primary", "primaryContainer", 4.5, "accent text inside an accent container"),

        // ---- the solemn accent, same list, because it is the same call sites -----------
        Pair("solemnAccent", "surface", 4.5, "a remembrance countdown on paper"),
        Pair("solemnAccent", "surfaceContainer", 4.5, "a remembrance countdown on a card"),
        Pair("solemnAccent", "surfaceVariant", 4.5, "a remembrance countdown on a remembrance card"),
        Pair("solemnAccent", "secondaryContainer", 4.5, "a remembrance card reached from a widget tap"),

        // ---- the solemn PRIMARY FAMILY, which `SolemnScope` swaps into the scheme --------
        //
        // Added after item 9's screenshot pair showed what a composition local alone could not
        // reach: a full-width SAFFRON "Send a message" button, and a saffron reminder switch,
        // on a death anniversary's screen. `Button` reads `colorScheme.primary`, so the scope
        // now replaces the family rather than providing a local beside it — which means these
        // four pairs are shipped and have to be measured like any others.
        Pair("onSolemnAccent", "solemnAccent", 4.5, "\"Send a message\" on a remembrance screen"),
        Pair("onSolemnContainer", "solemnContainer", 4.5, "a selected chip inside a solemn scope"),
        Pair("solemnAccent", "solemnContainer", 4.5, "accent text on its own solemn container"),
        Pair("onSurface", "solemnContainer", 4.5, "ink on a solemn container"),

        // ---- fills with a label on them ------------------------------------------------
        Pair("onPrimary", "primary", 4.5, "every filled Button: Save, Import, Send a wish"),
        Pair("onPrimaryContainer", "primaryContainer", 4.5, "the calendar's selected day"),
        Pair("onSecondary", "secondary", 4.5, "the secondary fill"),
        Pair("onSecondaryContainer", "secondaryContainer", 4.5, "the navigation bar's selected item"),
        Pair("onTertiary", "tertiary", 4.5, "the tertiary fill"),
        Pair("onTertiaryContainer", "tertiaryContainer", 4.5, "the tertiary container"),
        Pair("onSurface", "primaryContainer", 4.5, "ink on an accent fill"),
        Pair("onSurface", "secondaryContainer", 4.5, "ink on a highlighted card"),

        // ---- error ---------------------------------------------------------------------
        Pair("error", "surface", 4.5, "\"Remove <name>\", the quiet-hours clash note"),
        Pair("error", "surfaceContainer", 4.5, "a failed row in the import preview"),
        Pair("onError", "error", 4.5, "a label on the error fill"),
        Pair("onErrorContainer", "errorContainer", 4.5, "the blocked-records banner"),

        // ---- the snackbar, which is the one inverted surface in the app ----------------
        Pair("inverseOnSurface", "inverseSurface", 4.5, "every message the app shows"),
        Pair("inversePrimary", "inverseSurface", 4.5, "a snackbar action label"),

        // ---- UI components, 1.4.11 rather than 1.4.3 -----------------------------------
        Pair("outline", "surface", 3.0, "an OutlinedTextField's border; a remembrance dot"),
        Pair("outline", "surfaceContainer", 3.0, "the same, on a card"),
        Pair("outline", "surfaceVariant", 3.0, "the same, on a remembrance card"),
        Pair("primary", "surfaceContainerLow", 3.0, "the ring on a selected calendar day"),

        // ---- decorative ----------------------------------------------------------------
        Pair("outlineVariant", "surface", 1.5, "the timeline's month dividers"),
        Pair("outlineVariant", "surfaceContainer", 1.5, "a divider on a card"),
    )

    /**
     * Pairs that are **measured and reported but not gated**, each with the reason.
     *
     * Being explicit about these is the point. A silent omission is how a contrast table comes
     * to describe something other than what is on screen.
     */
    private val recordedOnly = listOf(
        Pair("primaryContainer", "surface", 0.0,
            "the calendar's SELECTED day cell against the grid. ~1.2:1 in light, and it stays " +
                "that way: a pale fill at 3:1 against ivory is not a pale fill, and \"warm " +
                "paper\" is pale fills. The state is carried instead by a border drawn in " +
                "`primary` — a 1.4.11 element, measured above at 5.93:1 — so selection does " +
                "not depend on this ratio."),
        Pair("secondaryContainer", "surface", 0.0,
            "the TODAY cell, and the highlighted card a notification or widget tap lands on. " +
                "Same reasoning, same border."),
    )

    private fun WarmPaper.get(name: String): Long = when (name) {
        "primary" -> primary
        "onPrimary" -> onPrimary
        "primaryContainer" -> primaryContainer
        "onPrimaryContainer" -> onPrimaryContainer
        "secondary" -> secondary
        "onSecondary" -> onSecondary
        "secondaryContainer" -> secondaryContainer
        "onSecondaryContainer" -> onSecondaryContainer
        "tertiary" -> tertiary
        "onTertiary" -> onTertiary
        "tertiaryContainer" -> tertiaryContainer
        "onTertiaryContainer" -> onTertiaryContainer
        "error" -> error
        "onError" -> onError
        "errorContainer" -> errorContainer
        "onErrorContainer" -> onErrorContainer
        "background" -> background
        "onBackground" -> onBackground
        "surface" -> surface
        "onSurface" -> onSurface
        "surfaceVariant" -> surfaceVariant
        "onSurfaceVariant" -> onSurfaceVariant
        "surfaceDim" -> surfaceDim
        "surfaceBright" -> surfaceBright
        "surfaceContainerLowest" -> surfaceContainerLowest
        "surfaceContainerLow" -> surfaceContainerLow
        "surfaceContainer" -> surfaceContainer
        "surfaceContainerHigh" -> surfaceContainerHigh
        "surfaceContainerHighest" -> surfaceContainerHighest
        "outline" -> outline
        "outlineVariant" -> outlineVariant
        "scrim" -> scrim
        "inverseSurface" -> inverseSurface
        "inverseOnSurface" -> inverseOnSurface
        "inversePrimary" -> inversePrimary
        "solemnAccent" -> solemnAccent
        "onSolemnAccent" -> onSolemnAccent
        "solemnContainer" -> solemnContainer
        "onSolemnContainer" -> onSolemnContainer
        else -> throw IllegalArgumentException("no such role: $name")
    }

    private fun hex(value: Long): String {
        val digits = "0123456789ABCDEF"
        val rgb = value and 0xFFFFFF
        val out = StringBuilder("#")
        for (shift in listOf(20, 16, 12, 8, 4, 0)) {
            out.append(digits[((rgb shr shift) and 0xF).toInt()])
        }
        return out.toString()
    }

    private fun check(themeName: String, palette: WarmPaper): List<String> {
        val failures = mutableListOf<String>()
        println("")
        println("### $themeName")
        println("")
        println("| Foreground | Background | Measured | Minimum | Where it is drawn |")
        println("|---|---|---|---|---|")
        for (pair in pairs) {
            val fg = palette.get(pair.foreground)
            val bg = palette.get(pair.background)
            val measured = Contrast.ratio(fg, bg)
            val pass = measured >= pair.minimum
            if (!pass) {
                failures += "$themeName: ${pair.foreground} on ${pair.background} is " +
                    "${measured.toRatioString()}:1, below ${pair.minimum} — ${pair.why}"
            }
            println(
                "| `${pair.foreground}` ${hex(fg)} | `${pair.background}` ${hex(bg)} | " +
                    "**${measured.toRatioString()}:1**${if (pass) "" else " ❌"} | " +
                    "${pair.minimum} | ${pair.why} |",
            )
        }
        println("")
        println("Measured but not gated:")
        println("")
        println("| Foreground | Background | Measured | Why it is not a gate |")
        println("|---|---|---|---|")
        for (pair in recordedOnly) {
            val fg = palette.get(pair.foreground)
            val bg = palette.get(pair.background)
            println(
                "| `${pair.foreground}` ${hex(fg)} | `${pair.background}` ${hex(bg)} | " +
                    "${Contrast.ratio(fg, bg).toRatioString()}:1 | ${pair.why} |",
            )
        }
        return failures
    }

    @Test
    fun every_shipped_pair_meets_its_threshold_in_both_themes() {
        val failures =
            check("Light — warm paper", WishKaroPalette.LIGHT) +
                check("Dark — ink", WishKaroPalette.DARK)

        assertTrue(
            failures.isEmpty(),
            "Contrast below threshold:\n" + failures.joinToString("\n") { "  - $it" },
        )
    }

    /**
     * The two values the direction was stated with, measured.
     *
     * This test does not fail — it asserts that they are below 4.5:1, which is **why** the
     * shipped tokens are darker. If a later change ever makes `#C8742A` legible on ivory, this
     * test goes red and tells whoever did it that the reason for the darker token is gone.
     */
    @Test
    fun the_directions_stated_accents_are_the_reason_the_shipped_ones_are_darker() {
        val ivory = WishKaroPalette.LIGHT.background
        val statedSaffron = Contrast.ratio(WishKaroPalette.REFERENCE_SAFFRON, ivory)
        val statedSolemn = Contrast.ratio(WishKaroPalette.REFERENCE_SOLEMN, ivory)

        println("stated saffron #C8742A on ivory: ${statedSaffron.toRatioString()}:1")
        println("stated solemn  #8A8578 on ivory: ${statedSolemn.toRatioString()}:1")
        println(
            "shipped saffron on ivory: " +
                Contrast.ratio(WishKaroPalette.LIGHT.primary, ivory).toRatioString() + ":1",
        )
        println(
            "shipped solemn  on ivory: " +
                Contrast.ratio(WishKaroPalette.LIGHT.solemnAccent, ivory).toRatioString() + ":1",
        )

        assertTrue(
            statedSaffron < 4.5,
            "#C8742A now measures ${statedSaffron.toRatioString()}:1 on ivory. If that is " +
                "genuinely >= 4.5 the shipped accent no longer needs to be darker — see " +
                "WishKaroPalette.REFERENCE_SAFFRON and docs/M7_PLAN.md §1.1.",
        )
        assertTrue(statedSolemn < 4.5, "#8A8578 now measures ${statedSolemn.toRatioString()}:1")
    }
}
