package `in`.wishkaro.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Half of the shared table. The other half is
 * `server/src/modules/posters/theme-library.spec.ts`, reading THE SAME FILE.
 *
 * The rule exists twice because the server REFUSES a theme outside the occasion's family and
 * this side FILTERS the picker so one is never shown. Neither half can be dropped: a client
 * that only filtered would let a tampered request through, and a server that only validated
 * would show somebody confetti on a remembrance and then 400 when they tapped it.
 *
 * `MILESTONE_CHECKLIST.md` §C — a rule implemented twice in two languages is held to one
 * shared table of cases, and the table is `docs/fixtures/poster-themes.json`, not a copy. If
 * the two drift, one of these suites goes red.
 *
 * `REQUIREMENTS.md` §11 files this under correctness: "no SOLEMN event can ever produce
 * celebratory copy, **a celebratory template**, a gift suggestion or a T-7 gifting reminder."
 *
 * In `androidUnitTest` rather than `commonTest` for the same reason `WishToneRulesTest` is:
 * it reads the fixture off disk, and copying the table into Kotlin would defeat the point of
 * having one table. Same stated cost — this runs on the JVM only, so Kotlin/Native is never
 * held to it. The rule is a family comparison rather than a regex, so the divergence risk is
 * low; recorded rather than waved away.
 */
class PosterThemesTest {

    @Serializable
    private data class FixtureTheme(
        val id: String,
        val family: String,
        val displayName: String = "",
        val palette: String = "",
        val motifs: List<String> = emptyList(),
    )

    @Serializable
    private data class PickerCase(
        val sentiment: String,
        val themeId: String,
        val offered: Boolean,
        val why: String = "",
    )

    @Serializable
    private data class FormatSpec(val ratio: String, val width: Int, val height: Int)

    @Serializable
    private data class Fixture(
        val families: List<String> = emptyList(),
        val aspect_ratios: Map<String, FormatSpec> = emptyMap(),
        val default_format: String = "",
        val banned_on_solemn: List<String> = emptyList(),
        val themes: List<FixtureTheme> = emptyList(),
        val picker_cases: List<PickerCase> = emptyList(),
    )

    private val fixture: Fixture by lazy {
        Json { ignoreUnknownKeys = true }.decodeFromString(fixtureFile().readText())
    }

    /** Walks up from the working directory, as `WishToneRulesTest` does and for the same reason. */
    private fun fixtureFile(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "docs/fixtures/poster-themes.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("docs/fixtures/poster-themes.json not found from ${File(".").absolutePath}")
    }

    private fun sentiment(name: String) =
        if (name == "SOLEMN") Sentiment.SOLEMN else Sentiment.CELEBRATORY

    @Test
    fun the_shared_table_is_actually_being_read() {
        // Without this, a path that stopped resolving makes every test below pass by
        // iterating an empty list — the one failure mode a fixture-driven suite has, and the
        // reason WishToneRulesTest opens the same way.
        assertTrue(fixture.picker_cases.size >= 12, "expected the full table, got ${fixture.picker_cases.size}")
        assertTrue(fixture.themes.size >= 6)
        assertEquals(3, fixture.aspect_ratios.size, "FR-POS-05's three formats")
    }

    @Test
    fun the_shipped_manifest_matches_the_shared_table() {
        // The list in PosterTheme.kt is a MIRROR of the fixture. This is what stops it
        // becoming a copy that drifts — M3's name-token lesson, applied to themes.
        assertEquals(
            fixture.themes.map { it.id }.toSet(),
            POSTER_THEMES.map { it.id }.toSet(),
            "the app's manifest and the shared table name different themes",
        )
        for (f in fixture.themes) {
            val mine = posterThemeById(f.id) ?: error("missing theme ${f.id}")
            assertEquals(f.family, mine.family.name, "family drifted for ${f.id}")
            assertEquals(f.displayName, mine.displayName, "display name drifted for ${f.id}")
            assertEquals(f.palette, mine.palette, "palette drifted for ${f.id}")
        }
    }

