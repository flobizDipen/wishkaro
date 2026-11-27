package `in`.wishkaro.app.ui.theme

/**
 * "Warm paper" — every colour this app draws, as plain ARGB numbers.
 *
 * ## Why this file holds numbers and not `Color`s
 *
 * `ThemeContrastTest` has to compute a WCAG ratio for every pair the app ships, and it runs in
 * `commonTest`, which is a different compilation from `commonMain`. Keeping the values as
 * `Long`s in a Compose-free file means the test measures **the same constants Material is
 * handed** rather than a second copy written out beside it. `Color.kt` turns them into a
 * `ColorScheme` and does nothing else, so there is one definition of every colour in the app.
 *
 * ## Where the values come from, and what moved
 *
 * The direction is Dipen's, chosen 2026-11-27 over "Soft modern" and "Festive gradient", and it
 * answers **TBD-5 / OPEN_DECISIONS A3**, open since M4. It is not this milestone's to reopen:
 * ivory ground, ink text, saffron accent, and a **desaturated** accent for remembrance —
 * deliberately the same design rather than a second one, because the same list holds a
 * five-year-old's birthday and a death anniversary.
 *
 * Two of the four given hex values had to move, and the prompt says so in advance: *"Where a
 * pair fails, darken the token. The direction survives a darker saffron; it does not survive
 * being unreadable."* Measured:
 *
 * | Given | As text on ivory | Under white |
 * |---|---|---|
 * | `#C8742A` saffron | **3.39:1** | **3.51:1** |
 * | `#8A8578` solemn | **3.56:1** | 3.63:1 |
 *
 * Both fail 4.5:1, and the saffron fails as a *fill* as well as as text — a white label on a
 * `#C8742A` button is 3.51:1. The accent could not simply be restricted to large text either:
 * `main` already uses `colorScheme.primary` as the colour of eight pieces of TEXT, seven of them
 * below WCAG's large-text threshold (`docs/M7_PLAN.md` §1.1 lists all eight). Restricting it
 * would have removed the identity from every list in the app.
 *
 * So the accent keeps its hue (28°) and its saturation (65%) and loses lightness: 0.475 → 0.34.
 * [REFERENCE_SAFFRON] is kept below as the direction's stated value; nothing draws with it.
 *
 * ## The rule this file is held to
 *
 * `ThemeContrastTest` fails the build when any shipped text pair drops below 4.5:1 or any UI
 * boundary below 3.0:1, in **either** theme. The numbers in `docs/M7_VERIFY.md` are that test's
 * own output, so the document cannot drift from the code.
 */
object WishKaroPalette {

    /**
     * The direction's stated saffron, `#C8742A`.
     *
     * Documented, never drawn: 3.39:1 on ivory. The light theme ships [WarmPaper.LIGHT]'s
     * `primary`, the same hue at a lower lightness; the dark theme ships a lightening of this
     * value, which on an ink ground has contrast to spare.
     */
    const val REFERENCE_SAFFRON: Long = 0xFFC8742A

    /** The direction's stated solemn accent, `#8A8578`. Documented, never drawn: 3.56:1. */
    const val REFERENCE_SOLEMN: Long = 0xFF8A8578

    /**
     * Light — paper.
     *
     * `surface` and `background` are the same ivory on purpose. Material's elevation overlays
     * do the separating, and a "paper" ground that is two different whites is a ground with a
     * seam in it.
     */
    val LIGHT = WarmPaper(
        primary = 0xFF8F531E,
        onPrimary = 0xFFFFFFFF,
        primaryContainer = 0xFFF7DFC3,
        onPrimaryContainer = 0xFF40210A,

        secondary = 0xFF6B5B45,
        onSecondary = 0xFFFFFFFF,
        secondaryContainer = 0xFFF0E3CF,
        onSecondaryContainer = 0xFF2A1E10,

        tertiary = 0xFF4F6152,
        onTertiary = 0xFFFFFFFF,
        tertiaryContainer = 0xFFD5E2D6,
        onTertiaryContainer = 0xFF0D1F12,

        error = 0xFFA32015,
        onError = 0xFFFFFFFF,
        errorContainer = 0xFFFBDAD4,
        onErrorContainer = 0xFF410500,

        background = 0xFFFDFBF7,
        onBackground = 0xFF15131C,
        surface = 0xFFFDFBF7,
        onSurface = 0xFF15131C,
        surfaceVariant = 0xFFEDE7DA,
        onSurfaceVariant = 0xFF55514A,

        surfaceDim = 0xFFE6E2DA,
        surfaceBright = 0xFFFDFBF7,
        surfaceContainerLowest = 0xFFFFFFFF,
        surfaceContainerLow = 0xFFFAF6EF,
        surfaceContainer = 0xFFF5F1E8,
        surfaceContainerHigh = 0xFFEFEAE0,
        surfaceContainerHighest = 0xFFE9E4D9,

        outline = 0xFF7A756A,
        // Darkened from a first pass at `#D5CFC2`, which the contrast test caught: it was
        // 1.50:1 against ivory — exactly on the line — and **1.38:1 against a card**, where
        // most of this app's dividers actually sit. A divider nobody can see is a divider that
        // is not doing its job, and the pair that failed was the one on the surface the
        // timeline and the import preview use.
        outlineVariant = 0xFFC0BAAA,
        scrim = 0xFF000000,

        inverseSurface = 0xFF302D27,
        inverseOnSurface = 0xFFF5F1E8,
        inversePrimary = 0xFFE8B07A,

        solemnAccent = 0xFF67635A,
        onSolemnAccent = 0xFFFFFFFF,
        solemnContainer = 0xFFE4E1D7,
        onSolemnContainer = 0xFF2A2820,
    )

