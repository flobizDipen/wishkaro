package `in`.wishkaro.app.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** A recurring annual date as the user entered it. `year` is null when they did not know it. */
data class EventDate(val day: Int, val month: Int, val year: Int?)

/**
 * The five v1 event types (FR-EVT-01). Held as a string on the wire and in SQLite so an
 * unknown value from a newer server degrades to `UNKNOWN` instead of crashing the parse.
 */
enum class EventType(val wire: String, val label: String) {
    BIRTHDAY("BIRTHDAY", "Birthday"),
    WEDDING_ANNIVERSARY("WEDDING_ANNIVERSARY", "Wedding anniversary"),
    ENGAGEMENT_ANNIVERSARY("ENGAGEMENT_ANNIVERSARY", "Engagement anniversary"),
    DEATH_ANNIVERSARY("DEATH_ANNIVERSARY", "Death anniversary"),
    CUSTOM("CUSTOM", "Something else"),
    UNKNOWN("", "Event"),
    ;

    companion object {
        fun from(wire: String): EventType = entries.firstOrNull { it.wire == wire } ?: UNKNOWN
    }
}

/**
 * Tone, mirrored from the server and NEVER computed here (FR-EVT-01a).
 *
 * The client deliberately does not derive this from the event type. A remembrance birthday is
 * `type = BIRTHDAY` and `sentiment = SOLEMN`, so a UI that inferred tone from type would wish
 * a dead person many happy returns — the failure FR-EVT-05 calls the worst this app can have.
 * An unrecognised value falls back to SOLEMN, because the safe direction is to say too little.
 */
enum class Sentiment {
    CELEBRATORY,
    SOLEMN,
    ;

    companion object {
        fun from(wire: String?): Sentiment = if (wire == "CELEBRATORY") CELEBRATORY else SOLEMN
    }
}

enum class TraitKind(val wire: String, val label: String) {
    HOBBY("HOBBY", "Hobbies"),
    LIKE("LIKE", "Likes"),
    DISLIKE("DISLIKE", "Dislikes"),
    BUDGET_BAND("BUDGET_BAND", "Budget"),
    ;

    companion object {
        fun from(wire: String): TraitKind? = entries.firstOrNull { it.wire == wire }
    }
}

data class PersonTrait(
    val id: String,
    val personId: String,
    val kind: TraitKind,
    val value: String,
)

data class PersonEvent(
    val id: String,
    val personId: String,
    val type: EventType,
    val sentiment: Sentiment,
    /** Only ever set on CUSTOM events (FR-EVT-06). */
    val label: String?,
    val isMuted: Boolean,
    val date: EventDate,
) {
    /** What to call this event on screen. A CUSTOM event has no name but the user's. */
    val title: String get() = if (type == EventType.CUSTOM) (label ?: "Custom") else type.label
}

data class Person(
    val id: String,
    val displayName: String,
    val relationToOwner: String?,
    val isSelf: Boolean,
    val gender: String?,
    val phone: String?,
    val email: String?,
    val notes: String?,
    /** All three derived on the server; mirrored here so the app reads them offline. */
    val isDeceased: Boolean,
    val isMinor: Boolean,
    val completenessScore: Int,
    /**
     * The server's ordered "what to add next" list, heaviest field first and empty for a
     * minor (FR-PPL-05). Wire names, deliberately — the client words them for display but
     * does not decide the order.
     */
    val completenessSuggestions: List<String>,
    val deletedAt: String?,
    val events: List<PersonEvent>,
    val traits: List<PersonTrait>,
) {
    /**
     * One flag the whole UI branches on.
     *
     * **Corrected in M5, after the bug reached a real screen.** This read `isDeceased`
     * alone, on the stated grounds that being deceased "is exactly when the server has
     * already made every one of their events SOLEMN". That is true of the SERVER's copy and
     * not of this one: `is_deceased` is derived server-side, so between creating a death
     * anniversary on the phone and the next sync pull, the local row says false.
     *
     * In that window the app treated a dead person as living. Observed on the device while
     * verifying M5: a death anniversary added on the handset opened the celebratory
     * composer — Funny and Poetic offered, "Send your wish with", and the words "Happy
     * birthday, Rohan" in the box — and the person screen headed their history "Wishes
     * sent". REQUIREMENTS.md §11 and FR-EVT-05 call that the worst failure this product can
     * have, and it has been reachable since M1.
     *
     * The events are the signal that was already right: `addEventLocally` seeds a
     * DEATH_ANNIVERSARY's `sentiment` SOLEMN at insert time, and the server confirms it on
     * the next pull, so it is correct on both sides of the round trip.
     *
     * OR, never a preference between the two. Every input can only push TOWARDS solemn, so
     * no combination of a stale value and a fresh one can produce a celebration for someone
     * who has died — the same direction `Sentiment.from()` falls back in, and for the same
     * reason: the safe failure is to say too little.
     *
     * This still does not DERIVE sentiment on the client, which FR-EVT-01a forbids. It
     * reads two mirrored values and takes the quieter one.
     */
    val isSolemn: Boolean
        get() = isDeceased ||
            events.any { it.sentiment == Sentiment.SOLEMN || it.type == EventType.DEATH_ANNIVERSARY }

    val traitsByKind: Map<TraitKind, List<PersonTrait>> get() = traits.groupBy { it.kind }

    /**
     * The single next thing worth adding, or null when there is nothing to ask for.
     *
     * The ORDER is the server's — that is where COMPLETENESS_WEIGHTS lives, and a second
     * copy of the weights on the client is the duplication BUILD_PROMPT_M1 rules out.
     *
     * But the list is a mirror, and a mirror goes stale. Fill in a phone number in aeroplane
     * mode and the synced list still says "phone" until the next pull, so the app would
     * cheerfully ask for something already on the screen. Each candidate is therefore
     * re-checked against what the device can see right now, and the first one still genuinely
     * missing wins. Ordering stays server-side; "is it filled in" is answered locally,
     * offline, from data the client already holds.
     */
    val nextImprovement: CompletenessField?
        get() = completenessSuggestions
            .asSequence()
            .mapNotNull { CompletenessField.from(it) }
            .firstOrNull { !it.isSatisfiedBy(this) }
}

