package `in`.wishkaro.app.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/**
 * The one implementation of "what is coming up".
 *
 * `BUILD_PROMPT_M4.md`: *one implementation of "what is coming up", shared by the list and
 * the widget. Two implementations is the duplication this project has refused three times.*
 *
 * So everything that answers that question goes through here — the list (FR-VIEW-01), the
 * calendar (FR-VIEW-02), the timeline (FR-VIEW-03), the Glance widget (FR-WID-01), and
 * `Person.toCard`, which had its own copy of this arithmetic until M4 and no longer does.
 * Every date question is still delegated to [BirthdayMath]; nothing here knows what a leap
 * year is.
 *
 * Pure. No I/O, no clock of its own unless a caller declines to supply one, nothing that
 * cannot be driven from a test in an arbitrary time zone.
 */

/**
 * One row per (person, event), which is the shape the views and the widget need.
 *
 * [PersonCard] is one row per PERSON and remains that, for the People directory. The
 * difference matters: somebody with a birthday in March and an anniversary in June appears
 * twice in a list of upcoming events and once in a directory of people.
 */
data class UpcomingEntry(
    val person: Person,
    val event: PersonEvent,
    val nextOccurrence: LocalDate,
    val daysUntil: Int,
    val turningAge: Int?,
) {
    val personId: String get() = person.id
    val displayName: String get() = person.displayName
    val title: String get() = event.title

    /** A deceased person's events are all SOLEMN; the UI branches on this one flag. */
    val isSolemn: Boolean get() = person.isSolemn

    val countdown: String get() = BirthdayMath.countdownLabel(daysUntil)
    val bucket: UpcomingBucket get() = UpcomingBucket.of(daysUntil)
}

/**
 * The four groups FR-VIEW-01 names, as ROLLING windows rather than calendar ones.
 *
 * This is the decision in this file worth arguing about, so: a calendar reading of "this
 * week" and "this month" puts a birthday four days away under **Later** whenever those four
 * days cross a month boundary. On 28 December, every birthday in the first week of January
 * — the ones a user most needs to see — would sit at the bottom of the list under the
 * heading for things that are not urgent. For an app whose entire job is "do not let me
 * miss this", that is the one failure that cannot be shipped.
 *
 * Rolling windows never do that. The cost is that the labels are approximate at the edges:
 * on the 25th, something 29 days away is called "This month" when it falls in the next one.
 * A heading that is loose beats a heading that hides.
 *
 * The boundaries themselves (7, 30) are the test cases in `UpcomingTest`, including across
 * a month end and a year end.
 */
enum class UpcomingBucket(val label: String) {
    TODAY("Today"),
    THIS_WEEK("This week"),
    THIS_MONTH("This month"),
    LATER("Later"),
    ;

    companion object {
        const val WEEK_DAYS = 7
        const val MONTH_DAYS = 30

        fun of(daysUntil: Int): UpcomingBucket = when {
            daysUntil <= 0 -> TODAY
            daysUntil <= WEEK_DAYS -> THIS_WEEK
            daysUntil <= MONTH_DAYS -> THIS_MONTH
            else -> LATER
        }
    }
}

/** A heading and the entries under it. Only non-empty buckets become sections. */
data class UpcomingSection(
    val bucket: UpcomingBucket,
    val entries: List<UpcomingEntry>,
)

/**
 * Every one of this person's events, with its next occurrence worked out.
 *
 * Muted events are INCLUDED, as they are on the person's card today: muting stops the
 * reminder, not the record, and silencing somebody's birthday in the user's own view of
 * their own data is a different feature from the one they asked for.
 */
fun upcomingFor(
    person: Person,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
    today: LocalDate = Clock.System.todayIn(timeZone),
): List<UpcomingEntry> = person.events.map { event ->
    UpcomingEntry(
        person = person,
        event = event,
        nextOccurrence = BirthdayMath.nextOccurrence(event.date.day, event.date.month, timeZone, today),
        daysUntil = BirthdayMath.daysUntilNext(event.date.day, event.date.month, timeZone, today),
        // A deceased person is never "turning" an age, whatever the arithmetic says.
        turningAge = if (person.isDeceased) {
            null
        } else {
            BirthdayMath.turningAge(event.date.day, event.date.month, event.date.year, timeZone, today)
        },
    )
}

/**
 * Everything coming up, soonest first.
 *
 * [withinDays] bounds the horizon, which is what the widget wants — a home-screen widget
 * showing a birthday eleven months away is showing noise. The list passes null and groups
 * the lot.
 *
 * Ties break on the person's name so the order is stable: two people sharing a birthday
 * must not swap places between one read and the next, or the list appears to shuffle itself
 * while the user is looking at it.
 */
fun upcomingEvents(
    people: List<Person>,
    timeZone: TimeZone = TimeZone.currentSystemDefault(),
    today: LocalDate = Clock.System.todayIn(timeZone),
    withinDays: Int? = null,
): List<UpcomingEntry> = people
    .flatMap { upcomingFor(it, timeZone, today) }
    .filter { withinDays == null || it.daysUntil <= withinDays }
    .sortedWith(compareBy({ it.daysUntil }, { it.displayName }, { it.event.id }))

/** The sections FR-VIEW-01 renders. Empty buckets are left out rather than shown empty. */
fun groupUpcoming(entries: List<UpcomingEntry>): List<UpcomingSection> =
    UpcomingBucket.entries.mapNotNull { bucket ->
        entries.filter { it.bucket == bucket }
            .takeIf { it.isNotEmpty() }
            ?.let { UpcomingSection(bucket, it) }
    }
