# M7 Plan — Identity and first run

> Branch `feat/m7-identity`, off `main` at `e1bfa0c`. Written before any Compose was changed,
> as `MILESTONE_CHECKLIST.md` §B requires.
>
> **What this milestone is:** the design system nine milestones deferred (TBD-5 / `OPEN_DECISIONS`
> A3, answered 2026-11-27), plus a splash, a real launcher icon, a component set, a dark theme,
> and `FR-AUTH-01`'s value carousel. **No new features.**

---

## 0. What `main` actually contains, measured rather than assumed

`MILESTONE_CHECKLIST.md` §A says a prompt must be written against what `main` contains. The same
applies to a plan. Every number below was counted on `e1bfa0c`, and two of them disagree with the
build prompt — stated here rather than discovered in the verification document.

| Claim in `BUILD_PROMPT_M7.md` | Measured | Verdict |
|---|---|---|
| 208 reads of `MaterialTheme.colorScheme` / `.typography` | **210** (`grep -rhoE 'MaterialTheme\.(colorScheme\|typography)' composeApp/src \| wc -l`) | close enough; the point stands |
| 81 composables | **84** `@Composable` in `commonMain` | close enough |
| exactly 2 hardcoded colours, both in `PosterScreen.kt` | **2** — `Color.Gray` and `Color.DarkGray`, `PosterScreen.kt:379-380` | **confirmed exactly** |
| **14 screens** | **17 screen composables / 17 `Screen` destinations** | **the prompt undercounts.** See §0.1 |
| `Theme.kt` is ten lines calling `MaterialTheme()` with no arguments | 10 lines, confirmed | confirmed |
| no `isSystemInDarkTheme` anywhere | confirmed — 0 hits in `composeApp/src` | confirmed |
| launcher icon background `#FF6750A4` | confirmed, `res/values/colors.xml` | confirmed |
| three shared components | confirmed — `Fields.kt`, `NavGlyphs.kt`, `WishKaroTopBar.kt` | confirmed |

### 0.1 "14 screens" is wrong, and it matters for item 2

Definition-of-done item 2 asks for screenshots of **all 14 screens in both themes**. There are
not 14. `Screen.kt` declares **17** destinations and there are **17** screen-level composables:

1. `SignInScreen` · 2. `UpcomingListScreen` · 3. `CalendarScreen` · 4. `TimelineScreen` ·
5. `PeopleListScreen` · 6. `AddPersonScreen` · 7. `PersonDetailScreen` · 8. `EditPersonScreen` ·
9. `EventEditorScreen` · 10. `SettingsScreen` · 11. `DeletedPeopleScreen` · 12. `ImportStartScreen` ·
13. `ImportContactsScreen` · 14. `ImportFileScreen` · 15. `ImportResultScreen` ·
16. `WishComposerScreen` · 17. `PosterScreen`

14 was most likely the count before M5 added the composer and M6 added the poster studio — the
figure was carried into `REQUIREMENTS.md`'s M7 row and into the prompt without being recounted.

**Decision: shoot all 17, plus the new onboarding carousel, in both themes.** 14 is a floor, not
a target, and meeting a number by picking which three screens not to show is the shape of
dishonesty this project keeps writing checklists about. The verification document reports
**18 screens × 2 themes = 36 screenshots** and says why the number is not 14.

### 0.2 Two things the prompt did not mention that this milestone has to handle

- **Devanagari.** `WishLanguage.HINDI.label` is `"हिन्दी"` and renders as a chip in the composer;
  a user's own Hindi draft goes in the text field; an India-first app's person names can be in
  any Indic script. **Inter has no Devanagari.** Android's `Typeface.Builder` appends the default
  fallback family, so a missing glyph should resolve through the system font rather than render
  tofu — but "should" is not a verification. §7 makes it a named device check with a screenshot.
- **The widget has no theme at all.** `UpcomingWidget` uses stock `GlanceTheme`, which is
  dynamic colour on Android 12+ — so the widget is currently the user's wallpaper palette, not
  WishKaro's. Definition-of-done item 9 names the widget, so it gets a `GlanceTheme` built from
  the same tokens.

---

## 1. The token set

### 1.1 Colour — and why the given hex values moved

The prompt says the exact values "are a starting point, not scripture. You may adjust them to
meet the contrast rule." They had to be adjusted, and by more than a nudge. The measured
starting point:

