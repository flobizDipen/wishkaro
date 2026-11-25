package `in`.wishkaro.app.platform

/**
 * Arms the device's own alarms from the stored plan (FR-REM-02, FR-REM-10).
 *
 * The seam exists because `scheduled_reminder` is the durable truth and the platform alarms
 * are derived state. Alarms do not survive a reboot, an app update or a force-stop — and on
 * several of the OEMs FR-REM-06 names, force-stop is what the battery manager does to you
 * overnight — so everything that arms one has to be able to do it again from the table.
 *
 * iOS has no implementation yet. M2 is Android-first and the iOS target only has to compile;
 * a no-op is the honest shape for that, rather than a half-written UNUserNotificationCenter
 * path nobody has run.
 */
interface ReminderScheduler {

    /**
     * Cancels everything and re-arms from the table. Idempotent on purpose: it is called on
     * launch, after every plan refresh, on boot and after an app update, and the cheapest way
     * to be correct under all four is to not try to be clever about which alarms changed.
     */
    suspend fun rearmAll()

    /** Drops one alarm, for a reminder the latest plan no longer contains. */
    fun cancel(dedupeKey: String)

    /**
     * Whether the OS will honour an exact alarm.
     *
     * False is not a failure: delivery degrades to an inexact alarm that still pierces Doze
     * but can drift by minutes. The Settings screen uses this to decide whether to offer the
     * permission (FR-SET-02), which is why it is on the interface rather than hidden inside
     * the Android implementation.
     */
    fun canScheduleExact(): Boolean

    /** Opens the OS screen where the user can grant exact alarms. No-op where there is none. */
    fun requestExactAlarmPermission()
}

/** The default on a platform M2 does not schedule on. Keeps iOS compiling without pretending. */
class NoopReminderScheduler : ReminderScheduler {
    override suspend fun rearmAll() = Unit
    override fun cancel(dedupeKey: String) = Unit
    override fun canScheduleExact(): Boolean = false
    override fun requestExactAlarmPermission() = Unit
}
