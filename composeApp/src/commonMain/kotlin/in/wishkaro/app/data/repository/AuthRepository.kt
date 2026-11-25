package `in`.wishkaro.app.data.repository

import `in`.wishkaro.app.data.TokenStore
import `in`.wishkaro.app.data.local.LocalStore
import `in`.wishkaro.app.data.remote.RegisterDeviceRequest
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

    suspend fun requestTestReminder(): Result<String> = runCatching {
        api.requestTestReminder().scheduledAtUtc
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
}

class SignInCancelled : Exception("Sign-in cancelled")
