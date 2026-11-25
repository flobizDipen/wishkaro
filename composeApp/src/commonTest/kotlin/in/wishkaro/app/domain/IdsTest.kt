package `in`.wishkaro.app.domain

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D23 says client-generated ids are UUIDv7. Until M2 they were v4, because each platform
 * reached for its own UUID type — so this file exists to keep the code and the decision from
 * drifting apart again.
 *
 * The server's `UuidSchema` only checks the shape, so a malformed id would sail through sync
 * and land in a Postgres `uuid` column as something that is not what any document claims.
 */
class IdsTest {

    private val UUID_SHAPE =
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    @Test
    fun looksLikeAUuid() {
        repeat(200) {
            val id = newUuidV7()
            assertTrue(UUID_SHAPE.matches(id), "not a lowercase UUID: $id")
        }
    }

    @Test
    fun carriesVersion7AndTheRfcVariant() {
        repeat(200) {
            val id = newUuidV7()
            // Version nibble: first character of the third group.
            assertEquals('7', id[14], "version nibble wrong in $id")
            // Variant: top two bits of the fourth group are 0b10, so 8, 9, a or b.
            assertTrue(id[19] in "89ab", "variant nibble wrong in $id")
        }
    }

    /** The whole point of v7: the first 48 bits are the millisecond it was minted. */
    @Test
    fun embedsTheTimestamp() {
        val millis = 1_795_615_088_632L
        val id = newUuidV7(nowMillis = millis, random = Random(1))
        val encoded = (id.substring(0, 8) + id.substring(9, 13)).toLong(16)
        assertEquals(millis, encoded)
    }

    /** Which is what gives index locality and an intuitive sync-cursor tiebreak. */
    @Test
    fun sortsByCreationTimeAcrossMilliseconds() {
        val ids = (0..50).map { newUuidV7(nowMillis = 1_795_615_088_000L + it, random = Random(it)) }
        assertEquals(ids, ids.sorted())
    }

    @Test
    fun doesNotCollide() {
        val ids = (1..5_000).map { newUuidV7() }
        assertEquals(ids.size, ids.toSet().size)
    }

    /** A clock that has not moved must still produce distinct ids. */
    @Test
    fun differsWithinOneMillisecond() {
        val ids = (1..1_000).map { newUuidV7(nowMillis = 1_795_615_088_632L) }
        assertTrue(ids.toSet().size > 990, "too many collisions inside one millisecond")
    }
}
