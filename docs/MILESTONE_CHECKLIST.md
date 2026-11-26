# Milestone Checklist

> Every item here was paid for. The right-hand column names the milestone that bought it, so
> that an item is never skipped on the grounds that nobody remembers why it is there.
>
> This is the standing process for every milestone from M3 onward. It does not replace a
> milestone's own `BUILD_PROMPT`, `PLAN` or `VERIFY` document — it is what those three are
> checked against.

---

## A. Before the session starts — Dipen

| ✅ | Item | Bought by |
|---|---|---|
| ☐ | The previous PR is **merged**, `main` is green, and the local checkout is on `main` and clean | — |
| ☐ | The build prompt was written **against what `main` actually contains**, not against the milestone table in REQUIREMENTS.md | M2, M3 |
| ☐ | Every item the previous milestone left **unproven** is named in the new prompt, with "close it or leave it honest" | M2 (OEM helper, mobile data) |
| ☐ | The hardware the definition of done needs actually exists — a non-Pixel phone, a real contacts list, a messy spreadsheet | M2 item 7, still open |
| ☐ | Any decision that would be expensive to reverse is flagged in the prompt as **"decide this before you write code"** | M2 (dedupe key), M3 (outbox, parse location) |

## B. Before any code is written — the session

| ✅ | Item | Bought by |
|---|---|---|
| ☐ | `docs/Mx_PLAN.md` exists, the flagged decisions are made **in writing with their reasoning**, and Dipen has seen it | M2 |
| ☐ | Each decision records what is being **given up**, not only what is chosen | M2 §1.2 |
| ☐ | Anything that contradicts `REQUIREMENTS.md` is **raised, not silently resolved** | M0, M1, M2 each hit one |
| ☐ | The implementation order puts the **floor before the features** — the thing everything else stands on goes first | M2 (delivery reporting), M3 (sync outbox) |

## C. While building

| ✅ | Item | Bought by |
|---|---|---|
| ☐ | A value that says **what changed** is consumed, not computed and dropped | M1 (`skipped`), M2 (`PlanDiff.removed`) |
| ☐ | Every reconciliation loop is checked against rows that arrived by a **route it was not written for** | M2 (snooze rows vs `rebuildLocalFallbacks`) |
| ☐ | One user action that has **more than one route** (local write, sync, server endpoint) means the same thing on all of them | M1 (restore returned an empty person) |
| ☐ | A value crossing a layer boundary is **compared in its parsed form**, never as text | M1 (timestamps as strings; `'2' < 'Z'` broke last-write-wins, latent since M0) |
| ☐ | Validation that runs **before** anything is applied cannot let one bad field block unrelated data | M2 (settings push), M3 (the outbox blocker) |
| ☐ | Anything two code paths can reach at once has been reasoned about **as a concurrent pair** | M2 (token rotation: two 401s, one refresh token) |
| ☐ | A rule implemented **twice in two languages** is held to one shared table of cases | M3 (name tokens, Kotlin + TS) |
| ☐ | Derived values stay server-derived. The client never tells the server what something means | M0 onward |
| ☐ | Nothing destructive in a migration. Real data exists on Railway and on the phone | M1 onward |
| ☐ | No secret reaches the repo — it is **public**, and a pushed secret is compromised, not deletable | M0 |

## D. Tests

