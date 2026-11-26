package `in`.wishkaro.app.data.local

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOne
import `in`.wishkaro.app.data.repository.SyncStore
import `in`.wishkaro.app.domain.EventDate
import `in`.wishkaro.app.domain.EventType
import `in`.wishkaro.app.domain.Person
import `in`.wishkaro.app.domain.PersonEvent
import `in`.wishkaro.app.domain.PersonTrait
import `in`.wishkaro.app.domain.Sentiment
import `in`.wishkaro.app.domain.TraitKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import `in`.wishkaro.app.data.repository.PlanStore
import `in`.wishkaro.app.domain.DeliveryStore
import `in`.wishkaro.app.domain.ReminderSettings
import `in`.wishkaro.app.domain.ReminderSource
import kotlinx.coroutines.withContext
import kotlin.time.Instant
import wishkaro.db.WishKaroDb

/**
 * Timestamps are compared as INSTANTS, never as strings.
 *
 * The two ends format ISO-8601 differently and the difference is not cosmetic. kotlinx
 * `Instant.toString()` emits up to nanosecond precision with trailing zeros trimmed
 * ("…:21.410233Z", or "…:21Z" on a whole second); JavaScript's `toISOString()` always emits
 * exactly three decimals ("…:21.410Z", "…:21.000Z"); and Prisma stores `timestamp(3)`, so a
 * value that goes up as microseconds comes back as milliseconds.
 *
 * A string comparison of those is wrong in both directions — "…410233Z" sorts BEFORE
 * "…410Z" because '2' < 'Z' — which would make the client treat its own fresh write as
 * older than the server's echo of it. That is last-write-wins losing to formatting.
 *
 * Parsing both sides costs a few microseconds per row and removes the whole class.
 */
private fun instantOrNull(value: String?): Instant? =
    value?.let { runCatching { Instant.parse(it) }.getOrNull() }

/** True when the local row is at least as new as the remote one, precision drift ignored. */
private fun localIsNotOlder(local: String, remote: String): Boolean {
    val l = instantOrNull(local)
    val r = instantOrNull(remote)
    // Unparseable either side: fall back to the string order rather than silently winning.
    if (l == null || r == null) return local >= remote
    return l >= r
}

/** True when two tombstones were written by the same delete, give or take precision. */
private fun sameTombstone(a: String?, b: String?): Boolean {
    if (a == null || b == null) return false
    val x = instantOrNull(a) ?: return a == b
    val y = instantOrNull(b) ?: return a == b
    return x.toEpochMilliseconds() == y.toEpochMilliseconds()
}

/**
 * SQLDelight is the read source of truth (D23).
 *
 * Every screen reads from here, so the app renders with the radio off. Writes land locally
 * first and are marked `pending_sync`; the sync engine drains them later.
 *
 * Derived columns — `sentiment`, `is_deceased`, `is_minor`, `completeness_score` — are
 * mirrored from the server and never computed here. A local write leaves them as they are
 * and the next pull corrects them, which is why a person's tone can lag a local edit by one
 * sync but can never be invented by the client.
 */
class LocalStore(private val db: WishKaroDb) : SyncStore, PlanStore, DeliveryStore {

    private val people = db.personQueries
    private val events = db.eventQueries
    private val traits = db.personTraitQueries
    private val sync = db.syncStateQueries
    private val seen = db.seenReminderQueries
    private val delivery = db.deliveryReportQueries
    private val settings = db.userSettingsQueries
    private val scheduled = db.scheduledReminderQueries

    // -----------------------------------------------------------------------------------
    // Reads
    // -----------------------------------------------------------------------------------

    /** People with their events and traits, as a live query. Updates on every write. */
    fun observePeople(): Flow<List<Person>> {
        val peopleFlow = people.selectAll().asFlow().mapToList(Dispatchers.Default)
        val eventsFlow = events.selectAll().asFlow().mapToList(Dispatchers.Default)
        val traitsFlow = traits.selectAll().asFlow().mapToList(Dispatchers.Default)
        return combine(peopleFlow, eventsFlow, traitsFlow) { personRows, eventRows, traitRows ->
            val eventsByPerson = eventRows.groupBy { it.person_id }
            val traitsByPerson = traitRows.groupBy { it.person_id }
            personRows.map { row ->
                row.toPerson(
                    eventsByPerson[row.id].orEmpty().map { it.toEvent() },
                    traitsByPerson[row.id].orEmpty().mapNotNull { it.toTrait() },
                )
            }
        }
    }

