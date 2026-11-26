package `in`.wishkaro.app.domain

/**
 * The poster theme library, as the picker sees it (FR-POS-04, FR-POS-13).
 *
 * ## Why the client has a copy at all
 *
 * The same reason `Tone.kt` has one. FR-POS-04 says the library "ships with the app", and the
 * thing that sentence is protecting is the picker: a user should be able to open a person,
 * see what is on offer and choose, without waiting on a network. The ARTWORK comes from the
 * server and is cached (`docs/M6_PLAN.md` §3.5 — the split is raised there, because
 * FR-POS-04 says "ships with the app" while §9 serves `GET /v1/themes`); the MANIFEST is
 * small and lives here.
 *
 * ## The rule exists twice, and is held to one table
 *
 * The server REFUSES a theme outside the occasion's family (`theme-library.ts`); this side
 * FILTERS so one is never shown. Neither half can be dropped — a client that only filtered
 * would let a tampered request through, and a server that only validated would show somebody
 * confetti on a remembrance and then 400 when they tapped it.
 *
 * `MILESTONE_CHECKLIST.md` §C: that means **one** shared table of cases,
 * `docs/fixtures/poster-themes.json`, read by `PosterThemesTest` here and by
 * `theme-library.spec.ts` there. The list below is a mirror of that file and `PosterThemesTest`
 * is what stops the two drifting.
 *
 * ## TBD-5
 *
 * Every theme here is a visible PLACEHOLDER and its display name says so. TBD-5 — visual
 * identity and poster art direction, due at M4, still open — is what replaces them, and
 * FR-POS-04's "hand-approved" names a hand that is not a session's. The mechanism is what is
 * built; the art is Dipen's.
 */
enum class ThemeFamily { CELEBRATORY, SOLEMN }

/** FR-POS-05's three output formats. [STATUS] is the default: the WhatsApp status size. */
enum class PosterFormat(
    val wire: String,
    val label: String,
    val width: Int,
    val height: Int,
    /** The cache key's component (FR-POS-03). The RATIO, not this enum's name. */
    val ratio: String,
) {
    STATUS("STATUS", "Status", 1080, 1920, "9:16"),
    SQUARE("SQUARE", "Square", 1080, 1080, "1:1"),
    LINK("LINK", "Link preview", 1200, 630, "1.91:1"),
    ;

    companion object {
        val DEFAULT = STATUS
        fun from(wire: String?): PosterFormat? = entries.firstOrNull { it.wire == wire }
    }
}

data class PosterTheme(
    val id: String,
    val family: ThemeFamily,
    val displayName: String,
    val palette: String,
    /** Hex, as the fixture gives them. The preview draws a gradient between the two. */
    val backgroundFrom: String,
    val backgroundTo: String,
    val textColor: String,
)

/**
 * The shipped manifest. Mirrors `docs/fixtures/poster-themes.json`, and `PosterThemesTest`
 * fails if it stops doing so.
 */
val POSTER_THEMES: List<PosterTheme> = listOf(
    PosterTheme("ph-confetti", ThemeFamily.CELEBRATORY, "Confetti — placeholder", "festive", "#F9A826", "#EF5DA8", "#1A1A1A"),
    PosterTheme("ph-sunrise", ThemeFamily.CELEBRATORY, "Sunrise — placeholder", "warm", "#FFD166", "#EF476F", "#1A1A1A"),
    PosterTheme("ph-bloom", ThemeFamily.CELEBRATORY, "Bloom — placeholder", "floral", "#A8E6CF", "#3D84A8", "#0B2B33"),
    PosterTheme("ph-gold", ThemeFamily.CELEBRATORY, "Gold — placeholder", "gold", "#F7E7A1", "#B8860B", "#2B2000"),

    // The solemn family. Three separate entries in a separate family — NOT celebratory
    // themes with the motifs taken out. `Tone.kt` took the same decision for words: a
    // conditional is a thing someone can forget, a set you have to pick is a thing the
    // compiler makes you pick. A remembrance template is never a birthday template with the
    // balloons removed.
    PosterTheme("ph-stillwater", ThemeFamily.SOLEMN, "Still water — placeholder", "muted-blue", "#5C7A89", "#2E4057", "#F2F4F5"),
    PosterTheme("ph-dusk", ThemeFamily.SOLEMN, "Dusk — placeholder", "muted-grey", "#8D99AE", "#2B2D42", "#F2F4F5"),
    PosterTheme("ph-lamp", ThemeFamily.SOLEMN, "Lamp — placeholder", "muted-amber", "#7A6652", "#2C2418", "#F5EFE6"),
)

/**
 * Which family an occasion may use (FR-POS-13).
 *
 * One expression, mirroring `familyFor()` on the server. One-to-one today, and written down
 * rather than inlined because this is the single most dangerous mapping in the milestone.
 */
fun familyFor(sentiment: Sentiment): ThemeFamily =
    when (sentiment) {
        Sentiment.SOLEMN -> ThemeFamily.SOLEMN
        Sentiment.CELEBRATORY -> ThemeFamily.CELEBRATORY
    }

/**
 * **The function the picker calls, and the one thing in this file that must never be wrong.**
 *
 * It takes a [Person], not a [Sentiment], and that is the whole point.
 *
 * `Person.isSolemn` is the ONE mirrored implementation on this side (FR-EVT-01a forbids the
 * client deriving sentiment; `isSolemn` reads two mirrored values and takes the quieter one).
 * M5 shipped two bugs in exactly this area — `454ea75`, where a death anniversary created on
 * the handset opened the celebratory composer because `person.is_deceased` is derived
 * server-side and the local row still said false; and `cb5919d`, where the server derived a
 * wish's sentiment before its own recompute had set the flag. Both were derived state read
 * one step too early, and both were invisible to a full passing suite because every fixture
 * in this repository builds a person the way the server returns one.
 *
 * M6's version of that failure is a picture of balloons on a remembrance, which is worse than
 * a word: a picture is what gets sent, seen and screenshotted.
 *
 * So the picker never reads `isDeceased`, never reads an event's `type`, and never derives
 * anything of its own. It asks `isSolemn`, which already ORs every signal that can only push
 * towards solemn. `PosterThemesTest` constructs the stale window on purpose and asserts that
 * nothing celebratory is reachable inside it.
 */
fun posterThemesFor(person: Person): List<PosterTheme> =
    themesInFamily(if (person.isSolemn) ThemeFamily.SOLEMN else ThemeFamily.CELEBRATORY)

/** The same filter, from a sentiment the server already derived. */
fun posterThemesFor(sentiment: Sentiment): List<PosterTheme> = themesInFamily(familyFor(sentiment))

private fun themesInFamily(family: ThemeFamily): List<PosterTheme> =
    POSTER_THEMES.filter { it.family == family }

/**
 * The default theme for an occasion: the first of its OWN family.
 *
 * Deliberately not a named constant, so there is no way to express a default that is not in
 * the family the occasion is allowed — a constant would be a second place for the solemn
 * rule to be got wrong.
 */
fun defaultPosterThemeFor(person: Person): PosterTheme = posterThemesFor(person).first()

fun posterThemeById(id: String): PosterTheme? = POSTER_THEMES.firstOrNull { it.id == id }
