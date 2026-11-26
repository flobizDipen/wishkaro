package `in`.wishkaro.app.data.repository

import `in`.wishkaro.app.data.remote.ApiErrorIssue

/**
 * How many rows of each entity one `POST /v1/sync` carries.
 *
 * The server caps each array at 500 (`SyncPushSchema`). 200 rather than 500 for three
 * reasons, in order of weight: a page of 200 people with their events is 50-100 KB, which is
 * a reasonable request on a hobby tier and on Indian mobile data; a page that fails costs
 * 200 rows of progress instead of 500; and 200 is already this codebase's page size in two
 * other places — the pull's default limit and `DELIVERY_REPORT_BATCH` — so there is one
 * number to remember rather than three.
 */
internal const val PUSH_PAGE_SIZE = 200

/**
 * A guard, not a budget. It is not expected to bind: an import is capped at 5,000 rows, so
 * even the largest one this app allows is about 50 pages of people and events together.
 * If it ever does bind, the remainder simply stays in the outbox and goes on the next run.
 */
internal const val MAX_PUSH_PAGES = 100

/**
 * How much of the outbox one run looks at.
 *
 * The query is `ORDER BY updated_at, id LIMIT this`, so a run always takes the oldest rows
 * and always takes the same ones. Before M3 the query had no LIMIT at all and one request
 * tried to carry everything — see docs/M3_PLAN.md §1.
 */
internal const val OUTBOX_SNAPSHOT_LIMIT = 10_000L

/** One request's worth of the outbox. */
internal data class PushPage(
    val people: List<wishkaro.db.Person> = emptyList(),
    val events: List<wishkaro.db.Event> = emptyList(),
    val traits: List<wishkaro.db.Person_trait> = emptyList(),
    val wishes: List<wishkaro.db.Wish> = emptyList(),
) {
    fun isEmpty(): Boolean =
        people.isEmpty() && events.isEmpty() && traits.isEmpty() && wishes.isEmpty()

    /** The same page without the rows the server has just named and refused. */
    fun without(rejected: List<RejectedRow>): PushPage {
        val ids = rejected.groupBy({ it.entity }, { it.id }).mapValues { it.value.toSet() }
        return PushPage(
            people = people.filterNot { it.id in ids[PERSON].orEmpty() },
            events = events.filterNot { it.id in ids[EVENT].orEmpty() },
            traits = traits.filterNot { it.id in ids[TRAIT].orEmpty() },
            wishes = wishes.filterNot { it.id in ids[WISH].orEmpty() },
        )
    }
}

internal data class PushPlan(
    val pages: List<PushPage>,
    /**
     * Events and traits held back because their person is pending but did not fit in this
     * snapshot. Counted rather than dropped silently, so a test can assert on it.
     */
    val deferred: Int = 0,
    /** True when [MAX_PUSH_PAGES] cut the list. The rest waits for the next run. */
    val truncated: Boolean = false,
)

/** One row the server refused by name, with the server's own reason. */
internal data class RejectedRow(val entity: String, val id: String, val reason: String)

internal const val PERSON = "person"
internal const val EVENT = "event"
internal const val TRAIT = "trait"
internal const val WISH = "wish"

/**
 * Cut the outbox snapshot into requests, so that a person is **never in a later page than
 * their own events**.
 *
 * That ordering is the whole reason this is not three independent `chunked(200)` calls.
 * `upsertEvent` returns UNKNOWN_PERSON when the event's person is not on the server yet;
 * within one request that cannot happen, because the server applies `people` before
 * `events`. Across pages it can, and chunking the three arrays independently would produce
 * it constantly — page one carrying events whose people are in page two.
 *
 * So pages are filled with UNITS. A unit is either a pending person together with every
 * pending event and trait of theirs, or an event/trait whose person is not pending at all
 * (something added to a person the server already knows).
 *
 * The third case has no unit: an event whose person **is** pending but is not in this
 * snapshot. It is deferred, not sent. Sending it would earn a guaranteed UNKNOWN_PERSON —
 * handled correctly, but a wasted row in a request and a row of the user's data budget. It
 * goes on a later run, once its person has gone.
 */
