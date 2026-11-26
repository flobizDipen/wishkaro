package `in`.wishkaro.app.data.repository

import `in`.wishkaro.app.data.local.RemoteEvent
import `in`.wishkaro.app.data.local.RemotePerson
import `in`.wishkaro.app.data.local.RemoteTrait
import `in`.wishkaro.app.data.remote.ApiException
import `in`.wishkaro.app.data.remote.DeliveryReportDto
import `in`.wishkaro.app.data.remote.DeliveryReportRequest
import `in`.wishkaro.app.data.remote.SettingsDto
import `in`.wishkaro.app.data.remote.SettingsPush
import `in`.wishkaro.app.data.remote.SyncEventPush
import `in`.wishkaro.app.data.remote.SyncPersonPush
import `in`.wishkaro.app.data.remote.SyncPushRequest
import `in`.wishkaro.app.data.remote.SyncPushResponse
import `in`.wishkaro.app.data.remote.SyncSkipped
import `in`.wishkaro.app.data.remote.SyncTraitPush
import `in`.wishkaro.app.domain.ReminderSettings
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
private const val DELIVERY_REPORT_BATCH = 200L

class SyncEngine(
    private val api: SyncApi,
    private val local: SyncStore,
    /**
     * Overridable so a test can reach the truncation case without a 20,000-row outbox.
     *
     * Declared BEFORE `now` on purpose: several existing tests construct this with a
     * trailing lambda, which binds to the last parameter.
     */
    private val maxPushPages: Int = MAX_PUSH_PAGES,
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
        /**
         * Rows set aside this run because the server named them and refused them. They keep
         * their pending flag but leave the drain, so the rest of the outbox keeps moving.
         * Reported here so the UI can tell the user; a row silently set aside would be the
         * same silent-partial-success M3 exists to avoid.
         */
        val blocked: List<Blocked> = emptyList(),
        /**
         * Events and traits deliberately held back because their person is pending but did
         * not fit in this run's snapshot. They go next run. Zero in every ordinary case.
         */
        val deferred: Int = 0,
        /**
         * True when this run hit its page cap and deliberately left part of the outbox for
         * the next one.
         *
         * Surfaced rather than computed and dropped. `planPages` has always worked this out
         * and the first version of this class threw it away at exactly the point it was
         * produced — the shape MILESTONE_CHECKLIST.md names as the first thing to look for,
         * and the third time this project has made it (`PlanDiff.removed`, M1's `skipped`).
         * A caller that wants the outbox empty has to be able to know it is not.
         */
        val outboxTruncated: Boolean = false,
        /** How many delivery reports this run managed to hand over (FR-REM-10). */
        val deliveryReported: Int = 0,
    )

    /** One row the server refused by name, with the server's own message. */
    data class Blocked(val id: String, val entity: String, val reason: String)

    /** What one run's push phase did, before it is folded into [Result]. */
    private data class PushOutcome(
        val counts: Counts = Counts(),
        val rejected: List<SyncSkipped> = emptyList(),
        val blocked: List<Blocked> = emptyList(),
        val deferred: Int = 0,
        val truncated: Boolean = false,
    )

    /** What one page did. */
    private data class PageResult(val counts: Counts, val skipped: List<SyncSkipped>)

    suspend fun sync(): kotlin.Result<Result> = mutex.withLock {
        runCatching {
            // Delivery reports first, before anything can fail.
            //
            // They are the measurement the rest of M2 is judged by (FR-REM-10), and they are
            // written in exactly the conditions that make them hardest to send: a local alarm
            // firing during an outage, on a phone that may not come back for days. If a
            // person push 400s, the reports that were waiting behind it should still have
            // gone. Its own failure is swallowed for the same reason in reverse — losing the
            // user's data because the metrics endpoint was down would be an absurd trade.
            val reported = runCatching { drainDeliveryReports() }.getOrDefault(0)
            val outcome = push()
            Result(
                pushed = outcome.counts,
                pulled = pull(),
                rejected = outcome.rejected,
                blocked = outcome.blocked,
                deferred = outcome.deferred,
                outboxTruncated = outcome.truncated,
                deliveryReported = reported,
            )
        }
    }

    /**
     * Sends the settings blob, alone, and settles it by what came back.
     *
     * The server answers with the winning copy whether ours won or lost, so applying it does
     * both jobs at once: a win is a no-op, and a loss replaces the local row with the newer
     * one. Either way the pending flag clears — which is what stops a device that lost from
     * re-pushing the same stale blob on every sync for ever.
     *
     * Swallows its own failure. A rejected settings blob is a bug to fix, not a reason to
     * stop the user's people and events from reaching the server.
     */
    private suspend fun pushSettings() {
        val settings = local.reminderSettings().takeIf { it.pendingSync } ?: return
        runCatching {
            val response = api.syncPush(SyncPushRequest(settings = settings.toPush()))
            response.settings?.let { local.applyRemoteSettings(it.toDomain()) }
            local.clearSettingsPending()
        }
    }

    /**
     * Hands the server everything this device has observed about reminder delivery.
     *
     * Rows are cleared only on a confirmed 200. A report the server never acknowledged stays
     * pending and goes again next time — re-sending is harmless because `recordDelivery` is
     * idempotent on the dedupe key, whereas dropping one silently deletes a data point from
     * the one number that says whether this product works.
     */
    private suspend fun drainDeliveryReports(): Int {
        val pending = local.pendingDeliveryReports(DELIVERY_REPORT_BATCH)
        if (pending.isEmpty()) return 0

        api.reportDelivery(
            DeliveryReportRequest(
                pending.map {
                    DeliveryReportDto(
                        dedupeKey = it.dedupe_key,
                        outcome = it.outcome,
                        channel = it.channel,
                        occurredAtUtc = it.occurred_at,
                    )
                },
            ),
        )
        local.clearDeliveryPending(pending.map { it.dedupe_key })
        return pending.size
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
    private suspend fun push(): PushOutcome {
        // Settings go in a request of their OWN, ahead of the records.
        //
        // Not an optimisation — isolation. Validation runs before anything is applied, so a
        // settings blob the server refuses takes the people and events in the same batch down
        // with it, and the outbox stops draining entirely. That happened twice during M2's
        // device verification, from two different field mismatches, and both times the
        // visible symptom was "my edits stopped syncing" rather than anything about settings.
        //
        // The same reasoning as the delivery-report drain above: a failure in the small,
        // rarely-changing thing must never block the user's actual data.
        pushSettings()

        // ONE bounded snapshot, paged in memory — not a loop on "is the outbox empty yet".
        //
        // That distinction is load-bearing. A STALE row is re-stamped and stays pending, and
        // an UNKNOWN_PERSON row stays pending unchanged, both by design. A loop that re-read
        // the outbox after each page would therefore never terminate on either of them.
        // Snapshot-and-page terminates by construction: every page is sent at most once per
        // run, and whatever is still pending at the end goes on the next run, exactly as it
        // did when the whole outbox was one request.
        val people = local.pendingPeople(OUTBOX_SNAPSHOT_LIMIT)
        val events = local.pendingEvents(OUTBOX_SNAPSHOT_LIMIT)
        val traits = local.pendingTraits(OUTBOX_SNAPSHOT_LIMIT)
        if (people.isEmpty() && events.isEmpty() && traits.isEmpty()) return PushOutcome()

        val plan = planPages(people, events, traits, local.pendingPersonIds(), maxPages = maxPushPages)

        var counts = Counts()
        val rejected = mutableListOf<SyncSkipped>()
        val blocked = mutableListOf<Blocked>()

        for (page in plan.pages) {
            val result = sendPage(page, blocked) ?: continue
            counts = Counts(
                people = counts.people + result.counts.people,
                events = counts.events + result.counts.events,
                traits = counts.traits + result.counts.traits,
            )
            rejected += result.skipped
        }

        return PushOutcome(counts, rejected, blocked, plan.deferred, plan.truncated)
    }

    /**
     * Send one page and settle it on its own response, before the next page is sent.
     *
     * That is what makes a page an independent commit: a failure in page four leaves pages
     * one to three cleared, not undone and not re-sent.
     *
     * Returns null when the page could not be handed over but the run should continue; the
     * page's rows simply stay pending. **Throws** when the run should stop.
     *
     * The difference is the status, and it matters:
     *
     *  - **5xx, or a transport failure** — not about this data. The next page will fail the
     *    same way, so stop and let the next sync try again.
     *  - **4xx** — about this data, and the pages after it are probably fine. Do not stop:
     *    that was the original bug, where one unacceptable row meant nothing else ever
     *    reached the server again.
     *
     * A `VALIDATION_FAILED` 400 is the one worth working on, because the server says which
     * row. Those rows are set aside with the server's own message and the page goes once
     * more without them, so a single 400-character name out of a spreadsheet costs its own
     * row and not the 199 around it.
     */
    private suspend fun sendPage(page: PushPage, blocked: MutableList<Blocked>): PageResult? {
        val response = try {
            api.syncPush(page.toRequest())
        } catch (failure: ApiException) {
            if (failure.status !in 400..499) throw failure
            if (failure.code != VALIDATION_FAILED) return null

            val rejectedRows = rowsRejectedBy(page, failure.details)
            // An empty list means the server refused the page without naming a row we can
            // find. "Could not attribute this" is not "nothing was wrong", so nothing is
            // blocked and the page stays pending — guessing would delete the user's data.
            if (rejectedRows.isEmpty()) return null

            rejectedRows.forEach { row ->
                when (row.entity) {
                    PERSON -> local.blockPerson(row.id, row.reason)
                    EVENT -> local.blockEvent(row.id, row.reason)
                    TRAIT -> local.blockTrait(row.id, row.reason)
                }
                blocked += Blocked(row.id, row.entity, row.reason)
            }

            val retry = page.without(rejectedRows)
            // Every row in the page was refused. There is nothing left to send, and they are
            // all blocked now, so the page is finished rather than failed.
            if (retry.isEmpty()) return PageResult(Counts(), emptyList())

            return try {
                settlePage(retry, api.syncPush(retry.toRequest()))
            } catch (retryFailure: ApiException) {
                // One retry, not a loop. Zod reports every failed check at once, so one pass
                // should have caught every bad row; a second failure means something this
                // code does not understand, and the honest answer is to leave the rows
                // pending rather than keep deleting them from the page until it fits.
                if (retryFailure.status !in 400..499) throw retryFailure
                null
            }
        }

        return settlePage(page, response)
    }

    /** Apply the server's answer to the rows this page actually sent. */
    private suspend fun settlePage(page: PushPage, response: SyncPushResponse): PageResult {
        // One timestamp for every re-stamp in this page, a millisecond past the server's own
        // clock. STALE means the stored row's `updated_at` is at or before `serverTime`, so
        // this is strictly newer than anything the server can currently be holding.
        val retryStamp = retryStamp(response.serverTime)

        settle(
            ids = page.people.map { it.id },
            skipped = response.skipped.filter { it.entity == PERSON },
            clear = local::clearPersonPending,
            restamp = { local.restampPersonPending(it, retryStamp) },
        )
        settle(
            ids = page.events.map { it.id },
            skipped = response.skipped.filter { it.entity == EVENT },
            clear = local::clearEventPending,
            restamp = { local.restampEventPending(it, retryStamp) },
        )
        settle(
            ids = page.traits.map { it.id },
            skipped = response.skipped.filter { it.entity == TRAIT },
            clear = local::clearTraitPending,
            restamp = { local.restampTraitPending(it, retryStamp) },
        )

        return PageResult(
            counts = Counts(page.people.size, page.events.size, page.traits.size),
            skipped = response.skipped,
        )
    }

    /**
     * The wire shape of one page.
     *
     * The push DTOs omit every server-derived field. The server's write schemas are
     * `.strict()`, so including `sentiment`, `isMinor` or `completenessScore` would make each
     * sync a 400 — and silently dropping them, which is what a lenient schema used to do,
     * would hide a client bug rather than report it.
     */
    private fun PushPage.toRequest() = SyncPushRequest(
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
    )

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
            // Taken only when it is newer than an unpushed local edit — the same last-write-
            // wins the records use, decided inside the store so the comparison is one place.
            page.settings?.let { local.applyRemoteSettings(it.toDomain()) }
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

        // PERSON / EVENT / TRAIT are the same wire values, and live in SyncPaging.kt
        // because the paging code needs them too. Matched as strings so an unrecognised
        // reason from a newer server is simply not one of the ones we hold — it clears,
        // exactly as an acknowledgement would, rather than sticking in the outbox forever.
        const val STALE = "STALE"
        /** The server's code for a body that failed zod. The only 400 that names rows. */
        const val VALIDATION_FAILED = "VALIDATION_FAILED"
        const val UNKNOWN_PERSON = "UNKNOWN_PERSON"
    }
}