| ✅ | Item | Bought by |
|---|---|---|
| ☐ | The test drives the **seam**, not only the pure function — the pure functions are not where this codebase breaks | M1, M2 (all six reviewed findings) |
| ☐ | Every fix is confirmed by **reverting it and watching the test go red** | M2 §8.5 |
| ☐ | Suites run in a zone that is not this machine's: `TZ=UTC` and `TZ=Pacific/Chatham` | M2 §0.0.2 (caught a test reading the ambient zone through production code) |
| ☐ | Anything that can only fail when two paths run together has a test that **runs them together** | M2 (the delivery-report downgrade, found on the two-path test's first run) |
| ☐ | A Koin/DI graph change is covered by `ModulesTest` — it verifies constructors without instantiating | M2 §0.0.1 |

## E. On the device — the procedure, not advice

> This exists because it was skipped once, on 2026-11-25, and a blind tap sequence went into
> **FloBooks** because WishKaro had crashed on launch and `monkey`'s output was discarded.
> Full write-up: `M2_VERIFY.md` §0.0.1.

| ✅ | Item |
|---|---|
| ☐ | `adb install -r …` — and **read the result** |
| ☐ | Launch with `am start`, never `monkey`, and never discard the output |
| ☐ | `adb shell dumpsys window \| grep -m1 mCurrentFocus` names `in.wishkaro.app/.MainActivity` |
| ☐ | `adb logcat -d -t 400 \| grep -c "FATAL EXCEPTION"` is **0** |
| ☐ | `adb exec-out screencap -p` — **look at the screen before the first tap** |
| ☐ | Only then send input |

**CI does not cover this.** CI compiles both targets and runs unit tests. It does not install
the app. A build that cannot start passes CI — M2 proved it, with 308 green tests.

## F. Verification document

| ✅ | Item | Bought by |
|---|---|---|
| ☐ | `docs/Mx_VERIFY.md` has **one row per definition-of-done item**, each saying what was actually observed | M2 §9 |
| ☐ | "Scheduled correctly" and "watched arriving" are written as **different claims** | M2 items 1, 4, 5 |
| ☐ | Anything unproven says **UNCHECKED** with the reason, and is not rounded up to a pass | M2 item 7 |
| ☐ | Exact reproduction steps, including how any failure was **simulated on purpose** | M2 §3 |
| ☐ | Bugs found in **earlier milestones' code** are recorded, not quietly fixed | M2 found four, three of them older |
| ☐ | Test fixtures contain **invented people**, never real contacts — the repo is public | M3 |
| ☐ | An incident note that turns out to be wrong is **corrected in place**; a wrong note is worse than none | M2 §0.0.1 (two corrections) |

## G. Review, before merge

| ✅ | Item | Bought by |
|---|---|---|
| ☐ | `/code-review high <PR#>` run on the PR, not on a stale local branch | M1, M2 |
| ☐ | Findings relayed to the building session with **code, failure path and a required test** | M2 |
| ☐ | Each fix **verified against the branch** by reading it — session reports are not taken at face value | M1 (app misidentification), M2 |
| ☐ | `gh pr checks` confirms CI is green **on the PR's current head**, not an earlier run | M2 |
| ☐ | Merge with `--merge`, never `--squash` — the per-commit reasoning is the reviewable artifact | M0 onward |

## H. After merge

| ✅ | Item |
|---|---|
| ☐ | `main` fast-forwarded and pushed |
| ☐ | The milestone row in `REQUIREMENTS.md` carries the **merge sha**, and names what the milestone did *not* establish |
| ☐ | The status line at the top of `REQUIREMENTS.md` is bumped |
| ☐ | The next milestone's prompt is written in the same shape, carrying the open items forward by name |

---

## The two shapes to look for first

Every bug the reviews caught in M1 and M2 was one of these. When reading a diff, start here:

1. **A value that says what changed is computed and then dropped.** `PlanDiff.removed` was
   documented as telling the caller which alarms to cancel, and the caller discarded it. M1's
   `skipped` list was the same shape.

2. **A reconciliation loop that is correct for the rows it was written for and wrong for rows
   that arrived another way.** `rebuildLocalFallbacks()` deletes every `LOCAL_FALLBACK` row it
   does not currently want — and a snoozed reminder is, by definition, not wanted by it. M1's
   sweep was filtered to unmuted events and met a muted one.

The pure functions in this codebase are well tested and keep being right. The seams between
them are where it breaks.

---

## What M3 added to this list

Each of these was bought the same way the rest were — by something going wrong.

| ✅ | Item | Bought by |
|---|---|---|
| ☐ | When `mCurrentFocus` names **`NotificationShade`** or a Keyguard window, the device is **locked**, not broken. `am start` on a locked device succeeds — the activity starts behind the keyguard — so the install says Success, the launch prints `Starting: Intent` and the fatal count is 0. Only the screenshot tells you | M3 §0.0 |
| ☐ | Ask of every feature: **who can reach it a week after they start using the app?** An empty state and a first-run offer between them only ever reach a user with *nobody* | M3 §6.1 (import had no route from a non-empty list) |
| ☐ | A **one-shot navigation needs a one-shot signal**, not a persistent value — or must be cleared at the entry point. State that outlives the screen outlives the Activity too | M3 §10.1 |
| ☐ | Any state shared between an **Activity-crossing await** and a singleton needs a recovery path that is NOT on the awaiting coroutine. A `finally` cannot help a coroutine that never resumes | M3 §10, the seventh finding |
| ☐ | A **client must never arbitrate a server-derived field with a client clock.** It cannot hold a newer version of something it never writes | M3 §6.3 |
| ☐ | A re-derivation must never move a row's `updated_at` **backwards**: it is the sync cursor's ordering key | M3 §6.3 |
| ☐ | Treat "a value that says what changed is computed and dropped" as a **grep, not a reading**. M3 made the same shape a third time, in the milestone that quoted the rule | M3 §10.3 (`truncated`), after `PlanDiff.removed` and `skipped` |
| ☐ | A **fake store cannot catch a disagreement between the code and the schema**, because it agrees with whatever the code does to it. `androidUnitTest` runs on the JVM and can hold `LocalStore` to a real in-memory SQLite | M3 §6.3 |
| ☐ | Guard **every** blind tap on the focused window, not just the first of a sequence — a notification can arrive between the screenshot and the tap | M3 (a typed string landed in a Slack reply box) |

## What M4 added to this list

| ✅ | Item | Bought by |
|---|---|---|
| ☐ | **`./gradlew connectedAndroidTest` UNINSTALLS the app when it finishes.** Not `pm clear` — a full uninstall, so the local database and the session in EncryptedSharedPreferences both go and the next launch is a first launch. Never run it against the handset holding the real account; use an emulator, or `tools/device/instrumented-tests.sh`, which installs with `-r` and drives `am instrument` directly | M4 §0.0 — it wiped the 566-person account off the owner's phone mid-milestone |
| ☐ | A **guard that rejects the app it is guarding** gets switched off. §E says `mCurrentFocus` should name `in.wishkaro.app/.MainActivity`; `dumpsys` prints the fully-qualified form. Accept both, and let focus SETTLE rather than failing on the `mCurrentFocus=null` that follows every `am start` | M4, first use of the device script |
| ☐ | **Compose hands `uiautomator` a clickable node with no text and no content-description** — the label is a separate child node — so a button with no `testTag` cannot be addressed from the view tree at all. Tag anything a device sequence has to touch, as it is written | M4 (the Add person button was unaddressable) |
| ☐ | **Assert AFTER the tap, not only before.** Every guard rail §E has bought checks the state beforehand and none can say where the tap landed. `tools/device/ui.sh expect` is the other half | M2 and M3's three blind-tap incidents, closed in M4 |
| ☐ | `android:configChanges` on the Activity means **the Activity is never recreated**, so "state survives rotation" is true because nothing was ever lost — while process death still loses everything. Check what the manifest actually declares before believing a restoration claim | M4 §2.5 (not one form in the app used `rememberSaveable`) |
| ☐ | **The soft keyboard is drawn over the app and `uiautomator` still reports the nodes behind it.** A node resolves perfectly and the tap lands on a letter key — a tap on a bottom-bar tab typed a comma into the search box. The IME window's frame is reported as the whole screen, so there is nothing to intersect against: close the keyboard and resolve the node AGAIN, because the layout moves | M4, during the list verification |
| ☐ | A **redundant filter is worse than none**: it implies the job belongs to a layer it does not. When a revert-check does not go red, find out why before moving on | M4 (a deleted-row filter in the repository that the SQL already did) |
