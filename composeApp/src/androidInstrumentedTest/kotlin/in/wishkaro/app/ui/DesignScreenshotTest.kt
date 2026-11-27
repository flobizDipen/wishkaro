package `in`.wishkaro.app.ui

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.test.platform.app.InstrumentationRegistry
import `in`.wishkaro.app.domain.EventType
import `in`.wishkaro.app.domain.PosterFormat
import `in`.wishkaro.app.domain.Sentiment
import `in`.wishkaro.app.domain.WishSource
import `in`.wishkaro.app.domain.ReminderSettings
import `in`.wishkaro.app.domain.Wish
import `in`.wishkaro.app.domain.WishChannel
import `in`.wishkaro.app.domain.WishLanguage
import `in`.wishkaro.app.domain.WishTone
import `in`.wishkaro.app.domain.posterThemesFor
import `in`.wishkaro.app.domain.toCard
import `in`.wishkaro.app.platform.OemVendor
import `in`.wishkaro.app.ui.addperson.AddPersonScreen
import `in`.wishkaro.app.ui.calendar.CalendarScreen
import `in`.wishkaro.app.ui.importing.ImportField
import `in`.wishkaro.app.ui.importing.ImportFileChooseScreen
import `in`.wishkaro.app.ui.importing.ImportResult
import `in`.wishkaro.app.ui.importing.ImportResultScreen
import `in`.wishkaro.app.ui.importing.ImportStartScreen
import `in`.wishkaro.app.ui.importing.PermissionRationale
import `in`.wishkaro.app.ui.onboarding.OnboardingScreen
import `in`.wishkaro.app.ui.people.PeopleListScreen
import `in`.wishkaro.app.ui.person.DeletedPeopleScreen
import `in`.wishkaro.app.ui.person.EditPersonScreen
import `in`.wishkaro.app.ui.person.EventEditorScreen
import `in`.wishkaro.app.ui.person.PersonDetailScreen
import `in`.wishkaro.app.ui.poster.PosterScreen
import `in`.wishkaro.app.ui.poster.PosterStage
import `in`.wishkaro.app.ui.poster.PosterState
import `in`.wishkaro.app.ui.settings.PrivacyState
import `in`.wishkaro.app.ui.settings.SettingsScreen
import `in`.wishkaro.app.ui.signin.SignInScreen
import `in`.wishkaro.app.ui.theme.WishKaroTheme
import `in`.wishkaro.app.ui.timeline.TimelineScreen
import `in`.wishkaro.app.ui.upcoming.UpcomingListScreen
import `in`.wishkaro.app.ui.wish.ComposerState
import `in`.wishkaro.app.ui.wish.DraftOrigin
import `in`.wishkaro.app.ui.wish.WishComposerScreen
import kotlinx.datetime.TimeZone
import java.io.File
import org.junit.Rule
import org.junit.Test

/**
 * Every screen in WishKaro, in both themes, from invented data.
 *
 * ## This is a harness, not an assertion
 *
 * It produces M7's definition-of-done item 2 — screenshots of every screen in light and dark —
 * and it deliberately asserts almost nothing. A screenshot test that compares against golden
 * images would be a different and much more expensive thing: goldens are device-specific, and
 * `MILESTONE_CHECKLIST.md` already records M6 shipping assertions pinned to platform values
 * that passed on the machine that wrote them and failed where the code runs. What this asserts
 * is only that each screen **composes and draws a non-empty bitmap** — which is not nothing: a
 * screen that throws on a `null` or renders zero pixels fails here rather than in a review.
 *
 * ## Why the screenshots are made this way rather than taken off the handset
 *
 * Because the repository is public and the handset holds a live account. `DesignFixtures`
 * explains that at length; the short version is that "no real person appears in these images"
 * is a property of this file rather than a claim about how carefully somebody looked at 36
 * PNGs. `M5` §0.5 and `M9` §9.6 are the two times this project paid for the other approach.
 *
 * ## Where the files go
 *
 * `getExternalFilesDir("design")` in the app's own sandbox, pulled with
 * `tools/device/screenshots.sh`. Not `/sdcard` — that needs a permission this app does not
 * have and should not acquire for a screenshot.
 *
 * ## Running it
 *
 *     ./gradlew :composeApp:assembleDebug :composeApp:assembleDebugAndroidTest
 *     ANDROID_SERIAL=<emulator> tools/device/screenshots.sh
 *
 * **On an emulator, not the handset.** `connectedAndroidTest` uninstalls the app when it
 * finishes — not `pm clear`, a full uninstall — which is how M4 wiped the owner's phone
 * mid-milestone. `tools/device/screenshots.sh` uses `am instrument` directly for the same
 * reason `tools/device/instrumented-tests.sh` does, but the emulator is where this belongs
 * regardless: it needs no account and holds no data.
 */
class DesignScreenshotTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val outputDir: File by lazy {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "design").apply { mkdirs() }
    }

    /**
     * Compose one screen ONCE and photograph it in both themes.
     *
     * The shape here is forced by the test rule and is worth recording, because the obvious
     * version does not work: `setContent` may be called **once per test**, so "render light,
     * render dark" as two calls throws `has already set content`. The theme is therefore a
     * `mutableStateOf` read inside the composition, and flipping it recomposes the same tree
     * into the other palette.
     *
     * That turns out to be the better test as well as the only one available. Both images come
     * from one composition of one screen with one set of fixtures, so a difference between them
     * can only be the theme — which is exactly the claim item 2 is making.
     *
     * The `Scaffold` wrapper matters: most of these screens sit inside one in the real app, and
     * a screen photographed without its container has whatever background the test harness's
     * window happens to have. `WishKaroTheme(darkTheme = …)` is passed explicitly rather than
     * read from the device, which is the reason that parameter is overridable at all — 46
     * images from one run, with no `adb shell cmd uimode` in between.
     */
    private fun both(name: String, content: @Composable () -> Unit) {
        val dark = mutableStateOf(false)

        rule.setContent {
            WishKaroTheme(darkTheme = dark.value) {
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.surface,
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) { content() }
                }
            }
        }

        capture(name, dark = false)
        rule.runOnUiThread { dark.value = true }
        capture(name, dark = true)
    }

    private fun capture(name: String, dark: Boolean) {
        // Fonts in `composeResources` load asynchronously, and the first frame of a screen can
        // be drawn in the fallback family before Fraunces arrives. Advancing the clock and
        // idling again is what makes the captured bitmap the settled one rather than the first
        // one — a screenshot of the wrong typeface would be a quietly wrong piece of evidence
        // for an item that is ABOUT the typeface.
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()

        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        check(bitmap.width > 0 && bitmap.height > 0) { "$name produced an empty bitmap" }

        val suffix = if (dark) "dark" else "light"
        File(outputDir, "$name-$suffix.png").outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    // ---------------------------------------------------------------------------------
    // First run
    // ---------------------------------------------------------------------------------

    @Test
    fun onboarding() = both("01-onboarding") {
        OnboardingScreen(onDone = {})
    }

    @Test
    fun signIn() = both("02-sign-in") {
        SignInScreen(busy = false, onSignIn = {})
    }

    // ---------------------------------------------------------------------------------
    // The four views (FR-VIEW-01..04)
    // ---------------------------------------------------------------------------------

    @Test
    fun upcoming() = both("03-upcoming") {
        UpcomingListScreen(
            sections = DesignFixtures.upcomingSections,
            focusedPersonId = DesignFixtures.meera.id,
            onOpenPerson = {},
            onAddPerson = {},
            onOpenImport = {},
        )
    }

    /**
     * FR-VIEW-05's empty state, which M4 recorded as met and never saw.
     *
     * `M4_VERIFY` item 2: *"the Upcoming, People and Timeline empty states were never seen,
     * because the only account available has 575 people in it and emptying it is not a
     * tidy-up."* It is still true of the handset, and it is why this harness exists as much
     * as the public-repo rule is.
     */
    @Test
    fun upcomingEmpty() = both("04-upcoming-empty") {
        UpcomingListScreen(
            sections = emptyList(),
            focusedPersonId = null,
            onOpenPerson = {},
            onAddPerson = {},
            onOpenImport = {},
        )
    }

    @Test
    fun calendar() = both("05-calendar") {
        CalendarScreen(
            entries = DesignFixtures.upcomingEntries,
            today = DesignFixtures.TODAY,
            onOpenPerson = {},
        )
    }

    @Test
    fun timeline() = both("06-timeline") {
        TimelineScreen(
            entries = DesignFixtures.upcomingEntries,
            today = DesignFixtures.TODAY,
            onOpenPerson = {},
        )
    }

    @Test
    fun people() = both("07-people") {
        PeopleListScreen(
            cards = DesignFixtures.everyone.map {
                it.toCard(TimeZone.currentSystemDefault(), DesignFixtures.TODAY)
            },
            filter = PeopleFilter(),
            knownRelations = listOf("Sister", "Cousin", "Colleague", "Neighbour"),
            focusedPersonId = null,
            busy = false,
            onOpenPerson = {},
            onAddPerson = {},
            onQueryChange = {},
            onRelationFilter = {},
            onMonthFilter = {},
            onClearFilter = {},
            onOpenDeleted = {},
            onRefresh = {},
            onOpenSettings = {},
            onOpenImport = {},
            blockedRecords = emptyList(),
            onSignOut = {},
        )
    }

    @Test
    fun peopleEmpty() = both("08-people-empty") {
        PeopleListScreen(
            cards = emptyList(),
            filter = PeopleFilter(),
            knownRelations = emptyList(),
            focusedPersonId = null,
            busy = false,
            onOpenPerson = {},
            onAddPerson = {},
            onQueryChange = {},
            onRelationFilter = {},
            onMonthFilter = {},
            onClearFilter = {},
            onOpenDeleted = {},
            onRefresh = {},
            onOpenSettings = {},
            onOpenImport = {},
            blockedRecords = emptyList(),
            onSignOut = {},
        )
    }

    // ---------------------------------------------------------------------------------
    // The person, celebratory and solemn — definition-of-done item 9's pair
    // ---------------------------------------------------------------------------------

    /**
     * These two tests are item 9.
     *
     * *"The solemn variant proved to be one token, with the screenshot pair."* The two calls
     * below are byte-for-byte the same except for which `Person` goes in, and the two
     * fixtures differ in `isDeceased` and in one event's `sentiment`. There is no second
     * screen, no `if (solemn)` in the layout, and no solemn-only composable — the difference
     * on screen is what `MaterialTheme.accent` returns inside the `SolemnScope` that
     * `PersonDetailScreen` opens with `person.isSolemn`.
     *
     * The WORDS also differ, and that is M5's work rather than M7's: `toneFor(person)` picks
     * one of two string sets. M7 did not touch it.
     */
    @Test
    fun personCelebratory() = both("09-person-celebratory") {
        PersonScreenFor(DesignFixtures.meera.id)
    }

    @Test
    fun personSolemn() = both("10-person-solemn") {
        PersonScreenFor(DesignFixtures.nanaji.id)
    }

    @Composable
    private fun PersonScreenFor(personId: String) {
        val person = DesignFixtures.everyone.first { it.id == personId }
        PersonDetailScreen(
            person = person,
            onBack = {},
            onEdit = {},
            onAddEvent = {},
            onEditEvent = {},
            onDeleteEvent = {},
            onToggleMute = { _, _ -> },
            onDelete = {},
            onAddTrait = { _, _ -> },
            onRemoveTrait = {},
            onSendWish = {},
            onMakePoster = {},
            wishes = if (person.isSolemn) {
                emptyList()
            } else {
                listOf(
                    Wish(
                        id = "w1",
                        personId = person.id,
                        type = EventType.BIRTHDAY,
                        occurrenceYear = 2025,
                        sentiment = Sentiment.CELEBRATORY,
                        eventId = person.events.first().id,
                        messageText = "Happy birthday! Hope the kiln behaves this year.",
                        tone = WishTone.WARM,
                        language = WishLanguage.ENGLISH,
                        channel = WishChannel.WHATSAPP,
                        source = WishSource.TEMPLATE,
                        sentAt = "2025-03-18T09:00:00Z",
                    ),
                )
            },
        )
    }

    // ---------------------------------------------------------------------------------
    // Forms
    // ---------------------------------------------------------------------------------

    @Test
    fun addPerson() = both("11-add-person") {
        AddPersonScreen(
            duplicates = emptyList(),
            onCheckDuplicates = { _, _ -> false },
            onDismissDuplicates = {},
            onSave = { _, _, _, _, _ -> },
            onOpenExisting = {},
            onCancel = {},
        )
    }

    @Test
    fun editPerson() = both("12-edit-person") {
        EditPersonScreen(
            person = DesignFixtures.meera,
            onBack = {},
            onSave = { _, _, _, _, _, _, _ -> },
        )
    }

    /**
     * The same screen for somebody whose relation and gender are NOT on either list.
     *
     * This is the case the dropdowns have to get right and the one a chip row got wrong by
     * accident rather than by design: "Chachi" is a real relation in an India-first app
     * (FR-PPL-02 says so in as many words), and it must open with the dropdown on **Other**
     * and the word still in the box — not with an empty picker that discards it on save.
     */
    @Test
    fun editPersonWithCustomValues() = both("24-edit-person-custom") {
        EditPersonScreen(
            person = DesignFixtures.chachi,
            onBack = {},
            onSave = { _, _, _, _, _, _, _ -> },
        )
    }

    @Test
    fun eventEditor() = both("13-event-editor") {
        EventEditorScreen(
            person = DesignFixtures.meera,
            eventId = DesignFixtures.meera.events.first().id,
            onBack = {},
            onSave = { _, _, _ -> },
        )
    }

    // ---------------------------------------------------------------------------------
    // Settings, the restore window, import
    // ---------------------------------------------------------------------------------

    @Test
    fun settings() = both("14-settings") {
        SettingsScreen(
            settings = ReminderSettings(
                ladderD7 = true,
                ladderD1 = true,
                ladderD0 = true,
                reminderHour = 9,
                quietHoursStart = 22,
                quietHoursEnd = 7,
                digestEnabled = false,
                digestHour = 9,
            ),
            reliability = ReliabilityState(
                notificationsAllowed = true,
                exactAlarmsAllowed = false,
                batteryUnrestricted = true,
                vendor = OemVendor.OTHER,
            ),
            selfTest = SelfTestState.Idle,
            onBack = {},
            onChange = {},
            onSendTest = {},
            onDismissTest = {},
            onOpenBatterySettings = {},
            onRequestExactAlarms = {},
            // An invented address on a reserved domain. RFC 2606 keeps `example.invalid`
            // exactly so it can be used this way, and `tools/check-no-pii.sh` does not match
            // it — which is the point: the delete dialog has to show an address, and that
            // address must not be a person's.
            privacy = PrivacyState(email = "you@example.invalid"),
        )
    }

    @Test
    fun deletedPeople() = both("15-deleted-people") {
        DeletedPeopleScreen(
            people = listOf(DesignFixtures.arjun),
            onBack = {},
            onRestore = {},
        )
    }

    @Test
    fun importStart() = both("16-import-start") {
        ImportStartScreen(onContacts = {}, onFile = {}, onBack = {})
    }

    /** FR-AUTH-06's prominent disclosure — the first thing the contacts import shows. */
    @Test
    fun importContacts() = both("17-import-contacts") {
        PermissionRationale(busy = false, onAsk = {}, onBack = {})
    }

    @Test
    fun importFile() = both("18-import-file") {
        ImportFileChooseScreen(busy = false, onPick = {}, onBack = {})
    }

    @Test
    fun importResult() = both("19-import-result") {
        ImportResultScreen(
            result = ImportResult(
                imported = 12,
                skipped = emptyList(),
                failed = emptyList(),
                warnings = listOf("Arjun Venkataraghavan" to "name shortened to fit"),
                header = listOf("Name", "Date", "Relation"),
                sourceName = "family-birthdays.csv",
            ),
            savedReport = null,
            busy = false,
            onSaveFailures = {},
            onDone = {},
        )
    }

    // ---------------------------------------------------------------------------------
    // The composer and the poster studio, each in both tones
    // ---------------------------------------------------------------------------------

    @Test
    fun composer() = both("20-composer") {
        WishComposerScreen(
            state = ComposerState(
                person = DesignFixtures.meera,
                event = DesignFixtures.meera.events.first(),
                occurrenceYear = 2026,
                text = "Happy birthday, Meera. Wishing you a year with more time at the " +
                    "wheel and fewer cracked glazes.",
                tone = WishTone.WARM,
                origin = DraftOrigin.TEMPLATE,
                loaded = true,
            ),
            onBack = {},
            onToneChange = {},
            onLanguageChange = {},
            onRegenerate = {},
            onEdit = {},
            onSend = {},
            onConfirmSent = {},
            onDismissConfirm = {},
        )
    }

    @Test
    fun composerSolemn() = both("21-composer-solemn") {
        WishComposerScreen(
            state = ComposerState(
                person = DesignFixtures.nanaji,
                event = DesignFixtures.nanaji.events.first(),
                occurrenceYear = 2026,
                text = "Thinking of your grandfather today, and of the bees.",
                tone = WishTone.HEARTFELT,
                origin = DraftOrigin.TEMPLATE,
                loaded = true,
            ),
            onBack = {},
            onToneChange = {},
            onLanguageChange = {},
            onRegenerate = {},
            onEdit = {},
            onSend = {},
            onConfirmSent = {},
            onDismissConfirm = {},
        )
    }

    @Test
    fun poster() = both("22-poster-studio") {
        PosterScreen(
            state = PosterState(
                person = DesignFixtures.meera,
                event = DesignFixtures.meera.events.first(),
                occurrenceYear = 2026,
                themes = posterThemesFor(DesignFixtures.meera),
                selectedThemeId = posterThemesFor(DesignFixtures.meera).firstOrNull()?.id,
                format = PosterFormat.DEFAULT,
                stage = PosterStage.IDLE,
                loaded = true,
            ),
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

    /**
     * A deliberate non-screen: the type scale, so the verification document can show what
     * Fraunces and Inter look like beside each other rather than asking a reader to find them
     * inside a list row.
     *
     * It is also where the **Devanagari** question is visible. Neither bundled family contains
     * those glyphs, and Android's `Typeface.Builder` is supposed to append the default fallback
     * family to a custom typeface so a missing glyph resolves through the system font. "Is
     * supposed to" is not a verification: a row of empty boxes in this image is what the
     * failure looks like, and `M7_VERIFY.md` item 3 says which it is.
     */
    @Test
    fun typeScale() = both("23-type-scale") {
        TypeSpecimen()
    }
}

@Composable
private fun TypeSpecimen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val type = MaterialTheme.typography
        val ink = MaterialTheme.colorScheme.onSurface
        val quiet = MaterialTheme.colorScheme.onSurfaceVariant

        Text("18 March", style = type.displaySmall, color = ink)
        Text("Meera Kulkarni", style = type.headlineMedium, color = ink)
        Text("in 9 days", style = type.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text("Settings", style = type.titleLarge, color = ink)
        Text("Meera Kulkarni", style = type.titleMedium, color = ink)
        Text("When to remind you", style = type.titleSmall, color = MaterialTheme.colorScheme.primary)
        Text("Body large \u2014 the quick brown fox jumps over the lazy dog.", style = type.bodyLarge, color = ink)
        Text("Body medium \u2014 the quick brown fox jumps over the lazy dog.", style = type.bodyMedium, color = ink)
        Text("Body small \u2014 a quiet note under a control.", style = type.bodySmall, color = quiet)
        Text("LABEL LARGE", style = type.labelLarge, color = ink)
        Text("Label medium", style = type.labelMedium, color = quiet)
        Text("Label small", style = type.labelSmall, color = quiet)

        Text("\u2014", style = type.bodyMedium, color = quiet)

        // Devanagari in both families. Neither contains these glyphs; if Android's fallback
        // works, they render in the system font, and if it does not, this is a row of boxes.
        Text("\u091C\u094D\u092F\u094B\u0924\u093F \u0926\u0947\u0936\u092A\u093E\u0902\u0921\u0947", style = type.titleMedium, color = ink)
        Text("\u0939\u093F\u0928\u094D\u0926\u0940 \u2014 \u0936\u0941\u092D \u091C\u0928\u094D\u092E\u0926\u093F\u0928", style = type.bodyLarge, color = ink)
    }
}
