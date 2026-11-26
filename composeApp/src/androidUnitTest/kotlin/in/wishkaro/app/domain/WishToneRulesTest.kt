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
 * `server/src/modules/wishes/tone-rules.spec.ts`, reading THE SAME FILE.
 *
 * The rule exists twice because the server VALIDATES which tones are legal and this side
 * HIDES the chips that are not offered. Neither half can be dropped: a client that only hid
 * them would let a tampered request through, and a server that only validated would show the
 * user a Funny button that 400s on a death anniversary.
 *
 * `MILESTONE_CHECKLIST.md` §C: a rule implemented twice in two languages is held to one
 * shared table of cases. That table is `docs/fixtures/wish-tones.json`, not a copy of it —
 * if the two drift, one of these two suites goes red.
 *
 * `docs/BUILD_PROMPT_M5.md`, definition of done item 9: the solemn restriction must be red if
 * someone adds Funny back. [funny_is_never_offered_on_a_solemn_occasion] is that assertion on
 * this side.
 *
 * In `androidUnitTest` rather than `commonTest` for the same reason `NameTokensTest` is: it
 * reads the fixture off disk, and copying the table into Kotlin would defeat the point of
 * having one table. Same stated cost — this runs on the JVM only, so Kotlin/Native is never
 * held to it. The rule here is a list membership rather than a regex, so the risk of
 * platform divergence is much lower than it is for name tokens, but it is not zero and it is
 * recorded rather than waved away.
 */
class WishToneRulesTest {

    @Serializable
    private data class Case(
        val sentiment: String,
        val tone: String,
        val expect: Boolean,
        val why: String = "",
    )

    @Serializable
    private data class SentimentCase(
        val eventType: String,
        val isDeceased: Boolean,
        val sentiment: String,
        val why: String = "",
    )

    @Serializable
    private data class Fixture(
        val tones: List<String> = emptyList(),
        val languages: List<String> = emptyList(),
        val allowed: Map<String, List<String>> = emptyMap(),
        val default_tone: Map<String, String> = emptyMap(),
        val cases: List<Case> = emptyList(),
        val sentiment_cases: List<SentimentCase> = emptyList(),
    )

    private val fixture: Fixture by lazy {
        Json { ignoreUnknownKeys = true }.decodeFromString(fixtureFile().readText())
    }

    /** Walks up from the working directory, as `NameTokensTest` does and for the same reason. */
    private fun fixtureFile(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "docs/fixtures/wish-tones.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("docs/fixtures/wish-tones.json not found from ${File(".").absolutePath}")
    }

    private fun sentiment(name: String) =
        if (name == "SOLEMN") Sentiment.SOLEMN else Sentiment.CELEBRATORY

    @Test
    fun the_shared_table_is_actually_being_read() {
        // Without this, a path that stopped resolving makes every test below pass by
        // iterating an empty list — the one failure mode a fixture-driven suite has.
        assertTrue(fixture.cases.size > 10, "expected the full table, got ${fixture.cases.size}")
        assertTrue(fixture.sentiment_cases.size > 8)
        assertEquals(6, fixture.tones.size, "six tones: FR-WSH-03's five plus HEARTFELT")
    }

    @Test
    fun every_tone_is_a_tone_this_build_knows() {
        // A seventh tone added to the fixture and not to the enum would otherwise be
        // silently skipped by every case below, since `WishTone.from` returns null.
        val unknown = fixture.tones.filter { WishTone.from(it) == null }
        assertTrue(unknown.isEmpty(), "the fixture names tones this build does not have: $unknown")
        val unknownLanguages = fixture.languages.filter { WishLanguage.from(it) == null }
        assertTrue(unknownLanguages.isEmpty(), "unknown languages: $unknownLanguages")
    }

    @Test
    fun every_case_in_the_shared_table() {
        val failures = fixture.cases.mapNotNull { case ->
            val tone = WishTone.from(case.tone) ?: return@mapNotNull "unknown tone ${case.tone}"
            val got = isToneAllowed(sentiment(case.sentiment), tone)
            if (got == case.expect) {
                null
            } else {
                "${case.sentiment}/${case.tone}: got $got, want ${case.expect}  (${case.why})"
            }
        }
        assertTrue(failures.isEmpty(), "the two implementations have drifted:\n" + failures.joinToString("\n"))
    }

    @Test
    fun the_whole_allowed_list_matches_not_just_case_by_case() {
        // Case-by-case checks cannot catch an EXTRA tone the fixture never mentions.
        fixture.allowed.forEach { (name, wanted) ->
            val got = tonesFor(sentiment(name)).map { it.wire }.sorted()
            assertEquals(wanted.sorted(), got, "the offered list for $name")
        }
    }

    @Test
    fun the_default_tone_is_itself_offered() {
        fixture.default_tone.forEach { (name, wanted) ->
            val s = sentiment(name)
            assertEquals(wanted, defaultToneFor(s).wire, "the default for $name")
            assertTrue(isToneAllowed(s, defaultToneFor(s)), "the default for $name must be offered")
        }
    }

    @Test
    fun funny_is_never_offered_on_a_solemn_occasion() {
        // Said separately from the table on purpose. The table is data and could be edited;
        // this is the requirement in one line, where a reviewer reading the diff sees it.
        // REQUIREMENTS.md §11 files this under correctness, not content.
        assertFalse(WishTone.FUNNY in tonesFor(Sentiment.SOLEMN))
        assertFalse(WishTone.POETIC in tonesFor(Sentiment.SOLEMN))
    }

    /**
     * Tone comes from the PERSON, never from the event type.
     *
     * The chain is where M1 broke: a deceased person's birthday is `type = BIRTHDAY` and
     * `sentiment = SOLEMN`, so a composer reading the type would offer Funny and draft many
     * happy returns for somebody who has died.
     *
     * The client cannot run `sentimentFor` — that is server-derived and mirrored — so this
     * asserts the thing the client CAN get wrong: that the offered list follows the person's
     * mirrored flag and nothing else.
     */
    @Test
    fun the_offered_tones_follow_the_person_not_the_event_type() {
        val deceased = person(isDeceased = true)
        val living = person(isDeceased = false)

        assertEquals(listOf("RESPECTFUL", "SHORT", "HEARTFELT"), tonesFor(deceased).map { it.wire })
        assertTrue(WishTone.FUNNY in tonesFor(living))
        assertFalse(WishTone.FUNNY in tonesFor(deceased))

        // And every solemn row in the shared table agrees, whatever the event type says.
        val solemnByType = fixture.sentiment_cases.filter { it.sentiment == "SOLEMN" && it.isDeceased }
        assertTrue(solemnByType.isNotEmpty())
        solemnByType.forEach {
            assertFalse(
                WishTone.FUNNY in tonesFor(deceased),
                "${it.eventType} on a deceased person must not offer FUNNY  (${it.why})",
            )
        }
    }

    private fun person(isDeceased: Boolean) = Person(
        id = "p1",
        displayName = "Anika Pillai",
        relationToOwner = "Sister",
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
        events = emptyList(),
        traits = emptyList(),
    )
}
