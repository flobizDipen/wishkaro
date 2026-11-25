package `in`.wishkaro.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Why sync compares instants and never strings.
 *
 * Found on the handset: a person restored from the 30-day window came back as an empty husk,
 * because the restore matched tombstones by string equality and the two ends format ISO-8601
 * differently —
 *
 *   person   2026-11-25T10:16:21.410233Z   kotlinx Instant.toString(), microseconds
 *   event    2026-11-25T10:16:21.410Z      the same value after a round trip through
 *                                          Prisma's timestamp(3) and JS toISOString()
 *
 * These tests pin the properties of the formats themselves, so the reasoning behind
 * LocalStore's comparison survives someone later thinking a string compare would do.
 */
class TimestampComparisonTest {

    private val microseconds = "2026-11-25T10:16:21.410233Z"
    private val milliseconds = "2026-11-25T10:16:21.410Z"

    @Test
    fun the_two_formats_describe_the_same_millisecond() {
        assertEquals(
            Instant.parse(microseconds).toEpochMilliseconds(),
            Instant.parse(milliseconds).toEpochMilliseconds(),
        )
    }

    /** The trap itself. '2' < 'Z', so the finer-grained string sorts EARLIER. */
    @Test
    fun comparing_them_as_strings_gets_the_order_backwards() {
        assertTrue(microseconds < milliseconds, "the premise of the bug no longer holds")
        assertFalse(
            microseconds >= milliseconds,
            "a string compare would call the client's own fresh write older than the " +
                "server's echo of it, and last-write-wins would lose to formatting",
        )
    }

    /**
     * Parsed, the microsecond value is 233µs LATER — not equal. That is the right answer for
     * last-write-wins, where the question is "is the local row at least as new": the client's
     * own write genuinely is. Tombstone matching asks a different question and so truncates
     * to milliseconds first; the two comparisons are deliberately not the same function.
     */
    @Test
    fun comparing_them_as_instants_gets_it_right() {
        assertTrue(Instant.parse(microseconds) >= Instant.parse(milliseconds))
        assertFalse(Instant.parse(milliseconds) >= Instant.parse(microseconds))
        assertEquals(
            Instant.parse(microseconds).toEpochMilliseconds(),
            Instant.parse(milliseconds).toEpochMilliseconds(),
        )
    }

    /**
     * The other half of the mismatch: on a whole second kotlinx emits no decimals at all
     * while JavaScript emits three. String equality fails there too, in the other direction.
     */
    @Test
    fun a_whole_second_formats_differently_at_each_end() {
        val wholeSecond = 1_795_000_000_000L
        val kotlinStyle = Instant.fromEpochMilliseconds(wholeSecond).toString()

        // kotlinx trims the ".000"; JavaScript's toISOString() always writes three decimals.
        assertFalse(kotlinStyle.contains(".000"), "kotlinx unexpectedly kept the zeros")
        val javascriptStyle = kotlinStyle.dropLast(1) + ".000Z"

        assertFalse(
            kotlinStyle == javascriptStyle,
            "if these ever become equal the formats have converged and this test is moot",
        )
        assertEquals(
            Instant.parse(kotlinStyle).toEpochMilliseconds(),
            Instant.parse(javascriptStyle).toEpochMilliseconds(),
        )
    }

    @Test
    fun a_genuinely_newer_instant_still_wins() {
        val older = Instant.parse("2026-11-25T10:16:21.410233Z")
        val newer = Instant.parse("2026-11-25T10:16:21.999Z")
        assertTrue(newer > older)
    }
}
