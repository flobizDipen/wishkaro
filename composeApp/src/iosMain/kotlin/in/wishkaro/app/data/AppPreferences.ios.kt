package `in`.wishkaro.app.data

import platform.Foundation.NSUserDefaults

/**
 * `NSUserDefaults`, which is the correct home for this rather than a placeholder.
 *
 * Worth saying plainly, because the file beside this one — `SecureStorage.ios.kt` — uses the
 * same API and carries a warning that it is a stand-in for the Keychain. The difference is what
 * is being stored. A session token in `NSUserDefaults` is a security defect; a boolean saying
 * this install has shown its introduction is exactly what user defaults are for.
 *
 * iOS is not shipped in v1 (D15); this keeps the target compiling, like everything else in
 * `iosMain`, and here it would also be right if it shipped.
 */
actual fun createAppPreferences(): AppPreferences = IosAppPreferences()

class IosAppPreferences : AppPreferences {
    private val defaults = NSUserDefaults.standardUserDefaults

    override fun getBoolean(key: String, default: Boolean): Boolean =
        if (defaults.objectForKey(key) == null) default else defaults.boolForKey(key)

    override fun putBoolean(key: String, value: Boolean) {
        defaults.setBool(value, key)
        // The Android actual uses `commit()` rather than `apply()` so the flag cannot be lost
        // to a process death right after the user leaves the carousel. This is the same
        // intent: `synchronize` is a no-op on modern iOS, but asking for it states that the
        // write is meant to be durable at this point rather than eventually.
        defaults.synchronize()
    }
}
