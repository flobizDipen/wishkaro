# M4 Verification — Views and the Widget

> Branch `feat/m4-views-widget`, off `0ee4c67`. Written against what was actually observed,
> in the shape `M3_VERIFY.md` §9 uses.
>
> Device: **Pixel 9a** (`4B201JEBF03493`), Android 17, 1080×2424 @ 420dpi.
> Account: the live Railway account with the **566 invented people** M3 left behind
> (`M3_VERIFY.md` §8) — **575 local records, 574 events** after the sign-in described in §0.2.
> No tablet or foldable hardware exists here; §0.3 says exactly what stood in for it.
>
> Where something was not proved, the row says **UNCHECKED** or **PARTIAL** and why. Nothing
> is rounded up.

---

## 0. Incidents

### 0.1 `connectedAndroidTest` uninstalled the app and wiped the device's data

`./gradlew :composeApp:connectedDebugAndroidTest` **uninstalls the app under test when it
finishes**. Not `pm clear` — a full uninstall. The local SQLite database went, the session
tokens in EncryptedSharedPreferences went, and the app came up on the sign-in screen.

Noticed because a screenshot showed "Continue with Google" when it should not have.
Confirmed twice: `adb shell pm list packages | grep wishkaro` returns nothing immediately
after a green test run, and `firstInstallTime` equalled `lastUpdateTime` on the next install.

**No records were lost** — they are on the server, and signing in pulled all 575 back. But
the verification approach this milestone adopted destroys the test account's local state
every time it runs. `tools/device/instrumented-tests.sh` is the fix: it installs both APKs
with `-r`, drives `am instrument` directly, and asserts afterwards that the app is still
installed. The Gradle task stays correct on an emulator.

### 0.2 Signing back in

Done by the owner's instruction, by tapping "Continue with Google" and confirming the
account already on the device. No password was typed. The notification permission dialog
follows that flow and was granted, restoring the state the device had before §0.1.

The pull restored **575 people and 574 events**, which is the offline-first architecture
doing the thing it exists for.

### 0.3 Three measurement mistakes, and what they cost

Recorded because each one produced a confident wrong answer, and two of them sent real work
down a blind alley. All six are now in `MILESTONE_CHECKLIST.md`.

1. **`am force-stop` blocks a package's PendingIntents.** Every widget tap was tested after
   a force-stop, so working actions appeared completely dead. The widget's click handling was
   rewritten four times — root versus child element, reified versus Intent action, implicit
   versus explicit component — to fix something that was never broken. **This was the single
   largest waste of effort in the milestone.**
2. **`dumpsys window | grep -oE "pkgA|pkgB"`** returns whichever string appears first
   anywhere in the dump, not the focused window. It reported the launcher while the app was
   plainly in the foreground.
3. **Swiping to scroll while a text field has focus** gesture-types into it. A scroll turned
   a person's name into `Anika Pillai 25-WIDGETTEST hu by by`. Cancelled without saving;
   the database was checked afterwards — 575 people, name back to `Anika Pillai 25`, zero
   rows matching `WIDGETTEST`.

### 0.4 A date was deleted and re-entered by hand

`tools/device/ui.sh tap "text:Remove"` resolved to **exactly one** node, so the script was
satisfied — and it was the *event's* Remove, not the person's. It deleted Aadhya Bhatt 9's
birthday. The person-level control reads "Remove Aadhya Bhatt 9"; the selector was mine and
it was wrong.

Re-entered from a screenshot taken minutes earlier that happened to show the values: Birthday
2 Jan 1974. The record reads "Birthday 2 Jan · 1974, in 37 days" again, matching the earlier
screenshot exactly. The original event remains as a tombstone; a new event id now holds the
date.

**`tap` prints the node it resolved.** It had been piped to `/dev/null`. A unique match is
not the same as the right match.

### 0.5 The disk ran out mid-milestone

244 MB free of 460 GB when M4 started — a sixth of the 1.4 GB M1 called a cliff. The
compiler failed with `No space left on device` partway through the navigation migration.
Cleared with the owner's explicit approval (`brew cleanup`, Chrome and Arc caches); nothing
of the owner's own was deleted.

---

## 1. The honest summary

