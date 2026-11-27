# M7 Verification — Identity and first run

> Branch `feat/m7-identity`, off `main` at `e1bfa0c`. **Not merged** at the time of writing.
>
> Three different claims, kept apart throughout, as M5, M6 and M9 did:
>
> - **PROVED** — I ran it and watched the result. The command and its output are here.
> - **READ-VERIFIED** — I read the code, or dumped the artefact, and followed the path. Nothing
>   exercised it end to end.
> - **NOT MET** — not done, or one clause of it not done. Never rounded up to "partial"
>   (`MILESTONE_CHECKLIST.md`, bought by M5 §1).

---

## 0. The honest summary, first

**Nine of the ten definition-of-done items are met. One — item 6 — is NOT MET**, and it is not
met in a specific and small way: the launcher icon is replaced and its `<monochrome>` layer is
present, declared and renders correctly, but *"verified on a launcher in both themed modes"* was
only ever verified in one of them. §6 says exactly what was and was not seen.

Everything else was done on **Dipen's Pixel 9a**, at his request mid-milestone, rather than on an
emulator.

Three things found while verifying rather than while building, each recorded where it happened:

- **The solemn variant was wrong, and item 9's own screenshot is what found it** (§9). A
  composition local reached every colour this app sets by hand and none of the colours Material
  sets for it — so a remembrance screen had a full-width saffron button and a saffron switch on
  it. Fixed by swapping the primary family in the `ColorScheme` rather than providing a local
  beside it.
- **Every scrolling form in this app could put a focused field behind the keyboard** (§7), and
  had been able to since M4 turned on edge-to-edge. `adjustResize` resizes a window; a window
  told not to fit system windows has nothing left to resize.
- **The first icon was rejected** (§6) and the second one is better for the reason the rejection
  gave.

### 0.1 "14 screens" is not a real number

Item 2 asks for screenshots of *"all 14 screens"*. There are not 14. `Screen.kt` declares **17**
destinations and there are **17** screen-level composables; 14 was most likely the count before
M5 added the composer and M6 added the poster studio, and it was carried into `REQUIREMENTS.md`
and then into the build prompt without being recounted.

Shooting 14 would have meant choosing three screens not to show. **24 were shot, in both themes,
for 48 images** — every screen, plus the two empty states nobody has ever seen, plus the
celebratory/solemn pair, plus a type specimen, plus the off-list form case §15 added.

### 0.2 What this milestone did not touch

No AI, no API key, no monetisation, no wish or poster templates, no gift catalog. **No `server/`
change of any kind.** AGP stays on 8.13.2. Nothing in `OPEN_DECISIONS.md` §A or §B is answered —
A3 was answered by Dipen on 2026-11-27 and this milestone builds it; A1, A2, A4, A5, B2 and B4
are untouched.

---

## 1. The ten items

| # | Item | Status |
|---|---|---|
| 1 | `Theme.kt` applies colour, type, shape and motion, and honours `isSystemInDarkTheme()` | **MET** — PROVED |
| 2 | Screenshots of every screen in both themes | **MET** — PROVED, 48 images |
| 3 | Fonts render from the bundle with airplane mode on; licences committed | **MET** — PROVED, byte-identical offline |
| 4 | `FR-AUTH-01`: three screens, skippable, shown once, surviving restart **and process death** | **MET** — PROVED on the handset |
| 5 | Cold start shows no white flash | **MET** — PROVED, frame by frame, both themes |
| 6 | Launcher icon replaced, monochrome layer present, verified on a launcher **in both themed modes** | **NOT MET** — one clause of three unverified. §6 |
| 7 | Zero hardcoded colours outside `ui/theme/`, behind an **unfiltered** workflow | **MET** — PROVED |
| 8 | A contrast table with measured numbers for every pair, both themes | **MET** — PROVED, and it is a build gate |
| 9 | The solemn variant proved to be one token, with the screenshot pair | **MET** — PROVED, after the pair found a bug |
| 10 | The 21 instrumented tests still pass; the unit suites stay green | **MET** — PROVED, 45 instrumented / 408 unit |

---

## 2. Item 1 — the theme

`composeApp/src/commonMain/kotlin/in/wishkaro/app/ui/theme/` was one file of ten lines. It is now
seven:

| File | What it holds |
|---|---|
| `Palette.kt` | every colour as plain ARGB `Long`s, both themes, every Material role by hand |
| `Color.kt` | those numbers as `lightColorScheme` / `darkColorScheme`, and nothing else |
| `Type.kt` | the full `Typography`, Fraunces on display/headline/title, Inter on body/label |
| `Shape.kt` | the `Shapes` set — 12dp cards, 4dp chips |
| `Motion.kt` | named durations and easings, so "settled" is a token |
| `Solemn.kt` | the one scope a remembrance differs by |
| `Contrast.kt` | WCAG 2.x ratio, in plain Kotlin, so the test can gate on it |
| `Theme.kt` | applies all of it, and reads `isSystemInDarkTheme()` |

`isSystemInDarkTheme` appeared **nowhere** in this repository before this milestone. It now
appears once, as the default of `WishKaroTheme`'s `darkTheme` parameter — which is overridable,
which is what let the harness shoot 46 images in one run without touching a device setting.

**Dynamic colour is refused deliberately**, stated in `Color.kt`: on Android 12+ it would repaint
the app from the user's wallpaper, which is the exact opposite of what this milestone is for.

PROVED by the screenshots in §3 and by `./gradlew :composeApp:assembleDebug`, which is green.

---

## 3. Item 2 — every screen, both themes

`docs/screenshots/m7/`, 46 images, half size (540×1212) so a public repository can carry them.

### 3.1 How they were made, and why not with the screenshot button

**They are composed from invented fixtures by an instrumented harness, not captured from a
running account.** `composeApp/src/androidInstrumentedTest/.../DesignScreenshotTest.kt` and
`DesignFixtures.kt`.

That is a decision about the public-repo rule, and this project has paid for the alternative
twice:

- `M5_VERIFY` §0.5 — the one screenshot proving definition-of-done item 1 had to be deleted,
  because a hand-off into a messaging app carried somebody's real conversations and phone
  numbers behind the thing it meant to capture.
- `M9` §9.6 — the account offered for a deletion test turned out to be the owner's own, with six
  people in it, two of which read as real family.

Composing from fixtures makes *"no real person appears in these 46 images"* a property of the
code rather than a claim about how carefully somebody looked at 46 files. The handset's own
account was never rendered.

The names are invented and chosen to exercise what the design has to survive: two tokens, one
token, one long enough to wrap, and one in Devanagari. `docs/fixtures/name-tokens.json` is
deliberately **not** reused — `OPEN_DECISIONS` D5 records that it contains a name nobody has
verified is invented.

### 3.2 The images

