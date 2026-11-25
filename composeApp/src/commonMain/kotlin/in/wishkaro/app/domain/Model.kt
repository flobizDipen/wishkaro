package `in`.wishkaro.app.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/** A birthday as the user entered it. `year` is null when they did not know it. */
data class EventDate(val day: Int, val month: Int, val year: Int?)

data class PersonEvent(
    val id: String,
    val personId: String,
    val type: String,
    val date: EventDate,
)

data class Person(
    val id: String,
    val displayName: String,
    val relationToOwner: String?,
    val isSelf: Boolean,
    val events: List<PersonEvent>,
)

/** A person with their next birthday already computed, ready to render. */
data class PersonCard(
    val person: Person,
    val nextOccurrence: LocalDate?,
    val daysUntil: Int?,
    val turningAge: Int?,
) {
    val countdown: String? = daysUntil?.let(BirthdayMath::countdownLabel)
}

fun Person.toCard(timeZone: TimeZone, today: LocalDate): PersonCard {
    val event = events.firstOrNull()
        ?: return PersonCard(this, nextOccurrence = null, daysUntil = null, turningAge = null)
    val next = BirthdayMath.nextOccurrence(event.date.day, event.date.month, timeZone, today)
    return PersonCard(
        person = this,
        nextOccurrence = next,
        daysUntil = BirthdayMath.daysUntilNext(event.date.day, event.date.month, timeZone, today),
        turningAge = BirthdayMath.turningAge(
            event.date.day,
            event.date.month,
            event.date.year,
            timeZone,
            today,
        ),
    )
}

/** The curated Indian-context relation list (FR-PPL-02). M0 uses the chips; no free text yet. */
val RELATION_CHIPS: List<String> = listOf(
    "Mother", "Father", "Brother", "Sister", "Spouse", "Son", "Daughter",
    "Grandparent", "Uncle", "Aunt", "Cousin", "Friend", "Best Friend",
    "Colleague", "Boss", "Neighbour", "Other",
)