| # | Definition of done | State |
|---|---|---|
| 1 | All four views work on a real phone against the 566-person account, and the list is usable at that size — scrolling, searching, filtering without visible jank | **Pass.** All four looked at on the device against 575 records (§2). Jank measured with `gfxinfo` over six hard flings: **4.35% janky frames, p50 9ms, p99 42ms, 0 missed vsyncs** — after the fix in §2.1. Search narrowed 575 to one |
| 2 | Every view's empty state leads somewhere | **PARTIAL.** The calendar's two empty states were seen on the device ("Tap a day with a dot…", and the per-day "Nothing on this day"). The Upcoming, People and Timeline empty states were **not** seen, because the only account available has 575 people in it and emptying it is not a tidy-up (§4.2) |
| 3 | Two-pane list-detail on a tablet or large foldable, with the rail, and dialogs as side sheets | **Pass, on a phone-shaped window.** Verified at 923dp (the Pixel 9a is genuinely expanded in landscape) and again at **1484×928dp** by resizing the running app to tablet geometry with `wm size`. **Not on tablet hardware**: the tablet emulator has no Google account and reaches only the sign-in screen (§4.1) |
| 4 | Fold, unfold, rotate, and split-screen on a real device: no restart, no lost state, including mid-edit state in a form | **PARTIAL.** Rotation and process death: **pass**, including a half-typed form (§3.1). Fold/unfold: the app survives it on an emulated Pixel Fold with 0 fatal exceptions and the window crossing 411dp↔841dp, but **with no session there is no state to lose**, so the "no lost state" half is unproven on a fold. Split-screen: the app launches and runs in `mWindowingMode=multi`, but a genuine side-by-side resize against a second app was **not driven** |
| 5 | State restoration has automated tests, not only a device walkthrough | **Pass.** 9 instrumented tests — `StateRestorationTester` for saved state, `ActivityScenario.recreate()` for a real onDestroy/onCreate, and three for the hinge arithmetic. Confirmed by reverting: making the selected tab a plain `remember` turns two red; deleting the hinge gap turns three red. **They do not run in CI** (§5) |
| 6 | The widget in all three sizes on a real home screen, showing real data | **PARTIAL.** On the owner's actual home screen with the real account throughout. **Two of the four declared sizes were observed** — the 4×2 default and the taller bucket, which shows three entries and "+5 more" where the default shows two. The 2×2 and the tablet-sized bucket were **not placed** |
| 7 | Tapping a widget entry opens that person; tapping the header opens the app | **Pass.** An entry opened Aadhya Menon 10, and a second opened Anika Pillai 25; the header brought the app forward from the launcher. Both go down M2's `wishkaro://` route |
| 8 | The widget updates after a data change, and after midnight | **PARTIAL.** Data change: **pass** — toggling a reminder produces `androidx.glance.session.SessionWorker` and `AppWidgetServiceImpl: Trying to notify widget update for package in.wishkaro.app`. Midnight: **scheduled, not observed.** `#MidnightWidgetWorker#` is in the system scheduler with `Run time: earliest=+7h32m` from 16:27 local — 23:59:51. It cannot fire before this is reviewed |
| 9 | Unit tests for the grouping boundaries, the calendar's month grid, and the navigation state | **Pass.** 19 on the grouping (every boundary, a month end, a year end, 29 February both ways), 12 on the month grid, 10 on the navigator plus a registration test that asks the sealed hierarchy what it contains. **296 tests** pass in `TZ=UTC` and `TZ=Pacific/Chatham` |
| 10 | CI green, and this document | CI state is on the PR; this document exists |

**Six of ten pass outright. Four are partial, and the reason in every case is hardware or a
clock, not a defect** — no tablet, no foldable with a session, no second app driven into
split-screen, and a midnight that has not arrived.

---

## 2. The four views, on the device

### 2.1 FR-VIEW-01, the list — and the bug only 574 events could show

Grouped Today / This week / This month / Later, with countdowns. On the device: **Today (2)**
— Aadhya Menon 10 and Anika Pillai 25, both "Birthday · 26 Nov" — and **This week (9)**
beginning with three "Tomorrow" entries.

The groups are **rolling windows** (0, 1–7, 8–30, 31+), not calendar ones. Read as calendar
months, a birthday four days away falls under "Later" whenever those four days cross a month
boundary, so on 28 December every birthday in the first week of January would sit at the
bottom under a heading meaning "not urgent". `UpcomingTest` pins that across a month end and
a year end.

**The bug:** `collectAsState` collects on the composition's context, so the next-occurrence
arithmetic for all 574 events and the sort over them ran **on the main thread**, on every
emission, while the user was scrolling. One `flowOn(Dispatchers.Default)`:

