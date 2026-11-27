package `in`.wishkaro.app.ui.poster

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import `in`.wishkaro.app.domain.EventDate
import `in`.wishkaro.app.domain.EventType
import `in`.wishkaro.app.domain.Person
import `in`.wishkaro.app.domain.PersonEvent
import `in`.wishkaro.app.domain.POSTER_THEMES
import `in`.wishkaro.app.domain.Sentiment
import `in`.wishkaro.app.domain.ThemeFamily
import `in`.wishkaro.app.domain.posterThemesFor
import org.junit.Rule
import org.junit.Test

/**
 * FR-POS-13 on a real device: **no celebratory theme is reachable in the picker for a
 * remembrance.**
 *
 * ## Why this is an instrumented test and not a unit test
 *
 * `occasion.spec.ts` and `PosterThemesTest` already assert that the filter FUNCTION returns
 * the right list. This asserts that the SCREEN shows that list and no other — which is a
 * different claim, and it is the one the requirement is about. FR-POS-13 says celebratory
 * themes are "filtered out of the picker", and a picker is a composition, not a function.
 *
 * The failure it is built to catch is the one M5 shipped twice: a correct rule and a screen
 * that reached around it. `454ea75` was exactly that shape — `tonesFor()` was right and the
 * composer asked it with the wrong argument.
 *
 * ## The stale window, on the device
 *
 * The solemn person below has `isDeceased = false` and a DEATH_ANNIVERSARY event, which is
 * precisely what the local SQLDelight row looks like between adding a death anniversary on
 * the handset and the next sync pull. Every other fixture in this repository builds a person
 * the way the SERVER returns one, with the flag already set — which is why the bug survived
 * twice with a full green suite.
 */
class PosterPickerFilterTest {

    @get:Rule
    val rule = createComposeRule()

    /** Invented, like every person in this repository's tests (MILESTONE_CHECKLIST.md §F). */
    private fun person(
        name: String,
        isDeceased: Boolean,
        events: List<PersonEvent>,
    ) = Person(
        id = "p1",
        displayName = name,
        relationToOwner = "Uncle",
        isSelf = false,
        gender = null,
        phone = null,
        email = null,
        notes = null,
        isDeceased = isDeceased,
        isMinor = false,
        completenessScore = 40,
        completenessSuggestions = emptyList(),
        deletedAt = null,
        events = events,
        traits = emptyList(),
    )

    private fun event(type: EventType, sentiment: Sentiment) = PersonEvent(
        id = "e-${type.name}",
        personId = "p1",
        type = type,
        sentiment = sentiment,
        label = null,
        isMuted = false,
        date = EventDate(day = 2, month = 11, year = 2014),
    )

    private fun render(state: PosterState) {
        rule.setContent {
            PosterScreen(
                state = state,
                onBack = {},
                onSelectTheme = {},
                onSelectFormat = {},
                onGenerate = {},
                onPickPhoto = {},
                onRemovePhoto = {},
                onHandOver = {},
                onReport = {},
            )
        }
    }

    @Test
    fun aRemembranceOffersNoCelebratoryTheme_evenWhileIsDeceasedIsStillFalse() {
        // The stale window, on purpose: the flag says alive, the event row says otherwise.
        val rohan = person(
            name = "Rohan Deshpande",
            isDeceased = false,
            events = listOf(
                event(EventType.DEATH_ANNIVERSARY, Sentiment.SOLEMN),
                // A birthday whose stored sentiment has NOT been recomputed yet.
                event(EventType.BIRTHDAY, Sentiment.CELEBRATORY),
            ),
        )

        render(
            PosterState(
                person = rohan,
                event = rohan.events.last(),
                themes = posterThemesFor(rohan),
                selectedThemeId = posterThemesFor(rohan).first().id,
                loaded = true,
            ),
        )

        val celebratory = POSTER_THEMES.filter { it.family == ThemeFamily.CELEBRATORY }
        val solemn = POSTER_THEMES.filter { it.family == ThemeFamily.SOLEMN }

        // Filtered OUT, not ranked lower. `assertDoesNotExist` is the assertion that
        // matches the requirement's word: a theme that is merely off-screen still exists in
        // the tree and is still reachable by scrolling.
        celebratory.forEach { theme ->
            rule.onNodeWithTag("poster_theme_${theme.id}").assertDoesNotExist()
        }
        solemn.forEach { theme ->
            rule.onNodeWithTag("poster_theme_${theme.id}").assertExists()
        }

        // And the screen says what it is, rather than being the celebratory screen with the
        // swatches swapped.
        rule.onNodeWithTag("poster_person").assertIsDisplayed()
    }

    @Test
    fun aLivingPersonStillGetsTheCelebratoryFamily() {
        // The other half. Without this, a filter that answered SOLEMN to everything would
        // pass the test above — which is the shape of green revert-check the checklist
        // warns about.
        val asha = person(
            name = "Asha Kulkarni",
            isDeceased = false,
            events = listOf(event(EventType.BIRTHDAY, Sentiment.CELEBRATORY)),
        )

        render(
            PosterState(
                person = asha,
                event = asha.events.first(),
                themes = posterThemesFor(asha),
                selectedThemeId = posterThemesFor(asha).first().id,
                loaded = true,
            ),
        )

        POSTER_THEMES.filter { it.family == ThemeFamily.CELEBRATORY }.forEach { theme ->
            rule.onNodeWithTag("poster_theme_${theme.id}").assertExists()
        }
        POSTER_THEMES.filter { it.family == ThemeFamily.SOLEMN }.forEach { theme ->
            rule.onNodeWithTag("poster_theme_${theme.id}").assertDoesNotExist()
        }
    }

    @Test
    fun reportThisContentIsOnEveryGeneratedAsset() {
        // FR-POS-12 and §12's Play GenAI box. It appears once there is an asset to report,
        // and not before — reporting a poster that does not exist is not a thing.
        val asha = person(
            name = "Asha Kulkarni",
            isDeceased = false,
            events = listOf(event(EventType.BIRTHDAY, Sentiment.CELEBRATORY)),
        )
        val ready = PosterState(
            person = asha,
            event = asha.events.first(),
            themes = posterThemesFor(asha),
            selectedThemeId = posterThemesFor(asha).first().id,
            stage = PosterStage.READY,
            poster = `in`.wishkaro.app.data.remote.PosterDto(
                id = "poster-1",
                personId = "p1",
                themeId = posterThemesFor(asha).first().id,
                family = "CELEBRATORY",
                sentiment = "CELEBRATORY",
                eventType = "BIRTHDAY",
                format = "STATUS",
            ),
            loaded = true,
        )

        render(ready)
        rule.onNodeWithTag("poster_report").assertExists()
    }
}
