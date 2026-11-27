package `in`.wishkaro.app.ui

import `in`.wishkaro.app.domain.CompletenessField
import `in`.wishkaro.app.domain.EventDate
import `in`.wishkaro.app.domain.EventType
import `in`.wishkaro.app.domain.Person
import `in`.wishkaro.app.domain.PersonEvent
import `in`.wishkaro.app.domain.PersonTrait
import `in`.wishkaro.app.domain.Sentiment
import `in`.wishkaro.app.domain.TraitKind
import `in`.wishkaro.app.domain.UpcomingBucket
import `in`.wishkaro.app.domain.UpcomingEntry
import `in`.wishkaro.app.domain.UpcomingSection
import kotlinx.datetime.LocalDate

/**
 * The people in M7's screenshots. **Every one of them is invented.**
 *
 * ## Why this file exists at all, and why the screenshots do not come off the handset
 *
 * This repository is **public**, and M7's definition of done asks for 36 screenshots with
 * people in them. The obvious way to produce those — open the app on the handset and press the
 * screenshot button — is the way this project has already been burned twice:
 *
 *  - `M5_VERIFY` §0.5: the one screenshot proving definition-of-done item 1 had to be deleted,
 *    because a hand-off into a messaging app carried somebody's real conversations and phone
 *    numbers behind the thing it meant to capture.
 *  - `M9` §9.6: the account offered for a deletion test turned out to be the owner's own, with
 *    six people in it, two of which read as real family.
 *
 * And `MILESTONE_CHECKLIST.md`, bought by M9: *"PII arrives in a verification document through
 * the HONESTY, not through carelessness."*
 *
 * So the screenshots are produced by a harness that composes each screen from the fixtures
 * below. Nothing in them comes from a device's database, a contacts list or a live account.
 * That makes "no real person appears in these images" a property of the code rather than a
 * claim about how carefully somebody looked, which is the distinction this project keeps
 * paying for.
 *
 * ## About the names
 *
 * Invented, and chosen to exercise what the design actually has to survive: a two-token Latin
 * name, a single-token one, a long one that will wrap at a large font scale, and a Devanagari
 * one — because `PersonAvatar` derives its initials through `nameTokens()`, which exists
 * because `जयदत्त जांसरी` used to tokenise to fragments (`M3_PLAN` §3), and because **neither
 * Inter nor Fraunces covers Devanagari**, so a screenshot is where a missing-glyph box would
 * be visible.
 *
 * `docs/fixtures/name-tokens.json` is NOT reused here. `OPEN_DECISIONS` D5 records that the
 * file contains a name nobody has verified is invented, while its own header asserts every
 * person in it is. Three milestones read that file; this one does not, and these names are
 * new.
 */
object DesignFixtures {

    /** A fixed date, so the same run produces the same countdowns. */
    val TODAY = LocalDate(2026, 3, 9)

    private fun event(
        id: String,
        personId: String,
        type: EventType,
        day: Int,
        month: Int,
        year: Int? = null,
        sentiment: Sentiment = Sentiment.CELEBRATORY,
        muted: Boolean = false,
        label: String? = null,
    ) = PersonEvent(
        id = id,
        personId = personId,
        type = type,
        sentiment = sentiment,
        label = label,
        isMuted = muted,
        date = EventDate(day, month, year),
    )

    private fun person(
        id: String,
        name: String,
        relation: String?,
        events: List<PersonEvent>,
        deceased: Boolean = false,
        minor: Boolean = false,
        score: Int = 100,
        suggestions: List<String> = emptyList(),
        gender: String? = null,
        phone: String? = null,
        email: String? = null,
        notes: String? = null,
        traits: List<PersonTrait> = emptyList(),
    ) = Person(
        id = id,
        displayName = name,
        relationToOwner = relation,
        isSelf = false,
        gender = gender,
        phone = phone,
        email = email,
        notes = notes,
        isDeceased = deceased,
        isMinor = minor,
        completenessScore = score,
        completenessSuggestions = suggestions,
        deletedAt = null,
        events = events,
        traits = traits,
    )

    /** Two tokens, a Latin name, a birthday nine days out. */
    val meera = person(
        id = "f-meera",
        name = "Meera Kulkarni",
        relation = "Sister",
        gender = "Female",
        phone = "+91 90000 00000",
        notes = "Allergic to lilies.",
        traits = listOf(
            PersonTrait("t1", "f-meera", TraitKind.HOBBY, "Pottery"),
            PersonTrait("t2", "f-meera", TraitKind.LIKE, "Filter coffee"),
            PersonTrait("t3", "f-meera", TraitKind.DISLIKE, "Surprise parties"),
        ),
        events = listOf(
            event("e-meera-b", "f-meera", EventType.BIRTHDAY, 18, 3, 1994),
            event("e-meera-a", "f-meera", EventType.WEDDING_ANNIVERSARY, 2, 11, 2019),
        ),
    )

