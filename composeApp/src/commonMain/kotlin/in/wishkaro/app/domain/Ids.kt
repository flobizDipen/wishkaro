package `in`.wishkaro.app.domain

import kotlin.random.Random
import kotlin.time.Clock

/**
 * Client-generated record ids (D23).
 *
 * Offline-first means a person exists and has an identity before the network has ever seen
 * them, so ids are minted here and the server accepts them as given. `M0_PLAN.md` and
 * `M1_PLAN.md` both say these are **UUIDv7**; until M2 they were v4, because each platform
 * reached for its own `UUID.randomUUID()` / `NSUUID()`. The documents were right and the code
 * had quietly drifted, so the code moved rather than the decision.
 *
 * Two reasons it is worth being v7 rather than relaxing the claim:
 *
 *  - **Index locality.** These ids land in the same Postgres b-trees as the server's own,
 *    which are already `uuid(7)` for exactly this reason. Random v4 ids scatter inserts
 *    across the whole index; v7 ids are time-ordered and append near the end.
 *  - **The sync cursor's tiebreaker is `id`** (see `sync-cursor.ts`). With v7 the tiebreak
 *    within one millisecond is creation order instead of noise.
 *
 * Shared code, not a platform seam, so Android and iOS cannot disagree about the format —
 * which they previously did in a small way, since `NSUUID().UUIDString()` returns uppercase
 * and `UUID.randomUUID()` returns lowercase.
 *
 * Layout (RFC 9562 §5.7):
 *
 * ```
 *   48 bits  unix_ts_ms
 *    4 bits  version (7)
 *   12 bits  rand_a
 *    2 bits  variant (0b10)
 *   62 bits  rand_b
 * ```
 *
 * `rand_a` is random rather than a counter, which RFC 9562 permits. Two ids minted in the
 * same millisecond are therefore not guaranteed to sort in creation order — nothing here
 * needs them to, and a counter would need shared mutable state for no benefit this app can
 * measure.
 */
fun newUuidV7(
    nowMillis: Long = Clock.System.now().toEpochMilliseconds(),
    random: Random = Random.Default,
): String {
    val timestamp = nowMillis and 0xFFFF_FFFF_FFFFL

    // 4 bits of version, then 12 random.
    val versionAndRandA = (0x7L shl 12) or random.nextLong(0, 1L shl 12)

    // 2 bits of variant (0b10), then 62 random. Split across the two trailing groups.
    val variantAndRandBHigh = (0b10L shl 14) or random.nextLong(0, 1L shl 14)
    val randBLow = random.nextLong(0, 1L shl 48)

    return buildString(36) {
        append(hex(timestamp ushr 16, 8))
        append('-')
        append(hex(timestamp and 0xFFFFL, 4))
        append('-')
        append(hex(versionAndRandA, 4))
        append('-')
        append(hex(variantAndRandBHigh, 4))
        append('-')
        append(hex(randBLow, 12))
    }
}

/** Lowercase, zero-padded to [width] digits. The server's UUID regex accepts either case; */
/** emitting one case from one place is what stops two platforms differing. */
private fun hex(value: Long, width: Int): String =
    value.toString(16).padStart(width, '0')
