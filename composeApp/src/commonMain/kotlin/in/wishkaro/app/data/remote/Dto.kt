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
 * The user's reminder settings (FR-REM-03, FR-REM-08, FR-REM-09).
 *
 * One type for both directions, unlike people and events. There are no server-derived fields
 * here — every one of these is the user's own choice — so there is nothing for a push shape
 * to leave out.
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

@Serializable
data class SyncPullResponse(
    val people: List<SyncPersonDto> = emptyList(),
    val events: List<SyncEventDto> = emptyList(),
    val traits: List<SyncTraitDto> = emptyList(),
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
    val settings: SettingsDto? = null,
)

@Serializable
data class SyncApplied(val people: Int = 0, val events: Int = 0, val traits: Int = 0)

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
data class ApiErrorDetail(val code: String, val message: String)
