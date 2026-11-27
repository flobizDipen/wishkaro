package `in`.wishkaro.app.data

/**
 * Device-local flags that are not secrets and must outlive the task.
 *
 * ## Why this is not `SecureStorage`
 *
 * `SecureStorage` was the obvious home — it exists, it is injected, it survives process death
 * and restart. It is the wrong one for exactly one reason: [TokenStore.clear] calls
 * `storage.clear()`, which empties the whole encrypted file on sign-out and on account
 * deletion. Putting "has seen the onboarding carousel" in there would mean the carousel came
 * back every time somebody signed out, and `FR-AUTH-01`'s requirement is **shown once**.
 *
 * It is also not the right shape: EncryptedSharedPreferences buys a Keystore round trip per
 * read to protect a boolean that says nothing about anybody.
 *
 * ## Why this is not `rememberSaveable` or the Navigation 3 back stack
 *
 * Both die with the task. Saved state survives process death while the task lives, and the
 * carousel has to survive the task being swiped away from Recents, the app being force-stopped,
 * and a reboot — which `M7_VERIFY.md` item 4 checks one at a time.
 *
 * ## Why this is not the database
 *
 * `LocalStore` is the account's mirror and is cleared when an account is deleted (M9,
 * FR-SET-04). "This install has shown its introduction" is a property of the install, not of
 * the account, and deleting an account should not re-introduce the app to the same person on
 * the same phone.
 *
 * Deliberately tiny: booleans only. Anything that wants more than a flag belongs in the
 * database or in the session, and a general-purpose key-value store on the side is how a
 * fourth source of truth gets started.
 */
interface AppPreferences {
    fun getBoolean(key: String, default: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}

/** Expect/actual boundary, like [createSecureStorage]. The actual lives in androidMain / iosMain. */
expect fun createAppPreferences(): AppPreferences
