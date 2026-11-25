package `in`.wishkaro.app.platform

import android.annotation.SuppressLint
import android.app.Activity

/**
 * The Activity currently in the foreground.
 *
 * Credential Manager needs one, and it has to be reachable from a Koin singleton that
 * outlives any single Activity. MainActivity sets this in onCreate and clears it in
 * onDestroy, so the reference never outlives the Activity it points at.
 */
@SuppressLint("StaticFieldLeak")
object ActivityHolder {
    @Volatile
    var current: Activity? = null
}
