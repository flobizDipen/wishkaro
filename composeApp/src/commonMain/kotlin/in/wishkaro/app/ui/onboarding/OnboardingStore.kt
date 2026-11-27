package `in`.wishkaro.app.ui.onboarding

import `in`.wishkaro.app.data.AppPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the value carousel has been shown — `FR-AUTH-01`'s "once".
 *
 * Deliberately NOT on `AppStore`. That is a Koin singleton on the Application holding UI state,
 * and M3 was bitten twice by UI state outliving the Activity while M4 moved navigation off it
 * for the same reason. This holds one boolean, reads it once at construction, and writes it
 * twice in the life of an install.
 *
 * The flag is read in the constructor rather than lazily: it is consulted on the very first
 * composition, before anything has had a chance to suspend, and a `null` "not read yet" state
 * would mean a frame of neither-carousel-nor-sign-in on every launch — which is the blank-frame
 * shape this app has already paid for three times.
 */
class OnboardingStore(private val preferences: AppPreferences) {

    private val _seen = MutableStateFlow(preferences.getBoolean(KEY_SEEN, false))

    /** True once the user has reached the end of the carousel, or skipped it. */
    val seen: StateFlow<Boolean> = _seen.asStateFlow()

    /**
     * Called when the carousel is finished OR skipped — the same call for both, because
     * `FR-AUTH-01` says *skippable*, and a skip that does not count as having seen it is a
     * carousel that reappears until the user gives in to it.
     *
     * Idempotent: a double tap on Skip writes the same value twice and navigates once.
     */
    fun markSeen() {
        if (_seen.value) return
        preferences.putBoolean(KEY_SEEN, true)
        _seen.value = true
    }

    private companion object {
        const val KEY_SEEN = "onboarding_seen_v1"
    }
}