internal fun planPages(
    people: List<wishkaro.db.Person>,
    events: List<wishkaro.db.Event>,
    traits: List<wishkaro.db.Person_trait>,
    wishes: List<wishkaro.db.Wish>,
    pendingPersonIds: Set<String>,
    pageSize: Int = PUSH_PAGE_SIZE,
    maxPages: Int = MAX_PUSH_PAGES,
): PushPlan {
    val inSnapshot = people.mapTo(mutableSetOf()) { it.id }
    val eventsByPerson = events.groupBy { it.person_id }
    val traitsByPerson = traits.groupBy { it.person_id }
    val wishesByPerson = wishes.groupBy { it.person_id }

    val units = mutableListOf<PushPage>()
    for (person in people) {
        units += PushPage(
            people = listOf(person),
            events = eventsByPerson[person.id].orEmpty(),
            traits = traitsByPerson[person.id].orEmpty(),
            wishes = wishesByPerson[person.id].orEmpty(),
        )
    }

    var deferred = 0
    for (event in events) {
        if (event.person_id in inSnapshot) continue
        if (event.person_id in pendingPersonIds) {
            deferred += 1
            continue
        }
        units += PushPage(events = listOf(event))
    }
    for (trait in traits) {
        if (trait.person_id in inSnapshot) continue
        if (trait.person_id in pendingPersonIds) {
            deferred += 1
            continue
        }
        units += PushPage(traits = listOf(trait))
    }
    // A wish depends on its PERSON and on nothing else. That is the whole reason
    // `wish.event_id` is a soft reference with no foreign key: with a real one, a wish would
    // have to be in the same page as its event as well, and a unit cannot express two
    // parents. See the Wish model in schema.prisma and docs/M5_PLAN.md §2.3.
    for (wish in wishes) {
        if (wish.person_id in inSnapshot) continue
        if (wish.person_id in pendingPersonIds) {
            deferred += 1
            continue
        }
        units += PushPage(wishes = listOf(wish))
    }

    val pages = mutableListOf<PushPage>()
    var currentPeople = mutableListOf<wishkaro.db.Person>()
    var currentEvents = mutableListOf<wishkaro.db.Event>()
    var currentTraits = mutableListOf<wishkaro.db.Person_trait>()
    var currentWishes = mutableListOf<wishkaro.db.Wish>()

    fun isCurrentEmpty(): Boolean =
        currentPeople.isEmpty() && currentEvents.isEmpty() &&
            currentTraits.isEmpty() && currentWishes.isEmpty()

    fun flush() {
        if (isCurrentEmpty()) return
        pages += PushPage(
            currentPeople.toList(),
            currentEvents.toList(),
            currentTraits.toList(),
            currentWishes.toList(),
        )
        currentPeople = mutableListOf()
        currentEvents = mutableListOf()
        currentTraits = mutableListOf()
        currentWishes = mutableListOf()
    }

    for (unit in units) {
        val wouldOverflow = currentPeople.size + unit.people.size > pageSize ||
            currentEvents.size + unit.events.size > pageSize ||
            currentTraits.size + unit.traits.size > pageSize ||
            currentWishes.size + unit.wishes.size > pageSize
        // The emptiness check is what stops a unit bigger than a whole page from looping: a
        // person with more than `pageSize` events gets a page to themselves rather than
        // flushing an empty page for ever. The server's own 500 cap still applies to it, and
        // one person with 500 events is not a shape this app can produce.
        if (wouldOverflow && !isCurrentEmpty()) {
            flush()
        }
        currentPeople += unit.people
        currentEvents += unit.events
        currentTraits += unit.traits
        currentWishes += unit.wishes
    }
    flush()

    return PushPlan(
        pages = pages.take(maxPages),
        deferred = deferred,
        truncated = pages.size > maxPages,
    )
}

/**
 * Which rows of [page] the server's validation errors actually name.
 *
 * `ZodValidationPipe` sends one issue per failed check, dot-joined:
 * `people.37.displayName`. Index 37 is the position in the array **this page** sent, which
 * is why this takes the page rather than the whole outbox.
 *
 * An issue that names no row is skipped on purpose, not treated as "no rows at fault":
 *
 *  - `people` alone is the array-level `.max(500)` check. It names no row, and chunking
 *    means it can no longer happen anyway.
 *  - an index past the end of the array means the server and this page disagree about what
 *    was sent, which is a bug to find, not a row to delete.
 *
 * The caller must treat an empty result as "could not attribute this failure" and leave the
 * page pending, never as "nothing was wrong".
 */
internal fun rowsRejectedBy(page: PushPage, issues: List<ApiErrorIssue>): List<RejectedRow> {
    val found = linkedMapOf<Pair<String, String>, String>()
    for (issue in issues) {
        val parts = issue.path.split('.')
        if (parts.size < 2) continue
        val index = parts[1].toIntOrNull() ?: continue
        val entity: String
        val id: String
        when (parts[0]) {
            "people" -> {
                entity = PERSON
                id = page.people.getOrNull(index)?.id ?: continue
            }
            "events" -> {
                entity = EVENT
                id = page.events.getOrNull(index)?.id ?: continue
            }
            "traits" -> {
                entity = TRAIT
                id = page.traits.getOrNull(index)?.id ?: continue
            }
            "wishes" -> {
                entity = WISH
                id = page.wishes.getOrNull(index)?.id ?: continue
            }
            else -> continue
        }
        // The field name is kept in the reason because "displayName: too long" is something a
        // user can act on and "too long" on its own is not. First issue per row wins; the
        // rest are the same row's other problems and one line is enough to go and look.
        val field = parts.getOrNull(2)
        found.getOrPut(entity to id) {
            if (field != null) "$field: ${issue.message}" else issue.message
        }
    }
    return found.map { (key, reason) -> RejectedRow(key.first, key.second, reason) }
}
