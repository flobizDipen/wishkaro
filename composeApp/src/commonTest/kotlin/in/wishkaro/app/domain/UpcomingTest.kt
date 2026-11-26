package `in`.wishkaro.app.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The grouping boundaries, which definition-of-done item 9 asks for by name: Today / This
 * week / This month / Later, across a month end and a year end.
 *
 * The month end and the year end are the point of this file. `Upcoming.kt` groups by ROLLING
 * windows rather than calendar ones, and the reason is exactly these cases: read as calendar
 * months, a birthday four days away on 28 December lands under "Later", at the bottom of the
 * screen, under a heading that means "not urgent". These tests are what stops anybody
 * quietly changing that back.
 *
 * Every case is driven from an explicit `today`, never the machine's clock, and the suite is
 * run in `TZ=UTC` and `TZ=Pacific/Chatham` (§D of the checklist, bought by M2 §0.0.2).
 */
class UpcomingTest {

    private val zone = TimeZone.UTC

    private fun person(
        name: String,
        vararg dates: Triple<Int, Int, Int?>,
        deceased: Boolean = false,
    ): Person = Person(
        id = "p-$name",
        displayName = name,
        relationToOwner = null,
        isSelf = false,
        gender = null,
        phone = null,
        email = null,
        notes = null,
        isDeceased = deceased,
        isMinor = false,
        completenessScore = 0,
        completenessSuggestions = emptyList(),
        events = dates.mapIndexed { index, (day, month, year) ->
            PersonEvent(
                id = "e-$name-$index",
                personId = "p-$name",
                type = EventType.BIRTHDAY,
                sentiment = if (deceased) Sentiment.SOLEMN else Sentiment.CELEBRATORY,
                label = null,
                isMuted = false,
                date = EventDate(day, month, year),
            )
        },
        traits = emptyList(),
        deletedAt = null,
    )

    private fun bucketOn(today: LocalDate, day: Int, month: Int): UpcomingBucket =
        upcomingFor(person("x", Triple(day, month, null)), zone, today).single().bucket

    // ---------------------------------------------------------------------------------
    // The boundaries themselves
    // ---------------------------------------------------------------------------------

    @Test
    fun `the four buckets fall where their boundaries say`() {
        val today = LocalDate(2026, 3, 10)

        assertEquals(UpcomingBucket.TODAY, bucketOn(today, 10, 3), "0 days")
        assertEquals(UpcomingBucket.THIS_WEEK, bucketOn(today, 11, 3), "1 day")
        assertEquals(UpcomingBucket.THIS_WEEK, bucketOn(today, 17, 3), "7 days, the last of the week")
        assertEquals(UpcomingBucket.THIS_MONTH, bucketOn(today, 18, 3), "8 days, the first of the month")
        assertEquals(UpcomingBucket.THIS_MONTH, bucketOn(today, 9, 4), "30 days, the last of the month")
        assertEquals(UpcomingBucket.LATER, bucketOn(today, 10, 4), "31 days")
    }

    @Test
    fun `today is today however far away the stored year is`() {
        val today = LocalDate(2026, 6, 15)
        val entry = upcomingFor(person("Asha", Triple(15, 6, 1974)), zone, today).single()

        assertEquals(0, entry.daysUntil)
        assertEquals(UpcomingBucket.TODAY, entry.bucket)
        assertEquals("Today", entry.countdown)
        assertEquals(52, entry.turningAge)
    }

    // ---------------------------------------------------------------------------------
    // A month end
    // ---------------------------------------------------------------------------------

    @Test
    fun `a few days into the next month is still This week, not Later`() {
        // The whole reason the windows roll. On 28 November, 1 December is three days away.
        val today = LocalDate(2026, 11, 28)

        assertEquals(UpcomingBucket.THIS_WEEK, bucketOn(today, 1, 12), "3 days, next month")
        assertEquals(UpcomingBucket.THIS_WEEK, bucketOn(today, 5, 12), "7 days, next month")
        assertEquals(UpcomingBucket.THIS_MONTH, bucketOn(today, 6, 12), "8 days, next month")
    }

    @Test
    fun `the last day of a month and the first of the next are one day apart`() {
        val today = LocalDate(2026, 4, 30)

        assertEquals(UpcomingBucket.TODAY, bucketOn(today, 30, 4))
        assertEquals(UpcomingBucket.THIS_WEEK, bucketOn(today, 1, 5), "tomorrow, next month")
        assertEquals("Tomorrow", upcomingFor(person("x", Triple(1, 5, null)), zone, today).single().countdown)
    }

    // ---------------------------------------------------------------------------------
    // A year end
    // ---------------------------------------------------------------------------------

    @Test
    fun `the first week of January is This week when seen from late December`() {
        val today = LocalDate(2026, 12, 28)

        assertEquals(UpcomingBucket.THIS_WEEK, bucketOn(today, 1, 1), "4 days, next year")
        assertEquals(UpcomingBucket.THIS_WEEK, bucketOn(today, 4, 1), "7 days, next year")
        assertEquals(UpcomingBucket.THIS_MONTH, bucketOn(today, 5, 1), "8 days, next year")
        assertEquals(UpcomingBucket.THIS_MONTH, bucketOn(today, 27, 1), "30 days, next year")
        assertEquals(UpcomingBucket.LATER, bucketOn(today, 28, 1), "31 days, next year")
    }

    @Test
    fun `new year's eve to new year's day`() {
        val today = LocalDate(2026, 12, 31)
        val entry = upcomingFor(person("Vikram", Triple(1, 1, 2000)), zone, today).single()

        assertEquals(1, entry.daysUntil)
        assertEquals(LocalDate(2027, 1, 1), entry.nextOccurrence)
        assertEquals(UpcomingBucket.THIS_WEEK, entry.bucket)
        assertEquals(27, entry.turningAge, "the age is for the occurrence's year, not today's")
    }