| | before | after |
|---|---|---|
| Janky frames | 10.00% | 4.35% |
| 50th / 99th percentile | 11ms / 77ms | 9ms / 42ms |
| Missed vsyncs | 2 | 0 |

Invisible with twenty people. This is what the prompt meant by using the 566.

### 2.2 FR-VIEW-02, the calendar — and the bug only a real month could show

A month grid with a dot on every day that holds something, larger for more than one; today
circled, the selected day filled. Tapping 27 Nov listed Arjun Bose 7, Diya Desai 3 and
Shaurya Trivedi 1.

The first version matched events on their **next occurrence**, so somebody born on the 10th
vanished from a calendar opened on the 26th — their next birthday is in the same month a
year away, and the calendar only reaches eleven months forward. **Twenty-five of November's
thirty days were blank.** It matches on the event's own month now and places it through
`BirthdayMath.occursOnIn`, so 29 February still lands on the 28th in a non-leap year. Three
tests, all red when reverted.

Week starts **Sunday**, as a constant rather than a guess at the system locale — this is an
India-first app and a grid that reshuffles itself when the user travels is worse than one
that is consistently the local convention.

### 2.3 FR-VIEW-03, the timeline

Twelve months from this one, every month shown including empty ones — that is the point of a
year view, and skipping them would make it the upcoming list with different headings. On the
device: November 2026 (7), December (41), with a date rail and countdowns down the left.

### 2.4 FR-VIEW-04, the People directory

**Alphabetical now**, not sorted by imminence: a directory ordered by whose birthday is
soonest cannot be scanned for a name. On the device: Aadhya Bhatt 9, Aadhya Bose 23, Aadhya
Chauhan 10, Aadhya Chauhan 16, Aadhya Chauhan 5, Aadhya Desai 31 — each with a **25%**
completeness badge, which is the server's score mirrored and shown only below 80%.

---

## 3. Rotation, resize and recreation

### 3.1 What the manifest was hiding

The Activity declared `configChanges` for orientation, screenSize, screenLayout,
smallestScreenSize, uiMode and density. Every one of those means *do not recreate me*. So
FR-VIEW-07's "survives rotation without losing state" would have come out **true because
nothing was ever lost**, while a process death still lost everything.

They are gone except `keyboardHidden`. That immediately found what it was meant to find:
**not one form in this app used `rememberSaveable`** — Add Person, Edit Person, the event
editor, the trait drafts, the import date dialog, the bulk-relation dialog. All invisible
while the Activity was never rebuilt.

Proved on the device, in this order:

1. Typed "Rotation Test Person" into Add Person.
2. Rotated to landscape — text survived, window id changed (`b24394b` → `d53ae6a`), so the
   Activity really was rebuilt.
3. HOME, then `adb shell am kill in.wishkaro.app` — **pid 26456 gone**.
4. Relaunched — **pid 27137** — and the app came back **on Add Person with the text still in
   the field**.

The old arrangement could not have done step 4 at all.

### 3.2 Tablet geometry, live

`wm size 2560x1600` + `wm density 276` on the **running** app: 1484×928dp, rail on the left,
People list and detail side by side, no restart, the open person still open. Reset
afterwards (`wm size reset`, `wm density reset`, confirmed back to 1080×2424 @ 420).

---

## 4. What could not be proved, and why

### 4.1 No tablet, and a foldable with no session

No tablet or foldable hardware exists here. `avdmanager` does not work on this machine — the
legacy one needs JAXB, which Java 11 removed, and under Java 8 it fails with a bare `null` —
so both AVDs were written by hand from the existing phone AVD's `config.ini`.

The **tablet** AVD boots. It has no Google account, so the app reaches only the sign-in
screen, and signing in there is not something to do on the owner's behalf. Item 3 was proved
by resizing the real device instead (§3.2), and this row says so rather than implying tablet
hardware.

