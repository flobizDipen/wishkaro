package `in`.wishkaro.app.data

import android.content.Context

/**
 * Plain `SharedPreferences`. Not encrypted, deliberately — nothing here is about a person.
 *
 * The context arrives through [SecureStorageContext], which the Application already sets before
 * Koin builds anything: an `expect fun` cannot take a parameter, and adding a second holder
 * object for the same context would be a second thing to remember to initialise.
 *
 * `MODE_PRIVATE`, so the file is readable only by this app's uid — which is every
 * `SharedPreferences` file's default and is stated here because this one is not encrypted and
 * somebody will reasonably ask.
 */
actual fun createAppPreferences(): AppPreferences =
    AndroidAppPreferences(SecureStorageContext.appContext)

private const val PREFS_FILE = "wishkaro_app_prefs"

class AndroidAppPreferences(context: Context) : AppPreferences {

    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    override fun getBoolean(key: String, default: Boolean): Boolean = prefs.getBoolean(key, default)

    /**
     * `commit()`, not `apply()`, and this is the one line in the file worth arguing about.
     *
     * `apply()` writes in the background. The carousel's flag is written at the exact moment
     * the user leaves the carousel, which is also a plausible moment for them to put the phone
     * down and for the process to be killed — and an `apply()` that had not reached disk would
     * show the introduction again. `FR-AUTH-01` says once.
     *
     * The cost is a blocking disk write on whichever thread calls this. It happens twice in the
     * life of an install (skip or finish), it is a single boolean, and `commit()` on a
     * same-process preferences file is sub-millisecond. Being certain is worth more here than
     * the microseconds are.
     */
    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).commit()
    }
}
