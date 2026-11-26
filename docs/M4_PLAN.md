# M4 Implementation Plan — Views and the Widget

> Branch: **`feat/m4-views-widget`**, off `0ee4c67`. PR at the end; `main` stays merge-only
> and merging is Dipen's.
> Written 2026-11-26, before any code, per `docs/MILESTONE_CHECKLIST.md` §B.
>
> `BUILD_PROMPT_M4.md` flagged three things to decide before writing code. They are §2, §3
> and §4 below, each with what it gives up. §1 is the state of the world as actually found,
> and it contains **one hard blocker and one thing the requirements and the toolchain
> disagree about** — both need an answer from you before §5 can start.
>
> **Nothing in this document has been built yet.** The only thing that has run is a
> throwaway dependency spike (§2.4), and it has been reverted — `git status` is clean.

---

## 0. What I read first

`BUILD_PROMPT_M4.md` in full. `REQUIREMENTS.md` §7.11 (FR-VIEW-01..10, FR-WID-01..03),
OD-13 and OD-15. `M0_PLAN.md` through `M3_PLAN.md`, for the decisions they locked in — in
particular M0's reason for holding AGP on the 8.x line, which turns out to matter in §2.
`MILESTONE_CHECKLIST.md`, all eight sections, and `M3_VERIFY.md` §6.2, §8, §9 and §10.

On the code side: `App.kt`, `AppStore.kt`, `MainActivity.kt`, `AndroidManifest.xml`,
`PeopleListScreen.kt`, `Model.kt`, `BirthdayMath.kt`, `composeApp/build.gradle.kts`,
`gradle/libs.versions.toml`.

The prompt's "what already exists — do not rebuild it" section is accurate in every
particular. I found nothing in it to correct.

---

## 1. State of the world, as actually found

Checked today against the branch and the machine, not against the milestone table.

