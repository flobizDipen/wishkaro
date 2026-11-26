package `in`.wishkaro.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Half of the shared table. The other half is `server/src/modules/people/name-tokens.spec.ts`,
 * reading THE SAME FILE.
 *
 * The rule exists twice, in two languages, because import dedup runs on the phone offline
 * and create-time dedup runs on the server. MILESTONE_CHECKLIST.md §C is explicit about the
 * price: a rule implemented twice is held to one shared table of cases. The table is the
 * fixture itself, not a copy of it — if the two implementations drift again, one of these
 * two suites goes red.
 *
 * WHY THIS LIVES IN `androidUnitTest` AND NOT `commonTest`. It reads
 * `docs/fixtures/name-tokens.json` off disk, which needs a filesystem. Copying the table
 * into Kotlin would defeat the entire point of having one table, and generating a Kotlin
 * source file from the JSON would put build machinery between the fixture and the assertion.
 * Reading the real file is the version that actually catches drift.
 *
 * The cost, stated rather than hidden: this runs on the JVM only. CI runs
 * `testDebugUnitTest` (which includes this) and `compileKotlinIosSimulatorArm64` (which
 * compiles but does not run anything), so Kotlin/Native's own regex engine is never held to
 * this table. Recorded in docs/M3_PLAN.md §3 and in M3_VERIFY.md as unproven for iOS.
 */
class NameTokensTest {

    @Serializable
    private data class Case(val name: String, val tokens: List<String>, val why: String)

    @Serializable
    private data class Pair(val a: String, val b: String, val match: Boolean, val why: String)

    @Serializable
    private data class Fixture(
        val cases: List<Case> = emptyList(),
        val duplicate_pairs: List<Pair> = emptyList(),
    )

    private val fixture: Fixture by lazy {
        Json { ignoreUnknownKeys = true }.decodeFromString(fixtureFile().readText())
    }

    /**
     * Walks up from the test's working directory to find the repo root.
     *
     * Gradle runs this with the working directory set to `composeApp/`, but that is a
     * convention rather than a promise, and a hard-coded `../docs/...` that silently stopped
     * resolving would turn this whole suite green-and-vacuous.
     */
    private fun fixtureFile(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "docs/fixtures/name-tokens.json")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("docs/fixtures/name-tokens.json not found from ${File(".").absolutePath}")
    }

    @Test
    fun the_shared_table_is_actually_being_read() {
        // Without this, a path that stopped resolving would make every test below pass by
        // iterating an empty list.
        assertTrue(fixture.cases.size > 15, "expected the full table, got ${fixture.cases.size}")
        assertTrue(fixture.duplicate_pairs.size > 5)
    }

    @Test
    fun every_case_in_the_shared_table() {
        val failures = fixture.cases.mapNotNull { case ->
            val got = nameTokens(case.name).sorted()
            val want = case.tokens.sorted()
            if (got == want) null else "${case.name.ifBlank { "<blank>" }}: got $got, want $want  (${case.why})"
        }
        assertTrue(failures.isEmpty(), "the two implementations have drifted:\n" + failures.joinToString("\n"))
    }

    @Test
    fun every_pair_in_the_shared_table() {
        val failures = fixture.duplicate_pairs.mapNotNull { pair ->
            val got = tokensOverlap(nameTokens(pair.a), nameTokens(pair.b))
            if (got == pair.match) null else "${pair.a} vs ${pair.b}: got $got, want ${pair.match}  (${pair.why})"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    // ---- the specific failures this rule was fixed for, named so a regression is readable ----

    @Test
    fun a_devanagari_name_is_not_shattered_at_its_vowel_signs() {
        // The old rule was `[^\p{L}\p{N}]+`. \p{L} excludes combining marks, and Devanagari
        // builds words out of them, so this used to produce [जयदत, सर] — fragments.
        assertEquals(setOf("जयदत्त", "जांसरी"), nameTokens("जयदत्त जांसरी"))
    }

    @Test
    fun a_gujarati_name_tokenises_to_whole_words() {
        assertEquals(setOf("જયદત્ત", "જાંસરી"), nameTokens("જયદત્ત જાંસરી"))
    }

    @Test
    fun word_order_does_not_matter() {
        assertTrue(
            tokensOverlap(nameTokens("Jansari Jaydatt"), nameTokens("Jaydatt Jansari")),
            "the case BUILD_PROMPT_M3 names by hand",
        )
    }
}
