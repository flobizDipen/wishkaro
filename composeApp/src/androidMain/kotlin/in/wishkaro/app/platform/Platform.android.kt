package `in`.wishkaro.app.platform

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import `in`.wishkaro.app.BuildConfig
import java.util.TimeZone
import java.util.UUID
import wishkaro.db.WishKaroDb

actual class DatabaseDriverFactory(private val context: Context) {
    actual fun create(): SqlDriver =
        AndroidSqliteDriver(WishKaroDb.Schema, context, "wishkaro.db")
}

actual object PlatformConfig {
    /**
     * From a build-config field, never a literal (engineering rules in BUILD_PROMPT_M0).
     * Override it with `wishkaro.apiBaseUrl` in local.properties.
     */
    actual val apiBaseUrl: String = BuildConfig.API_BASE_URL
    actual val platformName: String = "ANDROID"
}

actual fun currentTimeZoneId(): String = TimeZone.getDefault().id

actual fun newId(): String = UUID.randomUUID().toString()
