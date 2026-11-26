package `in`.wishkaro.app.data.remote

import `in`.wishkaro.app.data.TokenStore
import `in`.wishkaro.app.data.repository.PlanApi
import `in`.wishkaro.app.data.repository.SyncApi
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

class ApiException(
    val status: Int,
    val code: String,
    override val message: String,
    /**
     * The server's per-issue detail, when it sent any. Empty for every error that is not a
     * validation failure — and empty, too, for a validation failure the client could not
     * parse, which callers must treat as "no idea which row", not as "no rows at fault".
     */
    val details: List<ApiErrorIssue> = emptyList(),
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
) : SyncApi, PlanApi {
    /** Serialises token rotation. See [renewAccessToken] for why this is load-bearing. */
    private val refreshMutex = Mutex()

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

    override suspend fun fetchPlan(days: Int): PlanResponse =
        authed { token ->
            client.get("$baseUrl/v1/reminders/plan") {
                header("Authorization", "Bearer $token")
                parameter("days", days)
            }
        }.decode()

    override suspend fun reportDelivery(body: DeliveryReportRequest): DeliveryReportResponse =
        authed { token ->
            client.post("$baseUrl/v1/reminders/delivery") {
                header("Authorization", "Bearer $token")
                setBody(body)
            }
        }.decode()

    override suspend fun syncPull(cursor: String?): SyncPullResponse =
        authed { token ->
            client.get("$baseUrl/v1/sync") {
                header("Authorization", "Bearer $token")
                if (cursor != null) parameter("cursor", cursor)
            }
        }.decode()

    override suspend fun syncPush(body: SyncPushRequest): SyncPushResponse =
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

        val renewed = renewAccessToken(usedToken = access)
        return call(renewed)
    }

    /**
     * Exchanges the refresh token for a new pair — at most once, however many callers need it.
     *
     * The mutex is not an optimisation. Refresh tokens ROTATE: using one invalidates it and
     * mints a replacement, which is what makes a stolen token detectable (see RefreshToken in
     * schema.prisma). So two coroutines that both hit a 401 on an expired access token and
     * both POST the same refresh token produce one success and one failure — and the failure
     * path clears the session and signs the user out.
     *
     * M0 and M1 never saw this because they only ever made one authenticated call at a time.
     * M2 fetches the reminder plan alongside a sync and drains the delivery outbox alongside
     * a push, so the race became reachable, and it was observed: the phone signed itself out
     * on the first launch after the M2 server deployed.
     *
     * [usedToken] is the access token the caller already found to be rejected. Re-reading the
     * store after taking the lock and finding something different means another coroutine has
     * already refreshed, so this one takes that result instead of burning a second rotation.
     */
    private suspend fun renewAccessToken(usedToken: String): String = refreshMutex.withLock {
        val current = tokens.accessToken()
        if (current != null && current != usedToken) return@withLock current

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
        renewed.accessToken
    }

    private suspend inline fun <reified T> HttpResponse.decode(): T {
        if (status.isSuccess()) return body()
        val text = bodyAsText()
        val parsed = runCatching { json.decodeFromString<ApiErrorBody>(text) }.getOrNull()
        throw ApiException(
            status = status.value,
            code = parsed?.error?.code ?: "HTTP_${status.value}",
            message = parsed?.error?.message ?: text.ifBlank { status.description },
            details = parsed?.error?.details.orEmpty(),
        )
    }

    private fun HttpStatusCode.isSuccess(): Boolean = value in 200..299
}
