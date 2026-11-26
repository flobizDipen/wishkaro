package `in`.wishkaro.app.platform

/**
 * One contact as the device holds it, reduced to what import needs (FR-IMP-01).
 *
 * `photoUri` is read FOR DISPLAY ONLY. It is shown on the review screen so the user can
 * recognise who they are picking, and it is discarded when the screen closes — nothing is
 * written to `person.photo_key`, which stays reserved for M6, and nothing is uploaded.
 *
 * `rawBirthday` is the device's string, UNINTERPRETED. Android stores whatever the source
 * wrote: `1990-01-04`, `--01-04` with no year, or one of the sentinel years. Parsing it here
 * would be a second place where dates are interpreted; it goes through [ImportDates] like
 * every other string, so there is one rule and one table of cases.
 */
data class DeviceContact(
    val id: String,
    val displayName: String,
    val phone: String?,
    val rawBirthday: String?,
    val photoUri: String?,
)

/** What asking for a permission produced. */
enum class PermissionOutcome {
    GRANTED,

    /** Refused this time. Asking again is allowed, and the rationale is worth repeating. */
    DENIED,

    /**
     * Refused in a way Android will not ask about again.
     *
     * The system dialog simply never appears from here on, so a button that "asks again" is
     * a button that does nothing — the only route left is the app's own settings screen.
     * This is why it is a distinct outcome rather than a second DENIED.
     */
    DENIED_PERMANENTLY,
}

/**
 * Contacts, owned by the platform layer — shaped like [NotificationGateway], which already
 * does this job for POST_NOTIFICATIONS.
 *
 * Nothing above this interface ever sees a contact the user did not select: [readContacts]
 * is called only after the review screen has permission to show them, and the selection
 * happens above. Contacts the user did not pick must never reach a log line, an analytics
 * event or a crash report.
 */
interface ContactsGateway {
    fun hasPermission(): Boolean

    suspend fun requestPermission(): PermissionOutcome

    /** Opens this app's page in system Settings, the only route out of DENIED_PERMANENTLY. */
    fun openAppSettings()

    /** Every contact with a name. Empty when permission is not held. */
    suspend fun readContacts(): List<DeviceContact>
}