    /** One person, live. The detail screen subscribes to this and survives a force-stop. */
    fun observePerson(id: String): Flow<Person?> = observePeople().map { list ->
        list.firstOrNull { it.id == id }
    }

    /**
     * Soft-deleted people, for the restore surface (FR-PPL-08).
     *
     * `WHERE deleted_at IS NOT NULL` narrows that column's nullability, so SQLDelight
     * generates its own row type rather than reusing `Person` — hence the separate mapper
     * below instead of `toPerson`.
     */
    fun observeDeleted(): Flow<List<Person>> =
        people.selectDeleted().asFlow().mapToList(Dispatchers.Default).map { rows ->
            rows.map { row ->
                Person(
                    id = row.id,
                    displayName = row.display_name,
                    relationToOwner = row.relation_to_owner,
                    isSelf = row.is_self != 0L,
                    gender = row.gender,
                    phone = row.phone,
                    email = row.email,
                    notes = row.notes,
                    isDeceased = row.is_deceased != 0L,
                    isMinor = row.is_minor != 0L,
                    completenessScore = row.completeness_score.toInt(),
                    completenessSuggestions = row.completeness_suggestions
                        .split(",").filter { it.isNotBlank() },
                    deletedAt = row.deleted_at,
                    events = emptyList(),
                    traits = emptyList(),
                )
            }
        }

    /** A one-shot snapshot, for checks that must not subscribe — duplicate detection. */
    suspend fun allPeopleOnce(): List<Person> = withContext(Dispatchers.Default) {
        val eventsByPerson = events.selectAll().executeAsList().groupBy { it.person_id }
        people.selectAll().executeAsList().map { row ->
            row.toPerson(eventsByPerson[row.id].orEmpty().map { it.toEvent() }, emptyList())
        }
    }

    override suspend fun findPerson(id: String): Person? = withContext(Dispatchers.Default) {
        val row = people.selectById(id).executeAsOneOrNull() ?: return@withContext null
        row.toPerson(
            events.selectForPerson(id).executeAsList().map { it.toEvent() },
            traits.selectForPerson(id).executeAsList().mapNotNull { it.toTrait() },
        )
    }

    // -----------------------------------------------------------------------------------
    // Writes — all local-first, all queued for sync
    // -----------------------------------------------------------------------------------

    /** Writes a person and their first event locally, queued for sync. One transaction. */
    suspend fun addPersonLocally(
        personId: String,
        eventId: String,
        displayName: String,
        relation: String?,
        type: EventType,
        label: String?,
        date: EventDate,
        nowIso: String,
    ) = withContext(Dispatchers.Default) {
        db.transaction {
            insertPerson(
                id = personId,
                displayName = displayName,
                relation = relation,
                isSelf = false,
                nowIso = nowIso,
                pending = true,
            )
            insertEvent(eventId, personId, type, label, date, nowIso, muted = false, pending = true)
        }
    }

    suspend fun updatePersonLocally(
        personId: String,
        displayName: String,
        relation: String?,
        gender: String?,
        phone: String?,
        email: String?,
        notes: String?,
        isDeceased: Boolean,
        nowIso: String,
    ) = withContext(Dispatchers.Default) {
        people.updateProfile(
            display_name = displayName,
            relation_to_owner = relation,
            gender = gender,
            phone = phone,
            email = email,
            notes = notes,
            is_deceased = if (isDeceased) 1L else 0L,
            updated_at = nowIso,
            id = personId,
        )
    }

    /**
     * Soft delete, written as a tombstone rather than a DELETE (D23).
     *
     * The events go with it so the list and the scheduler agree even before the next sync,
     * and so a deletion made offline is still a deletion when it reaches the server.
     */
    suspend fun deletePersonLocally(personId: String, nowIso: String) =
        withContext(Dispatchers.Default) {
            db.transaction {
                people.markDeleted(deleted_at = nowIso, updated_at = nowIso, id = personId)
                events.selectForPerson(personId).executeAsList().forEach {
                    events.markDeleted(deleted_at = nowIso, updated_at = nowIso, id = it.id)
                }
                traits.selectForPerson(personId).executeAsList().forEach {
                    traits.markDeleted(deleted_at = nowIso, updated_at = nowIso, id = it.id)
                }
            }
        }

