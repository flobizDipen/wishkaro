package `in`.wishkaro.app.ui.poster

import `in`.wishkaro.app.data.remote.ApiException
import `in`.wishkaro.app.data.remote.PosterDto
import `in`.wishkaro.app.data.remote.ReportContentRequest
import `in`.wishkaro.app.data.remote.RequestPosterRequest
import `in`.wishkaro.app.data.remote.WishKaroApi
import `in`.wishkaro.app.domain.Person
import `in`.wishkaro.app.domain.PersonEvent
import `in`.wishkaro.app.domain.PosterFormat
import `in`.wishkaro.app.domain.PosterTheme
import `in`.wishkaro.app.domain.Sentiment
import `in`.wishkaro.app.domain.defaultPosterThemeFor
import `in`.wishkaro.app.domain.posterThemesFor
import `in`.wishkaro.app.domain.upcomingFor
import `in`.wishkaro.app.platform.FileGateway
import `in`.wishkaro.app.platform.MessageSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/** Why a poster could not be made. The app's own words, never the server's error code. */
enum class PosterProblem {
    OFFLINE,
    /** The server refused the content (§10 rule 7). Deliberately vague on screen. */
    BLOCKED,
    UNAVAILABLE,
}

/** Where a generation has got to (FR-POS-09's "visible progress state"). */
enum class PosterStage { IDLE, WORKING, READY, FAILED }

data class PosterState(
    val person: Person? = null,
    val event: PersonEvent? = null,
    val occurrenceYear: Int = 0,
    val themes: List<PosterTheme> = emptyList(),
    val selectedThemeId: String? = null,
    val format: PosterFormat = PosterFormat.DEFAULT,
    val stage: PosterStage = PosterStage.IDLE,
    val poster: PosterDto? = null,
    /** The composited bytes, once fetched. What gets handed to another app. */
    val posterBytes: ByteArray? = null,
    val cacheHit: Boolean = false,
    val problem: PosterProblem? = null,
    val photoUrl: String? = null,
    val uploadingPhoto: Boolean = false,
    val handedOver: Boolean = false,
    val reported: Boolean = false,
    val loaded: Boolean = false,
) {
    /**
     * Solemn if EITHER the person or the occasion says so.
     *
     * **Copied in shape from `ComposerState.sentiment`, and the duplication is deliberate
     * rather than careless.** That property exists because the first version of the
     * composer read `person.isSolemn` alone and shipped `454ea75` — a death anniversary
     * created on the handset opened the celebratory composer, because `is_deceased` is
     * derived server-side and the local row still said false. M6's version of that failure
     * is confetti on a remembrance, which is worse, because a picture is what gets sent and
     * seen.
     *
     * `Person.isSolemn` now ORs the person's own events in, so this is belt and braces: the
     * EVENT being viewed is also consulted, and both inputs can only push TOWARDS solemn.
     * No person loaded yet is SOLEMN too, because that is the quieter guess and an empty
     * picker is better than a wrong one.
     */
    val sentiment: Sentiment
        get() {
            val person = person ?: return Sentiment.SOLEMN
            val solemn = person.isSolemn || event?.sentiment == Sentiment.SOLEMN
            return if (solemn) Sentiment.SOLEMN else Sentiment.CELEBRATORY
        }

    val isSolemn: Boolean get() = sentiment == Sentiment.SOLEMN

    val selectedTheme: PosterTheme?
        get() = themes.firstOrNull { it.id == selectedThemeId } ?: themes.firstOrNull()

    val working: Boolean get() = stage == PosterStage.WORKING

    // Data classes with a ByteArray need these, or equality is identity and a StateFlow
    // emits on every poll tick whether anything changed or not.
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is PosterState &&
                person == other.person &&
                event == other.event &&
                occurrenceYear == other.occurrenceYear &&
                themes == other.themes &&
                selectedThemeId == other.selectedThemeId &&
                format == other.format &&
                stage == other.stage &&
                poster == other.poster &&
                posterBytes?.size == other.posterBytes?.size &&
                cacheHit == other.cacheHit &&
                problem == other.problem &&
                photoUrl == other.photoUrl &&
                uploadingPhoto == other.uploadingPhoto &&
                handedOver == other.handedOver &&
                reported == other.reported &&
                loaded == other.loaded)

    override fun hashCode(): Int {
        var result = person?.hashCode() ?: 0
        result = 31 * result + (poster?.hashCode() ?: 0)
        result = 31 * result + stage.hashCode()
        result = 31 * result + (posterBytes?.size ?: 0)
        return result
    }
}