    @Test
    fun every_format_matches_the_shared_table() {
        // FR-POS-05's exact pixel sizes, and the RATIO the cache key is built from — the
        // client sends a format and the server keys on the ratio, so a drift here would make
        // every request for that format a cache miss against artwork that fits it.
        for ((name, spec) in fixture.aspect_ratios) {
            val mine = PosterFormat.from(name) ?: error("the app has no format $name")
            assertEquals(spec.width, mine.width, "$name width")
            assertEquals(spec.height, mine.height, "$name height")
            assertEquals(spec.ratio, mine.ratio, "$name ratio")
        }
        assertEquals(fixture.default_format, PosterFormat.DEFAULT.wire)
    }

    @Test
    fun every_picker_case_in_the_shared_table() {
        val failures = fixture.picker_cases.mapNotNull { case ->
            val offered = posterThemesFor(sentiment(case.sentiment)).any { it.id == case.themeId }
            if (offered == case.offered) {
                null
            } else {
                "${case.sentiment}/${case.themeId}: got $offered, want ${case.offered}  (${case.why})"
            }
        }
        assertTrue(failures.isEmpty(), "shared table disagreements:\n${failures.joinToString("\n")}")
    }

    /**
     * Definition-of-done item 2, as one assertion: "no celebratory theme reachable in the
     * picker".
     *
     * Deliberately not a restatement of the `offered: false` rows. Those name seven themes;
     * this holds for a theme somebody adds tomorrow without touching the cases table, which
     * is the shape that actually ships a bug.
     */
    @Test
    fun no_celebratory_theme_is_reachable_for_a_solemn_occasion() {
        val offered = posterThemesFor(Sentiment.SOLEMN)
        assertTrue(offered.isNotEmpty(), "an empty solemn picker would push somebody elsewhere")
        assertTrue(offered.all { it.family == ThemeFamily.SOLEMN })

        for (theme in POSTER_THEMES.filter { it.family == ThemeFamily.CELEBRATORY }) {
            assertFalse(
                offered.any { it.id == theme.id },
                "${theme.id} is reachable on a SOLEMN occasion",
            )
        }
    }

    /**
     * FR-POS-13's lexicon, on the DATA.
     *
     * M5 §0.3 is why it is here and not on a prompt: a prompt has to NAME what it forbids, so
     * "never use confetti" fails its own check. A motif list is a list of things that get
     * drawn, which is the right surface.
     */
    @Test
    fun no_solemn_theme_names_a_celebratory_motif() {
        for (theme in POSTER_THEMES.filter { it.family == ThemeFamily.SOLEMN }) {
            val haystack = "${theme.id} ${theme.displayName} ${theme.palette}".lowercase()
            for (banned in fixture.banned_on_solemn) {
                assertFalse(haystack.contains(banned), "${theme.id} contains \"$banned\"")
            }
        }
    }

    // -----------------------------------------------------------------------------------
    // The stale window, constructed on purpose
    // -----------------------------------------------------------------------------------

    /**
     * **The test docs/BUILD_PROMPT_M6.md asks for by name**, on this side.
     *
     * > "build at least one test that constructs the stale window on purpose — a person whose
     * > death anniversary and whose poster request arrive together — because no fixture in
     * > this repo builds that shape on its own, which is precisely why it survived twice."
     *
     * The two it survived:
     *
     *  - `454ea75` — `person.is_deceased` is derived SERVER-side, so between creating a death
     *    anniversary on the handset and the next sync pull the local row says **false**.
     *    `Person.isSolemn` read that one flag, and the celebratory composer opened for
     *    somebody who had died. Reachable since M1, invisible to 370 tests.
     *  - `cb5919d` — the server derived a wish's sentiment from the same flag **before**
     *    `recomputeMany` had set it, and then the client took that wrong answer as
     *    authoritative over its own correct one.
     *
     * Both are the same shape: derived state read one step too early. M6 reads the same flag
     * to pick a theme FAMILY, so its failure is confetti on a remembrance.
     *
     * The window below is the exact one: `isDeceased = false`, because the server has not
     * seen the event yet, while the event itself is already on the phone with the SOLEMN
     * sentiment `addEventLocally` seeds at insert time.
     */
    @Test
    fun a_death_anniversary_created_on_the_phone_offers_no_celebratory_theme() {
        val staleWindow = person(
            isDeceased = false, // the server has not derived it yet — THIS is the window
            events = listOf(
                event(EventType.DEATH_ANNIVERSARY, Sentiment.SOLEMN),
            ),
        )

        val offered = posterThemesFor(staleWindow)

        assertTrue(offered.isNotEmpty())
        assertTrue(
            offered.all { it.family == ThemeFamily.SOLEMN },
            "a celebratory theme was reachable inside the stale window: " +
                offered.filter { it.family != ThemeFamily.SOLEMN }.map { it.id },
        )
        assertEquals(ThemeFamily.SOLEMN, defaultPosterThemeFor(staleWindow).family)
    }