    /**
     * Restores a person and the rows THIS delete tombstoned, matched on the exact timestamp.
     *
     * Restoring only the person row brings them back as an empty husk — no dates, no
     * countdown, no reminders — which is what the on-device walkthrough found. Matching on
     * the timestamp rather than restoring everything also means an event the user had
     * deleted a week earlier stays deleted: undoing one delete must not resurrect a whole
     * history. The server applies the same rule in people.service.ts.
     */
    suspend fun restorePersonLocally(personId: String, nowIso: String) =
        withContext(Dispatchers.Default) {
            db.transaction {
                val person = people.selectById(personId).executeAsOneOrNull()
                    ?: return@transaction
                val deletedAt = person.deleted_at
                people.markRestored(updated_at = nowIso, id = personId)
                if (deletedAt == null) return@transaction

                // sameTombstone, not string equality: the person row may still carry the
                // client's microsecond timestamp while its events have come back from the
                // server truncated to milliseconds. Exact matching finds nothing and the
                // person returns as an empty husk — which is what the device showed.
                events.selectAllForPerson(personId).executeAsList()
                    .filter { sameTombstone(it.deleted_at, deletedAt) }
                    .forEach { events.markRestored(updated_at = nowIso, id = it.id) }
                traits.selectAllForPerson(personId).executeAsList()
                    .filter { sameTombstone(it.deleted_at, deletedAt) }
                    .forEach { traits.markRestored(updated_at = nowIso, id = it.id) }
            }
        }

    suspend fun addEventLocally(
        eventId: String,
        personId: String,
        type: EventType,
        label: String?,
        date: EventDate,
        nowIso: String,
    ) = withContext(Dispatchers.Default) {
        // A new event on a deceased person is written muted, matching the server's default
        // (FR-EVT-05). The server re-derives it either way; doing it here as well means the
        // local UI is not briefly wrong between the write and the next sync.
        val deceased = people.selectById(personId).executeAsOneOrNull()?.is_deceased != 0L
        val muted = deceased && type == EventType.BIRTHDAY
        insertEvent(eventId, personId, type, label, date, nowIso, muted = muted, pending = true)
    }

    suspend fun updateEventLocally(
        eventId: String,
        type: EventType,
        label: String?,
        date: EventDate,
        nowIso: String,
    ) = withContext(Dispatchers.Default) {
        events.updateEvent(
            type = type.wire,
            label = label,
            day = date.day.toLong(),
            month = date.month.toLong(),
            year = date.year?.toLong(),
            updated_at = nowIso,
            id = eventId,
        )
    }

    suspend fun setEventMuted(eventId: String, muted: Boolean, nowIso: String) =
        withContext(Dispatchers.Default) {
            events.setMuted(is_muted = if (muted) 1L else 0L, updated_at = nowIso, id = eventId)
        }

    suspend fun deleteEventLocally(eventId: String, nowIso: String) =
        withContext(Dispatchers.Default) {
            events.markDeleted(deleted_at = nowIso, updated_at = nowIso, id = eventId)
        }

    suspend fun restoreEventLocally(eventId: String, nowIso: String) =
        withContext(Dispatchers.Default) {
            events.markRestored(updated_at = nowIso, id = eventId)
        }

    suspend fun addTraitLocally(
        traitId: String,
        personId: String,
        kind: TraitKind,
        value: String,
        nowIso: String,
    ) = withContext(Dispatchers.Default) {
        traits.insertOrReplace(
            id = traitId,
            person_id = personId,
            kind = kind.wire,
            value_ = value,
            // Always USER from this app. IMPORT and INFERRED are the server's to write.
            source = "USER",
            updated_at = nowIso,
            deleted_at = null,
            pending_sync = 1L,
        )
    }

    suspend fun deleteTraitLocally(traitId: String, nowIso: String) =
        withContext(Dispatchers.Default) {
            traits.markDeleted(deleted_at = nowIso, updated_at = nowIso, id = traitId)
        }

    // -----------------------------------------------------------------------------------
    // Sync plumbing
    // -----------------------------------------------------------------------------------

    override suspend fun pendingPeople(limit: Long): List<wishkaro.db.Person> =
        withContext(Dispatchers.Default) { people.selectPendingSync(limit).executeAsList() }

    override suspend fun pendingEvents(limit: Long): List<wishkaro.db.Event> =
        withContext(Dispatchers.Default) { events.selectPendingSync(limit).executeAsList() }

    override suspend fun pendingTraits(limit: Long): List<wishkaro.db.Person_trait> =
        withContext(Dispatchers.Default) { traits.selectPendingSync(limit).executeAsList() }

