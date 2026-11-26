package `in`.wishkaro.app.data.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import wishkaro.db.WishKaroDb
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What `applyRemote` does with the fields the SERVER owns.
 *
 * THE BUG, found on a real device during M3's verification and written down in
 * `M3_VERIFY.md` §6.3 before it was fixed:
 *
 * `completeness_score`, `completeness_suggestions` and `is_minor` are computed on the server
 * and the client never writes them. But they arrive on the same row as the fields the USER
 * writes, and `applyRemote` arbitrates that whole row with last-write-wins on a timestamp
 * the CLIENT stamped.
 *
 * The handset's clock was two seconds ahead of the deployment's. So: the client stamps a new
 * person `T_client`; the server stores it, re-derives, and stamps its own copy
 * `T_server ≈ T_client - 2s`; the pull returns the corrected row; and last-write-wins throws
 * it away because the local copy looks newer. Every person created on that device sat at a
 * completeness score of 0 for ever — FR-PPL-05 quietly inert, with nothing in any log.
 *
 * The invariant these tests pin is not about clocks: **the client cannot hold a newer version
 * of a field it never writes.** Arbitrating a server-derived column with a client clock is a
 * category error, and no amount of clock discipline would make it right.
 *
 * WHY THIS TEST LIVES HERE. `commonTest` cannot build a `LocalStore` — it needs a SQLDelight
 * driver, which is why `SyncEngineTest` works through hand-written fakes. A fake store cannot
 * catch this, because a fake agrees with whatever the code does to it. The Android unit-test
 * source set runs on the JVM, so it can stand up a real in-memory SQLite and drive the real
 * `applyRemote` against the real schema.
 */
class ApplyRemoteDerivedTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
        WishKaroDb.Schema.create(it)
    }
    private val db = WishKaroDb(driver)
    private val store = LocalStore(db)

    @AfterTest
    fun close() = driver.close()

    /** A person as it sits locally the instant after a successful push: settled, not pending. */
    private fun locallyWritten(
        id: String = "p1",
        name: String = "Aarav Shah",
        updatedAt: String,
        pending: Boolean = false,
    ) {
        db.personQueries.insertOrReplace(
            id = id,
            display_name = name,
            relation_to_owner = "Cousin",
            is_self = 0,
            photo_key = null,
            gender = null,
            phone = null,
            email = null,
            notes = null,
            is_deceased = 0,
            // The defaults a locally-created row has: the client never computes these.
            is_minor = 0,
            completeness_score = 0,
            completeness_suggestions = "",
            updated_at = updatedAt,
            deleted_at = null,
            pending_sync = if (pending) 1L else 0L,
        )
    }

    private fun remote(
        id: String = "p1",
        name: String = "Aarav Shah",
        updatedAt: String,
        score: Int = 45,
        suggestions: List<String> = listOf("gender", "email"),
        isMinor: Boolean = false,
    ) = RemotePerson(
        id = id,
        displayName = name,
        relationToOwner = "Cousin",
        isSelf = false,
        gender = null,
        phone = null,
        email = null,
        notes = null,
        isDeceased = false,
        isMinor = isMinor,
        completenessScore = score,
        completenessSuggestions = suggestions,
        updatedAt = updatedAt,
        deletedAt = null,
    )

    private fun row(id: String = "p1") = db.personQueries.selectById(id).executeAsOne()

    // ---------------------------------------------------------------------------------

    /**
     * THE REGRESSION. The device clock is ahead, so the server's re-derived copy carries an
     * OLDER timestamp — and is dropped, derived values and all.
     */
    @Test
    fun the_servers_derived_values_survive_a_device_clock_that_runs_fast() = runTest {
        // 06:04:02.406092Z — the client's own stamp, microsecond precision, two seconds ahead.
        locallyWritten(updatedAt = "2026-11-26T06:04:02.406092Z")

        // What the server sends back: same person, its own millisecond stamp, two seconds
        // earlier, carrying the values only it can compute.
        store.applyRemote(
            remotePeople = listOf(remote(updatedAt = "2026-11-26T06:04:00.481Z")),
            remoteEvents = emptyList(),
            remoteTraits = emptyList(),
            cursor = "c1",
        )

        val after = row()
        assertEquals(45, after.completeness_score.toInt(), "the score the server computed")
        assertEquals("gender,email", after.completeness_suggestions)
    }

    /**
     * And it must take only those. The row still loses last-write-wins, so the fields the
     * USER owns stay exactly as they are — otherwise this fix would quietly become "the
     * server always wins", which is a different and much worse change.
     */
    @Test
    fun the_users_own_fields_still_win_when_the_local_copy_is_newer() = runTest {
        locallyWritten(name = "Aarav Shah", updatedAt = "2026-11-26T06:04:02.406092Z")

        store.applyRemote(
            remotePeople = listOf(remote(name = "STALE NAME FROM THE SERVER", updatedAt = "2026-11-26T06:04:00.481Z")),
            remoteEvents = emptyList(),
            remoteTraits = emptyList(),
            cursor = "c1",
        )

        val after = row()
        assertEquals("Aarav Shah", after.display_name, "the newer local name is not overwritten")
        assertEquals("2026-11-26T06:04:02.406092Z", after.updated_at, "nor is the local timestamp")
        assertEquals(45, after.completeness_score.toInt(), "but the derived column is taken")
    }

    @Test
    fun is_minor_is_taken_too_because_the_client_never_computes_it() = runTest {
        locallyWritten(updatedAt = "2026-11-26T06:04:02.406092Z")

        store.applyRemote(
            remotePeople = listOf(remote(updatedAt = "2026-11-26T06:04:00.481Z", isMinor = true)),
            remoteEvents = emptyList(),
            remoteTraits = emptyList(),
            cursor = "c1",
        )

        assertEquals(1L, row().is_minor)
    }

    /**
     * The one case where the server's derived values must NOT be taken: an edit that has not
     * been pushed yet. The server derived them from the copy it has, which is the one before
     * that edit — a birth year the user just changed, say — so they are stale by definition
     * and the next push-and-pull will produce the right ones.
     */
    @Test
    fun a_row_with_an_unpushed_local_edit_is_left_entirely_alone() = runTest {
        locallyWritten(updatedAt = "2026-11-26T06:04:02.406092Z", pending = true)

        store.applyRemote(
            remotePeople = listOf(remote(updatedAt = "2026-11-26T06:04:00.481Z")),
            remoteEvents = emptyList(),
            remoteTraits = emptyList(),
            cursor = "c1",
        )

        val after = row()
        assertEquals(0, after.completeness_score.toInt(), "derived from a copy that predates the edit")
        assertEquals(1L, after.pending_sync, "and the row is still queued")
    }

    /** The ordinary case is untouched: a genuinely newer server copy replaces the row whole. */
    @Test
    fun a_newer_server_copy_still_replaces_the_row_completely() = runTest {
        locallyWritten(name = "Old Name", updatedAt = "2026-11-26T06:04:00.000Z")

        store.applyRemote(
            remotePeople = listOf(remote(name = "New Name", updatedAt = "2026-11-26T06:05:00.000Z")),
            remoteEvents = emptyList(),
            remoteTraits = emptyList(),
            cursor = "c1",
        )

        val after = row()
        assertEquals("New Name", after.display_name)
        assertEquals("2026-11-26T06:05:00.000Z", after.updated_at)
        assertEquals(45, after.completeness_score.toInt())
    }

    /** A person the device has never seen is inserted, derived values and all. */
    @Test
    fun an_unknown_person_is_inserted() = runTest {
        store.applyRemote(
            remotePeople = listOf(remote(id = "new", updatedAt = "2026-11-26T06:05:00.000Z")),
            remoteEvents = emptyList(),
            remoteTraits = emptyList(),
            cursor = "c1",
        )

        assertEquals(45, row("new").completeness_score.toInt())
    }

    /**
     * Events have exactly one server-owned field, and it is the one that must never be
     * wrong: `sentiment`. A remembrance birthday is `type = BIRTHDAY, sentiment = SOLEMN`,
     * and the client deliberately does not derive it (FR-EVT-01a) — so dropping the server's
     * value for a clock skew would leave the client holding the placeholder CELEBRATORY it
     * wrote at insert time, which is the one failure FR-EVT-05 calls the worst this app has.
     */
    @Test
    fun an_events_sentiment_survives_the_same_skew() = runTest {
        locallyWritten(updatedAt = "2026-11-26T06:04:02.406092Z")
        db.eventQueries.insertOrReplace(
            id = "e1",
            person_id = "p1",
            type = "BIRTHDAY",
            // What the client writes at insert time: a placeholder, safe only until the
            // server answers.
            sentiment = "CELEBRATORY",
            label = null,
            is_muted = 0,
            day = 4,
            month = 1,
            year = 1990,
            updated_at = "2026-11-26T06:04:02.406092Z",
            deleted_at = null,
            pending_sync = 0,
        )

        store.applyRemote(
            remotePeople = emptyList(),
            remoteEvents = listOf(
                RemoteEvent(
                    id = "e1",
                    personId = "p1",
                    type = "BIRTHDAY",
                    sentiment = "SOLEMN",
                    label = null,
                    isMuted = true,
                    day = 4,
                    month = 1,
                    year = 1990,
                    updatedAt = "2026-11-26T06:04:00.481Z",
                    deletedAt = null,
                ),
            ),
            remoteTraits = emptyList(),
            cursor = "c1",
        )

        val after = db.eventQueries.selectById("e1").executeAsOne()
        assertEquals("SOLEMN", after.sentiment, "the client must never hold its own opinion of tone")
        assertEquals(4L, after.day, "but the user's own fields are still arbitrated by LWW")
    }

    @Test
    fun the_cursor_is_still_advanced() = runTest {
        store.applyRemote(emptyList(), emptyList(), emptyList(), cursor = "cursor-42")
        assertTrue(store.cursor() == "cursor-42")
    }
}