| Pair | Given values | Measured |
|---|---|---|
| saffron accent as text on ivory | `#C8742A` on `#FDFBF7` | **3.39:1** — fails 4.5, fails even the prompt's own 3.5 guess |
| white label on a saffron button | `#FFFFFF` on `#C8742A` | **3.51:1** — fails 4.5 |
| solemn accent as text on ivory | `#8A8578` on `#FDFBF7` | **3.56:1** — fails 4.5 |
| ink on ivory | `#15131C` on `#FDFBF7` | 17.80:1 — fine |
| dark accent on dark ground | `#E09A52` on `#15131C` | 7.81:1 — fine |

So **both** halves of the light accent fail: it is unreadable as text *and* unreadable as a fill.
The prompt's rule — "the accent is for large text, icons, and fills with ink on top, never for
body text" — would have been one answer. It is the wrong answer **for this codebase**, because
`primary` is already used as a *text* colour in eight places that are not large text:

| Where | Style | WCAG class |
|---|---|---|
| `UpcomingListScreen` section header | `titleMedium` SemiBold ≈ 16sp | not large |
| `UpcomingListScreen` countdown | `labelLarge` 14sp | not large |
| `PeopleListScreen` countdown | `labelLarge` 14sp | not large |
| `components/Fields.kt` `SectionHeader` | `titleSmall` 14sp | not large |
| `CalendarScreen` day heading | `titleSmall` SemiBold | not large |
| `TimelineScreen` month heading | `titleMedium` SemiBold | not large |
| `PersonDetailScreen` countdown | `headlineSmall` 24sp | **large** |
| `ImportResultScreen` "Saved." | `bodySmall` 12sp | not large |

WCAG large text is 18.66px bold or 24px regular — in Compose, ≈18.66sp bold or 24sp regular. Seven
of those eight are below it. Rewriting all eight to stop using the accent would mean the identity
appears nowhere in the app's text, which is the opposite of what this milestone is for.

**So the token darkens instead, exactly as the prompt instructs where a pair fails.** Shipped:

| Token | Given | Shipped | On ivory | Under white |
|---|---|---|---|---|
| saffron accent (light) | `#C8742A` | **`#8F531E`** | **5.93:1** | **6.13:1** |
| solemn accent (light) | `#8A8578` | **`#67635A`** | **5.79:1** | 6.17:1 |
| saffron accent (dark) | `#E09A52` | `#E09A52` **unchanged** | 7.81:1 on ink | — |
| solemn accent (dark) | `#8A8578` | **`#B9B2A3`** | 8.72:1 on ink | — |

`#8F531E` is the same hue (28°) and the same saturation (65%) as `#C8742A` at lightness 0.34
instead of 0.475. It is a deeper burnt saffron, not a different colour. The direction is intact;
the lightness is not negotiable against 4.5:1.

`#C8742A` is kept in the file as a documented constant — it is the direction's reference value
and the thing the dark theme's `#E09A52` is a lightening of — but nothing draws with it.

**Every Material 3 role is filled deliberately**, as the prompt asks, not derived from a seed and
not dynamic. The full scheme is in `ui/theme/Color.kt` with the measured ratio beside each pair.

### 1.2 The contrast rule becomes a test, not a table

A table in a verification document is true on the day it is written. `ThemeContrastTest` in
`commonTest` computes the WCAG 2.x relative-luminance ratio for **every** pair this app actually
draws, in **both** themes, and fails the build below the threshold. The table in `M7_VERIFY.md`
is that test's own output, so the document cannot drift from the code.

Thresholds, stated rather than assumed:

| Class | Minimum | Why |
|---|---|---|
| body and label text on its background | 4.5:1 | WCAG 1.4.3 AA, normal text |
| UI component boundaries (`outline`) | 3.0:1 | WCAG 1.4.11 |
| dividers (`outlineVariant`) | 1.5:1 | decorative; recorded, not a pass/fail gate at AA |

Two pairs are recorded **as measurements rather than as gates**, with a reason:
`primaryContainer` and `secondaryContainer` against `surface` are ~1.2:1 in light. They are pale
fills — a pale fill at 3:1 against ivory is not pale, and "warm paper" is pale fills. Where one of
them carries *state* (the calendar's selected day, a deep-linked person's highlighted card) the
state is additionally carried by a **border drawn in `primary`**, which is a 1.4.11-compliant
element at 5.93:1. That is a real change to those two screens, in §4.

### 1.3 Type

