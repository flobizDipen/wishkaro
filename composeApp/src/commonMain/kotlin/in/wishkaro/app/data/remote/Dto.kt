package `in`.wishkaro.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire types. Deliberately separate from the domain model so a server change shows up as a
// compile error at the mapping layer rather than leaking through the whole app.

@Serializable
data class GoogleSignInRequest(val idToken: String, val timezone: String? = null)

@Serializable
data class RefreshRequest(val refreshToken: String)

@Serializable
data class SessionUserDto(
    val id: String,
    val email: String,
    val name: String? = null,
    val photoUrl: String? = null,
    val timezone: String,
)

@Serializable
data class SessionDto(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Int,
    val user: SessionUserDto? = null,
)

@Serializable
data class RegisterDeviceRequest(
    val fcmToken: String,
    val platform: String,
    val timezone: String? = null,
)

@Serializable
data class RegisterDeviceResponse(
    val deviceId: String,
    val timezone: String,
    val remindersRescheduled: Int = 0,
)

@Serializable
data class TestReminderResponse(
    val reminderId: String,
    val scheduledAtUtc: String,
    val dedupeKey: String,
    val message: String? = null,
)

/**
 * What this device did with a reminder (FR-REM-10).
 *
 * Batched because the reports that matter most are written while the phone has no network —
 * a local alarm firing during an outage is the case the whole fallback exists for — so they
 * queue locally and leave in a group when the radio comes back.
 */
@Serializable
data class DeliveryReportDto(
    val dedupeKey: String,
    val outcome: String,
    val channel: String,
    val occurredAtUtc: String,
)

@Serializable
data class DeliveryReportRequest(val reports: List<DeliveryReportDto>)

/**
 * The user's reminder settings as the server sends them DOWN (FR-REM-03, FR-REM-08,
 * FR-REM-09).
 *
 * Not the push shape — see [SettingsPush]. `timezone` is on this one and not on that one,
 * because the device reports its timezone through POST /v1/devices and the server's write
 * schema is `.strict()`: sending it back up is a 400, not a no-op. Two types rather than one,
 * for the same reason SyncPersonDto and SyncPersonPush are two types — so the next person to
 * add a field gets a compile error instead of a field report.
 */
@Serializable
data class SettingsDto(
    val reminderHour: Int = 9,
    val ladderD7: Boolean = true,
    val ladderD1: Boolean = true,
    val ladderD0: Boolean = true,
    val quietHoursStart: Int? = null,
    val quietHoursEnd: Int? = null,
    val digestEnabled: Boolean = false,
    val digestHour: Int = 9,
    val timezone: String = "Asia/Kolkata",
    val updatedAt: String = "1970-01-01T00:00:00Z",
)

/**
 * One already-rendered reminder from GET /v1/reminders/plan (FR-REM-02).
 *
 * The client does not build any of this. The title and body were written by the server's copy
 * layer, which is the only place the SOLEMN string set exists — see docs/M2_PLAN.md §1.3.
 */
@Serializable
data class PlanReminderDto(
    val dedupeKey: String,
    val scheduledAtUtc: String,
    val offset: String = "D0",
    val type: String = "REMINDER",
    val title: String,
    val body: String = "",
    val personId: String? = null,
    val personName: String? = null,
    val eventType: String? = null,
    val sentiment: String? = null,
    val deepLink: String? = null,
)

@Serializable
data class PlanResponse(
    val horizonDays: Int = 60,
    val reminders: List<PlanReminderDto> = emptyList(),
    val serverTime: String? = null,
)

@Serializable
data class DeliveryReportResponse(val recorded: Int = 0, val unknown: Int = 0)

/** What the client is allowed to write. Every field here is the user's own choice. */
@Serializable
data class SettingsPush(
    val reminderHour: Int,
    val ladderD7: Boolean,
    val ladderD1: Boolean,
    val ladderD0: Boolean,
    val quietHoursStart: Int? = null,
    val quietHoursEnd: Int? = null,
    val digestEnabled: Boolean,
    val digestHour: Int,
    val updatedAt: String,
)

// ---- sync ----

/**
 * The PULL shape. Carries the server-derived fields so the app can render them offline.
 *
 * NOT the push shape — see SyncPersonPush below. The server's write schemas are `.strict()`
 * and reject `isMinor` and `completenessScore` outright, so sending this type back up would
 * turn every sync into a 400. Two types rather than one optional-everything type, so that is
 * a compile error instead of a field report.
 */
@Serializable
data class SyncPersonDto(
    val id: String,
    val displayName: String,
    val relationToOwner: String? = null,
    val isSelf: Boolean = false,
    val gender: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val notes: String? = null,
    val isDeceased: Boolean = false,
    val isMinor: Boolean = false,
    val completenessScore: Int = 0,
    val completenessSuggestions: List<String> = emptyList(),
    val updatedAt: String,
    val deletedAt: String? = null,
)