| # | Screen | Light | Dark |
|---|---|---|---|
| 01 | Onboarding carousel (`FR-AUTH-01`) | `01-onboarding-light.png` | `01-onboarding-dark.png` |
| 02 | Sign-in | `02-sign-in-light.png` | `02-sign-in-dark.png` |
| 03 | Upcoming (`FR-VIEW-01`) | `03-upcoming-light.png` | `03-upcoming-dark.png` |
| 04 | Upcoming, empty (`FR-VIEW-05`) | `04-upcoming-empty-light.png` | `04-upcoming-empty-dark.png` |
| 05 | Calendar (`FR-VIEW-02`) | `05-calendar-light.png` | `05-calendar-dark.png` |
| 06 | Timeline (`FR-VIEW-03`) | `06-timeline-light.png` | `06-timeline-dark.png` |
| 07 | People (`FR-VIEW-04`) | `07-people-light.png` | `07-people-dark.png` |
| 08 | People, empty | `08-people-empty-light.png` | `08-people-empty-dark.png` |
| 09 | Person — celebratory | `09-person-celebratory-light.png` | `09-person-celebratory-dark.png` |
| 10 | Person — **solemn** | `10-person-solemn-light.png` | `10-person-solemn-dark.png` |
| 11 | Add person | `11-add-person-light.png` | `11-add-person-dark.png` |
| 12 | Edit person | `12-edit-person-light.png` | `12-edit-person-dark.png` |
| 13 | Event editor | `13-event-editor-light.png` | `13-event-editor-dark.png` |
| 14 | Settings | `14-settings-light.png` | `14-settings-dark.png` |
| 15 | Recently removed | `15-deleted-people-light.png` | `15-deleted-people-dark.png` |
| 16 | Import start | `16-import-start-light.png` | `16-import-start-dark.png` |
| 17 | Contacts disclosure (`FR-AUTH-06`) | `17-import-contacts-light.png` | `17-import-contacts-dark.png` |
| 18 | Import a spreadsheet | `18-import-file-light.png` | `18-import-file-dark.png` |
| 19 | Import result (`FR-IMP-07`) | `19-import-result-light.png` | `19-import-result-dark.png` |
| 20 | Wish composer | `20-composer-light.png` | `20-composer-dark.png` |
| 21 | Composer — **solemn** | `21-composer-solemn-light.png` | `21-composer-solemn-dark.png` |
| 22 | Poster studio | `22-poster-studio-light.png` | `22-poster-studio-dark.png` |
| 23 | Type specimen | `23-type-scale-light.png` | `23-type-scale-dark.png` |
| 24 | Edit person — **off-list** relation and gender | `24-edit-person-custom-light.png` | `24-edit-person-custom-dark.png` |

**Two screens in the import flow are shot as the state the user actually meets**, not as the
whole `when`: 17 is `PermissionRationale` (`FR-AUTH-06`'s prominent disclosure, which is a
requirement surface in its own right) and 18 is `ImportFileChooseScreen`. Both were made public
composables for this, and both are store-free. The contacts REVIEW list and the column-mapping
screen are **not shot** — they need a live `ImportStore`, which needs a repository, a contacts
gateway and a file gateway. Stated rather than passed over; they get the tokens like every other
screen and were not photographed.

### 3.3 What the harness asserts

Almost nothing, deliberately. Golden-image comparison is device-specific, and
`MILESTONE_CHECKLIST.md` records M6 shipping assertions pinned to platform values that passed on
the machine that wrote them and failed where the code runs. It asserts that each screen composes
and draws a non-empty bitmap — which caught nothing, but would catch a screen that throws on a
null.

```
$ ANDROID_SERIAL=4B201JEBF03493 tools/device/screenshots.sh
ok    both APKs installed over the existing ones; nothing was uninstalled
ok    running in.wishkaro.app.ui.DesignScreenshotTest
OK (23 tests)
ok    46 screenshots in /Users/.../build/design-shots
```

`tools/device/screenshots.sh` drives `am instrument` directly rather than
`./gradlew connectedDebugAndroidTest`, for the reason `MILESTONE_CHECKLIST.md` records: the
Gradle task **uninstalls the app when it finishes**, which on 2026-11-26 wiped the owner's
handset mid-M4. It would also have deleted the screenshots it had just produced.

---

## 4. Item 3 — fonts from the bundle, offline

### 4.1 What is committed

`composeApp/src/commonMain/composeResources/font/` — six unmodified upstream static TTFs,
~1.57 MB:

| File | Family | Weight | Upstream |
|---|---|---|---|
| `inter_regular.ttf` | Inter | 400 | `rsms/inter` v4.1, `extras/ttf/`, unmodified |
| `inter_medium.ttf` | Inter | 500 | same |
| `inter_semibold.ttf` | Inter | 600 | same |
| `fraunces_regular.ttf` | Fraunces 9pt | 400 | `undercasetype/Fraunces`, `fonts/ttf/`, unmodified |
| `fraunces_semibold.ttf` | Fraunces 9pt | 600 | same |
| `fraunces_bold.ttf` | Fraunces 9pt | 700 | same |

SHA-256 of every file is in `composeResources/files/licenses/FONTS.md`, beside the two OFL
copies. **Both are unmodified**, which matters: both families carry a Reserved Font Name, so a
static instance generated here would be a derivative that could not keep the name. Vendoring the
foundries' own static builds avoids the question rather than reasoning about it.

The licences sit one directory away rather than literally beside the binaries, because Compose
Multiplatform turns **every** file in a `font/` directory into a `FontResource` accessor — a
`.txt` there is either a font the app thinks it has or a generated identifier that will not
compile. They are still inside the resource tree, so they ship **inside the APK**, which is more
than "alongside" would have given. `design.yml` fails the build if a bundled family has no
licence, or if a licence is left behind by a family that has been removed.

### 4.2 PROVED, and by a stronger test than looking at it

The whole screenshot harness was run twice against the **same APK**: once with the network up,
once with airplane mode on, and the outputs compared byte for byte.

```
$ adb shell settings get global airplane_mode_on
0
$ tools/device/screenshots.sh         # baseline
OK (23 tests)

$ adb shell cmd connectivity airplane-mode enable
$ adb shell ping -c 1 -W 3 8.8.8.8
connect: Network is unreachable
$ tools/device/screenshots.sh         # same APK, no network
OK (23 tests)

$ for f in build/design-shots/*.png; do cmp -s "$f" "baseline/$(basename $f)" ...
identical: 46   different: 0
```

**All 46 identical** (the run that proved it; §15 later added two more screens). If a single glyph had come over the network, the offline render would
differ. This is a stronger claim than "the app looked right in airplane mode", which is what the
item asks for and what a screenshot would have given.

### 4.3 Devanagari — the thing that would have been a disaster

Neither Inter nor Fraunces contains Devanagari. `WishLanguage.HINDI.label` is `"हिन्दी"` and
appears as a chip in the composer; a person's name can be in any Indic script in an India-first
app.

Android's `Typeface.Builder` is supposed to append the default fallback family to a custom
typeface so a missing glyph resolves through the system font. "Is supposed to" is not a
verification, so the type specimen (23) sets `ज्योति देशपांडे` in the title face and
`हिन्दी — शुभ जन्मदिन` in the body face, and the upcoming list (03) carries a Devanagari name
with a Devanagari avatar.

**PROVED: they render correctly.** No tofu, no empty boxes, in either theme. Visible in
`23-type-scale-light.png` and `03-upcoming-light.png`.

---

## 5. Items 4 and 5 — first run

### 5.1 `FR-AUTH-01`, on the handset — PROVED

NOT MET since M0; nobody noticed until 2026-11-27. Three screens, **Remember · Wish · Gift**,
before sign-in, skippable.

Every tap below went through `tools/device/ui.sh`, which resolves a node from the accessibility
tree and asserts after the tap as well as before — the guard M4 bought after three blind taps
across M2 and M3 went into the wrong app.