| Scale | Family | Size / line | Note |
|---|---|---|---|
| displayLarge | Fraunces 9pt SemiBold | 57 / 64, −1.0 tracking | the carousel's one word |
| displayMedium | Fraunces 9pt SemiBold | 45 / 52, −0.8 | |
| displaySmall | Fraunces 9pt SemiBold | 36 / 44, −0.5 | the sign-in wordmark |
| headlineLarge | Fraunces 9pt SemiBold | 32 / 40 | |
| headlineMedium | Fraunces 9pt SemiBold | 28 / 36 | |
| headlineSmall | Fraunces 9pt SemiBold | 24 / 32 | **the countdown — the date is the hero** |
| titleLarge | Fraunces 9pt SemiBold | 22 / 28 | screen titles |
| titleMedium | Fraunces 9pt SemiBold | 18 / 24 | **a person's name** (M3 default is 16) |
| titleSmall | Fraunces 9pt SemiBold | 15 / 20 | section headers |
| bodyLarge | Inter Regular | 16 / 24 | |
| bodyMedium | Inter Regular | 14 / 20 | |
| bodySmall | Inter Regular | 12 / 18 | notes |
| labelLarge | Inter SemiBold | 14 / 20 | buttons, countdowns in lists |
| labelMedium | Inter Medium | 12 / 16 | |
| labelSmall | Inter Medium | 11 / 16 | |

Fraunces on display/headline/title, Inter on body/label — exactly as the prompt specifies.
`titleMedium` moves 16 → 18sp because it is what a person's name is drawn in on three screens, and
a name set in a serif at 18 is the single change that does most to stop this looking like a
default Material app.

### 1.4 Shape and motion

`Shapes`: extraSmall 4dp, small 8dp, **medium 12dp** (cards), large 16dp, extraLarge 24dp.
Chips use `extraSmall` — the prompt's "4dp chips, soft rather than pill-round". Material's
`FilterChip` defaults to `small` (8dp); overriding it is one line in the theme's chip shape, not
40 call sites.

`Motion.kt`: named durations (`Fast` 120ms, `Settled` 300ms, `Slow` 450ms) and easings
(`EaseOutSettled` = `CubicBezierEasing(0.2f, 0f, 0f, 1f)`, no overshoot, no spring). "Settled" is
a token so that it is not a number retyped in 40 places.

---

## 2. Fonts, bundled

`composeApp/src/commonMain/composeResources/font/`:

| File | Source | Bytes |
|---|---|---|
| `inter_regular.ttf` | `rsms/inter` v4.1 release, `extras/ttf/Inter-Regular.ttf`, unmodified | 411,640 |
| `inter_medium.ttf` | same release, unmodified | 417,300 |
| `inter_semibold.ttf` | same release, unmodified | 419,744 |
| `fraunces_regular.ttf` | `undercasetype/Fraunces`, `fonts/ttf/Fraunces9pt-Regular.ttf`, unmodified | 105,416 |
| `fraunces_semibold.ttf` | same, unmodified | 107,888 |
| `fraunces_bold.ttf` | same, unmodified | 109,116 |

~1.57 MB in all. Licences committed beside them as `OFL-Inter.txt` and `OFL-Fraunces.txt`, with a
`README.md` naming the upstream, the version and the fact that the binaries are **unmodified**.

Three decisions worth recording:

- **Static instances, not the variable fonts.** Google Fonts publishes only
  `Inter[opsz,wght].ttf` and `Fraunces[SOFT,WONK,opsz,wght].ttf`. Selecting a weight from a
  variable font needs `variationSettings`, whose support in `org.jetbrains.compose.resources` at
  1.11.1 is not something to bet a milestone on — and without it every weight above 400 is
  synthesised, which is faux bold. The upstream projects publish static instances themselves.
- **Upstream statics, never instanced here.** Both fonts carry a Reserved Font Name. Running
  `fonttools varLib.instancer` would produce a *derivative*, which the OFL permits but which must
  then not carry the reserved name. Vendoring the foundry's own unmodified static builds avoids
  the question entirely.
- **`Fraunces9pt`, one optical size.** Fraunces' `opsz` axis runs 9–144. Most of the Fraunces in
  this app is 15–24sp; `9pt` is the instance drawn for that range. One optical size is 3 files
  rather than 9.
- **Not `google-fonts` downloadable fonts.** The prompt requires the app to render identically in
  airplane mode and on a first launch with no network, which a downloadable provider cannot
  promise. Item 3 is verified with the radio off.

---

## 3. Solemn as one token, enforced

