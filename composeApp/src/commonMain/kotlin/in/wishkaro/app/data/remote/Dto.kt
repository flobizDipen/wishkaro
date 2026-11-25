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
    val cursor: String,
    val hasMore: Boolean = false,
    val serverTime: String? = null,
)

@Serializable
data class SyncPushRequest(
    val people: List<SyncPersonPush> = emptyList(),
    val events: List<SyncEventPush> = emptyList(),
    val traits: List<SyncTraitPush> = emptyList(),
)

@Serializable
data class SyncApplied(val people: Int = 0, val events: Int = 0, val traits: Int = 0)

@Serializable
data class SyncSkipped(val id: String, val entity: String, val reason: String)

@Serializable
data class SyncPushResponse(
    val applied: SyncApplied = SyncApplied(),
    val skipped: List<SyncSkipped> = emptyList(),
    val serverTime: String? = null,
)

@Serializable
data class ApiErrorBody(@SerialName("error") val error: ApiErrorDetail)

@Serializable
data class ApiErrorDetail(val code: String, val message: String)
