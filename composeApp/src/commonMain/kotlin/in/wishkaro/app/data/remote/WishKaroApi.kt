package `in`.wishkaro.app.data.remote

import `in`.wishkaro.app.data.TokenStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class ApiException(
    val status: Int,
    val code: String,
    override val message: String,
) : Exception("$status $code: $message")

/**
 * The HTTP client.
 *
 * Refresh is handled here rather than in a Ktor auth plugin so the retry is explicit and
 * easy to reason about: one 401 triggers at most one refresh and at most one replay. Anything
 * more elaborate hides the failure mode that actually matters — a refresh token that has been
 * revoked, which must sign the user out rather than loop.
 */
class WishKaroApi(
    private val baseUrl: String,
    private val tokens: TokenStore,
    engine: HttpClientEngine? = null,
    private val onSessionExpired: suspend () -> Unit = {},
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val client: HttpClient = run {
        val config: io.ktor.client.HttpClientConfig<*>.() -> Unit = {
            install(ContentNegotiation) { json(json) }
            defaultRequest { contentType(ContentType.Application.Json) }
            expectSuccess = false
        }
        if (engine != null) HttpClient(engine, config) else HttpClient(config)
    }

    // ---- auth (no access token required) ----

    suspend fun signInWithGoogle(idToken: String, timezone: String): SessionDto =
        client.post("$baseUrl/v1/auth/google") {
            setBody(GoogleSignInRequest(idToken, timezone))
        }.decode()

    suspend fun refresh(refreshToken: String): SessionDto =
        client.post("$baseUrl/v1/auth/refresh") {
            setBody(RefreshRequest(refreshToken))
        }.decode()

    suspend fun logout(refreshToken: String) {
        runCatching {
            client.post("$baseUrl/v1/auth/logout") { setBody(RefreshRequest(refreshToken)) }
        }
    }

    // ---- authenticated ----

    suspend fun registerDevice(body: RegisterDeviceRequest): RegisterDeviceResponse =
        authed { token ->
            client.post("$baseUrl/v1/devices") {
                header("Authorization", "Bearer $token")
                setBody(body)
            }
        }.decode()

    suspend fun requestTestReminder(): TestReminderResponse =
        authed { token ->
            client.post("$baseUrl/v1/reminders/test") { header("Authorization", "Bearer $token") }
        }.decode()

    suspend fun syncPull(cursor: String?): SyncPullResponse =
        authed { token ->
            client.get("$baseUrl/v1/sync") {
                header("Authorization", "Bearer $token")
                if (cursor != null) parameter("cursor", cursor)
            }
        }.decode()

    suspend fun syncPush(body: SyncPushRequest): SyncPushResponse =
        authed { token ->
            client.post("$baseUrl/v1/sync") {
                header("Authorization", "Bearer $token")
                setBody(body)
            }
        }.decode()

    /** Runs [call] with the current access token, refreshing once on a 401. */
    private suspend fun authed(call: suspend (String) -> HttpResponse): HttpResponse {
        val access = tokens.accessToken()
            ?: throw ApiException(401, "NO_SESSION", "Not signed in")

        val response = call(access)
        if (response.status != HttpStatusCode.Unauthorized) return response

        val refreshToken = tokens.refreshToken()
        if (refreshToken == null) {
            onSessionExpired()
            throw ApiException(401, "NO_SESSION", "Not signed in")
        }

        val renewed = runCatching { refresh(refreshToken) }.getOrElse {
            // The refresh token is gone or already used. Looping would never recover.
            tokens.clear()
            onSessionExpired()
            throw ApiException(401, "SESSION_EXPIRED", "Please sign in again")
        }
        tokens.save(renewed.accessToken, renewed.refreshToken)
        return call(renewed.accessToken)
    }

    private suspend inline fun <reified T> HttpResponse.decode(): T {
        if (status.isSuccess()) return body()
        val text = bodyAsText()
        val parsed = runCatching { json.decodeFromString<ApiErrorBody>(text) }.getOrNull()
        throw ApiException(
            status = status.value,
            code = parsed?.error?.code ?: "HTTP_${status.value}",
            message = parsed?.error?.message ?: text.ifBlank { status.description },
        )
    }

    private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299
}