`ui/theme/Solemn.kt`:

```kotlin
val LocalSolemn: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

/** The accent, desaturated where the composition is solemn. One read, every call site. */
val MaterialTheme.accent: Color
    @Composable get() = if (LocalSolemn.current) WishKaroColors.solemnAccent() else colorScheme.primary
```

Call sites change from `MaterialTheme.colorScheme.primary` to `MaterialTheme.accent`, and a
screen becomes solemn by being wrapped in `SolemnScope(person.isSolemn) { ... }`. A solemn
person's detail screen and a celebratory one's are then **the same composable, the same layout,
the same call tree**, differing by what one composition local returns.

**Item 9 is proved by construction as well as by screenshot:** the screenshot harness renders
`PersonDetailScreen` twice from one fixture that differs only in `isDeceased`, and the pair is in
the verification document. M5's solemn ladder, restricted tone list and separate string sets are
**not touched** — `M5_VERIFY.md` was read first; this themes what M5 built.

The existing `isSolemn ->  surfaceVariant` card-colour branches stay. They are a surface
distinction, not an accent one, and they predate this milestone.

---

## 4. Component inventory — derived from reading all 17 screens

Not from the prompt's list. Each row below names where the repetition actually is.

| Component | Repeated in | What it replaces |
|---|---|---|
| `WishKaroCard` | `UpcomingRow`, `PersonRow`, `EventRow`, `DeletedPeopleScreen`, `BlockedBanner`, `PreviewTable`, import start cards | 7 near-identical `Card(colors = when { highlighted -> …; isSolemn -> …; else -> … })` blocks |
| `PersonAvatar` | **nothing** — does not exist today | the brief's "avatar with initials fallback"; initials from `nameTokens()`, so it is the rule the app already has rather than a second one |
| `DateBadge` | `UpcomingRow`, `TimelineRow` (the 56dp date rail), `PersonRow`, `CalendarScreen`'s day heading | day-over-month in Fraunces; this is what makes the date the hero |
| `SectionHeader` | already exists in `Fields.kt` (7 screens) **and a second, different one** in `UpcomingListScreen` (label + count) | one component with an optional trailing slot |
| `EmptyState` | `UpcomingEmptyState`, `PeopleListScreen.EmptyState` ×2, `DeletedPeopleScreen`, `CalendarScreen` ×3, `PersonDetailScreen`, `WishComposerScreen`, `ImportResultScreen`, widget | 11 hand-written "nothing here" blocks, each a different shape |
| `LoadingState` | `UpcomingListScreen`, `PersonDetailScreen`, `WishComposerScreen`, `ImportContactsScreen`, `PosterScreen` | 5 centred `CircularProgressIndicator`s |
| `ActionPair` | `UpcomingEmptyState`, `PeopleListScreen.EmptyState`, `PersonDetailScreen`, `PosterScreen.HandOverRow`, `ImportFileScreen`, `PermissionRefused` | 6 primary+secondary button pairs |
| `Note` / `WarningNote` | `ClashNote`, `ProvenanceLine`, `PhotoRow`, `AddPersonScreen`, `EditPersonScreen`, `EventEditorScreen`, `MappingScreen` ×4, `ImportResultScreen` ×3 | ~14 `Text(…, bodySmall, onSurfaceVariant)` explanatory lines |
| `ToggleRow` | `SettingsScreen.SettingRow`, `EditPersonScreen`, `PersonDetailScreen.EventRow` | 3 switch-plus-label rows |
| `DetailTopBar` | 7 ad-hoc `TopAppBar`s with a `TextButton("Back"/"Cancel")` nav icon | one bar, one back affordance |

`Fields.kt`, `NavGlyphs.kt` and `WishKaroTopBar.kt` stay; `SectionHeader` moves out of `Fields.kt`
into its own file with the rest.

**Adoption order, because 17 screens is more surface than a design milestone should refactor at
once:** the components land first and are adopted on the screens the user meets most — Sign-in,
Upcoming, People, Person detail, Calendar, Timeline, Settings, and the carousel. The import flow,
the forms, the composer and the poster studio get the **tokens** (which they already read) plus
the specific fixes in §6, and are adopted into the components only where the change is local.
`M7_VERIFY.md` says which screens adopted which component rather than claiming a clean sweep.

---

## 5. Splash, icon, carousel