    @Test
    fun `an event just gone is nearly a year away, not negative`() {
        val today = LocalDate(2026, 1, 2)
        val entry = upcomingFor(person("x", Triple(1, 1, null)), zone, today).single()

        assertEquals(LocalDate(2027, 1, 1), entry.nextOccurrence)
        assertEquals(364, entry.daysUntil)
        assertEquals(UpcomingBucket.LATER, entry.bucket)
    }

    // ---------------------------------------------------------------------------------
    // 29 February, through BirthdayMath and nowhere else
    // ---------------------------------------------------------------------------------

    @Test
    fun `29 February is observed on the 28th in a non-leap year`() {
        // FR-EVT-09: early rather than late, because a reminder after the day is worthless.
        val today = LocalDate(2027, 2, 1)
        val entry = upcomingFor(person("Leap", Triple(29, 2, 2000)), zone, today).single()

        assertEquals(LocalDate(2027, 2, 28), entry.nextOccurrence)
        assertEquals(27, entry.daysUntil)
        assertEquals(UpcomingBucket.THIS_MONTH, entry.bucket)
    }

    @Test
    fun `29 February is itself in a leap year`() {
        val today = LocalDate(2028, 2, 20)
        val entry = upcomingFor(person("Leap", Triple(29, 2, 2000)), zone, today).single()

        assertEquals(LocalDate(2028, 2, 29), entry.nextOccurrence)
        assertEquals(9, entry.daysUntil)
    }

    // ---------------------------------------------------------------------------------
    // One row per event, not per person
    // ---------------------------------------------------------------------------------

    @Test
    fun `a person with two dates appears twice, in two different groups`() {
        val today = LocalDate(2026, 3, 10)
        val meera = person("Meera", Triple(12, 3, 1990), Triple(1, 7, 2015))

        val sections = groupUpcoming(upcomingEvents(listOf(meera), zone, today))

        assertEquals(listOf(UpcomingBucket.THIS_WEEK, UpcomingBucket.LATER), sections.map { it.bucket })
        assertTrue(sections.all { it.entries.single().displayName == "Meera" })
    }

    @Test
    fun `the list is sorted soonest first and ties break on the name`() {
        val today = LocalDate(2026, 3, 10)
        val people = listOf(
            person("Zoya", Triple(12, 3, null)),
            person("Aarav", Triple(12, 3, null)),
            person("Kabir", Triple(11, 3, null)),
        )

        val names = upcomingEvents(people, zone, today).map { it.displayName }

        // Kabir is a day sooner; Aarav and Zoya share a day and must not swap places between
        // one read and the next, or the list appears to shuffle itself as the user watches.
        assertEquals(listOf("Kabir", "Aarav", "Zoya"), names)
    }

    @Test
    fun `empty buckets become no section at all`() {
        val today = LocalDate(2026, 3, 10)
        val sections = groupUpcoming(upcomingEvents(listOf(person("Solo", Triple(11, 3, null))), zone, today))

        assertEquals(1, sections.size)
        assertEquals(UpcomingBucket.THIS_WEEK, sections.single().bucket)
    }

    @Test
    fun `nobody at all produces no sections rather than four empty ones`() {
        assertEquals(emptyList(), groupUpcoming(upcomingEvents(emptyList(), zone, LocalDate(2026, 3, 10))))
    }

    // ---------------------------------------------------------------------------------
    // The horizon the widget uses
    // ---------------------------------------------------------------------------------

    @Test
    fun `withinDays bounds the horizon for the widget`() {
        val today = LocalDate(2026, 3, 10)
        val people = listOf(
            person("Soon", Triple(11, 3, null)),
            person("Later", Triple(11, 9, null)),
        )

        // A home-screen widget showing a birthday six months away is showing noise.
        val names = upcomingEvents(people, zone, today, withinDays = 7).map { it.displayName }
        assertEquals(listOf("Soon"), names)
    }

    // ---------------------------------------------------------------------------------
    // Solemn people
    // ---------------------------------------------------------------------------------

    @Test
    fun `a deceased person is never turning an age`() {
        val today = LocalDate(2026, 3, 10)
        val entry = upcomingFor(person("Remembered", Triple(12, 3, 1950), deceased = true), zone, today)
            .single()

        assertNull(entry.turningAge)
        assertTrue(entry.isSolemn)
    }

    // ---------------------------------------------------------------------------------
    // toCard is a selection from this, not a second copy of it
    // ---------------------------------------------------------------------------------

    @Test
    fun `toCard picks the soonest of the same entries this produces`() {
        val today = LocalDate(2026, 3, 10)
        val meera = person("Meera", Triple(1, 7, 2015), Triple(12, 3, 1990))

        val card = meera.toCard(zone, today)
        val soonest = upcomingFor(meera, zone, today).minByOrNull { it.daysUntil }!!

        // If these ever disagree, the app has two answers to "what is coming up" again.
        assertEquals(soonest.event.id, card.nextEvent?.id)
        assertEquals(soonest.daysUntil, card.daysUntil)
        assertEquals(soonest.nextOccurrence, card.nextOccurrence)
        assertEquals(soonest.turningAge, card.turningAge)
    }

    @Test
    fun `a person with no dates has no card countdown and no entries`() {
        val today = LocalDate(2026, 3, 10)
        val nobody = person("Dateless")

        assertTrue(upcomingFor(nobody, zone, today).isEmpty())
        assertNull(nobody.toCard(zone, today).daysUntil)
    }
}
