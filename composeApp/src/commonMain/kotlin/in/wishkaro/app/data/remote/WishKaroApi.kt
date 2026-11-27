package `in`.wishkaro.app.data.remote

import `in`.wishkaro.app.data.TokenStore
import `in`.wishkaro.app.data.repository.PlanApi
import `in`.wishkaro.app.data.repository.SyncApi
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
/**
 * The server's code for "this account no longer exists" — `AuthService` answers it from both
 * the access-token check and the refresh path, so the client has one string to match rather
 * than two.
 */
const val USER_GONE: String = "USER_GONE"

class WishKaroApi(
    private val baseUrl: String,
    private val tokens: TokenStore,
    engine: HttpClientEngine? = null,
    private val onSessionExpired: suspend () -> Unit = {},
    /**
     * The account is GONE, not merely signed out (M9, FR-SET-04).
     *
     * A different callback from [onSessionExpired] because the two need different responses
     * and only one of them is reversible. An expired session means sign in again and your
     * data is still here; a deleted account means there is nothing to sign in to, and the
     * local database must go with it — otherwise a phone keeps showing 566 people, offline,
     * for ever, after their owner asked for them to be erased. FR-AUTH-07 already establishes
     * that losing a session wipes local data; the web deletion path makes that reachable
     * without the user ever opening the app. `docs/M9_PLAN.md` C9.
     */
    private val onAccountGone: suspend () -> Unit = {},
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

    /**
     * FR-WSH-01..04. One drafted message.
     *
     * POST although it reads nothing: it costs money, it is rate limited, and it is not
     * idempotent — Regenerate exists precisely to get a different answer to the same
     * question.
     *
     * Throws [ApiException] like every other call here, and the composer is expected to
     * catch it: a 429, a 503 and a dead network all mean the same thing to the user, which
     * is "here is a message this phone wrote, edit it" (docs/M5_PLAN.md §4.2).
     */
    suspend fun draftWish(body: DraftWishRequest): DraftWishResponse =
        authed { token ->
            client.post("$baseUrl/v1/wishes/draft") {
                header("Authorization", "Bearer $token")
                setBody(body)
            }
        }.decode()


    // ---- M6: posters (FR-POS-01..13) --------------------------------------------------

    /**
     * FR-POS-04's library, filtered for the occasion by the SERVER (FR-POS-13).
     *
     * The picker filters locally too, from `POSTER_THEMES`, so it renders with the radio
     * off — which is what FR-POS-04's "ships with the app" is protecting. This call is the
     * authority: a phone whose sync has not caught up has a person row that still says
     * alive, and that window is where `454ea75` shipped. When the two disagree the app
     * takes whichever is QUIETER, never whichever is fresher.
     */
    suspend fun posterThemes(personId: String, eventType: String): ThemesResponse =
        authed { token ->
            client.get("$baseUrl/v1/themes") {
                header("Authorization", "Bearer $token")
                parameter("personId", personId)
                parameter("eventType", eventType)
            }
        }.decode()

    /** FR-POS-09. Accepted and queued; poll [generationStatus]. */
    suspend fun requestPoster(body: RequestPosterRequest): RequestPosterResponse =
        authed { token ->
            client.post("$baseUrl/v1/ai/poster") {
                header("Authorization", "Bearer $token")
                setBody(body)
            }
        }.decode()

    suspend fun generationStatus(generationId: String): GenerationStatusResponse =
        authed { token ->
            client.get("$baseUrl/v1/ai/generation/$generationId") {
                header("Authorization", "Bearer $token")
            }
        }.decode()

    /** FR-HIS-01's "and the poster used", for a phone that has not cached the file. */
    suspend fun postersFor(personId: String): PosterListResponse =
        authed { token ->
            client.get("$baseUrl/v1/posters") {
                header("Authorization", "Bearer $token")
                parameter("personId", personId)
            }
        }.decode()

    /** FR-POS-12 / FR-SET-07. Both surfaces: a poster and a wish draft. */
    suspend fun reportContent(body: ReportContentRequest) {
        authed { token ->
            client.post("$baseUrl/v1/ai/report") {
                header("Authorization", "Bearer $token")
                setBody(body)
            }
        }.decode<Map<String, String>>()
    }

    /**
     * The person's photo (FR-PPL-04, FR-POS-01).
     *
     * Raw bytes with an image content type rather than multipart — the server registers a
     * raw parser for exactly three image types and this is one request with one file and
     * no fields.
     */
    suspend fun uploadPhoto(personId: String, bytes: ByteArray, contentType: String): PhotoUploadResponse =
        authed { token ->
            client.post("$baseUrl/v1/people/$personId/photo") {
                header("Authorization", "Bearer $token")
                contentType(ContentType.parse(contentType))
                setBody(bytes)
            }
        }.decode()

    /** The person's current photo URL, relative, or null. */
    suspend fun photoUrl(personId: String): String? =
        authed { token ->
            client.get("$baseUrl/v1/people/$personId/photo") {
                header("Authorization", "Bearer $token")
            }
        }.decode<PhotoUrlResponse>().url

    /**
     * The bytes behind a signed media URL.
     *
     * No Authorization header, deliberately: `MediaController` is `@Public` and the
     * SIGNATURE is the authorisation, because the URL is also handed to WhatsApp, which
     * fetches it as itself with no session of ours. Sending a bearer token to a route that
     * ignores it would be a token in one more place for no benefit.
     */
    suspend fun downloadMedia(absoluteUrl: String): ByteArray {
        /**
         * Status-checked like every other call here.
         *
         * This was `client.get(url).body()`, and the client is built with
         * `expectSuccess = false` — so a non-2xx body came back as bytes. A signed media
         * URL lives ten minutes and the poll can run nearly thirty seconds before the
         * fetch, so an expired signature returns a 403 JSON body; those forty-odd bytes
         * became `posterBytes`, which enabled both hand-over buttons and would have
         * written the error JSON to `poster.png` and handed it to a contact.
         */
        val response = client.get(absoluteUrl)
        if (response.status.value !in 200..299) {
            throw ApiException(
                status = response.status.value,
                code = "MEDIA_FETCH_FAILED",
                message = "Could not fetch ${'$'}absoluteUrl",
            )
        }
        return response.body()
    }

    suspend fun deletePhoto(personId: String) {
        authed { token ->
            client.delete("$baseUrl/v1/people/$personId/photo") {
                header("Authorization", "Bearer $token")
            }
        }
    }

    /**
     * A signed media path joined to this API's base URL.
     *
     * The server returns a RELATIVE path for the local blob store, because it does not know
     * which host the phone reached it on and a Host header is not something to trust for a
     * capability URL. An R2 deployment returns an absolute one. This handles both rather
     * than making every call site remember which.
     */
    fun absoluteUrl(url: String): String =
        if (url.startsWith("http://") || url.startsWith("https://")) url else "$baseUrl$url"

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

    /**
     * FR-SET-03's two artifacts: the lossless JSON and the CSV that re-imports.
     *
     * Fetched as TEXT, not parsed. The app is a pipe here — it hands the bytes to the user's
     * own save dialog and keeps no copy. Parsing an export into objects only to serialise them
     * again would mean this client had an opinion about the format, and then two things would
     * have to agree about it.
     */
    suspend fun fetchExport(): ExportFiles {
        val json = authed { token ->
            client.get("$baseUrl/v1/account/export") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        }
        val csv = authed { token ->
            client.get("$baseUrl/v1/account/export.csv?dataset=people") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
        }
        if (!json.status.isSuccess()) json.decode<Unit>()
        if (!csv.status.isSuccess()) csv.decode<Unit>()
        return ExportFiles(json = json.bodyAsText(), peopleCsv = csv.bodyAsText())
    }

    /**
     * `DELETE /v1/account` (FR-SET-04). Irreversible, and the server re-checks the typed
     * confirmation rather than trusting that a screen did.
     */
    suspend fun deleteAccount(confirmEmail: String) {
        val response = authed { token ->
            client.delete("$baseUrl/v1/account") {
                header(HttpHeaders.Authorization, "Bearer $token")
                setBody(DeleteAccountRequest(confirmEmail))
            }
        }
        if (!response.status.isSuccess()) response.decode<Unit>()
    }

    /** Runs [call] with the current access token, refreshing once on a 401. */
    private suspend fun authed(call: suspend (String) -> HttpResponse): HttpResponse {
        val access = tokens.accessToken()
            ?: throw ApiException(401, "NO_SESSION", "Not signed in")

        val response = call(access)
        if (response.status != HttpStatusCode.Unauthorized) return response

        /**
         * Read the code BEFORE refreshing, because after a hard delete the refresh token row
         * is gone too — so `refresh()` would answer INVALID_REFRESH_TOKEN and the account
         * being deleted would be indistinguishable from an ordinary expired session.
         *
         * The server answers USER_GONE from the auth guard itself (M9 closed that gap), so
         * this is the one place where "your account no longer exists" is actually legible.
         * Consuming the body is safe: this response is being discarded either way.
         */
        if (response.errorCode() == USER_GONE) {
            onAccountGone()
            throw ApiException(401, USER_GONE, "This account no longer exists")
        }

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

        val renewed = runCatching { refresh(refreshToken) }.getOrElse { cause ->
            // The refresh token is gone or already used. Looping would never recover.
            tokens.clear()
            if ((cause as? ApiException)?.code == USER_GONE) {
                // The other route to it: the access token was still inside its TTL when the
                // account was deleted, so the first call succeeded and only the refresh found
                // out. Same outcome, reached a moment later.
                onAccountGone()
                throw ApiException(401, USER_GONE, "This account no longer exists")
            }
            onSessionExpired()
            throw ApiException(401, "SESSION_EXPIRED", "Please sign in again")
        }
        tokens.save(renewed.accessToken, renewed.refreshToken)
        renewed.accessToken
    }

    /**
     * The `code` out of this app's one error envelope, or null when the body is not one.
     *
     * Deliberately total: a 401 from a proxy, a gateway or a half-deployed server is not JSON
     * at all, and a parse failure there must read as "no code" rather than as an exception
     * thrown from inside the retry path.
     */
    private suspend fun HttpResponse.errorCode(): String? =
        runCatching { json.decodeFromString<ApiErrorBody>(bodyAsText()).error.code }.getOrNull()

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
