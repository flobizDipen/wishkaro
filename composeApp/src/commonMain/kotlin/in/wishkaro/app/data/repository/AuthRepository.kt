package `in`.wishkaro.app.data.repository

import `in`.wishkaro.app.data.TokenStore
import `in`.wishkaro.app.data.local.LocalStore
import `in`.wishkaro.app.data.remote.ExportFiles
import `in`.wishkaro.app.data.remote.RegisterDeviceRequest
import `in`.wishkaro.app.data.remote.TestReminderResponse
import `in`.wishkaro.app.data.remote.WishKaroApi
import `in`.wishkaro.app.platform.GoogleSignInClient
import `in`.wishkaro.app.platform.GoogleSignInResult
import `in`.wishkaro.app.platform.NotificationGateway
import `in`.wishkaro.app.platform.PlatformConfig
import `in`.wishkaro.app.platform.currentTimeZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface AuthState {
    data object Unknown : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val name: String?, val email: String?) : AuthState
}

class AuthRepository(
    private val api: WishKaroApi,
    private val tokens: TokenStore,
    private val local: LocalStore,
    private val googleSignIn: GoogleSignInClient,
    private val notifications: NotificationGateway,
) {
    private val _state = MutableStateFlow<AuthState>(AuthState.Unknown)
    val state: StateFlow<AuthState> = _state.asStateFlow()

    fun restore() {
        _state.value = if (tokens.isSignedIn()) {
            AuthState.SignedIn(tokens.userName(), tokens.userEmail())
        } else {
            AuthState.SignedOut
        }
    }

    /**
     * Google Sign-In, then token exchange.
     *
     * The ID token goes straight to the server, which verifies it against Google's certs.
     * Nothing the client says about who the user is is trusted (FR-AUTH-03).
     */
    suspend fun signIn(): Result<Unit> = runCatching {
        when (val result = googleSignIn.signIn()) {
            is GoogleSignInResult.Cancelled -> throw SignInCancelled()
            is GoogleSignInResult.Failure -> error(result.message)
            is GoogleSignInResult.Success -> {
                val session = api.signInWithGoogle(result.idToken, currentTimeZoneId())
                tokens.save(session.accessToken, session.refreshToken)
                session.user?.let { tokens.saveUser(it.id, it.email, it.name) }
                _state.value = AuthState.SignedIn(session.user?.name, session.user?.email)
            }
        }
    }

    /**
     * Registers this device's FCM token so the server has somewhere to send reminders, and
     * reports the device timezone so pending reminders are rescheduled if it has changed
     * (FR-EVT-08).
     *
     * Safe to call repeatedly — the server keys on the token.
     */
    suspend fun registerDevice(): Result<Unit> = runCatching {
        val token = notifications.currentPushToken() ?: error("No FCM token yet")
        val response = api.registerDevice(
            RegisterDeviceRequest(
                fcmToken = token,
                platform = PlatformConfig.platformName,
                timezone = currentTimeZoneId(),
            ),
        )
        // A non-zero remindersRescheduled means the device changed timezone since the last
        // launch and the server has already moved the pending reminders to match (FR-EVT-08).
        // Nothing for the UI to do about it; the field is there for diagnostics.
        check(response.remindersRescheduled >= 0)
    }

    /**
     * Returns the whole response, not just the time.
     *
     * M2's self-test UI (FR-REM-07) holds the dedupe key and watches this device's own
     * de-duplication table for it, because "queued" is not evidence of delivery and the
     * point of the feature is for the user to end up convinced.
     */
    suspend fun requestTestReminder(): Result<TestReminderResponse> = runCatching {
        api.requestTestReminder()
    }

    /** Sign-out clears tokens AND the local database (FR-AUTH-07). */
    suspend fun signOut() {
        tokens.refreshToken()?.let { api.logout(it) }
        tokens.clear()
        local.wipe()
        _state.value = AuthState.SignedOut
    }

    fun onSessionExpired() {
        tokens.clear()
        _state.value = AuthState.SignedOut
    }

    /**
     * Delete this account, irreversibly (FR-SET-04, FR-AUTH-08).
     *
     * [confirmEmail] is what the user typed. It is sent rather than compared here: the server
     * is what decides, so a stale build, a second client or a curl cannot skip the check. The
     * screen compares it too, to give an answer before a round trip — two checks of the same
     * thing, with the authoritative one on the side that performs the deletion.
     *
     * The local wipe happens only AFTER the server has confirmed. Wiping first would destroy
     * the user's data on a request that failed, which is the one outcome worse than the
     * feature not working.
     */
    /** FR-SET-03. The bytes only; where they go is the caller's business. */
    suspend fun fetchExport(): Result<ExportFiles> = runCatching { api.fetchExport() }

    suspend fun deleteAccount(confirmEmail: String): Result<Unit> = runCatching {
        api.deleteAccount(confirmEmail)
        tokens.clear()
        local.wipe()
        _state.value = AuthState.SignedOut
    }

    /**
     * The account is gone — deleted here, on another device, or from the web page (M9, C9).
     *
     * **Wipes the local database**, which [onSessionExpired] deliberately does not. An expired
     * session is reversible: sign in again and everything is still there. A deleted account is
     * not, and leaving 566 people on the phone after their owner asked for them to be erased
     * is a privacy failure on the device even when the server side is spotless.
     *
     * FR-AUTH-07 already wipes on sign-out. What is new is that this can now happen without
     * the user touching the app at all.
     */
    suspend fun onAccountGone() {
        tokens.clear()
        local.wipe()
        _state.value = AuthState.SignedOut
    }
}

class SignInCancelled : Exception("Sign-in cancelled")
