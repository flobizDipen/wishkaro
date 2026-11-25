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
     * One flag the whole UI branches on. A person is solemn if they are deceased — which is
     * exactly when the server has already made every one of their events SOLEMN.
     */
    val isSolemn: Boolean get() = isDeceased

    val traitsByKind: Map<TraitKind, List<PersonTrait>> get() = traits.groupBy { it.kind }

    /** The single next thing worth adding, or null when there is nothing to ask for. */
    val nextImprovement: CompletenessField?
        get() = completenessSuggestions.firstNotNullOfOrNull { CompletenessField.from(it) }
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
    val soonest = events
        .mapNotNull { event ->
            val days = BirthdayMath.daysUntilNext(event.date.day, event.date.month, timeZone, today)
            days?.let { event to it }
        }
        .minByOrNull { it.second }
        ?: return PersonCard(this, null, null, null, null)

    val (event, days) = soonest
    return PersonCard(
        person = this,
        nextEvent = event,
        nextOccurrence = BirthdayMath.nextOccurrence(event.date.day, event.date.month, timeZone, today),
        daysUntil = days,
        // A deceased person is never "turning" an age, whatever the arithmetic says.
        turningAge = if (isDeceased) {
            null
        } else {
            BirthdayMath.turningAge(event.date.day, event.date.month, event.date.year, timeZone, today)
        },
    )
}

/** The curated Indian-context relation list (FR-PPL-02). Free text is allowed alongside. */
val RELATION_CHIPS: List<String> = listOf(
    "Mother", "Father", "Brother", "Sister", "Spouse", "Son", "Daughter",
    "Grandparent", "Uncle", "Aunt", "Cousin", "Friend", "Best Friend",
    "Colleague", "Boss", "Neighbour", "Other",
)
