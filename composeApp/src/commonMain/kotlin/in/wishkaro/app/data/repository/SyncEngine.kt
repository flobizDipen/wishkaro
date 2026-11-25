package `in`.wishkaro.app.data.repository

import `in`.wishkaro.app.data.local.RemoteEvent
import `in`.wishkaro.app.data.local.RemotePerson
import `in`.wishkaro.app.data.local.RemoteTrait
import `in`.wishkaro.app.data.remote.SyncEventPush
import `in`.wishkaro.app.data.remote.SyncPersonPush
import `in`.wishkaro.app.data.remote.SyncPushRequest
import `in`.wishkaro.app.data.remote.SyncSkipped
import `in`.wishkaro.app.data.remote.SyncTraitPush
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Push local changes, then pull remote ones.
 *
 * Push first, deliberately: pulling first would hand the device a server copy of a record it
 * has already edited locally, and last-write-wins would then have to arbitrate between two
 * versions of the same change. Pushing first means the server already knows.
 *
 * A mutex serialises runs. Sync is triggered from several places — app start, after a write,
 * after a push arrives — and two concurrent runs would double-push the same outbox rows.
 */
class SyncEngine(
    private val api: SyncApi,
    private val local: SyncStore,
    private val now: () -> Instant = Clock.System::now,
) {
    private val mutex = Mutex()

    data class Counts(val people: Int = 0, val events: Int = 0, val traits: Int = 0) {
        val total: Int get() = people + events + traits
    }

    data class Result(
        val pushed: Counts = Counts(),
        val pulled: Counts = Counts(),
        /**
         * Records the server refused, by reason. Surfaced rather than swallowed: a push that
         * reports three applied and two rejected is not the same event as a clean push, and
         * the difference used to be invisible to everything above this class.
         */
        val rejected: List<SyncSkipped> = emptyList(),
    )

    suspend fun sync(): kotlin.Result<Result> = mutex.withLock {
        runCatching {
            val (pushed, rejected) = push()
            Result(pushed = pushed, pulled = pull(), rejected = rejected)
        }
    }

    /**
     * Drain the outbox, then act on what the server actually said.
     *
     * The outbox used to be cleared unconditionally, on the reasoning that a record skipped as
     * STALE is settled because the server's copy is newer. That reasoning does not survive M1.
     * The server now bumps `updated_at` from its own derivation — a completeness backfill, a
     * child turning eighteen — with no user edit behind it. A genuine offline edit can
     * therefore come back STALE because a housekeeping sweep moved the timestamp, and
     * clearing the flag there deletes the user's work with no retry and nothing on screen.
     *
     * So each record is settled by what the server said about it:
     *
     *  - **applied** — clear the flag, as before.
     *  - **STALE** — keep it pending and re-stamp it just past the server's own clock. The
     *    next push then carries a timestamp the server must accept, so the user's edit wins
     *    over a derived bump and the retry terminates after exactly one round. Re-stamping to
     *    the device's clock instead would spin forever whenever that clock runs behind the
     *    server's, which is why `serverTime` is what we read.
     *  - **UNKNOWN_PERSON** — keep it pending, unchanged. The parent person has not reached
     *    the server yet; a new timestamp would not help and the row lands once they do.
     *  - **NOT_OWNED** — clear it. The record belongs to another account and no number of
     *    retries will change that; keeping it would re-push it on every sync forever.
     *
     * The cost of the STALE rule is that two devices editing one record resolve as
     * "whichever synced later wins" rather than "whichever edited later wins". WishKaro v1 is
     * one device per account and the project already documents per-record LWW as the limit
     * of what the v1 schema can express (docs/M0_PLAN.md §2) — losing a real edit to a
     * housekeeping sweep is the worse of the two failures.
     */
    private suspend fun push(): Pair<Counts, List<SyncSkipped>> {
        val people = local.pendingPeople()
        val events = local.pendingEvents()
        val traits = local.pendingTraits()
        if (people.isEmpty() && events.isEmpty() && traits.isEmpty()) {
            return Counts() to emptyList()
        }

        // The push DTOs omit every server-derived field. The server's write schemas are
        // .strict(), so including `sentiment`, `isMinor` or `completenessScore` would make
        // each sync a 400 — and silently dropping them, which is what a lenient schema used
        // to do, would hide a client bug rather than report it.
        val response = api.syncPush(
            SyncPushRequest(
                people = people.map {
                    SyncPersonPush(
                        id = it.id,
                        displayName = it.display_name,
                        relationToOwner = it.relation_to_owner,
                        isSelf = it.is_self != 0L,
                        gender = it.gender,
                        phone = it.phone,
                        email = it.email,
                        notes = it.notes,
                        isDeceased = it.is_deceased != 0L,
                        updatedAt = it.updated_at,
                        deletedAt = it.deleted_at,
                    )
                },
                events = events.map {
                    SyncEventPush(
                        id = it.id,
                        personId = it.person_id,
                        type = it.type,
                        label = it.label,
                        isMuted = it.is_muted != 0L,
                        day = it.day.toInt(),
                        month = it.month.toInt(),
                        year = it.year?.toInt(),
                        updatedAt = it.updated_at,
                        deletedAt = it.deleted_at,
                    )
                },
                traits = traits.map {
                    SyncTraitPush(
                        id = it.id,
                        personId = it.person_id,
                        kind = it.kind,
                        value = it.value_,
                        updatedAt = it.updated_at,
                        deletedAt = it.deleted_at,
                    )
                },
            ),
        )

        // One timestamp for every re-stamp in this batch, a millisecond past the server's own
        // clock. STALE means the stored row's `updated_at` is at or before `serverTime`, so
        // this is strictly newer than anything the server can currently be holding.
        val retryStamp = retryStamp(response.serverTime)

        settle(
            ids = people.map { it.id },
            skipped = response.skipped.filter { it.entity == PERSON },
            clear = local::clearPersonPending,
            restamp = { local.restampPersonPending(it, retryStamp) },
        )
        settle(
            ids = events.map { it.id },
            skipped = response.skipped.filter { it.entity == EVENT },
            clear = local::clearEventPending,
            restamp = { local.restampEventPending(it, retryStamp) },
        )
        settle(
            ids = traits.map { it.id },
            skipped = response.skipped.filter { it.entity == TRAIT },
            clear = local::clearTraitPending,
            restamp = { local.restampTraitPending(it, retryStamp) },
        )

        return Counts(people.size, events.size, traits.size) to response.skipped
    }

    /**
     * Apply the four outcomes above to one entity's slice of the batch.
     *
     * Everything the server did not name is applied — the response lists rejections, not
     * acceptances, so "not skipped" is the acknowledgement.
     */
    private suspend fun settle(
        ids: List<String>,
        skipped: List<SyncSkipped>,
        clear: suspend (List<String>) -> Unit,
        restamp: suspend (List<String>) -> Unit,
    ) {
        val byReason = skipped.groupBy({ it.reason }, { it.id })
        val stale = byReason[STALE].orEmpty()
        val unknownPerson = byReason[UNKNOWN_PERSON].orEmpty()
        val held = (stale + unknownPerson).toSet()

        if (ids.any { it !in held }) clear(ids.filter { it !in held })
        if (stale.isNotEmpty()) restamp(stale)
        // UNKNOWN_PERSON rows are simply left as they are: still pending, same timestamp.
    }

    /**
     * A millisecond past [serverTime], or past the device clock if the server did not say.
     *
     * Millisecond precision deliberately: Postgres stores `timestamp(3)` and the server
     * formats with `toISOString()`, so anything finer is invisible on the far side and a
     * microsecond bump would come straight back as STALE. See TimestampComparisonTest.
     */
    private fun retryStamp(serverTime: String?): String {
        val base = serverTime?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: now()
        return Instant.fromEpochMilliseconds(base.toEpochMilliseconds() + 1).toString()
    }

    private suspend fun pull(): Counts {
        var counts = Counts()
        var cursor = local.cursor()

        // The server pages; keep going until it says there is nothing left. Bounded so a
        // server that always reports hasMore cannot spin here forever.
        repeat(MAX_PAGES) {
            val page = api.syncPull(cursor)
            local.applyRemote(
                remotePeople = page.people.map {
                    RemotePerson(
                        id = it.id,
                        displayName = it.displayName,
                        relationToOwner = it.relationToOwner,
                        isSelf = it.isSelf,
                        gender = it.gender,
                        phone = it.phone,
                        email = it.email,
                        notes = it.notes,
                        isDeceased = it.isDeceased,
                        isMinor = it.isMinor,
                        completenessScore = it.completenessScore,
                        completenessSuggestions = it.completenessSuggestions,
                        updatedAt = it.updatedAt,
                        deletedAt = it.deletedAt,
                    )
                },
                remoteEvents = page.events.map {
                    RemoteEvent(
                        id = it.id,
                        personId = it.personId,
                        type = it.type,
                        sentiment = it.sentiment,
                        label = it.label,
                        isMuted = it.isMuted,
                        day = it.day,
                        month = it.month,
                        year = it.year,
                        updatedAt = it.updatedAt,
                        deletedAt = it.deletedAt,
                    )
                },
                remoteTraits = page.traits.map {
                    RemoteTrait(
                        id = it.id,
                        personId = it.personId,
                        kind = it.kind,
                        value = it.value,
                        source = it.source,
                        updatedAt = it.updatedAt,
                        deletedAt = it.deletedAt,
                    )
                },
                cursor = page.cursor,
            )
            counts = Counts(
                counts.people + page.people.size,
                counts.events + page.events.size,
                counts.traits + page.traits.size,
            )
            cursor = page.cursor
            if (!page.hasMore) return counts
        }
        return counts
    }

    private companion object {
        const val MAX_PAGES = 50

        // Wire values, matched as strings so an unrecognised reason from a newer server is
        // simply not one of the ones we hold — it clears, exactly as an acknowledgement
        // would, rather than becoming a row that sticks in the outbox forever.
        const val PERSON = "person"
        const val EVENT = "event"
        const val TRAIT = "trait"
        const val STALE = "STALE"
        const val UNKNOWN_PERSON = "UNKNOWN_PERSON"
    }
}
