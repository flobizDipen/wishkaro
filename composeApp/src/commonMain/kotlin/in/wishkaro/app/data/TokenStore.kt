package `in`.wishkaro.app.data

/**
 * Platform secure storage for session tokens (FR-AUTH-03).
 *
 * Android backs this with EncryptedSharedPreferences; iOS will use the Keychain when it ships.
 * Nothing above this interface ever sees where the bytes live.
 */
interface SecureStorage {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
    fun clear()
}

/** Expect/actual boundary. The actual lives in androidMain / iosMain. */
expect fun createSecureStorage(): SecureStorage

/**
 * The session, as the rest of the app sees it.
 *
 * Reads hit platform secure storage, which is cheap but not free, so the values are cached in
 * memory after the first read. There is exactly one session per install, so a cache is safe.
 */
class TokenStore(private val storage: SecureStorage) {

    private var cachedAccess: String? = null
    private var cachedRefresh: String? = null
    private var loaded = false

    private fun load() {
        if (loaded) return
        cachedAccess = storage.getString(KEY_ACCESS)
        cachedRefresh = storage.getString(KEY_REFRESH)
        loaded = true
    }

    fun accessToken(): String? {
        load()
        return cachedAccess
    }

    fun refreshToken(): String? {
        load()
        return cachedRefresh
    }

    fun isSignedIn(): Boolean = refreshToken() != null

    fun save(accessToken: String, refreshToken: String) {
        storage.putString(KEY_ACCESS, accessToken)
        storage.putString(KEY_REFRESH, refreshToken)
        cachedAccess = accessToken
        cachedRefresh = refreshToken
        loaded = true
    }

    fun saveUser(id: String, email: String, name: String?) {
        storage.putString(KEY_USER_ID, id)
        storage.putString(KEY_USER_EMAIL, email)
        if (name != null) storage.putString(KEY_USER_NAME, name)
    }

    fun userName(): String? = storage.getString(KEY_USER_NAME)

    fun userEmail(): String? = storage.getString(KEY_USER_EMAIL)

    fun clear() {
        storage.clear()
        cachedAccess = null
        cachedRefresh = null
        loaded = true
    }

    private companion object {
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_USER_ID = "user_id"
        const val KEY_USER_EMAIL = "user_email"
        const val KEY_USER_NAME = "user_name"
    }
}