    /**
     * Dark — ink.
     *
     * The accent is the direction's `#E09A52` unchanged: on an ink ground it measures 7.81:1,
     * so nothing had to move here. The solemn accent is lightened from `#8A8578` to `#B9B2A3`
     * for the same reason the light one was darkened — 8.72:1 rather than a value that happens
     * to sit near the threshold from the other side.
     */
    val DARK = WarmPaper(
        primary = 0xFFE09A52,
        onPrimary = 0xFF452300,
        primaryContainer = 0xFF4E2D0D,
        onPrimaryContainer = 0xFFFFDCBC,

        secondary = 0xFFD8C5A8,
        onSecondary = 0xFF3A2D1B,
        secondaryContainer = 0xFF3F3220,
        onSecondaryContainer = 0xFFF0E3CF,

        tertiary = 0xFFB5CCB7,
        onTertiary = 0xFF213425,
        tertiaryContainer = 0xFF374B3B,
        onTertiaryContainer = 0xFFD5E2D6,

        error = 0xFFFFB4A6,
        onError = 0xFF5F1407,
        errorContainer = 0xFF83261B,
        onErrorContainer = 0xFFFFDAD4,

        background = 0xFF15131C,
        onBackground = 0xFFF0EBE3,
        surface = 0xFF15131C,
        onSurface = 0xFFF0EBE3,
        surfaceVariant = 0xFF302D36,
        onSurfaceVariant = 0xFFCFC7BC,

        surfaceDim = 0xFF15131C,
        surfaceBright = 0xFF3A3744,
        surfaceContainerLowest = 0xFF0F0D15,
        surfaceContainerLow = 0xFF1C1A24,
        surfaceContainer = 0xFF201E28,
        surfaceContainerHigh = 0xFF2B2832,
        surfaceContainerHighest = 0xFF36323D,

        outline = 0xFF988F84,
        outlineVariant = 0xFF4A4651,
        scrim = 0xFF000000,

        inverseSurface = 0xFFF0EBE3,
        inverseOnSurface = 0xFF302D27,
        inversePrimary = 0xFF8F531E,

        solemnAccent = 0xFFB9B2A3,
        onSolemnAccent = 0xFF2B2823,
        solemnContainer = 0xFF433F37,
        onSolemnContainer = 0xFFE6E2D6,
    )
}

/**
 * One theme's worth of colour.
 *
 * Every Material 3 role is a field, and every field is given a value by hand in both themes —
 * no seed, no tonal-palette generation, no dynamic colour. The identity is the point, and a
 * scheme derived from one seed is a scheme nobody chose.
 *
 * [solemnAccent] is the one field Material does not have. It is not a role; it is what
 * `MaterialTheme.accent` returns inside a `SolemnScope`, which is the whole of M7's answer to
 * "a remembrance is the same design with the accent desaturated".
 */
data class WarmPaper(
    val primary: Long,
    val onPrimary: Long,
    val primaryContainer: Long,
    val onPrimaryContainer: Long,
    val secondary: Long,
    val onSecondary: Long,
    val secondaryContainer: Long,
    val onSecondaryContainer: Long,
    val tertiary: Long,
    val onTertiary: Long,
    val tertiaryContainer: Long,
    val onTertiaryContainer: Long,
    val error: Long,
    val onError: Long,
    val errorContainer: Long,
    val onErrorContainer: Long,
    val background: Long,
    val onBackground: Long,
    val surface: Long,
    val onSurface: Long,
    val surfaceVariant: Long,
    val onSurfaceVariant: Long,
    val surfaceDim: Long,
    val surfaceBright: Long,
    val surfaceContainerLowest: Long,
    val surfaceContainerLow: Long,
    val surfaceContainer: Long,
    val surfaceContainerHigh: Long,
    val surfaceContainerHighest: Long,
    val outline: Long,
    val outlineVariant: Long,
    val scrim: Long,
    val inverseSurface: Long,
    val inverseOnSurface: Long,
    val inversePrimary: Long,
    /** The accent, desaturated. See `Solemn.kt`. */
    val solemnAccent: Long,
    /** A label on a filled control inside a solemn scope. */
    val onSolemnAccent: Long,
    /** The container family's solemn twin, so a chip or a day cell desaturates too. */
    val solemnContainer: Long,
    val onSolemnContainer: Long,
)
