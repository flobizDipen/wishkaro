package `in`.wishkaro.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Tokens live in EncryptedSharedPreferences, keyed by a master key in the Android Keystore
 * (FR-AUTH-03 / REQUIREMENTS.md §11).
 *
 * The Application sets [appContext] before Koin builds anything that needs storage. An
 * `expect fun` cannot take a parameter, so the context arrives this way rather than through
 * the factory signature.
 */
@SuppressLint("StaticFieldLeak")
object SecureStorageContext {
    lateinit var appContext: Context
}

actual fun createSecureStorage(): SecureStorage =
    AndroidSecureStorage(SecureStorageContext.appContext)

private const val FILE_NAME = "wishkaro_secure_prefs"
private const val TAG = "SecureStorage"

// androidx.security-crypto deprecated EncryptedSharedPreferences without shipping a
// replacement. BUILD_PROMPT_M0 names it explicitly, and it still does what it says on a
// Keystore-backed device, so M0 uses it and the warning is suppressed deliberately rather
// than by accident.
@Suppress("DEPRECATION")
class AndroidSecureStorage(private val context: Context) : SecureStorage {

    private val prefs: SharedPreferences by lazy { openOrRecreate() }

    private fun openOrRecreate(): SharedPreferences = try {
        open()
    } catch (e: Exception) {
        // A keystore entry can be invalidated — app data restored to a new device, the
        // user changing their lock screen on some OEMs. The stored tokens are unreadable
        // either way, so dropping the file and signing in again beats crashing at launch.
        Log.w(TAG, "Encrypted prefs unreadable, recreating and signing out", e)
        context.deleteSharedPreferences(FILE_NAME)
        open()
    }

    private fun open(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }
}
