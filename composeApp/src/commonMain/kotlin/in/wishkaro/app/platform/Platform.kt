package `in`.wishkaro.app.platform

import `in`.wishkaro.app.domain.newUuidV7
import app.cash.sqldelight.db.SqlDriver

/** Creates the SQLDelight driver. Android: AndroidSqliteDriver. iOS: NativeSqliteDriver. */
expect class DatabaseDriverFactory {
    fun create(): SqlDriver
}

/** Build-time configuration. The API base URL is a build-config field, never a literal. */
expect object PlatformConfig {
    val apiBaseUrl: String
    val platformName: String
}

/** The device's IANA timezone, e.g. "Asia/Kolkata". Reported to the server on sign-in. */
expect fun currentTimeZoneId(): String

/**
 * A new, stable identifier for a record created on this device (D23).
 *
 * No longer a platform seam. Both platforms used to reach for their own UUID type, which gave
 * v4 ids where M0 and M1 both documented v7 — and gave them in different letter cases. One
 * shared implementation is the fix: see [newUuidV7].
 */
fun newId(): String = newUuidV7()

/**
 * The outcome of asking the platform to sign the user in with Google.
 *
 * The only thing that leaves the platform layer is the ID token — the server verifies it
 * against Google's certs, and nothing the client claims about the user is trusted.
 */
sealed interface GoogleSignInResult {
    data class Success(val idToken: String) : GoogleSignInResult
    data object Cancelled : GoogleSignInResult
    data class Failure(val message: String) : GoogleSignInResult
}

/** Platform Google Sign-In. Android uses Credential Manager (FR-AUTH-02). */
interface GoogleSignInClient {
    suspend fun signIn(): GoogleSignInResult
}

/** Notification permission and delivery, owned by the platform layer. */
interface NotificationGateway {
    /** False on Android 13+ until the user grants POST_NOTIFICATIONS. */
    fun hasPermission(): Boolean

    /** Shows the runtime permission dialog. No-op where the platform does not need one. */
    suspend fun requestPermission(): Boolean

    /** The current FCM registration token, or null if one is not available yet. */
    suspend fun currentPushToken(): String?
}