```
=== page 1 ===
ok    present: id:onboarding_heading_remember
      tapping (540,2257) =  resource-id='onboarding_next'
ok    tapped id:onboarding_next
=== page 2 ===
ok    present: id:onboarding_heading_wish
ok    tapped id:onboarding_next
=== page 3 ===
ok    present: id:onboarding_heading_gift
ok    tapped id:onboarding_next
ok    absent: id:onboarding

=== A. restart: force-stop + relaunch ===
ok    absent: id:onboarding

=== B. process death ===
pid before: 5833
pid after kill: ''
pid after relaunch: 5979
ok    absent: id:onboarding

=== C. the flag on disk ===
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="onboarding_seen_v1" value="true" />
</map>
```

**B was re-done because the first attempt was vacuous**, and that is worth recording. `am kill`
on a **foreground** process does nothing — `am start` then reported *"intent has been delivered
to currently running top-most instance"*, so the app had never died and "the carousel did not
come back" meant nothing. Backgrounding it first and checking `pidof` on both sides is what makes
the absence afterwards evidence. (`MILESTONE_CHECKLIST.md`, bought by M9: *"prove the negative
has a baseline before you assert it."*)

The flag lives in a new `AppPreferences`, **not** in `SecureStorage`: `TokenStore.clear()` empties
that whole encrypted file on sign-out and on account deletion, so the carousel would have
returned every time somebody signed out. Written with `commit()` rather than `apply()`, because
the moment it is written is also a plausible moment for the user to put the phone down.

**The copy is held to a test.** `OnboardingCopyTest` fails on "AI", "generated", "personalised"
and ten more, and also fails if the Wish page ever loses the sentence that makes it honest
("WishKaro never sends anything for you"). The Gift page describes the likes and dislikes M1
already stores — not a catalog, which does not exist until M8.

### 5.2 No white flash — PROVED, frame by frame, in both themes

`adb shell screenrecord` of a cold start, frames extracted with `ffmpeg`, and four probes per
frame taken **outside** the splash icon's 192dp circle. A frame counts as white only if it is
**neutral** (max channel − min channel ≤ 3) and ≥ 248 — ivory encodes as (253,249,245) through
the video codec and is 8 units warm, so a naive "close to white" test matches ivory itself. The
first run of this analysis did exactly that and had to be redone.

**Light, 70 frames / 1168 ms:**

| frame | t | the four probes | |
|---|---|---|---|
| 0–5 | 0–83 ms | (158,168,168) … | the launcher, before anything |
| 6–11 | 100–184 ms | rising through (229,229,224) | the cross-fade into the splash |
| **12** | **200 ms** | (254,249,246) ×4 | **ivory, and ivory from here on** |
| 30 | 501 ms | (253,249,245) ×4 | splash still up |
| 45 | 751 ms | (251,250,243) ×4 | the hand-off — no colour change |
| 69 | 1151 ms | app content | |

```
PURE WHITE frames (neutral, >=248): NONE
first all-ivory frame: 12 (200 ms)
```

**Dark, 64 frames:** identical shape — ink from frame 11 (184 ms), `frames anywhere near white:
NONE`, and the brightest probe in the whole recording is frame 0, which is the launcher.

The important line is not "no white" but that **nothing changes at the hand-off**: the ground is
one value from 200 ms to the end. That is `android:windowBackground` = `@color/wk_ground`, which
has a `values-night` twin and is the same resource the splash background uses. The flash was
never the splash; it was the window the splash hands over to.

---
## 6. Item 6 — the launcher icon. **NOT MET**

The item is three clauses. Two are done and the third is not, so the item is NOT MET rather than
partial. (`MILESTONE_CHECKLIST.md`, bought by M9: *"a requirement sentence is a LIST of clauses,
and each one needs a home."*)

| Clause | Status |
|---|---|
| Launcher icon replaced | **done** — PROVED on the launcher |
| Monochrome layer present | **done** — PROVED in the shipped APK and rendered |
| Verified on a launcher **in both themed modes** | **NOT DONE** — verified un-themed only |

### 6.1 Replaced — PROVED

`#FF6750A4` with a white gift box, committed in M0 under *"Placeholder mark. A real icon is a
launch task, not a walking-skeleton task."* Nine milestones later nothing had launched and it was
still there.

It is now a calendar page with one day marked, on ivory, with the marked day in the same
`@color/wk_accent` every countdown in the app is drawn in. Seen on the Pixel 9a's launcher:
`docs/screenshots/m7/icon/06-on-the-launcher.png`.

**The first replacement was rejected**, and the rejection improved it. M7 first drew a diya;
Dipen's objection was *"I want something so by just from icon you can recognise the app and its
usage."* That is the right test, and a lamp fails it — it says warmth and festivity, not **this
app knows when the day is**. The calendar is also not invented for the icon: `CalendarScreen`
has drawn exactly this since M4, a grid of days with a dot on the ones that hold something.

### 6.2 Monochrome present — PROVED, two ways

In the shipped APK:

```
$ aapt2 dump xmltree --file res/mipmap-anydpi-v26/ic_launcher.xml composeApp-debug.apk
  E: adaptive-icon (line=11)
      E: background  A: android:drawable=@0x7f060069
      E: foreground  A: android:drawable=@0x7f080098
      E: monochrome  A: android:drawable=@0x7f080099
```

And rendered on the device by `LauncherIconTest`, which inflates `@mipmap/ic_launcher`, asserts
`AdaptiveIconDrawable.monochrome` is non-null, tints it both ways and writes all three layers
out: `docs/screenshots/m7/icon/`.

That test also guards the failure mode that matters. A monochrome layer is usually wrong not by
being absent but by being the foreground with every fill set to one colour — which for this icon
would be a solid rounded slab, page and header and dots all the same. So it measures the
drawable's coverage of its own box and fails outside 5–45%. The shipped layer draws the page as
a **ring** with `evenOdd` and the header as a bar, so what survives losing the colour is a
calendar with one day larger than the rest.

### 6.3 Why the third clause is not met

Attempted, and what happened is worth writing down because it looks like success:

1. `theme_customization_overlay_packages` was set with `"android.theme.customization.themed_icon":"1"`.
   Nothing changed — Pixel Launcher caches icons.
2. The launcher was force-stopped and the setting re-applied. **Themed mode was then genuinely
   active**: its tiles visibly changed to the themed treatment, and the WishKaro tile's pixels
   differed from the un-themed run by 141,061 channel samples.
3. WishKaro still drew in **full colour** — because Pixel Launcher applies themed icons to the
   **home screen**, not to the app drawer or to search results, which is where the icon was
   being looked at.

Getting it onto the home screen means dragging a tile around **the owner's own home screen** with
`adb shell input swipe`. That is precisely the blind-gesture class of mistake `MILESTONE_CHECKLIST.md`
§E and M4's additions exist to stop — three of those went into the wrong app across M2 and M3,
one of them deleting a person's birthday. Not worth a screenshot.

So: the drawable is right, it is declared, it is in the APK, and it renders correctly when
tinted. **Whether Pixel Launcher chooses to use it on a home screen is READ-VERIFIED, not seen.**

The device's `theme_customization_overlay_packages` was restored to its original value
byte for byte, and the original is saved in the session scratch directory. Confirmed:

```
$ adb shell settings get secure theme_customization_overlay_packages
{"android.theme.customization.color_both":"0","_applied_timestamp":1757853202901,
 "android.theme.customization.color_source":"lock_wallpaper",
 "android.theme.customization.color_index":"1","android.theme.customization.theme_style":"VIBRANT"}
```

**To close this item** somebody with two minutes and their own phone: long-press WishKaro in the
drawer, drop it on a home screen, turn on Settings → Wallpaper & style → Themed icons, and look.

---

## 7. Item 7 — no colour named outside `ui/theme/`

`tools/check-no-raw-colors.sh`, run from `.github/workflows/design.yml` with **no path filter at
all**.

The filter decision is the whole point and the file says so at length. Adding the check as a step
in `android.yml` would have worked **today**, because every Kotlin file it scans happens to live
under `composeApp/`, which that workflow's filter covers. It would stop working the first time a
Compose file lands anywhere else — a `:design` module, a sample — which is the kind of move a
design milestone makes likely. That is M9's lesson (*"a guard that runs on SOME paths is not a
guard"*) applied before it costs anything rather than after.

**PROVED by watching it fail first.** The guard was written before the cleanup it guards, and its
first run printed exactly the three lines in `PosterScreen.kt`:

```
$ ./tools/check-no-raw-colors.sh
no-raw-colors: a colour is named outside .../ui/theme/.
  .../ui/poster/PosterScreen.kt:379:  parseHex(theme.backgroundFrom) ?: Color.Gray,
  .../ui/poster/PosterScreen.kt:380:  parseHex(theme.backgroundTo) ?: Color.DarkGray,
  .../ui/poster/PosterScreen.kt:473:  return Color(0xFF000000L or value)
```

The first two are now `colorScheme` reads. The third carries a reasoned exemption — it is an
alpha mask OR'd onto a `#RRGGBB` that came off the wire from `GET /v1/themes`, a parser rather
than a choice.

It then caught **my own test**: `LauncherIconTest` needs `android.graphics.Color` to tint a
drawable the way a launcher does. Test sources stay **in scope** — a design milestone's own tests
are exactly where a wrong colour gets laundered into evidence, and §3's screenshots come out of
that source set — so it carries the second and last exemption instead.

```
$ ./tools/check-no-raw-colors.sh
no-raw-colors: clean
```

The pattern deliberately does not match `Color.Transparent` or `Color.Unspecified`. Neither is a
colour; flagging them is how a check gets switched off.

`design.yml` also checks that every bundled font family has its licence committed, in both
directions. A font added later without its licence would compile, install, run and look right —
there is no other signal, and this repository is public.

### 7.1 The restyle, and the two things the tokens could not reach

Replacing the theme propagated to **210** colour reads. These did not come free:

- **The solemn accent was missing from the upcoming list.** `UpcomingListScreen` drew its
  countdown in an unconditional `colorScheme.primary`, so a death anniversary five days away was
  the same warm colour as a fifth birthday, on the app's default screen. `PeopleListScreen` had
  the branch; the upcoming list did not, and nothing could have told you which was right.
- **Every scrolling form could put a focused field behind the keyboard.** `adjustResize` resizes
  a window; a window told not to fit system windows has nothing left to resize — so from M4's
  `enableEdgeToEdge()` onward, `imePadding()` was required and absent on `AddPerson`,
  `EditPerson`, `EventEditor`, `PersonDetail`, the composer and the mapping screen. It goes
  **before** `verticalScroll`; after it, it pads the content rather than the viewport.
- **Seven screens are not in a `Scaffold`** — Settings, four import screens, two permission
  screens — and the app's outer `Scaffold` declares zero insets deliberately, so those seven got
  none at all. The Settings back button sat under the status bar on any phone with a cutout.
- **The widget had no theme.** `GlanceTheme()` with no arguments is **dynamic colour** on Android
  12+, so the one piece of this app that lives permanently on a home screen was painted from the
  user's wallpaper.
- **`window.isNavigationBarContrastEnforced`** — `enableEdgeToEdge()` turns it on, laying a grey
  wash over the bottom of our opaque `NavigationBar`. Invisible on gesture navigation, which is
  why it ships.

The `edge-to-edge` skill was used for the insets sweep, as the build prompt asks.

### 7.2 The status bar, checked on the handset because Dipen asked

PROVED in both themes, on the Pixel 9a: **dark icons on ivory, light icons on ink**, legible in
both. `enableEdgeToEdge()` from `ComponentActivity` flips them with the device's dark-mode
setting, and the edge-to-edge guidance says explicitly not to set them by hand when it does — so
nothing in this milestone touches them.

### 7.3 The toolbars, also because Dipen asked

They said **"Back"** and **"Cancel"** in words on seven screens, and two used the literal left
arrow character, which a screen reader announces as its Unicode name. Android has used an arrow
for up and a cross for close since 2014; the glyphs were missing rather than impossible, since
`NavGlyphs.kt` has drawn this app's icons since M4.

`NavGlyphs` gains back, close and overflow. `DetailTopBar` takes a `BackAffordance` — `UP` or
`CLOSE` — rather than a label, so the real distinction the old code was recording in words
(leaving a form discards what you typed; leaving a detail screen does not) survives as a type.
Each carries a content description, because an icon-only button announced as nothing is worse
than the word it replaced.

---

## 8. Item 8 — the contrast table

**Measured, not estimated, and it is a build gate rather than a document.** `ThemeContrastTest`
computes the WCAG 2.x ratio for every pair this app ships, in both themes, from the same
`WishKaroPalette` constants `Color.kt` hands Material — and fails the build below threshold. The
table below is that test's own printed output, so this document cannot drift from the code.

| Class | Minimum | Source |
|---|---|---|
| text on its background | **4.5:1** | WCAG 2.1 §1.4.3 AA, normal text |
| UI component boundary | **3.0:1** | WCAG 2.1 §1.4.11 |
| decorative divider | **1.5:1** | not an AA obligation; recorded so a change is visible |

### 8.1 The direction's stated values do not pass, and that is why the tokens moved

```
stated saffron #C8742A on ivory: 3.39:1
stated solemn  #8A8578 on ivory: 3.56:1
shipped saffron on ivory: 5.93:1
shipped solemn  on ivory: 5.79:1
```

The build prompt guessed `#C8742A` at "roughly 3.5:1" and said not to trust the figure. It is
**3.39:1** — and the half the prompt did not raise is worse: a **white label on a `#C8742A`
button is 3.51:1**, so the accent failed as a fill as well as as text.

The prompt's own rule — *"the accent is for large text, icons, and fills with ink on top, never
for body text"* — was the obvious answer and is the wrong one **for this codebase**. `main`
already drew eight pieces of TEXT in `colorScheme.primary`, and seven are below WCAG's large-text
threshold (18.66sp bold / 24sp regular): the upcoming section header, two countdowns, the shared
section header, the calendar's day heading, the timeline's month heading and the import report's
"Saved." line. Honouring the allowance meant rewriting those seven to stop using the accent,
which removes the identity from every list in the app.

So the token darkened instead, which is what the prompt instructs where a pair fails. `#8F531E`
is **the same hue (28°) and the same saturation (65%)** as `#C8742A` at lightness 0.34 instead of
0.475. The direction is intact; the lightness was not negotiable against 4.5:1.

`#C8742A` and `#8A8578` are kept in `Palette.kt` as documented constants that nothing draws, and
a second test asserts they are still below 4.5:1 — so if that ever stops being true, whoever made
it true is told the reason for the darker token is gone.

### 8.2 Every pair, both themes

### Light — warm paper
| Foreground | Background | Measured | Minimum | Where it is drawn |
|---|---|---|---|---|
| `onSurface` #15131C | `surface` #FDFBF7 | **17.80:1** | 4.5 | every body line, on paper |
| `onBackground` #15131C | `background` #FDFBF7 | **17.80:1** | 4.5 | the same, outside a Surface |
| `onSurface` #15131C | `surfaceContainer` #F5F1E8 | **16.32:1** | 4.5 | a person's name on a list card |
| `onSurface` #15131C | `surfaceContainerLow` #FAF6EF | **17.07:1** | 4.5 | text on a low-elevation card |
| `onSurface` #15131C | `surfaceContainerHigh` #EFEAE0 | **15.34:1** | 4.5 | text on the expanded-width side sheet |
| `onSurface` #15131C | `surfaceContainerHighest` #E9E4D9 | **14.51:1** | 4.5 | text on a menu / the highest container |
| `onSurface` #15131C | `surfaceVariant` #EDE7DA | **14.93:1** | 4.5 | a name on a REMEMBRANCE card |
| `onSurfaceVariant` #55514A | `surface` #FDFBF7 | **7.63:1** | 4.5 | the second line of every list row |
| `onSurfaceVariant` #55514A | `surfaceContainer` #F5F1E8 | **7.00:1** | 4.5 | the same, on a card |
| `onSurfaceVariant` #55514A | `surfaceVariant` #EDE7DA | **6.40:1** | 4.5 | the same, on a remembrance card |
| `onSurfaceVariant` #55514A | `surfaceContainerHighest` #E9E4D9 | **6.22:1** | 4.5 | secondary text at the top layer |
| `primary` #8F531E | `surface` #FDFBF7 | **5.93:1** | 4.5 | section headers, the calendar's day heading |
| `primary` #8F531E | `surfaceContainer` #F5F1E8 | **5.44:1** | 4.5 | the countdown on an upcoming card |
| `primary` #8F531E | `surfaceVariant` #EDE7DA | **4.98:1** | 4.5 | an accent line that lands on a remembrance card |
| `primary` #8F531E | `secondaryContainer` #F0E3CF | **4.85:1** | 4.5 | the countdown on a deep-linked, highlighted card |
| `primary` #8F531E | `primaryContainer` #F7DFC3 | **4.76:1** | 4.5 | accent text inside an accent container |
| `solemnAccent` #67635A | `surface` #FDFBF7 | **5.79:1** | 4.5 | a remembrance countdown on paper |
| `solemnAccent` #67635A | `surfaceContainer` #F5F1E8 | **5.31:1** | 4.5 | a remembrance countdown on a card |
| `solemnAccent` #67635A | `surfaceVariant` #EDE7DA | **4.86:1** | 4.5 | a remembrance countdown on a remembrance card |
| `solemnAccent` #67635A | `secondaryContainer` #F0E3CF | **4.73:1** | 4.5 | a remembrance card reached from a widget tap |
| `onSolemnAccent` #FFFFFF | `solemnAccent` #67635A | **5.98:1** | 4.5 | "Send a message" on a remembrance screen |
| `onSolemnContainer` #2A2820 | `solemnContainer` #E4E1D7 | **11.28:1** | 4.5 | a selected chip inside a solemn scope |
| `solemnAccent` #67635A | `solemnContainer` #E4E1D7 | **4.57:1** | 4.5 | accent text on its own solemn container |
| `onSurface` #15131C | `solemnContainer` #E4E1D7 | **14.06:1** | 4.5 | ink on a solemn container |
| `onPrimary` #FFFFFF | `primary` #8F531E | **6.13:1** | 4.5 | every filled Button: Save, Import, Send a wish |
| `onPrimaryContainer` #40210A | `primaryContainer` #F7DFC3 | **11.32:1** | 4.5 | the calendar's selected day |
| `onSecondary` #FFFFFF | `secondary` #6B5B45 | **6.55:1** | 4.5 | the secondary fill |
| `onSecondaryContainer` #2A1E10 | `secondaryContainer` #F0E3CF | **12.85:1** | 4.5 | the navigation bar's selected item |
| `onTertiary` #FFFFFF | `tertiary` #4F6152 | **6.64:1** | 4.5 | the tertiary fill |
| `onTertiaryContainer` #0D1F12 | `tertiaryContainer` #D5E2D6 | **12.83:1** | 4.5 | the tertiary container |
| `onSurface` #15131C | `primaryContainer` #F7DFC3 | **14.27:1** | 4.5 | ink on an accent fill |
| `onSurface` #15131C | `secondaryContainer` #F0E3CF | **14.53:1** | 4.5 | ink on a highlighted card |
| `error` #A32015 | `surface` #FDFBF7 | **7.32:1** | 4.5 | "Remove <name>", the quiet-hours clash note |
| `error` #A32015 | `surfaceContainer` #F5F1E8 | **6.71:1** | 4.5 | a failed row in the import preview |
| `onError` #FFFFFF | `error` #A32015 | **7.57:1** | 4.5 | a label on the error fill |
| `onErrorContainer` #410500 | `errorContainer` #FBDAD4 | **12.90:1** | 4.5 | the blocked-records banner |
| `inverseOnSurface` #F5F1E8 | `inverseSurface` #302D27 | **12.17:1** | 4.5 | every message the app shows |
| `inversePrimary` #E8B07A | `inverseSurface` #302D27 | **7.14:1** | 4.5 | a snackbar action label |
| `outline` #7A756A | `surface` #FDFBF7 | **4.44:1** | 3.0 | an OutlinedTextField's border; a remembrance dot |
| `outline` #7A756A | `surfaceContainer` #F5F1E8 | **4.07:1** | 3.0 | the same, on a card |
| `outline` #7A756A | `surfaceVariant` #EDE7DA | **3.72:1** | 3.0 | the same, on a remembrance card |
| `primary` #8F531E | `surfaceContainerLow` #FAF6EF | **5.69:1** | 3.0 | the ring on a selected calendar day |
| `outlineVariant` #C0BAAA | `surface` #FDFBF7 | **1.87:1** | 1.5 | the timeline's month dividers |
| `outlineVariant` #C0BAAA | `surfaceContainer` #F5F1E8 | **1.72:1** | 1.5 | a divider on a card |
Measured but not gated:
| Foreground | Background | Measured | Why it is not a gate |
|---|---|---|---|
| `primaryContainer` #F7DFC3 | `surface` #FDFBF7 | 1.25:1 | the calendar's SELECTED day cell against the grid. ~1.2:1 in light, and it stays that way: a pale fill at 3:1 against ivory is not a pale fill, and "warm paper" is pale fills. The state is carried instead by a border drawn in `primary` — a 1.4.11 element, measured above at 5.93:1 — so selection does not depend on this ratio. |
| `secondaryContainer` #F0E3CF | `surface` #FDFBF7 | 1.22:1 | the TODAY cell, and the highlighted card a notification or widget tap lands on. Same reasoning, same border. |
### Dark — ink
| Foreground | Background | Measured | Minimum | Where it is drawn |
|---|---|---|---|---|
| `onSurface` #F0EBE3 | `surface` #15131C | **15.50:1** | 4.5 | every body line, on paper |
| `onBackground` #F0EBE3 | `background` #15131C | **15.50:1** | 4.5 | the same, outside a Surface |
| `onSurface` #F0EBE3 | `surfaceContainer` #201E28 | **13.85:1** | 4.5 | a person's name on a list card |
| `onSurface` #F0EBE3 | `surfaceContainerLow` #1C1A24 | **14.48:1** | 4.5 | text on a low-elevation card |
| `onSurface` #F0EBE3 | `surfaceContainerHigh` #2B2832 | **12.19:1** | 4.5 | text on the expanded-width side sheet |
| `onSurface` #F0EBE3 | `surfaceContainerHighest` #36323D | **10.53:1** | 4.5 | text on a menu / the highest container |
| `onSurface` #F0EBE3 | `surfaceVariant` #302D36 | **11.39:1** | 4.5 | a name on a REMEMBRANCE card |
| `onSurfaceVariant` #CFC7BC | `surface` #15131C | **10.99:1** | 4.5 | the second line of every list row |
| `onSurfaceVariant` #CFC7BC | `surfaceContainer` #201E28 | **9.82:1** | 4.5 | the same, on a card |
| `onSurfaceVariant` #CFC7BC | `surfaceVariant` #302D36 | **8.07:1** | 4.5 | the same, on a remembrance card |
| `onSurfaceVariant` #CFC7BC | `surfaceContainerHighest` #36323D | **7.47:1** | 4.5 | secondary text at the top layer |
| `primary` #E09A52 | `surface` #15131C | **7.81:1** | 4.5 | section headers, the calendar's day heading |
| `primary` #E09A52 | `surfaceContainer` #201E28 | **6.98:1** | 4.5 | the countdown on an upcoming card |
| `primary` #E09A52 | `surfaceVariant` #302D36 | **5.73:1** | 4.5 | an accent line that lands on a remembrance card |
| `primary` #E09A52 | `secondaryContainer` #3F3220 | **5.28:1** | 4.5 | the countdown on a deep-linked, highlighted card |
| `primary` #E09A52 | `primaryContainer` #4E2D0D | **5.23:1** | 4.5 | accent text inside an accent container |
| `solemnAccent` #B9B2A3 | `surface` #15131C | **8.72:1** | 4.5 | a remembrance countdown on paper |
| `solemnAccent` #B9B2A3 | `surfaceContainer` #201E28 | **7.79:1** | 4.5 | a remembrance countdown on a card |
| `solemnAccent` #B9B2A3 | `surfaceVariant` #302D36 | **6.41:1** | 4.5 | a remembrance countdown on a remembrance card |
| `solemnAccent` #B9B2A3 | `secondaryContainer` #3F3220 | **5.90:1** | 4.5 | a remembrance card reached from a widget tap |
| `onSolemnAccent` #2B2823 | `solemnAccent` #B9B2A3 | **6.96:1** | 4.5 | "Send a message" on a remembrance screen |
| `onSolemnContainer` #E6E2D6 | `solemnContainer` #433F37 | **8.09:1** | 4.5 | a selected chip inside a solemn scope |
| `solemnAccent` #B9B2A3 | `solemnContainer` #433F37 | **4.97:1** | 4.5 | accent text on its own solemn container |
| `onSurface` #F0EBE3 | `solemnContainer` #433F37 | **8.83:1** | 4.5 | ink on a solemn container |
| `onPrimary` #452300 | `primary` #E09A52 | **5.97:1** | 4.5 | every filled Button: Save, Import, Send a wish |
| `onPrimaryContainer` #FFDCBC | `primaryContainer` #4E2D0D | **9.51:1** | 4.5 | the calendar's selected day |
| `onSecondary` #3A2D1B | `secondary` #D8C5A8 | **7.94:1** | 4.5 | the secondary fill |
| `onSecondaryContainer` #F0E3CF | `secondaryContainer` #3F3220 | **9.83:1** | 4.5 | the navigation bar's selected item |
| `onTertiary` #213425 | `tertiary` #B5CCB7 | **7.76:1** | 4.5 | the tertiary fill |
| `onTertiaryContainer` #D5E2D6 | `tertiaryContainer` #374B3B | **7.02:1** | 4.5 | the tertiary container |
| `onSurface` #F0EBE3 | `primaryContainer` #4E2D0D | **10.38:1** | 4.5 | ink on an accent fill |
| `onSurface` #F0EBE3 | `secondaryContainer` #3F3220 | **10.48:1** | 4.5 | ink on a highlighted card |
| `error` #FFB4A6 | `surface` #15131C | **10.80:1** | 4.5 | "Remove <name>", the quiet-hours clash note |
| `error` #FFB4A6 | `surfaceContainer` #201E28 | **9.65:1** | 4.5 | a failed row in the import preview |
| `onError` #5F1407 | `error` #FFB4A6 | **7.76:1** | 4.5 | a label on the error fill |
| `onErrorContainer` #FFDAD4 | `errorContainer` #83261B | **7.19:1** | 4.5 | the blocked-records banner |
| `inverseOnSurface` #302D27 | `inverseSurface` #F0EBE3 | **11.56:1** | 4.5 | every message the app shows |
| `inversePrimary` #8F531E | `inverseSurface` #F0EBE3 | **5.17:1** | 4.5 | a snackbar action label |
| `outline` #988F84 | `surface` #15131C | **5.78:1** | 3.0 | an OutlinedTextField's border; a remembrance dot |
| `outline` #988F84 | `surfaceContainer` #201E28 | **5.16:1** | 3.0 | the same, on a card |
| `outline` #988F84 | `surfaceVariant` #302D36 | **4.24:1** | 3.0 | the same, on a remembrance card |
| `primary` #E09A52 | `surfaceContainerLow` #1C1A24 | **7.29:1** | 3.0 | the ring on a selected calendar day |
| `outlineVariant` #4A4651 | `surface` #15131C | **2.00:1** | 1.5 | the timeline's month dividers |
| `outlineVariant` #4A4651 | `surfaceContainer` #201E28 | **1.79:1** | 1.5 | a divider on a card |
Measured but not gated:
| Foreground | Background | Measured | Why it is not a gate |
|---|---|---|---|
| `primaryContainer` #4E2D0D | `surface` #15131C | 1.49:1 | the calendar's SELECTED day cell against the grid. ~1.2:1 in light, and it stays that way: a pale fill at 3:1 against ivory is not a pale fill, and "warm paper" is pale fills. The state is carried instead by a border drawn in `primary` — a 1.4.11 element, measured above at 5.93:1 — so selection does not depend on this ratio. |
| `secondaryContainer` #3F3220 | `surface` #15131C | 1.48:1 | the TODAY cell, and the highlighted card a notification or widget tap lands on. Same reasoning, same border. |

### 8.3 What this does NOT measure, stated rather than implied

- **Alpha.** Every palette value is opaque; Material draws some of its own states at partial
  alpha (disabled content at 38%) and those composites are not covered.
- **Gradients.** The poster preview paints a server-supplied gradient from `PosterTheme`, whose
  colours are the server's.
- **Anything another app draws** — the share sheet, the photo picker, WhatsApp.

---

## 9. Item 9 — the solemn variant, and the bug its own screenshot found

### 9.1 The pair

`docs/screenshots/m7/09-person-celebratory-{light,dark}.png` and
`10-person-solemn-{light,dark}.png`, plus `20-composer` / `21-composer-solemn`.

The two calls that produce them are byte-for-byte the same except for which `Person` goes in, and
the two fixtures differ in `isDeceased` and in one event's `sentiment`. There is **no second
screen, no `if (solemn)` in the layout, and no solemn-only composable.** The difference on screen
is what the scope `PersonDetailScreen` opens with `person.isSolemn` returns.

The WORDS also differ — "Send a message" not "Send a wish", "Death anniversary approaching" not
"Birthday coming up", no age line — and that is **M5's work, not M7's**. `toneFor(person)` picks
one of two string sets. M7 did not touch it; `M5_VERIFY.md` was read first.

### 9.2 The pair was supposed to be evidence. It was a bug report.

The first implementation was a composition local plus a `MaterialTheme.accent` extension, and
`Solemn.kt` carried a comment arguing that a filled button should keep its accent fill because
*"a remembrance screen with no affordances is not quieter, it is broken."*

Then the screenshot was taken. Everything the app colours **by hand** went correctly to stone —
countdown, avatar ring, section headers, back affordance. And the single largest object on the
page, a **full-width saffron "Send a message" button**, did not. Nor did the reminder switch.
`Button` reads `colorScheme.primary` and knew nothing about any of this.

The warmest things on a death anniversary's screen were the two the implementation had exempted,
and the exemption was written in the same sitting as the code with no check in between —
`MILESTONE_CHECKLIST.md`, bought by M6: *"a comment that asserts an invariant the code does not
hold is worse than silence."*

`SolemnScope` now replaces the **primary family in the `ColorScheme`**, so every Material
component that takes its colour from `primary` — `Button`, `Switch`, `FilterChip`'s selected
state, both progress indicators, the text cursor — desaturates without being told and without one
call site passing `colors =`. Four new pairs went into the contrast table with it.

Visible in the pair: the solemn screen's primary button is stone, its switches are stone, and the
celebratory one's are saffron. Nothing else about the two images differs.

---

## 10. Item 10 — the suites

### 10.1 Instrumented, on the Pixel 9a

```
$ adb install -r composeApp-debug.apk && adb install -r composeApp-debug-androidTest.apk
installed both, nothing uninstalled
$ adb shell am instrument -w -r in.wishkaro.app.test/androidx.test.runner.AndroidJUnitRunner
OK (45 tests)
```

**The 21 the item names are the six pre-M7 classes, and all 21 pass:**

| Class | Tests |
|---|---|
| `PosterSharingTest` | 5 |
| `ActivityBridgesTest` | 4 |
| `PosterPickerFilterTest` | 3 |
| `NavigationRestorationTest` | 3 |
| `ActivityRecreationTest` | 3 |
| `TwoPaneHingeTest` | 3 |
| **pre-M7 total** | **21** |
| `DesignScreenshotTest` (new) | 23 |
| `LauncherIconTest` (new) | 1 |
| **total** | **45** |

Run through `am instrument`, never `./gradlew connectedDebugAndroidTest`, which uninstalls the
app and would have taken the handset's account with it.

`D3` is unchanged: **these still do not run in CI**, which has no device.

### 10.2 Unit, and the iOS target

```
$ ./gradlew :composeApp:testDebugUnitTest --rerun-tasks
BUILD SUCCESSFUL
unit tests: 408 failures: 0

$ ./gradlew :composeApp:compileKotlinIosSimulatorArm64
BUILD SUCCESSFUL
```

408 includes the two suites M7 added — `ThemeContrastTest` (2) and `OnboardingCopyTest` (4).
The iOS target still compiles, including the new `AppPreferences` actual.

---

## 11. The component set, and what actually adopted it

Item 8 of the build prompt's scope asks for a real component set *derived from the screens*.
`docs/M7_PLAN.md` §4 has the inventory with the count of hand-written copies each one replaces.
What is honest to say is **which screens adopted which**, rather than claiming a clean sweep:

| Component | Replaces | Adopted by |
|---|---|---|
| `WishKaroCard` | 7 hand-written `Card(colors = when {…})` blocks | Upcoming, People, Person detail, Deleted, Settings cards, Import start |
| `PersonAvatar` | did not exist | Upcoming, People, Person detail, Deleted |
| `DateBadge` | dates glued into `buildString` at `bodyMedium` | Upcoming, Timeline, Person detail's event rows |
| `SectionHeader` | **two** different ones, which did not agree | all 7 previous callers, plus Upcoming and Calendar |
| `EmptyState` | 11 differently-shaped "nothing here" blocks | Upcoming ×1, People ×2, Calendar ×1, Deleted, Composer |
| `LoadingState` | 5 centred spinners | Upcoming, Person detail, Composer, Import contacts |
| `ActionPair` | 6 primary+secondary pairs | Upcoming empty, People empty, Person detail |
| `Note` / `WarningNote` | ~14 `bodySmall` lines, a third with no colour set | Settings, Import ×8, Poster, Composer, Add/Edit/Event |
| `ToggleRow` | 3 switch rows, 3 layouts | Settings, Edit person, Person detail's event rows |
| `DetailTopBar` | 7 top bars, 3 different back affordances | all 7 |

**Not adopted**, and named rather than implied: the import flow's contacts review and
column-mapping screens keep their own layout. They got the tokens, the insets fix and `Note`, but
their list rows are not `WishKaroCard` and their headers are not `SectionHeader`. The composer's
chip rows and the poster studio's swatch grid are likewise unchanged in structure.

---

## 12. What Dipen changed mid-milestone

Three interventions, all of which improved the result:

1. **"we have pixel 9a connected, can you test in that"** — the plan had the harness running on
   an emulator. Everything in §§3–6 and §10 was done on the handset instead. The only thing the
   handset could not give was a PII-free data set, which the fixtures solve anyway.
2. **"in android we dont have back writtern, we have either back button"** — §7.3. Inherited from
   M0–M6 and reproduced by M7 without questioning it.
3. **"i dont like the icon… can you make more wishkaro type?"**, then **"can we have something
   like calendar… so by just from icon you can recognize the app and its usage"** — §6.1. The
   second version is better and the test he gave for it is the right one.

---

## 13. What M7 could not close, and what it adds to the list

| # | Item | Where it stands |
|---|---|---|
| **1** | **Item 6's third clause** — the themed-icon rendering is read-verified, not seen | §6.3. Two minutes on a home screen closes it |
| **2** | Two import screens are not photographed and not adopted into the component set | §3.2, §11. They need a live `ImportStore` |
| **3** | `D3` — instrumented tests still do not run in CI | unchanged since M4; CI has no device |
| **4** | `D1` — the OEM battery helper is still unexercised on a non-Pixel device | **ninth** milestone carrying it. M7 did not touch it and could not |
| **5** | `D2` — the mobile-data leg of the Railway deploy is still unproven | unchanged |

### 13.1 For `OPEN_DECISIONS.md`

`A3` / **TBD-5 is now built**, not just answered, and can be struck. The one thing in it that
moved is recorded here and in `Palette.kt`: **the shipped accent is `#8F531E`, not the stated
`#C8742A`**, same hue and saturation at a lower lightness, because the stated value is 3.39:1 on
ivory and fails as a fill as well as as text. Reversing it is one line, and §8.1 says what it
would cost.

Nothing else in §A or §B was touched.

---

## 14. The public-repo check, run before pushing

```
$ ./tools/check-no-pii.sh
no-pii: clean
$ ./tools/check-no-raw-colors.sh
no-raw-colors: clean
```

**Before**, not after. A red check after a push means the content is already published, and git
history is permanent.

Every person in the 46 screenshots is invented, by construction rather than by inspection (§3.1).
The one email address that appears on screen — in the Settings delete-account dialog — is
`you@example.invalid`, which RFC 2606 reserves for exactly this and which `check-no-pii.sh`
deliberately does not match. No screenshot was taken of the handset's own account, and the only
handset screenshots in this document's evidence are the launcher tile cropped to WishKaro alone
and the carousel, which has no data in it at all.

---

## 15. After the definition of done — Dipen's form pass

Everything above was complete and the PR was open when Dipen asked for a second round:

> *"in form we can have some dropdown like gender, for phone we can have +91 and for relation
> we can have dropdown as well with other as last option and we can have the textinput. Also we
> are not taking photopicker for photo. And check more improvement in all the flows if we can
> make."*

This section is that work. It is on the same branch because these screens are the ones M7 had
just restyled — a second PR touching the same files would conflict with the first before
either merged — and because every screenshot above would otherwise have been stale on arrival.

### 15.1 Two things the audit found before any code was written

Both changed what got built, so they are first.

**Gender is deliberately not an enum, and that decision stands.** `schema.prisma`, on the
column:

> *Free text, deliberately not an enum: an enum of genders is a product decision this app has
> no reason to make, and gift logic in M8 reads traits rather than this field.*

So the stored value is still a string, "Other" opens a free-text box, and a value written
before this existed still loads into it. **Only the input changed.** A dropdown that closed the
set would have overturned a recorded decision to save a few taps.

**Nothing in this product reads a person's gender.** Checked rather than assumed: it is in no
wish template, no poster prompt and no model call. The only thing that touches it is
`derived.ts`, where filling it in is worth **5 points** of the completeness score. Worth
knowing, because it is the difference between a data-entry nicety and a feature — and it is the
former.

### 15.2 The photo was already built, except for the part the user could reach

**No server change was needed and none was made.** Every piece has existed for milestones:

| Piece | Since |
|---|---|
| `person.photo_key` in `schema.prisma` | **M1**, under *"the column exists now so the feature is a code change later rather than a migration"* |
| `POST` / `GET` / `DELETE /v1/people/:id/photo` | M6 — and `WishKaroApi.uploadPhoto`'s own comment names **FR-PPL-04** first, FR-POS-01 second |
| the system photo picker, needing no permission | M6, in `MainActivity` |
| the retention sweep clearing `photo_key` | M9 |

A photo could be set only by opening the **poster studio**, and seen only on a **poster**. M7
put it on the person's own screen, where `FR-PPL-04` always had it, with
`ui/person/PersonPhotoStore.kt` and an optional `photo` on `PersonAvatar` — initials stay the
fallback.

**PROVED on the handset**, without browsing anybody's gallery:

```
focus before:  in.wishkaro.app/in.wishkaro.app.MainActivity
ok    tapped id:person_pick_photo
focus after:   com.google.android.photopicker/com.android.photopicker.MainActivity
--- backing out without choosing anything ---
focus:         in.wishkaro.app/in.wishkaro.app.MainActivity
FATAL EXCEPTION count: 0
clickable="true"  enabled="true"
```

That is the control, the system picker, and the **cancel** path — which matters, because
cancelling is the branch that has to clear `uploading` and did not in an early draft. The
upload itself goes through the identical `files.pickPhoto()` + `api.uploadPhoto()` call the
poster studio has used since M6; it is **READ-VERIFIED here**, not re-proved, and no photo was
uploaded to the owner's account.

### 15.3 Phone: a country code, not a hardcoded `+91`

The obvious reading of *"for phone we can have +91"* is a fixed prefix. That would have **taken
away a working case**: `whatsAppNumber()` already handles `+44 7700 900123` and is tested on
it, and this is an app for remembering the birthdays of families, which in this country are
spread across the Gulf, the UK, the US and Singapore. So `+91` is the default and the first
entry; the rest of the list is where Indian families are.

`PhoneNumbers.split` applies **exactly the rules `whatsAppNumber()` applies**, so the country
the field shows is the country WhatsApp would open — where those two disagreed, the field would
say one thing and the chat would be another, which is the worst possible version of this.
`PhoneNumberTest` is written against the same shapes `WhatsAppNumberTest` uses, and its last
case round-trips every one of them and asserts **which chat would open** rather than what the
string looks like.

Two cases worth naming:

- **Longest code first.** `+977` is Nepal; matched shortest-first it reads as `+91` and a wrong
  national part. Tested.
- **An empty number stores NULL, not `"+91"`.** Otherwise every person edited after this change
  acquires a phone consisting of a country code, the completeness score counts it as filled, and
  `whatsAppNumber("+91")` returns `"91"` and opens a chat with nobody. Tested.

### 15.4 Relation and gender: one component, after the first version got it wrong twice

Both are the same problem — a curated list that must not become a closed one — so they are one
component, `DropdownWithOther`. They were not, at first, and the screenshot showed what that
cost:

1. **Two fields labelled "Relation"** stacked on each other, and two labelled "Gender", because
   each screen named its text box after the thing rather than after what it is for. Now
   "What do you call them?" and "How do they describe it?".
2. **The free-text box appeared on a fresh form** with nothing chosen — two controls for one
   value, which is the exact thing replacing the chip row was meant to stop. It now appears only
   when "Other" is the answer.
3. **`+44  United Kingdom` rendered as `+44  Unite`** in a 110dp field. The menu shows the
   country; the closed field shows the code.

All three were found by looking at `24-edit-person-custom-light.png`, which exists because the
case it covers is the one these dropdowns must not break: **"Chachi" is a real relation**, and
`FR-PPL-02` says so in as many words — *"an enum of relations would make 'Mama' or 'Chachi'
unrepresentable in an India-first app."* That person opens with the dropdown on **Other**, the
word still in the box, and a `+44` number intact.

### 15.5 The month is a dropdown, which is the biggest of these

A birthday was three numeric boxes side by side, so entering one meant deciding unprompted
whether `06` in the middle was June. `ImportDates` has a whole date-order toggle and a
paragraph explaining that WishKaro *"never guesses this from the file"*, because exactly this
ambiguity costs real data — and the manual form had the same ambiguity with none of the
explanation. A month picked by name cannot be ambiguous.

`DateFieldsState` is untouched: strings, a half-typed year still legal, and 29 February with no
year still valid.

### 15.6 The rest of the flows

| Change | Why |
|---|---|
| Name fields capitalise **words** | `Words`, not `Sentences`. The difference is "meera kulkarni" on every screen that person ever appears on, and many Indic keyboard layouts have autocapitalisation off |
| Phone / email / number keyboards | the fields had no `keyboardType` at all, so a phone number was typed on a QWERTY |
| `imeAction` across every form | Next through the fields, Done on the last |
| Trait entry submits from the keyboard | the one field a user fills in repeatedly — three hobbies is now three taps of the action key |
| People search: `ImeAction.Search`, and a **clear button** | with 575 people a stale query is the difference between an empty list and an app that looks broken. M4 also found that swiping to scroll with this field focused types into it |
| The redundant "Relation" section header is gone | the dropdown's own label says Relation |

### 15.7 Suites after this round

```
$ ./gradlew :composeApp:testDebugUnitTest --rerun-tasks
unit tests: 418 failures: 0

$ adb shell am instrument -w -r in.wishkaro.app.test/...
OK (46 tests)

$ ./gradlew :composeApp:compileKotlinIosSimulatorArm64
BUILD SUCCESSFUL
```

418 unit (up from 408: `PhoneNumberTest` adds 10) and 46 instrumented (up from 45: the off-list
screenshot). **All 21 pre-M7 instrumented tests still pass.** Both guards clean before the push.

### 15.8 What this round did NOT do

- **No server change**, and none was needed — §15.2.
- **No schema change**, client or server. `gender` is still free text, `phone` is still one
  string, `photo_key` already existed.
- **No new permission.** The system photo picker needs none, which is why M6 chose it over
  `READ_MEDIA_IMAGES`.
- **The contacts-review and column-mapping screens are still not adopted** into the component
  set — unchanged from §11, and still named rather than implied.