    /** One token. The avatar has a single letter to work with. */
    val devika = person(
        id = "f-devika",
        name = "Devika",
        relation = "Colleague",
        score = 45,
        suggestions = listOf(CompletenessField.PHONE.wire, CompletenessField.NOTES.wire),
        events = listOf(event("e-devika-b", "f-devika", EventType.BIRTHDAY, 11, 3)),
    )

    /** Devanagari, and the longest name here. Both are deliberate — see the file header. */
    val jyoti = person(
        id = "f-jyoti",
        name = "ज्योति देशपांडे",
        relation = "Neighbour",
        score = 70,
        events = listOf(event("e-jyoti-b", "f-jyoti", EventType.BIRTHDAY, 27, 3, 1988)),
    )

    /** Long enough to wrap in a 44dp-indented row at a large font scale. */
    val arjun = person(
        id = "f-arjun",
        name = "Arjun Venkataraghavan",
        relation = "Cousin",
        score = 90,
        events = listOf(event("e-arjun-c", "f-arjun", EventType.CUSTOM, 5, 4, label = "Shop anniversary")),
    )

    /**
     * The remembrance. `isDeceased = true` AND a SOLEMN death anniversary.
     *
     * The pair `M7_VERIFY.md` item 9 rests on is this person and [meera] rendered through the
     * same composable — `PersonDetailScreen` — with nothing different about the call but which
     * `Person` goes in.
     */
    val nanaji = person(
        id = "f-nanaji",
        name = "Hariprasad Rao",
        relation = "Grandfather",
        deceased = true,
        notes = "Kept bees.",
        events = listOf(
            event(
                "e-nanaji-d",
                "f-nanaji",
                EventType.DEATH_ANNIVERSARY,
                21,
                3,
                2021,
                sentiment = Sentiment.SOLEMN,
            ),
            event(
                "e-nanaji-b",
                "f-nanaji",
                EventType.BIRTHDAY,
                4,
                9,
                1941,
                sentiment = Sentiment.SOLEMN,
                muted = true,
            ),
        ),
    )

    /** FR-PPL-09: a minor is excluded from profiling, and the person screen says so. */
    val ishaan = person(
        id = "f-ishaan",
        name = "Ishaan Kulkarni",
        relation = "Nephew",
        minor = true,
        score = 60,
        events = listOf(event("e-ishaan-b", "f-ishaan", EventType.BIRTHDAY, 14, 3, 2019)),
    )

    /**
     * The off-list case, which is the one the new dropdowns exist to not break.
     *
     * "Chachi" is not in `RELATION_CHIPS` and never will be — FR-PPL-02 is explicit that an
     * enum of relations *"would make 'Mama' or 'Chachi' unrepresentable in an India-first
     * app"*. Her gender is off-list too, and her phone carries a `+44`, which is the country
     * code a hardcoded `+91` prefix would have silently rewritten.
     */
    val chachi = person(
        id = "f-chachi",
        name = "Sunita Deshmukh",
        relation = "Chachi",
        gender = "Prefers not to say",
        phone = "+44 7700 900123",
        notes = "Sends sweets every Diwali.",
        events = listOf(event("e-chachi-b", "f-chachi", EventType.BIRTHDAY, 2, 8, 1966)),
    )

    val everyone = listOf(meera, devika, jyoti, arjun, nanaji, ishaan, chachi)

    private fun entry(p: Person, e: PersonEvent, days: Int, turningAge: Int? = null) =
        UpcomingEntry(
            person = p,
            event = e,
            nextOccurrence = LocalDate(2026, e.date.month, e.date.day),
            daysUntil = days,
            turningAge = turningAge,
        )

    /**
     * An upcoming list with one of everything the design has to hold at once: a birthday
     * today, a remembrance three days out, an age, a name with no year, and a custom event.
     */
    val upcomingSections: List<UpcomingSection> = listOf(
        UpcomingSection(
            bucket = UpcomingBucket.TODAY,
            entries = listOf(entry(devika, devika.events[0], days = 0)),
        ),
        UpcomingSection(
            bucket = UpcomingBucket.THIS_WEEK,
            entries = listOf(
                entry(ishaan, ishaan.events[0], days = 5, turningAge = 7),
            ),
        ),
        UpcomingSection(
            bucket = UpcomingBucket.THIS_MONTH,
            entries = listOf(
                entry(meera, meera.events[0], days = 9, turningAge = 32),
                entry(nanaji, nanaji.events[0], days = 12, turningAge = 5),
                entry(jyoti, jyoti.events[0], days = 18, turningAge = 38),
            ),
        ),
        UpcomingSection(
            bucket = UpcomingBucket.LATER,
            entries = listOf(entry(arjun, arjun.events[0], days = 27)),
        ),
    )

    val upcomingEntries: List<UpcomingEntry> = upcomingSections.flatMap { it.entries }
}
