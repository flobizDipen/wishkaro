package `in`.wishkaro.app.domain

/**
 * The user's reminder settings (FR-REM-03, FR-REM-08, FR-REM-09).
 *
 * A mirror of the server's row, not a second source of truth. The Settings screen edits this
 * copy, the sync engine pushes it, and the server's answer wins when it is newer — the same
 * per-record last-write-wins the rest of the data uses.
 */
data class ReminderSettings(
    val reminderHour: Int = 9,
    val ladderD7: Boolean = true,
    val ladderD1: Boolean = true,
    val ladderD0: Boolean = true,
    val quietHoursStart: Int? = null,
    val quietHoursEnd: Int? = null,
    val digestEnabled: Boolean = false,
    val digestHour: Int = 9,
    val timezone: String = "Asia/Kolkata",
    val updatedAt: String = "1970-01-01T00:00:00Z",
    val pendingSync: Boolean = false,
) {
    val quietHoursOn: Boolean get() = quietHoursStart != null && quietHoursEnd != null
}

/**
 * Quiet hours (FR-REM-09), as the client needs them.
 *
 * The server applies this when it writes a reminder, which is why `scheduled_at_utc` arrives
 * already deferred and the client normally has nothing to do. This exists for the one path
 * the server cannot reach: a local fallback alarm for an event created offline that the
 * server has never materialised (docs/M2_PLAN.md §1.3).
 *
 * It is a second implementation of the rule, and that is a cost worth naming rather than
 * hiding. It is tested against the same table of cases as the server's, including the
 * midnight-spanning window that breaks the naive version.
 */
object QuietHours {

    /**
     * Whether a local hour falls inside the window.
     *
     * `start <= h < end` is right for 09:00–17:00 and silently wrong for 22:00–07:00, where
     * it matches nothing at all — so a user with overnight quiet hours gets woken at 3am by
     * an app that believes it is being quiet.
     *
     * Start inclusive, end exclusive, which is what makes "defer to the window's end"
     * terminate rather than defer again by a day.
     */
    fun isQuietHour(hour: Int, start: Int, end: Int): Boolean {
        if (start == end) return false
        return if (start < end) hour in start until end else hour >= start || hour < end
    }
}