    /**
     * The same window with the sentiment not yet seeded either — the worst case.
     *
     * `addEventLocally` seeds SOLEMN at insert time, so this shape should not occur. It is
     * tested because `Person.isSolemn` also ORs on the event TYPE, and that third signal is
     * the one that holds when a row arrived by a route nobody wrote it for — which is the
     * second of the two bug shapes `MILESTONE_CHECKLIST.md` tells you to look for first.
     */
    @Test
    fun a_death_anniversary_with_neither_derived_flag_set_still_offers_no_celebratory_theme() {
        val worstCase = person(
            isDeceased = false,
            events = listOf(event(EventType.DEATH_ANNIVERSARY, Sentiment.CELEBRATORY)),
        )

        assertTrue(posterThemesFor(worstCase).all { it.family == ThemeFamily.SOLEMN })
    }

    /**
     * A remembrance birthday: `type = BIRTHDAY`, `sentiment = SOLEMN` (FR-EVT-05, OD-14).
     *
     * The picker must not read the event type. M1 got this exact case wrong once.
     */
    @Test
    fun a_deceased_persons_birthday_offers_no_celebratory_theme() {
        val remembranceBirthday = person(
            isDeceased = true,
            events = listOf(event(EventType.BIRTHDAY, Sentiment.SOLEMN)),
        )

        assertTrue(posterThemesFor(remembranceBirthday).all { it.family == ThemeFamily.SOLEMN })
    }

    /**
     * The other direction, and it matters as much.
     *
     * A rule that made EVERYBODY solemn would pass every assertion above while quietly
     * removing the product. `PersonSolemnTest` makes the same point for tone and M5 was
     * right to: the quieter bug is the same size.
     */
    @Test
    fun an_ordinary_birthday_still_gets_the_celebratory_family() {
        val living = person(
            isDeceased = false,
            events = listOf(event(EventType.BIRTHDAY, Sentiment.CELEBRATORY)),
        )

        val offered = posterThemesFor(living)
        assertTrue(offered.isNotEmpty())
        assertTrue(offered.all { it.family == ThemeFamily.CELEBRATORY })
        assertFalse(offered.any { it.family == ThemeFamily.SOLEMN })
    }

    // --- invented people only (MILESTONE_CHECKLIST.md §F) -------------------------------

    private fun person(isDeceased: Boolean, events: List<PersonEvent>) = Person(
        id = "p1",
        displayName = "Test Person",
        relationToOwner = "Friend",
        isSelf = false,
        gender = null,
        phone = null,
        email = null,
        notes = null,
        isDeceased = isDeceased,
        isMinor = false,
        completenessScore = 0,
        completenessSuggestions = emptyList(),
        deletedAt = null,
        events = events,
        traits = emptyList(),
    )

    private fun event(type: EventType, sentiment: Sentiment) = PersonEvent(
        id = "e1",
        personId = "p1",
        type = type,
        sentiment = sentiment,
        label = null,
        isMuted = false,
        date = EventDate(day = 4, month = 7, year = 1980),
    )
}