    /**
     * Every person id still waiting to be pushed, blocked ones included.
     *
     * Blocked ones ARE included, deliberately: a blocked person is not on the server, so
     * their events must not be sent either. Excluding them here would send an event whose
     * person can never arrive, earning an UNKNOWN_PERSON on every run for ever.
     */
    override suspend fun pendingPersonIds(): Set<String> = withContext(Dispatchers.Default) {
        val drainable = people.selectPendingSync(Long.MAX_VALUE).executeAsList().map { it.id }
        val blocked = people.selectBlocked().executeAsList().map { it.id }
        (drainable + blocked).toSet()
    }

    override suspend fun blockPerson(id: String, reason: String) {
        withContext(Dispatchers.Default) { people.markSyncBlocked(reason, id) }
    }

    override suspend fun blockEvent(id: String, reason: String) {
        withContext(Dispatchers.Default) { events.markSyncBlocked(reason, id) }
    }

    override suspend fun blockTrait(id: String, reason: String) {
        withContext(Dispatchers.Default) { traits.markSyncBlocked(reason, id) }
    }

    /** Rows the server named and refused, for the banner that tells the user about them. */
    fun observeBlockedCount(): Flow<Long> = combine(
        people.countBlocked().asFlow().mapToOne(Dispatchers.Default),
        events.countBlocked().asFlow().mapToOne(Dispatchers.Default),
        traits.countBlocked().asFlow().mapToOne(Dispatchers.Default),
    ) { p, e, t -> p + e + t }

    /** The blocked rows themselves, with the server's reason, newest problem last. */
    suspend fun blockedRows(): List<BlockedRow> = withContext(Dispatchers.Default) {
        val byId = people.selectAll().executeAsList().associateBy({ it.id }, { it.display_name })
        people.selectBlocked().executeAsList().map {
            BlockedRow(it.id, "person", it.display_name, it.sync_block_reason.orEmpty())
        } + events.selectBlocked().executeAsList().map {
            BlockedRow(it.id, "event", byId[it.person_id] ?: "A person", it.sync_block_reason.orEmpty())
        } + traits.selectBlocked().executeAsList().map {
            BlockedRow(it.id, "trait", byId[it.person_id] ?: "A person", it.sync_block_reason.orEmpty())
        }
    }

    override suspend fun clearPersonPending(ids: List<String>) = withContext(Dispatchers.Default) {
        db.transaction { ids.forEach(people::clearPendingSync) }
    }

    override suspend fun clearEventPending(ids: List<String>) = withContext(Dispatchers.Default) {
        db.transaction { ids.forEach(events::clearPendingSync) }
    }

    override suspend fun clearTraitPending(ids: List<String>) = withContext(Dispatchers.Default) {
        db.transaction { ids.forEach(traits::clearPendingSync) }
    }

    /**
     * Put a rejected write back on the wire instead of discarding it.
     *
     * `updated_at` is the only thing last-write-wins compares, so a row the server called
     * STALE can only ever lose again until its timestamp moves. The sync engine passes a
     * value just past the server's own clock, which is what makes the retry terminate rather
     * than bounce once per sync forever.
     */
    override suspend fun restampPersonPending(ids: List<String>, updatedAt: String) =
        withContext(Dispatchers.Default) {
            db.transaction { ids.forEach { people.restampPending(updatedAt, it) } }
        }

    override suspend fun restampEventPending(ids: List<String>, updatedAt: String) =
        withContext(Dispatchers.Default) {
            db.transaction { ids.forEach { events.restampPending(updatedAt, it) } }
        }

    override suspend fun restampTraitPending(ids: List<String>, updatedAt: String) =
        withContext(Dispatchers.Default) {
            db.transaction { ids.forEach { traits.restampPending(updatedAt, it) } }
        }

