package `in`.wishkaro.app.data

import platform.Foundation.NSUserDefaults

/**
 * Placeholder. When iOS ships this must be the Keychain (REQUIREMENTS.md §11) — NSUserDefaults
 * is not secure storage and is used here only to keep the target compiling.
 */
actual fun createSecureStorage(): SecureStorage = IosSecureStorage()

class IosSecureStorage : SecureStorage {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun getString(key: String): String? = defaults.stringForKey(key)

    override fun putString(key: String, value: String) = defaults.setObject(value, key)

    override fun remove(key: String) = defaults.removeObjectForKey(key)

    override fun clear() {
        listOf("access_token", "refresh_token", "user_id", "user_email", "user_name")
            .forEach(defaults::removeObjectForKey)
    }
}