**Splash.** `androidx.core:core-splashscreen` 1.0.1 (minSdk 26 needs the compat library).
`Theme.WishKaro.Starting` with `windowSplashScreenBackground` = ivory in `values/`, ink in
`values-night/`, and `postSplashScreenTheme` = `Theme.WishKaro`, which itself gets a
`android:windowBackground` of the same ivory/ink. **The white flash is the point of the item**: it
comes from the *window background*, not the splash, so `values-night/themes.xml` and an explicit
`windowBackground` are the fix, and `installSplashScreen()` plus `setKeepOnScreenCondition` holds
the splash until the first frame is ready. Verified by recording a cold start with
`adb shell screenrecord` and reading the frames, not by watching it once.

**Launcher icon.** `#FF6750A4` → ivory `#FDFBF7` background, saffron foreground, and a
`<monochrome>` layer so Android 13+ themed icons work. The mark: a lit diya flame over a crescent,
drawn as a vector — one shape that reads at 48dp, India-first without being a flag. Verified on
the handset's launcher with themed icons both on and off.

**The carousel — `FR-AUTH-01`.** Three screens, **Remember · Wish · Gift**, skippable, shown once.

Copy that is **true of the app being shipped** (the prompt is explicit, and "Gift" has nothing
behind it until M8):

| Screen | Heading | Body |
|---|---|---|
| Remember | *Remember* | "Every birthday and anniversary that matters, in one place. WishKaro reminds you a week before, the day before, and on the day." |
| Wish | *Wish* | "A message ready when the day comes — yours to change before you send it. WishKaro never sends anything for you." |
| Gift | *Gift* | "Keep what somebody likes beside their name, so next year you are not starting from nothing." |

No AI is promised. "Gift" describes the traits M1 already stores, which is true today, rather
than the catalog M8 will add.

**Persistence.** The flag lives in `SecureStorage` — already an `expect/actual` with an Android
`EncryptedSharedPreferences` implementation, already injected, and already surviving process death
and restart, which is exactly item 4's requirement. It is **not** `rememberSaveable`: saved state
dies with the task, and "shown once" has to outlive a swipe from Recents. It is also **not** a new
storage mechanism; adding one would be a new feature.

The carousel sits **before sign-in**, in the same `authState` branch `App.kt` already has, because
the point of a value carousel is to be read by somebody deciding whether to sign in.

---

## 6. The restyle pass — what tokens cannot fix

Walked all 17 screens. These are the items that are code, not colour:

1. **`PosterScreen.kt:379-380`** — `Color.Gray` / `Color.DarkGray` become
   `MaterialTheme.colorScheme.surfaceVariant` / `surfaceContainerHighest`, matching the fallback
   the preview above already uses. (`parseHex`'s `Color(0xFF000000L or value)` is a *parser* and
   stays; the guard in §8 needs an explicit exemption for it, named in the file.)
2. **Empty states** — `M4_VERIFY` says they "lead somewhere". They do, and they look like plain
   text. All 11 become `EmptyState` with a drawn mark, a heading in Fraunces, and the actions as
   an `ActionPair`.
3. **Type hierarchy** — "the date should dominate; right now everything is `bodyMedium`."
   `UpcomingRow` and `PersonRow` get a `DateBadge` on the leading edge and the name in
   `titleMedium` (Fraunces 18sp); the countdown moves from `labelLarge` to `titleMedium`.
4. **Edge-to-edge** — `enableEdgeToEdge()` is already called and the outer `Scaffold` is
   deliberately `WindowInsets(0,0,0,0)` with per-screen `Scaffold`s insetting themselves. The gap
   is the screens that are **not** in a `Scaffold`: `SettingsScreen` (a bare `LazyColumn`),
   `ImportStartScreen`, `ImportFileScreen`, `ImportResultScreen`, `PermissionRationale`,
   `PermissionRefused`, `ContactsReview` — seven screens whose content can sit under a system bar.
   Each gets the appropriate `windowInsetsPadding`. The `edge-to-edge` skill is used for this.
5. **The calendar's selected / today cells** and **the highlighted person card** get a `primary`
   border, per §1.2 — the state stops being conveyed by a 1.2:1 fill alone.
6. **The widget** gets a `GlanceTheme` built from the same tokens, replacing dynamic colour, and
   a dark variant.

---

## 7. Verification plan, written before building

`MILESTONE_CHECKLIST.md` §E and §F, plus the specific traps this milestone has.

