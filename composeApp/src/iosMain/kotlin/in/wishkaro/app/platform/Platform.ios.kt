package `in`.wishkaro.app.platform

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import platform.Foundation.NSTimeZone
import platform.Foundation.NSUUID
import platform.Foundation.localTimeZone
import wishkaro.db.WishKaroDb

/**
 * iOS is kept COMPILING, not shipped (D15 / BUILD_PROMPT_M0 scope).
 *
 * The driver, timezone and id generation are real — they cost nothing and keep the shared
 * data layer honest. Sign-in and notifications are stubs that fail loudly rather than
 * silently pretending to work, so nobody mistakes this for a working iOS build.
 */
actual class DatabaseDriverFactory {
    actual fun create(): SqlDriver = NativeSqliteDriver(WishKaroDb.Schema, "wishkaro.db")
}

actual object PlatformConfig {
    // iOS has no BuildConfig. When iOS ships this reads from Info.plist; today it points at
    // the simulator's host loopback so the value is at least not a lie.
    actual val apiBaseUrl: String = "http://127.0.0.1:3000"
    actual val platformName: String = "IOS"
}

actual fun currentTimeZoneId(): String = NSTimeZone.localTimeZone.name

actual fun newId(): String = NSUUID().UUIDString()

class IosGoogleSignInClient : GoogleSignInClient {
    override suspend fun signIn(): GoogleSignInResult =
        GoogleSignInResult.Failure("Google Sign-In is not implemented on iOS yet (v1 is Android-first)")
}

class IosNotificationGateway : NotificationGateway {
    override fun hasPermission(): Boolean = false
    override suspend fun requestPermission(): Boolean = false
    override suspend fun currentPushToken(): String? = null
}
