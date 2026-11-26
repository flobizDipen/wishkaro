package `in`.wishkaro.app.ui.wish

import `in`.wishkaro.app.data.local.LocalStore
import `in`.wishkaro.app.data.remote.ApiException
import `in`.wishkaro.app.data.remote.DraftWishRequest
import `in`.wishkaro.app.data.remote.WishKaroApi
import `in`.wishkaro.app.domain.CompletenessField
import `in`.wishkaro.app.domain.EventType
import `in`.wishkaro.app.domain.newUuidV7
import `in`.wishkaro.app.domain.Person
import `in`.wishkaro.app.platform.MessageSender
import `in`.wishkaro.app.domain.PersonEvent
import `in`.wishkaro.app.domain.Sentiment
import `in`.wishkaro.app.domain.Wish
import `in`.wishkaro.app.domain.WishChannel
import `in`.wishkaro.app.domain.WishLanguage
import `in`.wishkaro.app.domain.WishSource
import `in`.wishkaro.app.domain.WishTone
import `in`.wishkaro.app.domain.defaultToneFor
import `in`.wishkaro.app.domain.localTemplate
import `in`.wishkaro.app.domain.tonesFor
import `in`.wishkaro.app.domain.upcomingFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Why the message in the box is the one that is there.
 *
 * Four sources, and the user is told which one they are looking at:
 *
 *  - a model, through our server (`source = AI`);
 *  - a draft this phone already had for this exact occasion, from earlier today;
 *  - a template this phone wrote, because there was no network, the model declined, or the
 *    rate limit bit (docs/M5_PLAN.md §4);
 *  - whatever the user typed over the top of any of those.
 */
enum class DraftOrigin { MODEL, CACHED, TEMPLATE, EDITED }

/**
 * What the user is told when a draft could not be fetched.
 *
 * Deliberately NOT the server's message for every case. A rate limit has something specific
 * and true to say ("give it a few minutes"); a refusal does not, and the screen where
 * somebody is writing about a death is the worst place in this app to explain that a
 * classifier declined.
 */
enum class DraftProblem { OFFLINE, RATE_LIMITED, UNAVAILABLE }

data class ComposerState(
    val person: Person? = null,
    val event: PersonEvent? = null,
    val occurrenceYear: Int = 0,
    val text: String = "",
    val tone: WishTone = WishTone.WARM,
    val language: WishLanguage = WishLanguage.ENGLISH,
    val origin: DraftOrigin = DraftOrigin.TEMPLATE,
    val drafting: Boolean = false,
    val problem: DraftProblem? = null,
    /** FR-HIS-04: "Last year you sent…", read locally so it is there with the radio off. */
    val lastYear: Wish? = null,
    /** Set once the message has been handed to another app (FR-WSH-07). */
    val askedToConfirm: Boolean = false,
    /**
     * FR-HIS-05's one question, after a send. Null means there is nothing to ask.
     *
     * "Never more than one per interaction" is the requirement, and this field is how that
     * is enforced rather than remembered: it is set once, when a wish is recorded, and
     * cleared the moment it is answered or dismissed. There is no counter to get wrong.
     */
    val nudge: CompletenessField? = null,
    val handedToChannel: WishChannel? = null,
    val loaded: Boolean = false,
) {
    /**
     * Solemn if EITHER the person or the occasion says so.
     *
     * **This cost a real bug, found on the device.** The first version read
     * `person.isSolemn` alone, and `person.is_deceased` is DERIVED SERVER-SIDE — so for a
     * person created seconds ago the local row still says false until a sync pull lands. A
     * death anniversary added on the phone therefore opened the CELEBRATORY composer: Funny
     * and Poetic on offer, "Send your wish with", and the words "Happy birthday, Rohan" in
     * the box. REQUIREMENTS.md §11 and FR-EVT-05 call that the worst failure this product
     * can have, and it was on screen.
     *
     * The event's own `sentiment` is the signal that was already right. `addEventLocally`
     * seeds it SOLEMN for a DEATH_ANNIVERSARY at insert time and the server corrects it on
     * the next pull, so it is correct both before and after the round trip — which the
     * person flag is not.
     *
     * OR rather than a preference between them, deliberately. Both inputs can only ever push
     * TOWARDS solemn, so no combination of a stale value and a fresh one can produce a
     * celebration for someone who has died. That is the same direction `Sentiment.from()`
     * falls back in, and for the same reason: the safe failure is to say too little.
     */
    val sentiment: Sentiment
        get() {
            val personSolemn = person?.isSolemn == true
            val occasionSolemn = event?.sentiment == Sentiment.SOLEMN
            // No person loaded yet is also SOLEMN: it is the quieter of the two guesses.
            if (person == null) return Sentiment.SOLEMN
            return if (personSolemn || occasionSolemn) Sentiment.SOLEMN else Sentiment.CELEBRATORY
        }

    /**
     * The tones this occasion offers (FR-WSH-03, FR-WSH-09).
     *
     * From the PERSON, never from the event type. A deceased person's birthday is
     * `type = BIRTHDAY` and `sentiment = SOLEMN`, and M1 already got this wrong once.
     */
    val tones: List<WishTone> get() = tonesFor(sentiment)

    val isSolemn: Boolean get() = sentiment == Sentiment.SOLEMN

    /** The send button is dead without words. Nothing is handed over empty. */
    val canSend: Boolean get() = text.isNotBlank()
}