    /**
     * Applies a page pulled from the server.
     *
     * Last-write-wins on `updated_at`, resolved here rather than in SQL because UPSERT needs
     * SQLite 3.24 and minSdk is 26. A row still flagged `pending_sync` is never overwritten:
     * the local edit has not reached the server yet, so the server's copy is by definition
     * the older one.
     */
    override suspend fun applyRemote(
        remotePeople: List<RemotePerson>,
        remoteEvents: List<RemoteEvent>,
        remoteTraits: List<RemoteTrait>,
        cursor: String,
    ) = withContext(Dispatchers.Default) {
        db.transaction {
            remotePeople.forEach { remote ->
                val local = people.selectById(remote.id).executeAsOneOrNull()
                if (local != null &&
                    (local.pending_sync != 0L || localIsNotOlder(local.updated_at, remote.updatedAt))
                ) {
                    return@forEach
                }
                people.insertOrReplace(
                    id = remote.id,
                    display_name = remote.displayName,
                    relation_to_owner = remote.relationToOwner,
                    is_self = if (remote.isSelf) 1L else 0L,
                    photo_key = null,
                    gender = remote.gender,
                    phone = remote.phone,
                    email = remote.email,
                    notes = remote.notes,
                    is_deceased = if (remote.isDeceased) 1L else 0L,
                    is_minor = if (remote.isMinor) 1L else 0L,
                    completeness_score = remote.completenessScore.toLong(),
                    completeness_suggestions = remote.completenessSuggestions.joinToString(","),
                    updated_at = remote.updatedAt,
                    deleted_at = remote.deletedAt,
                    pending_sync = 0L,
                )
            }
            remoteEvents.forEach { remote ->
                val local = events.selectById(remote.id).executeAsOneOrNull()
                if (local != null &&
                    (local.pending_sync != 0L || localIsNotOlder(local.updated_at, remote.updatedAt))
                ) {
                    return@forEach
                }
                events.insertOrReplace(
                    id = remote.id,
                    person_id = remote.personId,
                    type = remote.type,
                    sentiment = remote.sentiment,
                    label = remote.label,
                    is_muted = if (remote.isMuted) 1L else 0L,
                    day = remote.day.toLong(),
                    month = remote.month.toLong(),
                    year = remote.year?.toLong(),
                    updated_at = remote.updatedAt,
                    deleted_at = remote.deletedAt,
                    pending_sync = 0L,
                )
            }
            remoteTraits.forEach { remote ->
                val local = traits.selectById(remote.id).executeAsOneOrNull()
                if (local != null &&
                    (local.pending_sync != 0L || localIsNotOlder(local.updated_at, remote.updatedAt))
                ) {
                    return@forEach
                }
                traits.insertOrReplace(
                    id = remote.id,
                    person_id = remote.personId,
                    kind = remote.kind,
                    value_ = remote.value,
                    source = remote.source,
                    updated_at = remote.updatedAt,
                    deleted_at = remote.deletedAt,
                    pending_sync = 0L,
                )
            }
            sync.setCursor(cursor)
        }
    }

    override suspend fun cursor(): String? = withContext(Dispatchers.Default) {
        sync.selectCursor().executeAsOneOrNull()?.cursor
    }

    // -----------------------------------------------------------------------------------
    // Notification de-duplication
    // -----------------------------------------------------------------------------------

    /** True the FIRST time a dedupe key is seen; false on every repeat (FR-REM-02). */
    override suspend fun claimReminder(dedupeKey: String, nowIso: String): Boolean =
        withContext(Dispatchers.Default) {
            db.transactionWithResult {
                val already = seen.wasSeen(dedupeKey).executeAsOne()
                if (already) {
                    false
                } else {
                    seen.markSeen(dedupeKey, nowIso)
                    true
                }
            }
        }

    // -----------------------------------------------------------------------------------
    // Delivery reporting (FR-REM-10)
    // -----------------------------------------------------------------------------------

    /**
     * Records what this device did with a reminder, for the server to collect later.
     *
     * Written locally first and never posted directly, because the deliveries that matter
     * most are the ones that happen with no network: a local alarm firing during an outage is
     * the exact case the fallback exists for, and the exact case a direct POST cannot report.
     *
     * Upsert by hand — `ON CONFLICT DO UPDATE` needs SQLite 3.24 and `minSdk 26` can be
     * older than that, a constraint the rest of this schema already works under.
     *
     * SHOWN is sticky, enforced in the UPDATE's own WHERE clause rather than here. Observed
     * on a real device: the local alarm reported SHOWN, a push for the same key arrived two
     * minutes later and reported SUPPRESSED_DUPLICATE, and the second report overwrote the
     * first — so the only record of an actual delivery was replaced by a record of a
     * suppression, before either had reached the server.
     */
    override suspend fun recordDelivery(
        dedupeKey: String,
        outcome: String,
        channel: String,
        occurredAtIso: String,
    ): Unit = withContext(Dispatchers.Default) {
        db.transaction {
            if (delivery.exists(dedupeKey).executeAsOne()) {
                delivery.updateReport(outcome, channel, occurredAtIso, dedupeKey)
            } else {
                delivery.record(dedupeKey, outcome, channel, occurredAtIso)
            }
        }
    }

    override suspend fun pendingDeliveryReports(limit: Long): List<wishkaro.db.Delivery_report> =
        withContext(Dispatchers.Default) { delivery.pending(limit).executeAsList() }

