package `in`.wishkaro.app.data.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import `in`.wishkaro.app.domain.EventType
import `in`.wishkaro.app.domain.Sentiment
import `in`.wishkaro.app.domain.WishChannel
import `in`.wishkaro.app.domain.WishLanguage
import `in`.wishkaro.app.domain.WishSource
import `in`.wishkaro.app.domain.WishTone
import kotlinx.coroutines.test.runTest
import wishkaro.db.WishKaroDb
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wish table against a REAL in-memory SQLite, through the real `LocalStore`.
 *
 * `commonTest` cannot build a `LocalStore` — it needs a SQLDelight driver — so `SyncEngineTest`
 * works through hand-written fakes. That is fine for the engine's decisions and useless here:
 * **a fake store cannot catch a disagreement between the code and the schema, because it
 * agrees with whatever the code does to it** (M3 §6.3, and `MILESTONE_CHECKLIST.md`'s M3
 * additions). Everything in this file is exactly that kind of disagreement — a column written
 * in the wrong order, a derived value the client keeps when it should not, a query whose
 * boundary is off by one year.
 *
 * `androidUnitTest` runs on the JVM, so it can stand up the real schema. Same arrangement as
 * `ApplyRemoteDerivedTest`.
 */
class WishStoreTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
        WishKaroDb.Schema.create(it)
    }
    private val db = WishKaroDb(driver)
    private val store = LocalStore(db)

    @AfterTest
    fun close() = driver.close()

    private suspend fun record(
        id: String,
        year: Int,
        text: String,
        type: EventType = EventType.BIRTHDAY,
        sentiment: Sentiment = Sentiment.CELEBRATORY,
        tone: WishTone = WishTone.WARM,
        personId: String = "p1",
    ) = store.recordWishLocally(
        id = id,
        personId = personId,
        type = type,
        occurrenceYear = year,
        sentiment = sentiment,
        eventId = null,
        messageText = text,
        tone = tone,
        language = WishLanguage.ENGLISH,
        channel = WishChannel.WHATSAPP,
        source = WishSource.AI,
        nowIso = "$year-03-14T04:30:00Z",
    )

    @Test
    fun a_recorded_wish_round_trips_through_the_real_schema() = runTest {
        record("w1", 2026, "Many happy returns.")

        val stored = db.wishQueries.selectById("w1").executeAsOne()
        // Column ORDER is the thing a fake cannot check: `insertOrReplace` supplies sixteen
        // positional values and two of them are adjacent strings. A swap compiles.
        assertEquals("p1", stored.person_id)
        assertEquals("BIRTHDAY", stored.type)
        assertEquals(2026L, stored.occurrence_year)
        assertEquals("Many happy returns.", stored.message_text)
        assertEquals("WARM", stored.tone)
        assertEquals("ENGLISH", stored.language)
        assertEquals("WHATSAPP", stored.channel)
        assertEquals("AI", stored.source)
        assertNull(stored.poster_id, "M6 owns this column; M5 must not write it")
        assertEquals(1L, stored.pending_sync, "a local write goes in the outbox")
    }

    @Test
    fun the_domain_mapping_survives_a_tone_this_build_does_not_know() = runTest {
        // A wish from a newer server carrying a seventh tone is still something the user sent
        // to somebody. The unknown enum becomes null; the ROW survives. Dropping it would
        // make FR-PPL-07's history silently incomplete and the no-repeat rule silently weaker.
        record("w1", 2026, "Sent anyway.")
        db.wishQueries.applyServerDerived(sentiment = "CELEBRATORY", id = "w1")
        driver.execute(null, "UPDATE wish SET tone = 'LYRICAL' WHERE id = 'w1'", 0)

        val wishes = store.previousWishes("p1", EventType.BIRTHDAY, 2027)
        assertEquals(1, wishes.size)
        assertNull(wishes[0].tone, "an unknown tone is null, not a dropped row")
        assertEquals("Sent anyway.", wishes[0].messageText)
    }

    @Test
    fun previous_wishes_excludes_this_year_and_is_newest_first() = runTest {
        record("w23", 2023, "Twenty twenty-three.")
        record("w24", 2024, "Twenty twenty-four.")
        record("w25", 2025, "Twenty twenty-five.")
        record("w26", 2026, "This year, already sent.")

        val previous = store.previousWishes("p1", EventType.BIRTHDAY, 2026)

        // Strictly earlier years, the same line the server's no-repeat query draws. If the two
        // disagreed about which side of it today is on, the composer would show the user a
        // message the model had never been told to avoid.
        assertEquals(listOf(2025, 2024, 2023), previous.map { it.occurrenceYear })
    }

    @Test
    fun previous_wishes_ignores_another_occasion_on_the_same_person() = runTest {
        record("w1", 2025, "For the birthday.")
        record("w2", 2025, "For the other thing.", type = EventType.CUSTOM)

        val previous = store.previousWishes("p1", EventType.BIRTHDAY, 2026)
        assertEquals(listOf("For the birthday."), previous.map { it.messageText })
    }

    @Test
    fun a_withdrawn_wish_leaves_the_history_but_stays_as_a_tombstone() = runTest {
        record("w1", 2025, "Deleted afterwards.")
        store.deleteWishLocally("w1", "2026-01-01T00:00:00Z")

        assertTrue(store.previousWishes("p1", EventType.BIRTHDAY, 2026).isEmpty())

        val row = db.wishQueries.selectById("w1").executeAsOne()
        // Soft, never a DELETE: a deletion made offline still has to reach the server.
        assertEquals("2026-01-01T00:00:00Z", row.deleted_at)
        assertEquals(1L, row.pending_sync)
    }

    @Test
    fun the_outbox_is_ordered_bounded_and_skips_blocked_rows() = runTest {
        record("w1", 2024, "One.")
        record("w2", 2025, "Two.")
        record("w3", 2026, "Three.")

        assertEquals(listOf("w1", "w2"), store.pendingWishes(2).map { it.id }, "oldest first, bounded")

        store.blockWish("w1", "messageText: too long")
        assertEquals(listOf("w2", "w3"), store.pendingWishes(10).map { it.id })

        // A blocked row KEEPS its pending flag. It still holds an unsent change; it has only
        // left the drain. Clearing the flag would be saying the change had been sent.
        assertEquals(1L, db.wishQueries.selectById("w1").executeAsOne().pending_sync)
        assertEquals(1L, db.wishQueries.countBlocked().executeAsOne())
    }

    @Test
    fun a_blocked_wish_shows_up_in_the_banner_count_and_rows() = runTest {
        record("w1", 2026, "One.")
        store.blockWish("w1", "tone: not available on a SOLEMN occasion")

        val rows = store.blockedRows()
        assertEquals(1, rows.size)
        assertEquals("wish", rows[0].entity)
        assertTrue(rows[0].reason.contains("SOLEMN"))
    }

    @Test
    fun the_server_corrects_a_sentiment_the_client_guessed_wrong() = runTest {
        // The client writes a PLACEHOLDER from the person's mirrored flag when it records a
        // wish, then settles the push. The server derives the real answer. If the device's
        // clock runs fast — M3 §6.3's two seconds — last-write-wins says the local row is
        // newer, and without the applyServerDerived branch the correction is thrown away.
        //
        // Getting this wrong words a dead person's history entry as a celebration, which
        // FR-EVT-05 calls the worst failure this app can have.
        record("w1", 2026, "Thinking of her today.", sentiment = Sentiment.CELEBRATORY)
        store.clearWishPending(listOf("w1"))

        store.applyRemote(
            remotePeople = emptyList(),
            remoteEvents = emptyList(),
            remoteTraits = emptyList(),
            remoteWishes = listOf(remoteWish(updatedAt = "2026-03-14T04:29:58.481Z")),
            cursor = "c1",
        )

        val after = db.wishQueries.selectById("w1").executeAsOne()
        assertEquals("SOLEMN", after.sentiment, "the client must never keep its own guess at tone")
        assertEquals(
            "Thinking of her today.",
            after.message_text,
            "but the row still loses last-write-wins, so the user's own text stands",
        )
    }

    @Test
    fun an_unpushed_local_wish_is_not_overwritten_by_the_server() = runTest {
        record("w1", 2026, "Mine, not yet pushed.")

        store.applyRemote(
            remotePeople = emptyList(),
            remoteEvents = emptyList(),
            remoteTraits = emptyList(),
            remoteWishes = listOf(remoteWish(updatedAt = "2030-01-01T00:00:00Z", text = "Theirs.")),
            cursor = "c1",
        )

        val after = db.wishQueries.selectById("w1").executeAsOne()
        assertEquals("Mine, not yet pushed.", after.message_text)
        assertEquals(1L, after.pending_sync)
        // And the derived column is NOT taken either, because the server derived it from the
        // copy it has, which is the one before this edit. The next push-and-pull fixes it.
        assertEquals("CELEBRATORY", after.sentiment)
    }

    @Test
    fun a_wish_arriving_fresh_from_the_server_lands_settled() = runTest {
        store.applyRemote(
            remotePeople = emptyList(),
            remoteEvents = emptyList(),
            remoteTraits = emptyList(),
            remoteWishes = listOf(remoteWish(updatedAt = "2026-03-14T04:30:00Z")),
            cursor = "c1",
        )

        val after = db.wishQueries.selectById("w1").executeAsOne()
        assertEquals(0L, after.pending_sync, "nothing to push: it came from there")
        assertEquals("SOLEMN", after.sentiment)
        assertEquals("c1", store.cursor())
    }

    private fun remoteWish(
        updatedAt: String,
        text: String = "Thinking of her today.",
    ) = RemoteWish(
        id = "w1",
        personId = "p1",
        type = "BIRTHDAY",
        occurrenceYear = 2026,
        // A remembrance birthday: the type is BIRTHDAY and the sentiment is not celebratory.
        sentiment = "SOLEMN",
        eventId = null,
        messageText = text,
        tone = "RESPECTFUL",
        language = "ENGLISH",
        channel = "WHATSAPP",
        source = "AI",
        sentAt = "2026-03-14T04:30:00Z",
        updatedAt = updatedAt,
        deletedAt = null,
    )
}