/**
 * The poster studio (FR-POS-01..13).
 *
 * ## What it does NOT do
 *
 * It never sends anything. FR-WSH-06 is unchanged by the payload being a picture: the
 * poster is handed to another app with the message beside it and the user presses send
 * there. There is no code path from here to a delivered message.
 *
 * It never derives a sentiment. The picker asks `posterThemesFor(person)`, which reads the
 * one mirrored implementation, and the server refuses anything outside the family anyway —
 * three gates, for the reason `posters.service.ts` gives: the picker protects the user, the
 * controller protects against a client bug, and the pipeline protects against a replayed
 * queue payload.
 */
class PosterStore(
    private val api: WishKaroApi,
    private val sender: MessageSender,
    private val files: FileGateway,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(PosterState())
    val state: StateFlow<PosterState> = _state.asStateFlow()

    /**
     * Open on a person, and optionally a specific event.
     *
     * Takes a [Person] rather than an id, as `WishStore.open` does: the screen has already
     * loaded them and a second read would be a second chance for the two to disagree about
     * who this is.
     *
     * `eventId` is null from the person screen, where the user means "the next thing coming
     * up for them". Resolved here so every entry point agrees what that means — and
     * resolved exactly as the composer resolves it, because the two screens must not
     * disagree about which occasion a person is about to be greeted for.
     */
    fun open(person: Person, eventId: String?) {
        val zone = TimeZone.currentSystemDefault()
        val today = Clock.System.todayIn(zone)
        val upcoming = upcomingFor(person, zone, today)

        val event = eventId?.let { id -> person.events.firstOrNull { it.id == id } }
            ?: upcoming.minByOrNull { it.daysUntil }?.event
            ?: person.events.firstOrNull()

        val occurrenceYear = (
            eventId?.let { id -> upcoming.firstOrNull { it.event.id == id }?.nextOccurrence?.year }
                ?: upcoming.minByOrNull { it.daysUntil }?.nextOccurrence?.year
                ?: today.year
            )

        _state.value = PosterState(
            person = person,
            event = event,
            occurrenceYear = occurrenceYear,
            themes = posterThemesFor(person),
            // The first of the occasion's OWN family, so there is no way to express a
            // default that is not allowed.
            selectedThemeId = defaultPosterThemeFor(person).id,
            loaded = true,
        )

        scope.launch { refreshPhoto(person.id) }
    }

    fun selectTheme(themeId: String) {
        val state = _state.value
        /**
         * Guarded, even though the picker only shows the allowed list.
         *
         * A UI that can only offer legal values today is a UI somebody adds a "recently
         * used" row to tomorrow. The cost of the check is one `any`; the cost of not having
         * it is the failure this whole milestone is written around.
         */
        if (state.themes.none { it.id == themeId }) return
        _state.value = state.copy(selectedThemeId = themeId, poster = null, posterBytes = null, stage = PosterStage.IDLE)
    }

    fun selectFormat(format: PosterFormat) {
        _state.value = _state.value.copy(
            format = format,
            // A different format is a different cache key and therefore a different
            // picture. Keeping the old one on screen under a new label would be a lie.
            poster = null,
            posterBytes = null,
            stage = PosterStage.IDLE,
        )
    }

    /** FR-POS-09: ask, then poll. */
    fun generate() {
        val state = _state.value
        val person = state.person ?: return
        val theme = state.selectedTheme ?: return

        _state.value = state.copy(
            stage = PosterStage.WORKING,
            problem = null,
            poster = null,
            posterBytes = null,
            handedOver = false,
            reported = false,
        )

        scope.launch {
            val accepted = try {
                api.requestPoster(
                    RequestPosterRequest(
                        personId = person.id,
                        eventId = state.event?.id,
                        eventType = (state.event?.type ?: person.events.firstOrNull()?.type)?.name
                            ?: "BIRTHDAY",
                        occurrenceYear = state.occurrenceYear,
                        themeId = theme.id,
                        format = state.format.wire,
                    ),
                )
            } catch (cause: ApiException) {
                _state.value = _state.value.copy(stage = PosterStage.FAILED, problem = problemFor(cause))
                return@launch
            } catch (_: Exception) {
                _state.value = _state.value.copy(stage = PosterStage.FAILED, problem = PosterProblem.OFFLINE)
                return@launch
            }

            poll(accepted.generationId)
        }
    }

    /**
     * Polling, with a ceiling.
     *
     * FR-POS-09 budgets p95 under 15 seconds on a cache miss and under 2 on a hit, so the
     * interval is short at the start and the ceiling is generous enough to cover a miss
     * that is merely slow rather than broken. Without a ceiling a job that never completes
     * leaves a spinner on screen for ever, which is the one outcome worse than an error.
     */
    private suspend fun poll(generationId: String) {
        repeat(MAX_POLLS) { attempt ->
            delay(if (attempt == 0) FIRST_POLL_MS else POLL_MS)

            val status = try {
                api.generationStatus(generationId)
            } catch (_: Exception) {
                // One failed poll is not a failed generation — the job is on the server.
                return@repeat
            }

            when (status.status) {
                "SUCCEEDED" -> {
                    val poster = status.poster
                    if (poster == null) {
                        _state.value = _state.value.copy(
                            stage = PosterStage.FAILED,
                            problem = PosterProblem.UNAVAILABLE,
                        )
                        return
                    }
                    _state.value = _state.value.copy(
                        stage = PosterStage.READY,
                        poster = poster,
                        cacheHit = status.cacheHit,
                        posterBytes = poster.url?.let { fetchBytes(it) },
                    )
                    return
                }

                "BLOCKED" -> {
                    _state.value = _state.value.copy(
                        stage = PosterStage.FAILED,
                        problem = PosterProblem.BLOCKED,
                    )
                    return
                }

                "FAILED" -> {
                    _state.value = _state.value.copy(
                        stage = PosterStage.FAILED,
                        problem = PosterProblem.UNAVAILABLE,
                    )
                    return
                }
            }
        }

        _state.value = _state.value.copy(stage = PosterStage.FAILED, problem = PosterProblem.UNAVAILABLE)
    }

    private suspend fun fetchBytes(url: String): ByteArray? =
        runCatching { api.downloadMedia(api.absoluteUrl(url)) }.getOrNull()

    /**
     * Hands the poster to WhatsApp, or to the share sheet (FR-POS-01, FR-WSH-05, FR-WSH-06).
     *
     * [caption] is the message M5 drafted, when there is one. FR-POS-01's "instead of, or
     * alongside" is a user choice, not a mode — so an empty caption is an ordinary case and
     * not an error.
     */
    fun handOver(toWhatsApp: Boolean, caption: String) {
        val bytes = _state.value.posterBytes ?: return
        val ok = if (toWhatsApp) {
            sender.shareImageToWhatsApp(bytes, "image/png", caption)
        } else {
            sender.shareImage(bytes, "image/png", caption)
        }
        // False means the other app could not be opened — never that nothing was sent,
        // because nothing is ever sent from here.
        if (ok) _state.value = _state.value.copy(handedOver = true)
    }

    /** FR-POS-12 / FR-SET-07. One tap, on the asset that is on screen. */
    fun report(reason: String, detail: String?) {
        val poster = _state.value.poster ?: return
        scope.launch {
            runCatching {
                api.reportContent(
                    ReportContentRequest(
                        feature = "poster",
                        posterId = poster.id,
                        reason = reason,
                        detail = detail,
                    ),
                )
            }
            // Acknowledged whatever the server said. A report that fails to upload is not
            // something to argue with the user about at the moment they are telling us
            // something went wrong.
            _state.value = _state.value.copy(reported = true)
        }
    }

    /** FR-PPL-04 / FR-POS-01. One photo per person; the server keeps the latest. */
    fun pickPhoto() {
        val person = _state.value.person ?: return
        scope.launch {
            _state.value = _state.value.copy(uploadingPhoto = true)
            val picked = files.pickPhoto()
            if (picked == null) {
                _state.value = _state.value.copy(uploadingPhoto = false)
                return@launch
            }
            runCatching { api.uploadPhoto(person.id, picked.bytes, picked.mimeType) }
            _state.value = _state.value.copy(
                uploadingPhoto = false,
                // The next poster takes the photo path, so whatever is on screen is stale.
                poster = null,
                posterBytes = null,
                stage = PosterStage.IDLE,
            )
            refreshPhoto(person.id)
        }
    }

    fun removePhoto() {
        val person = _state.value.person ?: return
        scope.launch {
            runCatching { api.deletePhoto(person.id) }
            _state.value = _state.value.copy(
                photoUrl = null,
                poster = null,
                posterBytes = null,
                stage = PosterStage.IDLE,
            )
        }
    }

    private suspend fun refreshPhoto(personId: String) {
        val url = runCatching { api.photoUrl(personId) }.getOrNull()
        _state.value = _state.value.copy(photoUrl = url?.let { api.absoluteUrl(it) })
    }

    private fun problemFor(cause: ApiException): PosterProblem =
        when (cause.code) {
            "CONTENT_BLOCKED" -> PosterProblem.BLOCKED
            "NO_SESSION", "SESSION_EXPIRED" -> PosterProblem.OFFLINE
            else -> PosterProblem.UNAVAILABLE
        }

    private companion object {
        /** A cache hit lands in well under a second; asking early keeps it feeling instant. */
        const val FIRST_POLL_MS = 400L
        const val POLL_MS = 900L
        /** ~27s. Comfortably past FR-POS-09's 15s p95 for a miss, short of a dead spinner. */
        const val MAX_POLLS = 30
    }
}