    override suspend fun clearDeliveryPending(keys: List<String>) = withContext(Dispatchers.Default) {
        if (keys.isNotEmpty()) delivery.clearPending(keys)
    }

    // -----------------------------------------------------------------------------------
    // Reminder settings (FR-REM-03, FR-REM-08, FR-REM-09)
    // -----------------------------------------------------------------------------------

    override suspend fun reminderSettings(): ReminderSettings = withContext(Dispatchers.Default) {
        settings.select().executeAsOne().toDomain()
    }

    /** A local edit. Enters the outbox; the server takes it only if it is strictly newer. */
    suspend fun saveSettingsLocally(value: ReminderSettings, nowIso: String) =
        withContext(Dispatchers.Default) {
            settings.saveLocal(
                reminder_hour = value.reminderHour.toLong(),
                ladder_d7 = if (value.ladderD7) 1L else 0L,
                ladder_d1 = if (value.ladderD1) 1L else 0L,
                ladder_d0 = if (value.ladderD0) 1L else 0L,
                quiet_hours_start = value.quietHoursStart?.toLong(),
                quiet_hours_end = value.quietHoursEnd?.toLong(),
                digest_enabled = if (value.digestEnabled) 1L else 0L,
                digest_hour = value.digestHour.toLong(),
                updated_at = nowIso,
            )
        }

    /**
     * The server's copy, applied only when it is newer than ours.
     *
     * String comparison on an ISO-8601 instant: both sides emit UTC with a `Z` suffix and
     * millisecond precision, so lexical order is chronological order. The same comparison
     * the sync engine already makes for people and events.
     */
    override suspend fun applyRemoteSettings(remote: ReminderSettings): Boolean =
        withContext(Dispatchers.Default) {
            db.transactionWithResult {
                val local = settings.select().executeAsOne().toDomain()
                if (local.pendingSync && local.updatedAt > remote.updatedAt) {
                    false
                } else {
                    settings.applyRemote(
                        reminder_hour = remote.reminderHour.toLong(),
                        ladder_d7 = if (remote.ladderD7) 1L else 0L,
                        ladder_d1 = if (remote.ladderD1) 1L else 0L,
                        ladder_d0 = if (remote.ladderD0) 1L else 0L,
                        quiet_hours_start = remote.quietHoursStart?.toLong(),
                        quiet_hours_end = remote.quietHoursEnd?.toLong(),
                        digest_enabled = if (remote.digestEnabled) 1L else 0L,
                        digest_hour = remote.digestHour.toLong(),
                        timezone = remote.timezone,
                        updated_at = remote.updatedAt,
                    )
                    true
                }
            }
        }

    override suspend fun clearSettingsPending(): Unit = withContext(Dispatchers.Default) {
        settings.clearPending()
    }

    // -----------------------------------------------------------------------------------
    // The reminder plan (FR-REM-02)
    // -----------------------------------------------------------------------------------

    /**
     * Replaces the server-authored part of the plan.
     *
     * Rows the new plan does not mention are deleted, because the server has decided they
     * should not fire — an event muted, a person deleted, a date moved to a different key.
     * LOCAL_FALLBACK rows are left alone: they belong to events the server has never seen,
     * so its silence about them says nothing.
     */
    override suspend fun replacePlan(entries: List<ScheduledReminderRow>): PlanDiff =
        withContext(Dispatchers.Default) {
            db.transactionWithResult {
                val keys = entries.map { it.dedupeKey }
                val removed = scheduled.staleFromPlan(keys.ifEmpty { listOf("") })
                    .executeAsList()
                    .map { it.dedupe_key }
                removed.forEach { scheduled.delete(it) }
                entries.forEach { e ->
                    scheduled.upsert(
                        dedupe_key = e.dedupeKey,
                        scheduled_at = e.scheduledAt,
                        offset_rung = e.offset,
                        title = e.title,
                        body = e.body,
                        person_id = e.personId,
                        person_name = e.personName,
                        event_type = e.eventType,
                        sentiment = e.sentiment,
                        deep_link = e.deepLink,
                        source = "PLAN",
                        dedupe_key_ = e.dedupeKey,
                    )
                }
                PlanDiff(added = keys, removed = removed)
            }
        }

    suspend fun scheduledReminder(dedupeKey: String): wishkaro.db.Scheduled_reminder? =
        withContext(Dispatchers.Default) { scheduled.byKey(dedupeKey).executeAsOneOrNull() }

    suspend fun upcomingReminders(fromIso: String, limit: Long = 200): List<wishkaro.db.Scheduled_reminder> =
        withContext(Dispatchers.Default) { scheduled.upcoming(fromIso, limit).executeAsList() }