/**
 * The composer (FR-WSH-01..07).
 *
 * A feature store beside `AppStore`, the way `ImportStore` is — `AppStore` is already the
 * app's largest file and composer state is per-screen and short-lived, not app-wide.
 *
 * **Nothing in this class sends anything** (FR-WSH-06). It prepares text and hands it to
 * another app through [MessageSender]; the user presses send there. There is no code path
 * here that can transmit a message, which is why FR-WSH-07 has to ASK whether it was sent —
 * the app genuinely does not know.
 */
class WishStore(
    private val api: WishKaroApi,
    private val local: LocalStore,
    private val sender: MessageSender,
    private val scope: CoroutineScope,
    private val now: () -> Instant = Clock.System::now,
    private val zone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
    private val _state = MutableStateFlow(ComposerState())
    val state: StateFlow<ComposerState> = _state.asStateFlow()

    /**
     * Drafts the user has turned down in THIS sitting (FR-WSH-04).
     *
     * Not persisted, and deliberately not written to history: a draft nobody sent is not
     * something to avoid repeating next year. Cleared whenever the composer opens.
     */
    private val rejected = mutableListOf<String>()

    /** How many times Regenerate has been pressed, so the offline templates rotate. */
    private var templateIndex = 0

    /**
     * Open on a person, and optionally a specific event.
     *
     * `eventId` is null from the person screen and from the widget, where the user means
     * "the next thing coming up for them". Resolved here rather than at the call site so
     * all three entry points agree about what that means.
     */
    fun open(person: Person, eventId: String?) {
        rejected.clear()
        templateIndex = 0

        val localToday = Clock.System.todayIn(zone())
        val event = eventId?.let { id -> person.events.firstOrNull { it.id == id } }
            ?: upcomingFor(person, zone(), localToday).minByOrNull { it.daysUntil }?.event
            ?: person.events.firstOrNull()

        val occurrenceYear = eventId
            ?.let { id -> upcomingFor(person, zone(), localToday).firstOrNull { it.event.id == id }?.nextOccurrence?.year }
            ?: upcomingFor(person, zone(), localToday).minByOrNull { it.daysUntil }?.nextOccurrence?.year
            ?: localToday.year

        // The same rule as ComposerState.sentiment, and it has to be: this is what picks
        // the opening template and the opening tone, which is what the user actually sees.
        val sentiment =
            if (person.isSolemn || event?.sentiment == Sentiment.SOLEMN) Sentiment.SOLEMN
            else Sentiment.CELEBRATORY
        val tone = defaultToneFor(sentiment)

        _state.value = ComposerState(
            person = person,
            event = event,
            occurrenceYear = occurrenceYear,
            // Something editable from the first frame. FR-VIEW-05: never a blank screen that
            // leads nowhere, and the user got here by tapping a reminder — they are about to
            // walk into a room.
            text = localTemplate(sentiment, tone, person.displayName, 0),
            tone = tone,
            language = WishLanguage.ENGLISH,
            origin = DraftOrigin.TEMPLATE,
            drafting = true,
            loaded = true,
        )
        scope.launch {
            loadLastYear()
            requestDraft(replacing = true)
        }
    }

    private suspend fun loadLastYear() {
        val s = _state.value
        val person = s.person ?: return
        val type = s.event?.type ?: EventType.BIRTHDAY
        val previous = local.previousWishes(person.id, type, s.occurrenceYear, limit = 1)
        _state.value = _state.value.copy(lastYear = previous.firstOrNull())
    }

    fun setTone(tone: WishTone) {
        if (tone == _state.value.tone) return
        // Re-draft rather than reword locally. FR-WSH-03 asks for a genuinely different
        // message, and "the same text with a different label on it" is the failure that
        // requirement is about.
        _state.value = _state.value.copy(tone = tone, drafting = true)
        scope.launch { requestDraft(replacing = true) }
    }

    fun setLanguage(language: WishLanguage) {
        if (language == _state.value.language) return
        _state.value = _state.value.copy(language = language, drafting = true)
        scope.launch { requestDraft(replacing = true) }
    }

    /** FR-WSH-04. The current draft goes on the avoid list before a new one is asked for. */
    fun regenerate() {
        val current = _state.value.text.trim()
        if (current.isNotEmpty() && current !in rejected) {
            rejected += current
            // Three is the server's cap. Keeping the newest three rather than the oldest:
            // the user is moving away from something, and the most recent rejection is the
            // best description of what they do not want.
            while (rejected.size > 3) rejected.removeAt(0)
        }
        templateIndex += 1
        _state.value = _state.value.copy(drafting = true)
        scope.launch { requestDraft(replacing = true) }
    }

    /** Free editing (FR-WSH-04). The user's words win over anything that arrives later. */
    fun edit(text: String) {
        _state.value = _state.value.copy(text = text, origin = DraftOrigin.EDITED, problem = null)
    }

    private suspend fun requestDraft(replacing: Boolean) {
        val s = _state.value
        val person = s.person ?: return

        try {
            val response = api.draftWish(
                DraftWishRequest(
                    personId = person.id,
                    eventId = s.event?.id,
                    eventType = (s.event?.type ?: EventType.BIRTHDAY).wire,
                    occurrenceYear = s.occurrenceYear,
                    tone = s.tone.wire,
                    language = s.language.wire,
                    avoid = rejected.toList(),
                ),
            )

            // The user may have typed while this was in flight. Their words win: silently
            // replacing what somebody is in the middle of writing is the worst thing a
            // composer can do, and it is a real race on a slow connection.
            if (_state.value.origin == DraftOrigin.EDITED && _state.value.text != s.text) {
                _state.value = _state.value.copy(drafting = false)
                return
            }

            _state.value = _state.value.copy(
                text = response.text,
                // The SERVER says whether a model wrote this. A client that decided for
                // itself would write AI into the history for something a template produced.
                origin = if (response.source == "AI") DraftOrigin.MODEL else DraftOrigin.TEMPLATE,
                drafting = false,
                problem = null,
            )
        } catch (e: ApiException) {
            fallBackLocally(problemFor(e), replacing)
        } catch (e: Exception) {
            // A dead radio does not produce an ApiException — it throws out of Ktor before
            // there is a status to read. Same answer to the user either way.
            @Suppress("UNUSED_EXPRESSION") e
            fallBackLocally(DraftProblem.OFFLINE, replacing)
        }
    }

    /**
     * The local template, and a line saying why.
     *
     * The same path for all three failures, which is the whole reason it is worth building
     * properly: no network, the model declined, the limit bit. The user is not stranded in
     * any of them — they have editable words and a Retry.
     */
    private fun fallBackLocally(problem: DraftProblem, replacing: Boolean) {
        val s = _state.value
        val person = s.person ?: return
        if (!replacing && s.text.isNotBlank()) {
            _state.value = s.copy(drafting = false, problem = problem)
            return
        }
        if (s.origin == DraftOrigin.EDITED) {
            // Never overwrite what the user wrote, even with a template.
            _state.value = s.copy(drafting = false, problem = problem)
            return
        }
        _state.value = s.copy(
            text = localTemplate(s.sentiment, s.tone, person.displayName, templateIndex),
            origin = DraftOrigin.TEMPLATE,
            drafting = false,
            problem = problem,
        )
    }

    private fun problemFor(e: ApiException): DraftProblem = when {
        e.status == 429 -> DraftProblem.RATE_LIMITED
        // 401 has already been handled by the API's own refresh-and-retry; reaching here
        // means the session is gone, and the composer is not the place to say so.
        else -> DraftProblem.UNAVAILABLE
    }

    // -----------------------------------------------------------------------------------
    // Handing over (FR-WSH-05, FR-WSH-06)
    // -----------------------------------------------------------------------------------

    /**
     * Prepare the message and hand it to the user's own app.
     *
     * **Nothing is sent here.** [MessageSender] opens another application with the text
     * already in it; the user presses send. FR-WSH-06 makes that a correctness requirement
     * and there is deliberately no code path in this class that could do otherwise.
     *
     * Returns false when the target is not installed, so the screen can say so rather than
     * appearing to do nothing.
     */
    fun handOff(channel: WishChannel): Boolean {
        val s = _state.value
        val person = s.person ?: return false
        if (!s.canSend) return false

        val handed = when (channel) {
            WishChannel.WHATSAPP -> sender.openWhatsApp(person.phone, s.text)
            WishChannel.SMS -> sender.openSms(person.phone, s.text)
            WishChannel.SHARE -> sender.share(s.text)
            WishChannel.COPY -> sender.copyToClipboard(s.text)
        }
        if (!handed) return false

        // The question comes AFTER the hand-off (FR-WSH-07), and the app genuinely does not
        // know the answer — it handed the text to another application and lost sight of it.
        _state.value = s.copy(askedToConfirm = true, handedToChannel = channel)
        return true
    }

    /**
     * "Did you send it?" — yes.
     *
     * Writes the history row locally and lets the ordinary sync push carry it, like every
     * other write in this app. So answering yes works on a train, which is exactly where
     * somebody who just handed a message to WhatsApp is likely to be.
     */
    fun confirmSent() {
        val s = _state.value
        val person = s.person ?: return
        val channel = s.handedToChannel ?: return
        val nowIso = now().toString()

        scope.launch {
            local.recordWishLocally(
                id = newUuidV7(),
                personId = person.id,
                type = s.event?.type ?: EventType.BIRTHDAY,
                occurrenceYear = s.occurrenceYear,
                // A PLACEHOLDER from the person's mirrored flag. The server derives the real
                // answer and the next pull corrects it — the client is allowed to guess here
                // and is not allowed to keep its guess.
                sentiment = s.sentiment,
                eventId = s.event?.id,
                messageText = s.text,
                tone = s.tone,
                language = s.language,
                channel = channel,
                source = when (s.origin) {
                    DraftOrigin.MODEL -> WishSource.AI
                    DraftOrigin.CACHED -> WishSource.AI
                    DraftOrigin.TEMPLATE -> WishSource.TEMPLATE
                    // Edited over the top of whatever arrived. The words that went out are
                    // the user's, and the history should say so.
                    DraftOrigin.EDITED -> WishSource.USER
                },
                nowIso = nowIso,
            )
            /**
             * FR-HIS-05. ONE question, and only when there is a real one to ask.
             *
             * `nextImprovement` is the server's ordered list re-checked against what this
             * device can see right now, so it never asks for something already on screen.
             * It is empty for a minor (FR-PPL-09, REQUIREMENTS §12), which is exactly right:
             * the moment after sending a child a birthday message is not the moment to start
             * profiling them, and the server has already decided not to ask.
             *
             * A solemn send asks nothing at all. "You've just written to somebody about a
             * death — what are their hobbies?" is the question this app must never put on a
             * screen, and no ordering of the completeness list makes it acceptable.
             */
            val nudge = if (s.isSolemn) null else person.nextImprovement
            _state.value = _state.value.copy(askedToConfirm = false, nudge = nudge)
        }
    }

    /** The nudge was answered or waved away. Either way it does not come back. */
    fun dismissNudge() {
        _state.value = _state.value.copy(nudge = null)
    }

    /** Dismissible (FR-WSH-07). No row, and no second ask. */
    fun dismissConfirm() {
        _state.value = _state.value.copy(askedToConfirm = false, handedToChannel = null)
    }

    fun close() {
        _state.value = ComposerState()
        rejected.clear()
        templateIndex = 0
    }
}
