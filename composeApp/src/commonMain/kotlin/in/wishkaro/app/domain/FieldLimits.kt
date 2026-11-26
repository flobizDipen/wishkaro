package `in`.wishkaro.app.domain

/**
 * The lengths the server will accept, mirrored here to stop a field at the edge rather than
 * letting the user discover it three days later.
 *
 * This is PREVENTION, not validation, and the distinction matters. The safety net for a
 * record the server refuses is `sync_blocked` plus the banner on the people list — that has
 * to keep working, because the server has rules this app does not mirror and will grow more.
 * What these do is close the one hole a user can walk into by typing: nothing capped any of
 * these, `PersonUpsertSchema` caps `notes` at 2,000, and a long note therefore made a person
 * stop syncing — silently, until M3's review added the banner.
 *
 * Mirroring a few maxima is a much smaller duplication than mirroring `sync.schemas.ts`, and
 * being wrong about one is cheap in a way validation never is: too small and the user is
 * stopped early with the counter showing why; too large and the banner catches it.
 */
object FieldLimits {
    const val NAME = 120
    const val RELATION = 60
    const val GENDER = 40
    const val PHONE = 40
    const val EMAIL = 254
    const val NOTES = 2000
    const val TRAIT = 120

    /** The point at which a counter stops being clutter and starts being a warning. */
    fun showCounter(current: Int, limit: Int): Boolean = current >= limit - 100
}