The **foldable** AVD works as a foldable: the emulator accepts the hinge configuration
("Configuring second built-in display with width 1080 and height 2092 for pixel_fold
device") and reports CLOSED, HALF_OPENED, OPENED and REAR_DISPLAY_MODE. Folding and
unfolding with the app installed takes the window between **411dp and 841dp** — across the
breakpoint that swaps the bottom bar for the rail — with **0 fatal exceptions**. But with no
session there is nothing on screen except sign-in, so *"no lost state across a fold"* is
**unproven**.

The hinge *arithmetic* is proven instead, by three instrumented tests: given a hinge at a
known place the list pane ends at it, the detail pane starts past it, and nothing is laid out
across the crease. **The hinge and a real two-pane screen have never been seen in one
picture.**

### 4.2 Empty states

Three of the four views' empty states were not seen on the device, because the only account
available has 575 people in it. Emptying it would be a deliberate act (`M3_VERIFY.md` §8),
not a tidy-up, and it is the only honest test of item 1.

### 4.3 Still carried forward, now for a third and fourth milestone

- **The OEM battery helper has never run on a non-Pixel device.** Unchecked across M2, M3 and
  now M4. The only phone here is a Pixel.
- **The mobile-data leg of the Railway deploy is still unproven.** The SIM problem in
  `M2_PLAN.md` §0.2 is unchanged and was not retried.
- **The name-token table still never runs against Kotlin/Native.** CI compiles the iOS target
  and runs no tests on it. M4 did not change this.
- **The dedup rule's precision at scale** (`M3_VERIFY.md` §6.2) is still open. Raised in
  `M4_PLAN.md` §1.3 as a product decision; not M4 work and not taken.

---

## 5. Tests

| Suite | Count | Where it runs |
|---|---|---|
| Unit (`commonTest` + `androidUnitTest`) | **296** | CI and locally, in `TZ=UTC` and `TZ=Pacific/Chatham` |
| Instrumented (`androidInstrumentedTest`) | **9** | **Device only.** CI has no emulator |

New in M4: 19 grouping-boundary tests, 12 month-grid tests, 10 navigator tests, a serializer
registration test that enumerates the sealed hierarchy, 6 repository tests against a real
in-memory SQLite, 6 state-restoration tests and 3 hinge tests.

**CI does not cover item 5.** The instrumented suite is a local gate, run with
`tools/device/instrumented-tests.sh`. Saying "CI green" and leaving it there would imply
otherwise.

Every fix in this milestone was confirmed by reverting it and watching a test go red:
sign-out clearing every tab, the serializer registration, the deleted-event filter, the
calendar's event placement, the saved tab, and the hinge gap.

---

## 6. Bugs found in earlier milestones' code

§F of the checklist asks for these to be recorded rather than quietly fixed.

| Found | Since | What it was |
|---|---|---|
| A dead band of empty purple above every title | **M0** | `App.kt`'s Scaffold and each screen's own Scaffold both inset for the status bar, so it was counted twice. It is in every device screenshot in M1, M2 and M3 and was never named |
| No form used `rememberSaveable` | **M1** | Invisible while `configChanges` stopped the Activity being rebuilt; loses the user's typing the moment it is |
| A deep link replayed on every Activity recreation | **M2** | The Intent is a persistent value the Activity hands to each `onCreate`. Rotating after tapping a reminder threw the user back to that person |
| A deep link into a cold process was dropped entirely | **M2** | `onCreate` handles the intent before the composition restores the session, so `focusPerson` saw `AuthState.Unknown` and did nothing. Tapping a reminder with the app not running landed on the list |
| `MainActivity.onDestroy` released the permission and file-picker bridges unguarded | **M3** | `ActivityHolder` was guarded and the other four were not. Without the guard a new Activity installs its launchers and the outgoing one nulls them — M3's seventh finding, word for word |

---

## 7. Decisions this milestone took

Full reasoning in `docs/M4_PLAN.md`; what actually shipped:

- **Navigation 3**, not the hand-rolled navigator. The fallback in `M4_PLAN.md` §2.6 was not
  needed — the decision point at the end of commit 2 passed.
- **Material 3 Adaptive 1.2.0 plus our own `Scene`**, because `adaptive-navigation3` first
  appears in the 1.3.0 line and needs AGP 9.1 and compileSdk 37, which M0 ruled out. Raised
  in `M4_PLAN.md` §2.4a as a conflict with FR-VIEW-06's wording rather than resolved quietly.
- **One implementation of "what is coming up"**, which meant rewriting `Person.toCard` onto
  it rather than adding a second one beside it.
- **Rolling group windows**, for the reason in §2.1.
- **Dropping `configChanges`**, for the reason in §3.1.
- Two deep-link entry points through one route, so the widget joins M2's path.

## 8. Test data

Unchanged: **575 people, 574 events**, all invented except the two real contacts M3 imported
deliberately. One event id was replaced (§0.4). Nothing was added, and nothing was deleted
that was not put back.