/**
 * The push shape, which deliberately has no `timezone`.
 *
 * The device reports its timezone through POST /v1/devices, and the server's settings write
 * schema is `.strict()` — so sending it back up is a 400, and validation runs before anything
 * is applied, which means one bad settings blob takes the people and events in the same batch
 * down with it. Observed on the phone: a settings change left pending_sync = 1 and every
 * later push 400'd behind it, so the outbox stopped draining entirely.
 */
private fun ReminderSettings.toPush() = SettingsPush(
    reminderHour = reminderHour,
    ladderD7 = ladderD7,
    ladderD1 = ladderD1,
    ladderD0 = ladderD0,
    quietHoursStart = quietHoursStart,
    quietHoursEnd = quietHoursEnd,
    digestEnabled = digestEnabled,
    digestHour = digestHour,
    updatedAt = updatedAt,
)

private fun SettingsDto.toDomain() = ReminderSettings(
    reminderHour = reminderHour,
    ladderD7 = ladderD7,
    ladderD1 = ladderD1,
    ladderD0 = ladderD0,
    quietHoursStart = quietHoursStart,
    quietHoursEnd = quietHoursEnd,
    digestEnabled = digestEnabled,
    digestHour = digestHour,
    timezone = timezone,
    updatedAt = updatedAt,
)