@Serializable
data class SyncEventDto(
    val id: String,
    val personId: String,
    val type: String = "BIRTHDAY",
    val sentiment: String = "CELEBRATORY",
    val label: String? = null,
    val isMuted: Boolean = false,
    val day: Int,
    val month: Int,
    val year: Int? = null,
    val updatedAt: String,
    val deletedAt: String? = null,
)

@Serializable
data class SyncTraitDto(
    val id: String,
    val personId: String,
    val kind: String,
    val value: String,
    val source: String = "USER",
    val updatedAt: String,
    val deletedAt: String? = null,
)

/**
 * A wish on the way DOWN (FR-HIS-01). M5.
 *
 * `sentiment` is here and absent from [SyncWishPush], the same split [SyncEventDto] and
 * [SyncEventPush] already have: the app has to word a history entry correctly with no server
 * in reach, and it must never be the side that decides what solemn means.
 */
@Serializable
data class SyncWishDto(
    val id: String,
    val personId: String,
    val type: String = "BIRTHDAY",
    val occurrenceYear: Int,
    val sentiment: String = "SOLEMN",
    val eventId: String? = null,
    val messageText: String,
    val tone: String,
    val language: String,
    val channel: String,
    val source: String,
    val sentAt: String,
    val updatedAt: String,
    val deletedAt: String? = null,
)

// ---- push shapes: derived fields deliberately absent ----

@Serializable
data class SyncPersonPush(
    val id: String,
    val displayName: String,
    val relationToOwner: String? = null,
    val isSelf: Boolean = false,
    val gender: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val notes: String? = null,
    val isDeceased: Boolean = false,
    val updatedAt: String,
    val deletedAt: String? = null,
)

@Serializable
data class SyncEventPush(
    val id: String,
    val personId: String,
    val type: String = "BIRTHDAY",
    val label: String? = null,
    val isMuted: Boolean = false,
    val day: Int,
    val month: Int,
    val year: Int? = null,
    val updatedAt: String,
    val deletedAt: String? = null,
)

@Serializable
data class SyncTraitPush(
    val id: String,
    val personId: String,
    val kind: String,
    val value: String,
    val updatedAt: String,
    val deletedAt: String? = null,
)

/**
 * A wish on the way UP. No `sentiment` — the server derives it and `.strict()` rejects it —
 * and no `posterId`, which M6 owns and nothing writes yet.
 */
@Serializable
data class SyncWishPush(
    val id: String,
    val personId: String,
    val type: String = "BIRTHDAY",
    val occurrenceYear: Int,
    val eventId: String? = null,
    val messageText: String,
    val tone: String,
    val language: String,
    val channel: String,
    val source: String,
    val sentAt: String,
    val updatedAt: String,
    val deletedAt: String? = null,
)

@Serializable
data class SyncPullResponse(
    val people: List<SyncPersonDto> = emptyList(),
    val events: List<SyncEventDto> = emptyList(),
    val traits: List<SyncTraitDto> = emptyList(),
    /** Empty from an M4 server, which has never heard of a wish. M5. */
    val wishes: List<SyncWishDto> = emptyList(),
    /** Always present from M2; nullable so an older server does not break the client. */
    val settings: SettingsDto? = null,
    val cursor: String,
    val hasMore: Boolean = false,
    val serverTime: String? = null,
)

@Serializable
data class SyncPushRequest(
    val people: List<SyncPersonPush> = emptyList(),
    val events: List<SyncEventPush> = emptyList(),
    val traits: List<SyncTraitPush> = emptyList(),
    val wishes: List<SyncWishPush> = emptyList(),
    val settings: SettingsPush? = null,
)

@Serializable
data class SyncApplied(
    val people: Int = 0,
    val events: Int = 0,
    val traits: Int = 0,
    val wishes: Int = 0,
)

@Serializable
data class SyncSkipped(val id: String, val entity: String, val reason: String)

@Serializable
data class SyncPushResponse(
    val applied: SyncApplied = SyncApplied(),
    val skipped: List<SyncSkipped> = emptyList(),
    /** The settings as they stand after the push — present only when the client sent some. */
    val settings: SettingsDto? = null,
    val serverTime: String? = null,
)

@Serializable
data class ApiErrorBody(@SerialName("error") val error: ApiErrorDetail)

@Serializable
data class ApiErrorDetail(
    val code: String,
    val message: String,
    /**
     * The per-issue detail a `VALIDATION_FAILED` carries, which the client used to discard.
     *
     * `ZodValidationPipe` returns one entry per failed check, and for a sync push the path
     * names the exact row: `people.37.displayName`. That is what lets a rejected page be
     * retried without the one row the server refused, instead of the whole page jamming the
     * outbox for ever (docs/M3_PLAN.md §1.6).
     *
     * Absent on most errors, so it is nullable with a default — the client's `Json` has
     * `ignoreUnknownKeys`, but an absent key still needs somewhere to land.
     */
    val details: List<ApiErrorIssue>? = null,
)