    suspend fun overdueReminders(nowIso: String): List<wishkaro.db.Scheduled_reminder> =
        withContext(Dispatchers.Default) { scheduled.overdue(nowIso).executeAsList() }

    suspend fun markReminderFired(dedupeKey: String, nowIso: String) =
        withContext(Dispatchers.Default) { scheduled.markFired(nowIso, dedupeKey) }

    override suspend fun upsertLocalFallback(entry: ScheduledReminderRow): Unit =
        write(entry, ReminderSource.LOCAL_FALLBACK)

    /**
     * A snoozed reminder (FR-REM-04).
     *
     * Deliberately a different `source` from a local fallback. `rebuildLocalFallbacks()`
     * reconciles by deleting every LOCAL_FALLBACK row it does not currently want, and a
     * snooze key can never be in that wanted set — so writing one as LOCAL_FALLBACK meant the
     * next plan refresh silently cancelled it, usually within the hour the user had just
     * asked to be reminded in.
     */
    suspend fun upsertSnooze(entry: ScheduledReminderRow): Unit =
        write(entry, ReminderSource.SNOOZE)

    private suspend fun write(entry: ScheduledReminderRow, source: String): Unit =
        withContext(Dispatchers.Default) {
            scheduled.upsert(
                dedupe_key = entry.dedupeKey,
                scheduled_at = entry.scheduledAt,
                offset_rung = entry.offset,
                title = entry.title,
                body = entry.body,
                person_id = entry.personId,
                person_name = entry.personName,
                event_type = entry.eventType,
                sentiment = entry.sentiment,
                deep_link = entry.deepLink,
                source = source,
                dedupe_key_ = entry.dedupeKey,
            )
        }

    /** Events the server has never acknowledged — the only ones a local fallback arms for. */
    override suspend fun unsyncedEvents(): List<wishkaro.db.Event> =
        withContext(Dispatchers.Default) { events.selectUnsynced().executeAsList() }

    override suspend fun localFallbacks(): List<wishkaro.db.Scheduled_reminder> =
        withContext(Dispatchers.Default) { scheduled.localFallbacks().executeAsList() }

    override suspend fun deleteScheduled(dedupeKey: String): Unit =
        withContext(Dispatchers.Default) { scheduled.delete(dedupeKey) }

    /**
     * Whether this device has already drawn a notification for this key.
     *
     * The self-test's evidence (FR-REM-07). `seen_reminder` is the one place that knows for
     * certain whether a notification reached this handset — the server knows only that it
     * handed a push to FCM, which is exactly the step that fails on the phones this feature
     * exists for.
     */
    suspend fun wasReminderSeen(dedupeKey: String): Boolean = withContext(Dispatchers.Default) {
        seen.wasSeen(dedupeKey).executeAsOne()
    }

    /**
     * Hands a claim back when nothing was actually drawn.
     *
     * See ReminderDelivery: claiming before drawing is what keeps two racing paths to one
     * notification, and releasing on a failed draw is what stops a reminder blocked by a
     * missing permission from being lost for ever.
     */
    override suspend fun releaseReminderClaim(dedupeKey: String): Unit =
        withContext(Dispatchers.Default) { seen.releaseClaim(dedupeKey) }

    /** Sign-out wipes local data (FR-AUTH-07). */
    suspend fun wipe() = withContext(Dispatchers.Default) {
        db.transaction {
            traits.deleteAll()
            events.deleteAll()
            people.deleteAll()
            seen.deleteAll()
            delivery.deleteAll()
            scheduled.deleteAll()
            settings.reset()
            sync.reset()
        }
    }

    // -----------------------------------------------------------------------------------
    // Row mapping
    // -----------------------------------------------------------------------------------

    private fun insertPerson(
        id: String,
        displayName: String,
        relation: String?,
        isSelf: Boolean,
        nowIso: String,
        pending: Boolean,
    ) {
        people.insertOrReplace(
            id = id,
            display_name = displayName,
            relation_to_owner = relation,
            is_self = if (isSelf) 1L else 0L,
            photo_key = null,
            gender = null,
            phone = null,
            email = null,
            notes = null,
            is_deceased = 0L,
            is_minor = 0L,
            completeness_score = 0L,
            completeness_suggestions = "",
            updated_at = nowIso,
            deleted_at = null,
            pending_sync = if (pending) 1L else 0L,
        )
    }

