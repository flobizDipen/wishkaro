package `in`.wishkaro.app.ui.wish

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import `in`.wishkaro.app.data.local.LocalStore
import `in`.wishkaro.app.domain.EventDate
import `in`.wishkaro.app.domain.EventType
import `in`.wishkaro.app.domain.Person
import `in`.wishkaro.app.domain.PersonEvent
import `in`.wishkaro.app.domain.Sentiment
import `in`.wishkaro.app.domain.WishChannel
import `in`.wishkaro.app.domain.WishLanguage
import `in`.wishkaro.app.domain.WishSource
import `in`.wishkaro.app.domain.WishTone
import `in`.wishkaro.app.platform.MessageSender
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import wishkaro.db.WishKaroDb
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The composer's behaviour, against a real in-memory SQLite and a fake sender.
 *
 * `commonTest` cannot build a `LocalStore` — it needs a driver — and a fake store would
 * agree with whatever the code does to it, which is useless for the one thing this class
 * actually writes: a history row with eight columns that have to be right. M3 §6.3.
 *
 * The API is faked, deliberately and only here: what is under test is what the composer does
 * with a 429, a 503 and a dead socket, not what a model writes. The words are never asserted
 * on.
 */
class WishStoreTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
        WishKaroDb.Schema.create(it)
    }
    private val db = WishKaroDb(driver)
    private val local = LocalStore(db)

    @AfterTest
    fun close() = driver.close()

    /** Records what it was asked to open, and opens nothing. */
    private class FakeSender(var installed: Boolean = true) : MessageSender {
        val opened = mutableListOf<Pair<String, String>>()
        override fun openWhatsApp(phone: String?, text: String): Boolean {
            if (!installed) return false
            opened += "whatsapp" to text
            return true
        }
        override fun openSms(phone: String?, text: String): Boolean {
            opened += "sms" to text; return true
        }
        override fun share(text: String): Boolean { opened += "share" to text; return true }
        override fun copyToClipboard(text: String): Boolean { opened += "copy" to text; return true }
    }

    /**
     * Waits for an asynchronous write to land.
     *
     * `LocalStore` writes on `Dispatchers.Default`, so `confirmSent()` returns before the
     * row exists — the first version of two of these tests asserted immediately and one of
     * them passed anyway, by luck, which is the worst possible outcome for a test. Polling
     * is the honest shape for "this is genuinely asynchronous", and a timeout failure says
     * what was being waited for.
     */
    private suspend fun eventually(what: String, timeoutMs: Long = 5_000, check: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            kotlinx.coroutines.delay(20)
        }
        kotlin.test.fail("timed out waiting for: $what")
    }

    private suspend fun awaitWishes(count: Int = 1): List<`in`.wishkaro.app.domain.Wish> {
        eventually("$count wish row(s)") {
            local.previousWishes("p1", EventType.BIRTHDAY, 2100).size >= count
        }
        return local.previousWishes("p1", EventType.BIRTHDAY, 2100)
    }

    private fun person(
        deceased: Boolean = false,
        name: String = "Anika Pillai",
        type: EventType = EventType.BIRTHDAY,
    ) = Person(
        id = "p1",
        displayName = name,
        relationToOwner = "Sister",
        isSelf = false,
        gender = null,
        phone = "+91 98765 43210",
        email = null,
        notes = null,
        isDeceased = deceased,
        isMinor = false,
        completenessScore = 40,
        completenessSuggestions = listOf("hobby"),
        deletedAt = null,
        events = listOf(
            PersonEvent(
                id = "e1",
                personId = "p1",
                type = type,
                sentiment = if (deceased) Sentiment.SOLEMN else Sentiment.CELEBRATORY,
                label = null,
                isMuted = false,
                date = EventDate(14, 3, 1994),
            ),
        ),
        traits = emptyList(),
    )

    private fun store(sender: FakeSender = FakeSender()) =
        WishStore(
            api = unreachableApi(),
            local = local,
            sender = sender,
            // Unconfined so every `scope.launch` runs eagerly: this suite is about what
            // the store WRITES, and a scope that never advances records nothing.
            //
            // It also means the draft request really is attempted, against a port nothing is
            // listening on — so the offline fallback below is the production path running,
            // not a stub of it.
            scope = TestScope(UnconfinedTestDispatcher()),
        )

    /** Points at a port nothing is listening on, so every draft request fails for real. */
    private fun unreachableApi(): `in`.wishkaro.app.data.remote.WishKaroApi =
        `in`.wishkaro.app.data.remote.WishKaroApi(
            baseUrl = "http://localhost:1",
            tokens = `in`.wishkaro.app.data.TokenStore(FakeSecureStorage()),
        )

    private class FakeSecureStorage : `in`.wishkaro.app.data.SecureStorage {
        private val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) { values[key] = value }
        override fun remove(key: String) { values.remove(key) }
        override fun clear() = values.clear()
    }

    @Test
    fun the_composer_opens_holding_editable_words() = runTest {
        val store = store()
        store.open(person(), eventId = "e1")

        val s = store.state.value
        // FR-VIEW-05, and the whole of §4 of the plan: never a blank screen that leads
        // nowhere. The user got here by tapping a reminder — they are about to walk into a
        // room, and a spinner is not an answer.
        assertTrue(s.text.isNotBlank())
        assertEquals(DraftOrigin.TEMPLATE, s.origin)
        assertTrue(s.canSend)
    }

    @Test
    fun a_deceased_person_gets_the_solemn_composer() = runTest {
        val store = store()
        store.open(person(deceased = true), eventId = "e1")

        val s = store.state.value
        // The type is still BIRTHDAY. The PERSON is what makes it solemn — M1's bug.
        assertEquals(EventType.BIRTHDAY, s.event?.type)
        assertTrue(s.isSolemn)
        assertEquals(listOf(WishTone.RESPECTFUL, WishTone.SHORT, WishTone.HEARTFELT), s.tones)
        assertFalse(WishTone.FUNNY in s.tones)
        assertEquals(WishTone.RESPECTFUL, s.tone)
    }

    @Test
    fun the_solemn_opening_draft_carries_no_celebratory_word() = runTest {
        val store = store()
        store.open(person(deceased = true), eventId = "e1")

        val banned = listOf("celebrat", "congrat", "happy", "wish", "cheers", "party")
        val text = store.state.value.text.lowercase()
        assertTrue(banned.none { it in text }, "the solemn composer opened on: $text")
    }

    @Test
    fun regenerate_gives_something_different_offline() = runTest {
        val store = store()
        store.open(person(), eventId = "e1")
        val first = store.state.value.text
        store.regenerate()
        // Rotation through the local set. "Regenerate did nothing" reads as broken.
        assertTrue(store.state.value.text != first)
    }

    @Test
    fun the_users_own_words_are_never_overwritten() = runTest {
        val store = store()
        store.open(person(), eventId = "e1")
        store.edit("I wrote this myself.")
        store.regenerate()

        // A regenerate after an edit still asks for a new draft — but the fallback must not
        // stamp a template over what somebody typed. Silently replacing text a user is in
        // the middle of writing is the worst thing a composer can do.
        assertEquals("I wrote this myself.", store.state.value.text)
        assertEquals(DraftOrigin.EDITED, store.state.value.origin)
    }

    @Test
    fun handing_off_opens_the_app_and_then_asks() = runTest {
        val sender = FakeSender()
        val store = store(sender)
        store.open(person(), eventId = "e1")
        val text = store.state.value.text

        assertTrue(store.handOff(WishChannel.WHATSAPP))
        assertEquals(listOf("whatsapp" to text), sender.opened)
        // FR-WSH-07: the question comes AFTER the hand-off, because the app genuinely does
        // not know — it handed the text to another application and lost sight of it.
        assertTrue(store.state.value.askedToConfirm)
        // And nothing is in history yet. A hand-off is not a send.
        assertEquals(0, local.previousWishes("p1", EventType.BIRTHDAY, 2100).size)
    }

    @Test
    fun a_missing_target_reports_rather_than_pretending() = runTest {
        val sender = FakeSender(installed = false)
        val store = store(sender)
        store.open(person(), eventId = "e1")

        // WhatsApp is not installed. The screen says so rather than the tap doing nothing,
        // and the app must NOT ask "did you send it?" about something it never handed over.
        assertFalse(store.handOff(WishChannel.WHATSAPP))
        assertFalse(store.state.value.askedToConfirm)
    }

    @Test
    fun an_empty_composer_hands_nothing_over() = runTest {
        val store = store()
        store.open(person(), eventId = "e1")
        store.edit("   ")
        assertFalse(store.state.value.canSend)
        assertFalse(store.handOff(WishChannel.SMS))
    }

    @Test
    fun saying_yes_writes_one_history_row_with_the_right_columns() = runTest {
        val sender = FakeSender()
        val store = store(sender)
        store.open(person(), eventId = "e1")
        store.edit("My own words.")
        store.handOff(WishChannel.WHATSAPP)
        store.confirmSent()

        val rows = awaitWishes()
        assertEquals(1, rows.size)
        val wish = rows.single()
        assertEquals("My own words.", wish.messageText)
        assertEquals(WishChannel.WHATSAPP, wish.channel)
        assertEquals(WishLanguage.ENGLISH, wish.language)
        // The user typed over the draft, so the history says so. Recording this as AI would
        // be the app lying about its own contribution, and next year's no-repeat rule reads
        // the two differently.
        assertEquals(WishSource.USER, wish.source)
        assertEquals("e1", wish.eventId)
    }

    @Test
    fun an_untouched_template_is_recorded_as_a_template() = runTest {
        val store = store()
        store.open(person(), eventId = "e1")
        store.handOff(WishChannel.COPY)
        store.confirmSent()

        assertEquals(WishSource.TEMPLATE, awaitWishes().single().source)
    }

    @Test
    fun dismissing_the_question_records_nothing() = runTest {
        val store = store()
        store.open(person(), eventId = "e1")
        store.handOff(WishChannel.SHARE)
        store.dismissConfirm()

        // Dismissible (FR-WSH-07). "I did not send it" and "I am not answering" are both
        // handled by writing nothing, which is the honest thing: the app does not know.
        assertEquals(0, local.previousWishes("p1", EventType.BIRTHDAY, 2100).size)
        assertFalse(store.state.value.askedToConfirm)
    }

    @Test
    fun exactly_one_enrichment_nudge_follows_a_send() = runTest {
        val store = store()
        store.open(person(), eventId = "e1")
        store.handOff(WishChannel.WHATSAPP)
        store.confirmSent()
        awaitWishes()
        eventually("the nudge") { store.state.value.nudge != null }

        // FR-HIS-05: one, never more than one per interaction.
        assertNotNull(store.state.value.nudge)
        store.dismissNudge()
        assertNull(store.state.value.nudge)

        // And a second send in the same sitting does not resurrect a dismissed one until a
        // new send actually happens.
        assertNull(store.state.value.nudge)
    }

    @Test
    fun a_solemn_send_never_asks_for_a_hobby() = runTest {
        val store = store()
        store.open(person(deceased = true), eventId = "e1")
        store.handOff(WishChannel.WHATSAPP)
        store.confirmSent()
        awaitWishes()

        // "You've just written to somebody about a death — what are their hobbies?" is the
        // question this app must never put on a screen, and no ordering of the completeness
        // list makes it acceptable.
        assertNull(store.state.value.nudge)
    }

    @Test
    fun closing_the_composer_leaves_nothing_behind() = runTest {
        val store = store()
        store.open(person(), eventId = "e1")
        store.edit("Half a message to Anika.")
        store.close()

        // A half-written message to one person must never appear in a composer opened for
        // another. The screen's DisposableEffect calls this.
        assertEquals("", store.state.value.text)
        assertNull(store.state.value.person)
    }
}