/**
 * The fields the completeness score weighs, named for display.
 *
 * The ORDER is the server's; this enum only supplies wording, and an unrecognised name from
 * a newer server is dropped rather than shown raw.
 */
enum class CompletenessField(val wire: String, val prompt: String) {
    RELATION("relation", "how you know them"),
    BIRTH_YEAR("birthYear", "their birth year"),
    GENDER("gender", "their gender"),
    PHONE("phone", "their phone number"),
    EMAIL("email", "their email"),
    NOTES("notes", "a note about them"),
    HOBBY("hobby", "their hobbies"),
    LIKE("like", "something they like"),
    DISLIKE("dislike", "something they dislike"),
    BUDGET_BAND("budgetBand", "a budget band"),
    ;

    /**
     * Whether the device can already see this field filled in, without asking the server.
     *
     * Deliberately NOT a score: it answers "is there something here", not "how much is it
     * worth". The weighting stays in one place, on the server.
     */
    fun isSatisfiedBy(person: Person): Boolean = when (this) {
        RELATION -> !person.relationToOwner.isNullOrBlank()
        // A birthday with no year does not count, exactly as the server has it (FR-EVT-02).
        BIRTH_YEAR -> person.events.any { it.type == EventType.BIRTHDAY && it.date.year != null }
        GENDER -> !person.gender.isNullOrBlank()
        PHONE -> !person.phone.isNullOrBlank()
        EMAIL -> !person.email.isNullOrBlank()
        NOTES -> !person.notes.isNullOrBlank()
        HOBBY -> person.traits.any { it.kind == TraitKind.HOBBY }
        LIKE -> person.traits.any { it.kind == TraitKind.LIKE }
        DISLIKE -> person.traits.any { it.kind == TraitKind.DISLIKE }
        BUDGET_BAND -> person.traits.any { it.kind == TraitKind.BUDGET_BAND }
    }

    companion object {
        fun from(wire: String): CompletenessField? = entries.firstOrNull { it.wire == wire }
    }
}

/** A person with their next event already computed, ready to render. */
data class PersonCard(
    val person: Person,
    val nextEvent: PersonEvent?,
    val nextOccurrence: LocalDate?,
    val daysUntil: Int?,
    val turningAge: Int?,
) {
    val countdown: String? = daysUntil?.let(BirthdayMath::countdownLabel)
}

/**
 * The next event across ALL of a person's events, not just the first one (FR-PPL-03).
 *
 * Muted events still appear here. Muting stops the reminder, not the record — the countdown
 * on the person's card is the user's own view of their own data and silencing that would be
 * a different feature from the one they asked for.
 */
fun Person.toCard(timeZone: TimeZone, today: LocalDate): PersonCard {
    // M4: this is now a SELECTION from the shared upcoming query rather than a second copy
    // of it. It used to do its own next-occurrence, days-until and turning-age arithmetic,
    // which made it the app's second implementation of "what is coming up" the moment the
    // list and the widget needed a first. `Upcoming.kt` is the only one now.
    val soonest = upcomingFor(this, timeZone, today).minByOrNull { it.daysUntil }
        ?: return PersonCard(this, null, null, null, null)

    return PersonCard(
        person = this,
        nextEvent = soonest.event,
        nextOccurrence = soonest.nextOccurrence,
        daysUntil = soonest.daysUntil,
        turningAge = soonest.turningAge,
    )
}

/** The curated Indian-context relation list (FR-PPL-02). Free text is allowed alongside. */
val RELATION_CHIPS: List<String> = listOf(
    "Mother", "Father", "Brother", "Sister", "Spouse", "Son", "Daughter",
    "Grandparent", "Uncle", "Aunt", "Cousin", "Friend", "Best Friend",
    "Colleague", "Boss", "Neighbour", "Other",
)