    private fun insertEvent(
        id: String,
        personId: String,
        type: EventType,
        label: String?,
        date: EventDate,
        nowIso: String,
        muted: Boolean,
        pending: Boolean,
    ) {
        events.insertOrReplace(
            id = id,
            person_id = personId,
            type = type.wire,
            // A placeholder the server immediately overwrites on the next pull. The client
            // never decides tone; this value only has to be safe for the seconds before the
            // server's answer arrives, and for a brand-new event on a living person it is.
            sentiment = if (type == EventType.DEATH_ANNIVERSARY) "SOLEMN" else "CELEBRATORY",
            label = label,
            is_muted = if (muted) 1L else 0L,
            day = date.day.toLong(),
            month = date.month.toLong(),
            year = date.year?.toLong(),
            updated_at = nowIso,
            deleted_at = null,
            pending_sync = if (pending) 1L else 0L,
        )
    }
}

private fun wishkaro.db.Person.toPerson(
    events: List<PersonEvent>,
    traits: List<PersonTrait>,
): Person = Person(
    id = id,
    displayName = display_name,
    relationToOwner = relation_to_owner,
    isSelf = is_self != 0L,
    gender = gender,
    phone = phone,
    email = email,
    notes = notes,
    isDeceased = is_deceased != 0L,
    isMinor = is_minor != 0L,
    completenessScore = completeness_score.toInt(),
    completenessSuggestions = completeness_suggestions.split(",").filter { it.isNotBlank() },
    deletedAt = deleted_at,
    events = events,
    traits = traits,
)

private fun wishkaro.db.Event.toEvent(): PersonEvent = PersonEvent(
    id = id,
    personId = person_id,
    type = EventType.from(type),
    sentiment = Sentiment.from(sentiment),
    label = label,
    isMuted = is_muted != 0L,
    date = EventDate(day.toInt(), month.toInt(), year?.toInt()),
)

/** Null when the server sends a trait kind this build does not know about yet. */
private fun wishkaro.db.Person_trait.toTrait(): PersonTrait? {
    val parsed = TraitKind.from(kind) ?: return null
    return PersonTrait(id = id, personId = person_id, kind = parsed, value = value_)
}

data class RemotePerson(
    val id: String,
    val displayName: String,
    val relationToOwner: String?,
    val isSelf: Boolean,
    val gender: String?,
    val phone: String?,
    val email: String?,
    val notes: String?,
    val isDeceased: Boolean,
    val isMinor: Boolean,
    val completenessScore: Int,
    val completenessSuggestions: List<String>,
    val updatedAt: String,
    val deletedAt: String?,
)

data class RemoteEvent(
    val id: String,
    val personId: String,
    val type: String,
    val sentiment: String,
    val label: String?,
    val isMuted: Boolean,
    val day: Int,
    val month: Int,
    val year: Int?,
    val updatedAt: String,
    val deletedAt: String?,
)

data class RemoteTrait(
    val id: String,
    val personId: String,
    val kind: String,
    val value: String,
    val source: String,
    val updatedAt: String,
    val deletedAt: String?,
)

/** One row of the plan, as the server handed it down. */
data class ScheduledReminderRow(
    val dedupeKey: String,
    val scheduledAt: String,
    val offset: String,
    val title: String,
    val body: String,
    val personId: String? = null,
    val personName: String? = null,
    val eventType: String? = null,
    val sentiment: String? = null,
    val deepLink: String? = null,
)

/** What a plan refresh changed, so the caller knows which alarms to arm and cancel. */
data class PlanDiff(val added: List<String>, val removed: List<String>)

private fun wishkaro.db.User_settings.toDomain() = ReminderSettings(
    reminderHour = reminder_hour.toInt(),
    ladderD7 = ladder_d7 == 1L,
    ladderD1 = ladder_d1 == 1L,
    ladderD0 = ladder_d0 == 1L,
    quietHoursStart = quiet_hours_start?.toInt(),
    quietHoursEnd = quiet_hours_end?.toInt(),
    digestEnabled = digest_enabled == 1L,
    digestHour = digest_hour.toInt(),
    timezone = timezone,
    updatedAt = updated_at,
    pendingSync = pending_sync == 1L,
)

/**
 * One row the server named and refused, ready to show the user.
 *
 * `personName` rather than an id, because the only useful thing to say about a blocked row
 * is whose it is — and `reason` is the server's own message, passed through rather than
 * reworded, so a report of this is a report of what actually happened.
 */
data class BlockedRow(
    val id: String,
    val entity: String,
    val personName: String,
    val reason: String,
)