| Thing | State |
|---|---|
| `main` | `0ee4c67`, clean. M3 merged at `75c3ea2`, the M3 follow-up at `c7e818b` (PR #5) |
| **Both open M3 findings** | **Closed.** See §1.1 — I checked the code, not the PR title |
| Device | `4B201JEBF03493` — **Pixel 9a**, the same and only phone. USB, authorised |
| Tablet or foldable | **None attached, and no AVD exists at all** (`emulator -list-avds` is empty) |
| Emulator parts | `android-36 google_apis_playstore arm64-v8a` image is present; `pixel_9_pro_fold`, `pixel_fold` and `pixel_tablet` skins are present. So an AVD *can* be built — see the next row |
| **Disk** | **244 MB free of 460 GB.** This is a blocker. §1.2 |
| Non-Pixel phone | Still none. M2's item 7 stays **UNCHECKED** for a third milestone |
| Mobile-data leg | Not retried. The SIM problem in `M2_PLAN.md` §0.2 is unchanged |
| 566-person account | Assumed intact; not yet verified on the device, because I have not installed anything yet |

### 1.1 The two open M3 findings have landed

The prompt said to check and say what I found. I read the code rather than the PR title.

1. **Blocked rows had no reader.** Closed. `AppStore.blockedRecords` now maps
   `local.observeBlockedCount()` into `local.blockedRows()`, `App.kt` collects it and passes
   it into `PeopleListScreen(blockedRecords = …)`, and
   `androidUnitTest/.../BlockedRowsAreSurfacedTest.kt` holds it to a real in-memory SQLite —
   including the case where the banner goes away again after a clean sync.
2. **Import row numbers did not match the user's file.** Closed, in `Csv.kt`, `Xlsx.kt` and
   `ImportPlan.kt` with tests in `CsvTest`, `XlsxTest` and `ImportPlanTest`.

Neither is being built over. Nothing else from M3's review list is open.

### 1.2 BLOCKER — 244 MB of disk

```
/dev/disk3s5   460Gi   417Gi   244Mi   100%   /System/Volumes/Data
```

M1 was blocked at **1.4 GB** (`M1_PLAN.md` §1) and called it a cliff. This is a sixth of
that. What it stops, specifically:

- **Creating the foldable and tablet AVDs.** A fresh AVD writes a userdata image and a
  snapshot; the system image is already on disk but the AVD is not. This is why
  definition-of-done items **3 and 4 cannot start** today.
- **A clean build.** The spike in §2.4 only succeeded because almost everything was already
  in `~/.gradle/caches` and `composeApp/build`. `./gradlew clean` followed by a release or
  instrumented-test build will not fit.
- **`adb exec-out screencap`**, `uiautomator dump` and logcat captures, all of which §4
  depends on, write to disk on both ends.

What is reclaimable, measured, so the ask is concrete rather than "free up some space":

| Path | Size | Note |
|---|---|---|
| `~/Library/Caches` | **6.9 GB** | Normally safe to clear; it is your machine, so I have not touched it |
| `~/.gradle/caches` | 3.9 GB | Clearing it costs a long re-download on the next build. Last resort |
| `~/Library/Android/sdk` | 8.2 GB | Needed. Not a candidate |
| `composeApp/build` + `.gradle` | 133 MB | Trivial, and I will need it again |

**I have deleted nothing.** Freeing ~10 GB would clear this comfortably; roughly 15 GB would
also leave room for two AVDs. Tell me if you would rather I ran a specific clean-up, and
which, and I will — otherwise this is yours.

### 1.3 The dedup rule at scale — raised, as asked

`M3_VERIFY.md` §6.2: 49 of 600 rows were skipped as "appears twice in this import" because
they shared **one** name token and a day/month — `Anika Gupta 21` against `Anika Pillai 22`.
The rule is behaving as FR-IMP-08 specifies; what 600 rows showed is that one shared token
plus a day/month is a weak signal at scale, and there is no way to say "import it anyway".

This is a product decision and it is not mine to take. The two options M3 named were:
require **two** shared tokens when a row has no other corroboration, or add an **"import
anyway"** action per skipped row on the result screen. M4 does not need either to proceed,
and I am **not** proposing to do it inside M4 — it is import, not views. I am raising it
because the prompt says to and because it is still true. **Your call, and it can wait.**

---

## 2. Decision 1 — navigation

**Recommendation: replace the hand-rolled navigator with Navigation 3, scoped to navigation
only, landed first and alone, before any new view is written.**

### 2.1 What is actually wrong today

`AppStore.screen` is a `StateFlow<Screen>` and `backStack` is an `ArrayDeque<Screen>`, both
fields on a Koin `single` that lives on the Application. `App.kt` dispatches on the former
with a `when`. Three properties follow from that, and all three are load-bearing for M4:

- **One `Screen` value cannot say "list and detail at once."** FR-VIEW-06 needs it to.
- **Nothing is saved.** Not across process death, and the Activity-vs-Application lifetime
  mismatch is the exact shape of two of M3's seven findings. `android:configChanges` is
  currently hiding the rotation half of this (§2.5).
- **There is no per-destination history**, so four top-level destinations (FR-VIEW-01..04)
  would share one stack, and backing out of Calendar would land you in whatever you were
  looking at in People.

And FR-WID-02 adds a second deep-link entry point beside M2's `wishkaro://person/<id>`, so
both now need a sensible synthetic stack behind them rather than `backStack.clear()` plus a
hand-placed `Screen.People`.

### 2.2 Why Navigation 3 rather than making the hand-rolled one correct

I took the fallback seriously and wrote it out (§2.6) before choosing. The honest comparison
is this: to make the hand-rolled navigator correct I would have to write a saveable stack
with a `Saver`, a map of per-tab stacks, a `SaveableStateHolder` per stack so a tab's scroll
position and form state survive being switched away from, and a concept that lets more than
one destination be on screen at once. That list **is** Navigation 3's core, hand-rolled by
one person, in the milestone that also ships a widget. Its bugs would be novel ones, in
code nobody has read but me, and they would be in the layer every screen sits on.

The three things that decided it:

1. **The primitives match the four pressures one-for-one.** The back stack is a
   `SnapshotStateList<NavKey>` I mutate directly — `rememberNavBackStack` makes it survive
   configuration change *and* process death. `Scene` strategies exist precisely so a layout
   can read more than one entry off the stack, which is FR-VIEW-06. Per-tab stacks are a
   `Map<NavKey, NavBackStack>` with one `SaveableStateHolder` decorator each, which is the
   documented multiple-back-stacks recipe.
2. **These are not alphas.** `androidx.navigation3:navigation3-runtime` is at **1.2.2
   stable**, `org.jetbrains.androidx.navigation3:navigation3-ui` at **1.2.0 stable**. Both
   publish Kotlin/Native artifacts including `iosArm64`, which this project needs because it
   keeps the iOS target compiling.
3. **I compiled it before recommending it** (§2.4), on both targets, on this machine, on
   this AGP. It is not a paper decision.

### 2.3 What it gives up

- **Three new dependency families sit under every screen in the app.** If a Compose-version
  mismatch appears later, unwinding is not a version bump.
- **Nav3 moves fast.** 1.3 is already in alpha. An upgrade in M6 or M7 is likely to be a
  migration, not a bump.
- **You can no longer read one `when` and know what is on screen.** `NavDisplay` plus a
  scene strategy owns that, and the answer becomes "whatever the strategy decided from the
  window size and the last two entries". That is the feature, and it is also the cost.
- **Every screen's call site changes in one commit.** `store::openPerson` becomes
  `navigator::goTo`. The diff is wide even though each hunk is shallow. I will keep that
  commit to *only* that, so it is reviewable by reading one thing repeatedly.
- **State lives in two places during and after this**: navigation in Compose saved state,
  data in the Koin singleton. That is the intended end state, not an accident, but it needs
  a rule, so: **nothing that drives navigation lives on `AppStore` any more.** `_screen`,
  `backStack`, `go()`, `back()`, the `openX()` family and the navigating half of
  `focusPerson` all leave. `AppStore` keeps data, actions, and one-shot *events*.
- **Material 3 Adaptive's own Nav3 bridge is not available to this project** (§2.4), so the
  two-pane `Scene` is ~80 lines I write from the documented recipe rather than a dependency.

### 2.4 The spike — what was actually compiled, and what it found

Written, compiled, and reverted. `git status` is clean; nothing from it is in the branch.

**It compiles.** `:composeApp:compileDebugKotlinAndroid` *and*
`:composeApp:compileKotlinIosArm64`, both `BUILD SUCCESSFUL`, with a `NavDisplay`, a typed
`entryProvider`, a saveable back stack and a `ListDetailPaneScaffold` driven by
`currentWindowAdaptiveInfo()`.

Two findings came out of it that change the plan, and one of them is for you:

**(a) `adaptive-navigation3` cannot be used here, and FR-VIEW-06 names it.**

`org.jetbrains.compose.material3.adaptive:adaptive-navigation3` — the Material-adaptive
bridge that supplies a ready-made `ListDetailSceneStrategy` for Nav3 — exists only from
**1.3.0**, and 1.3.0 of the whole adaptive line refuses to build here:

```
Dependency 'androidx.compose.material3.adaptive:adaptive-navigation3-android:1.3.0'
requires Android Gradle plugin 9.1.0 or higher.
This build currently uses Android Gradle plugin 8.13.2.
```

It also wants `compileSdk 37`. **M0 held AGP on the 8.x line deliberately** — AGP 9 forbids
the Kotlin Multiplatform plugin and the Android application plugin in the same subproject,
which would split `composeApp` in two (`gradle/libs.versions.toml`, and `M0_PLAN.md`).

FR-VIEW-06 says two-pane list-detail "via Material 3 Adaptive (`OD-15`)". **This is the
requirements conflict the prompt told me to stop and ask about rather than silently pick.**
I am not picking. The options:

| | What it means | Cost |
|---|---|---|
| **A (my recommendation)** | Use Material 3 Adaptive **1.2.0** for `currentWindowAdaptiveInfo()`, `WindowSizeClass`, hinge posture and `ListDetailPaneScaffold` — all of which are Material 3 Adaptive, and all of which compile here — and write the ~80-line Nav3 `Scene` that feeds it myself, from the documented recipe | Inside M4. No toolchain change |
| **B** | Take AGP 9 + compileSdk 37 so the bridge resolves | Reverses M0's decision and splits `composeApp` into two subprojects. A milestone of its own, and not this one |
| **C** | Drop Nav3, use `rememberListDetailPaneScaffoldNavigator` on its own | Two navigators that do not know about each other — the duplication this project has refused three times |

A keeps the letter of FR-VIEW-06 (it *is* Material 3 Adaptive drawing the two panes) and
gives up only the convenience wrapper.

> **DECIDED: option A. Dipen's choice, taken after reading this section rather than finding
> the swap in a diff.** Material 3 Adaptive 1.2.0 directly for `currentWindowAdaptiveInfo()`,
> `WindowSizeClass`, hinge posture and `ListDetailPaneScaffold`; the Navigation 3 `Scene`
> written by hand from the documented recipe. `REQUIREMENTS.md` carries an amendment against
> FR-VIEW-06 saying so with the AGP-8 reason, the same way M3 amended FR-IMP-04 rather than
> leaving a documented thing that is not what was built.

**(b) The saveable back stack needs an explicit serializer module in common code.**

`rememberNavBackStack(...)` has a default `SavedStateConfiguration` on Android and none on
Kotlin/Native, so common code must pass one, with a `SerializersModule` registering every
`NavKey` subclass polymorphically. It compiled once I did. Practical consequence: **adding a
destination means registering it in one place or it silently fails to restore**, which is a
trap, so it gets a test that asserts every `Screen` subclass round-trips. That test is
cheap and it is the kind this project's checklist exists for.

Versions the spike proved, on this machine, on AGP 8.13.2 / compileSdk 36:

```
androidx.navigation3:navigation3-runtime                       1.2.2    (KMP, incl. iosArm64)
org.jetbrains.androidx.navigation3:navigation3-ui              1.2.0    (KMP)
org.jetbrains.compose.material3.adaptive:adaptive              1.2.0
org.jetbrains.compose.material3.adaptive:adaptive-layout       1.2.0
org.jetbrains.compose.material3.adaptive:adaptive-navigation   1.2.0
androidx.glance:glance-appwidget                               1.2.0    (androidMain)
androidx.glance:glance-material3                               1.2.0    (androidMain)
```

### 2.5 Decision 1b — the manifest is currently hiding the bug M4 is meant to prove absent

```xml
android:configChanges="orientation|screenSize|screenLayout|keyboardHidden|smallestScreenSize|uiMode|density"
```

Every one of those means *the Activity is not recreated when that changes*. So today,
rotating or folding the phone does not restart anything — and FR-VIEW-07's "survives fold,
unfold, rotation and resize without losing state" would come out **true for the wrong
reason**, while a process death still loses everything. That is a claim I would have to
write into `M4_VERIFY.md` knowing it was hollow.

**Proposal: drop everything except `keyboardHidden`**, so rotation, fold, resize and
multi-window all recreate the Activity, and then make the state genuinely survive it.

> **DECIDED: do it.** Approved on the grounds set out below — writing a hollow claim into a
> verification document is the one thing this project's checklist exists to prevent. The
> condition attached to the approval was that the picker and the two permission flows under
> rotation are verified rather than assumed, and that anything needing more than a call-site
> fix is reported rather than quietly reverted. **That condition found a real bug**, which is
> written up in `M4_VERIFY.md` §0.6 along with its fix.

**This is the riskiest single line in the plan, and here is the risk, named.** Recreation on
rotate puts new pressure on exactly the seam that produced M3's seventh finding: the
`CompletableDeferred`s in `MainActivity` (`PermissionBridge`, `ContactsPermissionBridge`,
`FilePickerBridge`) are awaited by a coroutine on an Application-scoped store while the
Activity that will complete them can go away. M3 fixed `_busy` at the call site; it did not
remove the shape. Rotating the phone with the file picker open will now exercise it every
time instead of rarely.

That is an argument for doing it, not against — but it means the first thing I verify after
the change is the picker and the two permission flows under rotation, with a test, not a
hope. If it turns out to need more than a call-site fix, I will stop and tell you rather
than quietly putting the `configChanges` back.

`resizeableActivity` is absent, which already defaults to `true` at this `targetSdk`. I will
state it explicitly anyway, because FR-VIEW-09 names it and an implicit default is not a
decision anyone can read. There are no orientation locks anywhere — I checked.

### 2.6 The fallback, written out so it is a real option

If the migration goes badly in the first two commits, I stop and take this instead rather
than pressing on: `NavState(topLevel: Tab, stacks: Map<Tab, List<Screen>>, detail: Screen?)`
as a data class with a hand-written `Saver`, hoisted into `App()` with `rememberSaveable`,
passed down, and `AppStore` keeps none of it. Same scope, same ordering, same tests. The
difference is that the bugs would be mine and new rather than the library's and known.

I would rather not, for the reasons in §2.2, but the decision point is **the end of commit
2** — before any view is built on top — and I will say explicitly which one M4 shipped.

---

## 3. Decision 2 — where the Glance line sits

**The widget renders a list of `UpcomingEntry` and nothing else. It never sees a `Person`,
never touches `LocalStore` directly, and performs no date arithmetic of its own.**

### 3.1 The shape

```
commonMain/domain/Upcoming.kt          ← the ONE implementation of "what is coming up"
    data class UpcomingEntry(personId, displayName, eventType, label,
                             date, nextOccurrence, daysUntil, turningAge, isSolemn)
    enum class UpcomingBucket { TODAY, THIS_WEEK, THIS_MONTH, LATER }
    fun upcomingFor(person, zone, today): List<UpcomingEntry>
    fun bucketFor(entry, today): UpcomingBucket
    fun groupUpcoming(entries, today): List<UpcomingSection>
        — pure, no I/O, every date question delegated to BirthdayMath

commonMain/data/repository/UpcomingRepository.kt
    fun observeUpcoming(): Flow<List<UpcomingEntry>>     ← the list, calendar, timeline
    suspend fun upcomingNow(limit: Int): List<UpcomingEntry>   ← the widget, one shot

androidMain/widget/UpcomingWidget.kt   ← Glance only. Resolves UpcomingRepository from the
                                         Koin global context inside provideGlance, renders.
androidMain/widget/WidgetUpdates.kt    ← updateAll() after a write or sync; the midnight
                                         job, which rides M2's existing WorkManager upkeep
```

The widget runs in the app's own process, started by the broadcast, so
`WishKaroApplication.onCreate` has already built the Koin graph by the time `provideGlance`
runs. It gets `UpcomingRepository`, never `AppStore` — a UI store on the Application is the
thing M3 was bitten by twice, and the widget has no business inside it.

### 3.2 `PersonCard` gets rewritten rather than left beside it

This is the part that makes "one implementation" true rather than aspirational.
`Person.toCard()` **already** computes "what is coming up" — soonest event, days until,
turning age. If I add `Upcoming.kt` and leave `toCard` alone, this project has two
implementations of exactly the thing it has refused to duplicate three times, and the second
one is the old one.

So `toCard` is rewritten to `upcomingFor(this, zone, today).minByOrNull { it.daysUntil }`.
`PersonCard` survives — the People directory is one row per *person* — but it is now a
selection from the shared computation rather than a parallel one. `BirthdayMathTest` and
`CompletenessTest` keep passing unchanged, which is how I will know the rewrite is faithful.

The views and the widget need one row per **event**, not per person (FR-VIEW-01 groups
events; a person with a birthday in March and an anniversary in June appears twice). That is
why `UpcomingEntry` exists at all, and it is the only reason.

### 3.3 What it gives up

The widget re-queries the database instead of reading the app's in-memory `StateFlow` — a
few milliseconds per update, in exchange for the widget working when the app's UI has never
been composed, which is the normal case for a home-screen widget. The alternative is a
widget that is correct only after you have opened the app, which is not a widget.

---

## 4. Decision 3 — how UI gets verified on the device

You asked for a recommendation and a rough cost early. This is it.

### 4.1 The actual gap

Three incidents across M2 and M3: a tap into FloBooks, a typed string into a Slack reply
box, a tap onto a different real person's record with a text field focused. Every guard rail
those bought — and §E now has five — checks the state **before** the tap. Not one of them
can tell where the tap landed. M3's own conclusion was that tapping coordinates read off a
screenshot does not converge, because reading a coordinate off a picture is guessing, and a
guess that is wrong 1 time in 30 is a guess that is wrong every milestone.

M4 is four views, a widget, two-pane layouts and fold handling. More taps, more often, on a
layout that moves when the window changes size. The same method will fail more.

### 4.2 What I recommend — two things, and deliberately not a third

**(A) Stop reading coordinates off screenshots. Resolve them from the view tree. (~2–3 h)**

- One production change: `Modifier.semantics { testTagsAsResourceId = true }` on the app
  root, so every `Modifier.testTag("…")` shows up as `resource-id` in a `uiautomator dump`.
  Everything M4 adds gets a tag as it is written, which costs nothing at the time.
- One script, `tools/device/ui.sh`, with four verbs:
  - `guard` — the existing §E sequence: focused window, fatal count, screenshot.
  - `tap <id|text>` — dump the tree, find the node, **fail loudly on 0 matches or more than
    1**, tap the centre of its bounds. It never guesses and it never taps a coordinate.
  - `expect <id|text>` — dump and assert. **Every `tap` is followed by one.**
  - `type <text>` — only after `expect` has confirmed the focused field by id.
- **`expect` is the half that does not exist today.** A tap that lands in Slack fails the
  step instead of carrying on into it. That is the whole of the three incidents, closed.
- It needs nothing from the build, it works on the launcher and the widget as well as the
  app, and it works the same on the emulated fold.

**(B) Instrumented Compose tests, for exactly the item that already demands them. (~4–6 h)**

Definition-of-done item 5 requires state restoration to have automated tests, so this is not
discretionary spend — it is a listed deliverable. A new `androidInstrumentedTest` source set
with `compose-ui-test-junit4`, covering:

- `StateRestorationTester`: the back stack, per-tab history, a mid-edit form's text, and the
  two-pane ↔ single-pane transition keeping its selected person.
- `createAndroidComposeRule` + `scenario.recreate()`: real Activity recreation, which is the
  only thing that catches what §2.5 exposes — including the file picker and the two
  permission bridges under rotation.

**Honest limit, which goes in `M4_VERIFY.md` rather than being glossed:** these need a
device or emulator, and CI has neither. They are a local gate. CI stays unit-tests-only, and
item 10 will say so in those words rather than implying CI covers item 5.

**(C) What I am not doing.** A page-object UI-automation suite across all four views. That
is the thing that eats a milestone and produces a harness instead of an app, and the prompt
specifically warned against spending M4 on one.

### 4.3 Cost, plainly

Roughly a day in total. Of that, (B) is already a definition-of-done item, so the genuinely
new spend is **(A), at two to three hours** — and (A) is the cheap one, the one with no
production risk, and the one that closes all three incidents. I would do (A) first, before
any M4 UI is driven on the device at all.

### 4.4 What it still cannot prove

`uiautomator` sees the accessibility tree, not pixels. It can tell you the right node was
tapped and the right screen followed; it cannot tell you the layout looked right. Screenshots
still get looked at by eye for every layout claim, per §E. The script makes taps reliable; it
does not make me stop looking.

---

## 5. Implementation order

The floor before the features, per §B of the checklist. Nothing below starts until §2, §3
and §4 have your answer, and §1.2 is a hard stop for everything from step 5 onward.

| # | Commit(s) | Why here |
|---|---|---|
| 0 | `tools/device/ui.sh` + `testTagsAsResourceId` | §4A. Before anything is driven on the device, not after the first incident |
| 1 | Nav3 in, `Screen` → `NavKey`, back stack hoisted out of `AppStore`, deep links rebuilt on it | The floor. Everything else sits on it |
| 2 | State-restoration tests + the manifest change (§2.5) + picker/permission flows under recreation | **The decision point.** If this is bad, §2.6 instead — and I will say so before going further |
| 3 | `Upcoming.kt`, `UpcomingRepository`, `toCard` rewritten onto it, boundary tests | DoD 9. The shared query, before the two things that must share it |
| 4 | FR-VIEW-01 list with Today / This week / This month / Later, against the 566 | DoD 1, 2 |
| 5 | FR-VIEW-02 calendar, FR-VIEW-03 timeline, FR-VIEW-04 People directory | DoD 1, 2, 9 |
| 6 | Adaptive layout: the two-pane `Scene`, rail, bottom nav, dialogs → side sheets | DoD 3 |
| 7 | Fold, rotate, resize, split-screen on the device and the emulated fold | DoD 4, 5 |
| 8 | The Glance widget, three sizes, deep links, midnight + on-write refresh | DoD 6, 7, 8 |
| 9 | `docs/M4_VERIFY.md`, one honest row per item | DoD 10 |

Steps 4 and 5 are each one or more commits; "small and reviewable" beats the table.

### 5.1 One scope note you should see now

FR-VIEW-01's **List** is upcoming *events*; FR-VIEW-04's **People** is the alphabetical
directory. Today's `PeopleListScreen` is the second of those sorted like the first, and it
also carries Settings, Sign out, Deleted, Import and the blocked-records banner.

Splitting it into two destinations means those five have to go somewhere. My intent: they
move to an app-level overflow reachable from every tab, **not** onto one of the four. That
is directly the M3 checklist item — *who can reach it a week after they start using the
app?* — and burying Import inside the People tab would repeat it. If you want them placed
differently, now is the cheap moment to say.

---

## 6. Tests this milestone owes

Beyond the per-step tests above, the things §D will be checked against:

- **Grouping boundaries** across a month end and a year end, in `TZ=UTC` and
  `TZ=Pacific/Chatham`: an event on the 31st seen from the 30th, "this week" crossing into
  the next month, "this month" crossing into January, 29 February via `BirthdayMath`.
- **The calendar's month grid** — leading and trailing blanks, a 6-row month, a leap
  February, week-start.
- **Navigation state** — every `NavKey` subclass round-trips through the serializer module
  (§2.4b), per-tab history, and the back stack surviving recreation.
- **`ModulesTest`** extended for `UpcomingRepository` and anything else the Koin graph gains.
- **Every fix reverted once** to watch its test go red, per §D.

---

## 7. What I need from you before step 1

1. **§2 — Navigation 3, or the fallback in §2.6?** My recommendation is Nav3, compiled and
   proven on both targets on this machine.
2. **§2.4a — Material 3 Adaptive 1.2.0 + my own Scene (A), or something else?** FR-VIEW-06
   names a library version this project's toolchain cannot take. I am not picking silently.
3. **§2.5 — drop the `configChanges` line?** It makes FR-VIEW-07 a real claim, and it will
   put real pressure on the picker and permission seams.
4. **§4 — the verification spend: (A) ~2–3 h plus (B), which item 5 requires anyway?**
5. **§1.2 — the 244 MB.** Items 3, 4 and 6 cannot start until this moves, and clearing space
   on your machine is not something I will do uninvited.
6. **§1.3 — the dedup rule.** Not M4 work. Still yours, and it can wait.
7. **§5.1 — Settings / Sign out / Deleted / Import / the blocked banner at app level?**

Steps 0 through 4 of §5 can begin the moment 1–4 are answered; they need no disk beyond what
is already there. Steps 5 onward need §1.2.