/** One failed check from the server's validation. `path` is dot-joined, as the server sends it. */
@Serializable
data class ApiErrorIssue(
    val path: String,
    val message: String,
    val code: String? = null,
)

// ---- M5: wish drafting (FR-WSH-01..04) ----

/**
 * What the client is allowed to ask for.
 *
 * Ids and choices, never meanings. No relation, no hobbies, no `sentiment` — the server looks
 * those up, and `sentiment` in particular decides which prompt family runs and which tones
 * are legal, so a client that could assert it could ask for a Funny draft about a death
 * anniversary. The server's schema is `.strict()` and would reject one anyway; not having a
 * field to put it in is the half that makes it a compile error here.
 */
@Serializable
data class DraftWishRequest(
    val personId: String,
    val eventId: String? = null,
    val eventType: String,
    val occurrenceYear: Int,
    val tone: String,
    val language: String,
    /** Drafts turned down in THIS sitting (FR-WSH-04). Ephemeral — nothing is stored. */
    val avoid: List<String> = emptyList(),
)

@Serializable
data class DraftWishResponse(
    val text: String,
    val tone: String,
    val language: String,
    /**
     * AI or TEMPLATE, decided by the SERVER.
     *
     * The client does not get to say whether a model wrote this. Writing AI into the history
     * for something a template produced would be the app lying in its own records, and the
     * server is the only side that knows which provider served the call.
     */
    val source: String = "AI",
    val model: String? = null,
    val draftedAt: String? = null,
)

// ---------------------------------------------------------------------------------------
// M6 — AI posters (FR-POS-01..13)
// ---------------------------------------------------------------------------------------

/**
 * What the app asks for (FR-POS-01, FR-POS-05).
 *
 * Note what is ABSENT, and it is the same absence `DraftWishRequest` has: no `sentiment`,
 * no `family`, no copy. A client that could assert the sentiment could ask for confetti on
 * a remembrance, so the one input that decides the theme family is the one input the client
 * does not get to supply. The server derives it and refuses a theme outside the family.
 */
@Serializable
data class RequestPosterRequest(
    val personId: String,
    val eventId: String? = null,
    val eventType: String,
    val occurrenceYear: Int,
    /** Null takes the occasion's own default, which is the first of its OWN family. */
    val themeId: String? = null,
    val format: String,
)

/** 202. FR-POS-09 is asynchronous, so what comes back is something to poll. */
@Serializable
data class RequestPosterResponse(val generationId: String, val status: String)

/** FR-POS-09's visible progress state. */
@Serializable
data class GenerationStatusResponse(
    val generationId: String,
    val status: String,
    val cacheHit: Boolean = false,
    val degraded: Boolean = false,
    val moderation: String = "UNCHECKED",
    val latencyMs: Int? = null,
    /**
     * A code for a developer, never shown. The app has its own words — the screen where
     * somebody is making a remembrance poster is the worst place to surface an error code.
     */
    val errorCode: String? = null,
    val poster: PosterDto? = null,
)

@Serializable
data class PosterDto(
    val id: String,
    val personId: String,
    val themeId: String,
    val family: String,
    val sentiment: String,
    val eventType: String,
    val format: String,
    /** FR-POS-08. True on the free tier; M7 is what can make it false. */
    val attributed: Boolean = true,
    val createdAt: String? = null,
    /**
     * Short-lived and signed (§11), and RELATIVE to the API's base URL.
     *
     * Relative because the local blob store signs a path and the server does not know what
     * host the phone reached it on — behind Railway's proxy the request's own Host header
     * is the only clue and it is not one worth trusting for a capability URL. The client
     * joins it to the base URL it already has. An R2 deployment returns an absolute URL and
     * `absoluteUrl` below handles both.
     */
    val url: String? = null,
    val urlExpiresAt: String? = null,
)

@Serializable
data class PosterListResponse(val posters: List<PosterDto> = emptyList())

@Serializable
data class ThemesResponse(
    /** The sentiment the SERVER derived. The picker's local filter must agree with it. */
    val sentiment: String,
    val themes: List<ThemeDto> = emptyList(),
)

@Serializable
data class ThemeDto(
    val id: String,
    val family: String,
    val displayName: String,
    val palette: String,
)

/** FR-POS-12, FR-SET-07, and the Play GenAI policy box in §12. */
@Serializable
data class ReportContentRequest(
    val feature: String,
    val posterId: String? = null,
    val generationId: String? = null,
    val reportedText: String? = null,
    val reason: String,
    val detail: String? = null,
)

@Serializable
data class PhotoUploadResponse(val assetId: String, val url: String? = null)

@Serializable
data class PhotoUrlResponse(val url: String? = null)
