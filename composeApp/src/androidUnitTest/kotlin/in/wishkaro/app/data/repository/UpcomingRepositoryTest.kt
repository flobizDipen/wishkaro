package `in`.wishkaro.app.data.repository

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import `in`.wishkaro.app.data.local.LocalStore
import `in`.wishkaro.app.domain.EventDate
import `in`.wishkaro.app.domain.EventType
import `in`.wishkaro.app.domain.UpcomingBucket
import `in`.wishkaro.app.domain.groupUpcoming
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import wishkaro.db.WishKaroDb
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The upcoming query against a REAL database.
 *
 * `UpcomingTest` drives the pure grouping, which is where the boundaries live. This drives
 * the seam — §D of the checklist, and M3's own addition that *a fake store cannot catch a
 * disagreement between the code and the schema, because it agrees with whatever the code
 * does to it.* The widget and all four views read through this one object, so what it does
 * with a deleted person or a person with no dates is worth pinning to the real schema.
 *
 * `commonTest` cannot build a `LocalStore`; the Android unit-test source set runs on the JVM
 * and can stand up an in-memory SQLite, which is why this lives here.
 */
class UpcomingRepositoryTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also {
        WishKaroDb.Schema.create(it)
    }
    private val db = WishKaroDb(driver)
    private val store = LocalStore(db)

    /** A fixed day, so nothing here depends on when it is run. */
    private val today = LocalDate(2026, 3, 10)

    private val repository = UpcomingRepository(
        local = store,
        timeZone = { TimeZone.UTC },
        today = { today },
    )

    @AfterTest
    fun close() = driver.close()

    private suspend fun person(
        id: String,
        name: String,
        vararg dates: Pair<Int, Int>,
        deleted: Boolean = false,
    ) {
        val now = "2026-03-01T00:00:00Z"
        // addPersonLocally insists on a first event, exactly as the Add Person screen does
        // (FR-PPL-01), so a dateless person is made by adding one and removing it — which is
        // also the only way a real user ends up with one.
        val first = dates.firstOrNull()
        store.addPersonLocally(
            personId = id,
            eventId = "$id-e0",
            displayName = name,
            relation = null,
            type = EventType.BIRTHDAY,
            label = null,
            date = EventDate(first?.first ?: 1, first?.second ?: 1, 1990),
            nowIso = now,
        )
        if (first == null) store.deleteEventLocally("$id-e0", now)
        dates.drop(1).forEachIndexed { index, (day, month) ->
            store.addEventLocally(
                eventId = "$id-e${index + 1}",
                personId = id,
                type = EventType.BIRTHDAY,
                label = null,
                date = EventDate(day, month, 1990),
                nowIso = now,
            )
        }
        if (deleted) store.deletePersonLocally(id, "2026-03-02T00:00:00Z")
    }

    @Test
    fun `the widget's one-shot read sees the same events the list does`() = runTest {
        person("p1", "Kabir", 11 to 3)
        person("p2", "Meera", 12 to 3, 1 to 7)

        val live = repository.observeUpcoming().first()
        val oneShot = repository.upcomingNow()

        // If these two ever disagree, the widget and the list are answering the same
        // question differently, which is the one thing BUILD_PROMPT_M4 rules out by name.
        assertEquals(
            live.map { it.personId to it.event.id },
            oneShot.map { it.personId to it.event.id },
        )
        assertEquals(3, oneShot.size, "two people, three events")
    }

    /**
     * The filtering happens in SQL (`person.selectAll` and `event.selectAll` both carry
     * `WHERE deleted_at IS NULL`), not in the repository. Pinned here anyway, against the
     * real schema, because that is the layer a future change would break it from — and a
     * removed person appearing on the home-screen widget is the kind of bug a user tells
     * other people about.
     */
    @Test
    fun `a deleted person is not coming up`() = runTest {
        person("p1", "Kabir", 11 to 3)
        person("p2", "Gone", 11 to 3, deleted = true)

        assertEquals(listOf("Kabir"), repository.upcomingNow().map { it.displayName })
        assertEquals(listOf("Kabir"), repository.observeUpcoming().first().map { it.displayName })
    }

    @Test
    fun `a person with no dates is simply absent, not an empty row`() = runTest {
        person("p1", "Kabir", 11 to 3)
        person("p2", "Dateless")

        val entries = repository.upcomingNow()
        assertEquals(1, entries.size)
        assertEquals("Kabir", entries.single().displayName)
    }

    @Test
    fun `a removed date stops coming up while the person stays`() = runTest {
        person("p1", "Kabir", 11 to 3, 12 to 3)
        store.deleteEventLocally("p1-e1", "2026-03-02T00:00:00Z")

        val entries = repository.upcomingNow()
        assertEquals(1, entries.size, "one of the two dates was removed")
        assertEquals("p1-e0", entries.single().event.id)
    }

    @Test
    fun `the horizon and the limit are both honoured`() = runTest {
        person("p1", "Soon", 11 to 3)
        person("p2", "Alsosoon", 12 to 3)
        person("p3", "Months", 11 to 9)

        assertEquals(
            listOf("Alsosoon", "Soon"),
            repository.upcomingNow(withinDays = 7).map { it.displayName }.sorted(),
        )
        assertEquals(1, repository.upcomingNow(limit = 1).size, "the widget asks for a few, not all")
    }

    @Test
    fun `grouping a real read puts each event under the right heading`() = runTest {
        person("p1", "Today", 10 to 3)
        person("p2", "ThisWeek", 15 to 3)
        person("p3", "ThisMonth", 1 to 4)
        person("p4", "Later", 1 to 9)

        val sections = groupUpcoming(repository.upcomingNow())

        assertEquals(
            listOf(
                UpcomingBucket.TODAY,
                UpcomingBucket.THIS_WEEK,
                UpcomingBucket.THIS_MONTH,
                UpcomingBucket.LATER,
            ),
            sections.map { it.bucket },
        )
        assertTrue(sections.all { it.entries.size == 1 })
    }
}