**Screenshots come from an instrumented screenshot harness, not from the handset's screen.**
This is a deliberate choice and it is about PII. The handset holds a live account; M9 §9.6 records
that the owner's own account has people in it that read as real family, and M5 §0.5 records a
screenshot that had to be deleted because of what was behind the app. A harness composes each
screen from **invented fixtures** under `WishKaroTheme(dark = false)` and `(dark = true)` and
captures the bitmap. 36 screenshots, every person in them invented, by construction rather than by
inspection. `tools/check-no-pii.sh` is run **before** the push, never after.

The harness runs on an **emulator**, not the handset: `connectedAndroidTest` uninstalls the app
when it finishes (`MILESTONE_CHECKLIST.md`, bought by M4 wiping the handset mid-milestone).

**What must happen on the handset itself**, with `tools/device/ui.sh` guarding every tap:

| Item | How |
|---|---|
| 3 — fonts render from the bundle offline | airplane mode on, force-stop, cold launch, screenshot; plus the Devanagari check from §0.2 |
| 4 — carousel shown once, surviving restart **and process death** | fresh install → carousel; skip → sign-in; kill via `am force-stop`; relaunch → no carousel; `am kill` for process death; reboot |
| 5 — no white flash | `adb shell screenrecord` of a cold start, frames extracted and read one by one |
| 6 — launcher icon, themed and not | `adb shell settings` toggle / launcher settings, screenshot of the launcher |
| 10 — the 21 instrumented tests | `tools/device/instrumented-tests.sh`, **never** `./gradlew connectedAndroidTest` |

**Items that will be reported NOT MET if they are not met.** `M9_VERIFY.md` §16 is the model and
"partial" is not a word this document will use.

---

## 8. `tools/check-no-raw-colors.sh`

Modelled on `tools/check-no-pii.sh`, for the reason M9 bought: a guard inside a path-filtered
workflow is blind to the case that motivated it.

- Greps `composeApp/src/**/*.kt` for `Color(0x…)`, `Color.Red`-style named constants and
  `android.graphics.Color`, **excluding `ui/theme/`** — which is the one directory that is allowed
  to name a colour.
- One escape hatch, deliberate and narrow: a line carrying the comment `// raw-color-ok:` plus a
  reason. `PosterScreen.parseHex` is the only use, and the reason is in the file.
- `--pattern` prints the regex, so there is one definition if a test ever wants it.
- Runs from `.github/workflows/design.yml` with **no path filter at all** — not added to
  `android.yml`, which is filtered to `composeApp/**` and would be blind to a colour added
  anywhere else.
- The `ThemeContrastTest` runs in `android.yml` with the unit tests, so the contrast rule is a
  build gate as well as a table.

---

## 9. Order of work

1. Tokens (`Color.kt`, `Type.kt`, `Shape.kt`, `Motion.kt`, `Solemn.kt`, `Theme.kt`) + fonts + `ThemeContrastTest`.
2. `tools/check-no-raw-colors.sh` + `design.yml`, and fix `PosterScreen`'s two colours — the guard
   before the cleanup, so the guard is proved by going red first.
3. Components.
4. Splash, window background, launcher icon.
5. The carousel.
6. Restyle pass across all 17 screens, including the edge-to-edge sweep and the widget theme.
7. Screenshot harness → 36 screenshots.
8. Handset verification.
9. `M7_VERIFY.md`.

The floor goes before the features (`MILESTONE_CHECKLIST.md` §B): nothing can be restyled until
the tokens exist, and the guard goes in before the cleanup it guards so that it is seen to fail.

---

## 10. What this plan does NOT do

- **No AI, no API key, no monetisation, no wish/poster templates, no gift catalog.** M8.
- **No server changes.** Nothing in `server/` is touched.
- **No AGP upgrade.** 8.13.2 stays.
- **Nothing in `OPEN_DECISIONS.md` §A or §B is answered.** A3 was answered by Dipen on 2026-11-27;
  this milestone builds it. A1, A2, A4, A5, B2, B4 are untouched.
- **No new storage, no new navigation model, no new permissions.**

One thing is raised rather than decided, because it is a product decision and §A's rule is that a
session asks: **`#C8742A` cannot be the shipped accent.** The shipped value is `#8F531E`, the same
hue at a lower lightness, because 3.39:1 is unreadable. If Dipen prefers the brighter saffron, the
only ways to have it are to stop using the accent for text at all — which removes the identity
from every list in the app — or to accept text below AA. Both are his call; the darker token is
the default taken so the milestone is not blocked, and reversing it is one line in `Color.kt`.
