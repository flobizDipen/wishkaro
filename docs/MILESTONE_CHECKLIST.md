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
| ☐ | The build prompt is checked against the **decisions table and the prose sections**, not only against the code. A prompt written against `main` can still contradict a locked decision that has never been exercised — nothing has run, so nothing disagrees yet | M5: the prompt said "default to `claude-opus-5-5`, do not quietly pick a cheaper model" while **D20** said "Haiku for high-volume wish drafting". Opposite instructions about the same feature, both written before a line of drafting code existed, neither spotted |

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
| ☐ | **An Intent is a persistent value.** `AppStore`'s channel made post-write navigation one-shot, and the deep link was still replayed on every `onCreate` — because the Activity keeps the Intent and hands it to each one. Rotating after a notification threw the user back to that person. Clear `intent.data` after handling it | M4, found by rotating in two-pane and landing on "This person is no longer here" |
| ☐ | **`testTagsAsResourceId` does not reach a `Dialog`.** A dialog is its own composition root in its own window, so tags inside one never become resource-ids and `ui.sh` cannot see them. Address dialog content by text | M4 |
| ☐ | A **unique selector can still be the wrong one.** `text:Remove` matched exactly one node and it was the event's delete, not the person's. `tap` prints the node it resolved — read it before a destructive action rather than piping it to /dev/null | M4 (an invented person's birthday was deleted and had to be re-entered by hand) |
| ☐ | **`am force-stop` blocks the package's PendingIntents.** A widget tap after a force-stop does nothing at all, which is indistinguishable from a broken widget. Hours went into "fixing" working widget actions because every test began with a force-stop | M4 §0.0 |
| ☐ | **Grep the `mCurrentFocus` LINE, not the whole `dumpsys window` output.** `dumpsys window \| grep -oE "pkgA\|pkgB"` returns whichever appears first anywhere in the dump, which is usually not the focused window. It reported the launcher while the app was in front | M4 |
| ☐ | **Never swipe to scroll while a text field has focus.** The gesture goes to the keyboard, which gesture-types into the field: a scroll turned a person's name into "Anika Pillai 25-WIDGETTEST hu by by". Dismiss the keyboard first — `ui.sh tap` now does | M4 |
| ☐ | A **redundant filter is worse than none**: it implies the job belongs to a layer it does not. When a revert-check does not go red, find out why before moving on | M4 (a deleted-row filter in the repository that the SQL already did) |

## What M5 added to this list

Each of these was bought the same way the rest were. M5 is the first milestone that did not
finish, so two of them are about reviewing and documenting rather than about code.

| ✅ | Item | Bought by |
|---|---|---|
| ☐ | **The same stale-derived-value bug has a SERVER half.** Where a push batch both sets a derived flag and writes a record that reads it, the record is derived from the value before the recompute — and if that record is excluded from re-derivation, the wrong answer is permanent AND is then taken as authoritative by the client, overwriting its own correct guess | M5 §5.1a — a death anniversary and a BIRTHDAY wish in one batch stored the wish CELEBRATORY for ever |
| ☐ | A **server-derived field is WRONG on the client, not stale, for one sync window** — and no fixture has that shape, because every fixture builds a record the way the SERVER returns it. Where such a field gates something that must not fail (tone, minors, credits, quotas), OR it with a local signal that is correct on BOTH sides of the round trip, in the direction where being wrong is harmless | M5 §0.1 — a death anniversary created on the handset opened the CELEBRATORY composer, offering Funny and Poetic with "Happy birthday" in the box. Reachable since M1, invisible to 370 tests, found by adding a person on a real phone |
| ☐ | **A plan that quotes requirements by `FR-` id will silently skip every rule that does not have one.** §§7.x have ids; §10's seven hard rules do not. A milestone whose subject has a PROSE section in REQUIREMENTS must be walked against that section as a list, rule by rule, in the plan | M5 §0.6 — three of seven hard rules missed, two plan "questions" raised that §10 and FR-PPL-09 had already answered |
| ☐ | **A fake that stands in for a remote service tests response handling for free and request construction not at all.** Assert on the outgoing body too, or a field can be deleted from the request with every test still green | M5 §5.3 — removing the JSON schema from the OpenAI request broke nothing |
| ☐ | **A comment that makes a performance claim either carries the measurement or does not make the claim.** The dangerous ones are plausible and unfalsifiable at a glance | M5 §5.2 — "an index scan over one day" was a guess; `EXPLAIN` showed 1,465 buffers to return 227 rows, because the column was trailing in every index |
| ☐ | **Asserting on what a service THROWS is not asserting on what a client RECEIVES.** The gap between them is exactly where an error envelope lives. Drive a real request for each error shape | M5 §0.2 — `retryAfterSeconds` computed and dropped by the shared filter; the **fourth** time this project has made that shape |
| ☐ | **A test that races an async write can pass by luck**, which is worse than failing: a green suite that is not checking what it claims. Wait for the write with a named timeout rather than assuming it landed | M5 §5.4 — two composer tests asserted on a `Dispatchers.Default` write and one passed anyway |
| ☐ | **A test must ask the layer that holds the rule.** A test that drives a SERVICE never runs the validation pipe, so a schema cap it claims to cover is untested and green | M5 §5.4 — the second vacuous test in one milestone, caught only by the revert-check |
| ☐ | **The revert-check is not a formality.** Every vacuous test this project has shipped was green and plausible; the only thing that told it apart from a real one was deleting the code it claimed to cover. Run it on every fix, and when it does NOT go red, find out why before moving on | M5 §§5.2–5.4, three times in one milestone |
| ☐ | A flow built inside a **function** rather than as a property, and collected in composition without `remember`, launches a new sharing coroutine per recomposition into a scope that is never cancelled — and `collectAsState` keys on the instance, so collection restarts and the list flashes empty | M5 §5.1a — `wishesOf()` was the only `stateIn` in `AppStore` behind a function |
| ☐ | **A banned-lexicon test cannot be pointed at a PROMPT.** A prompt has to name what it forbids, so "never congratulate" fails the check. The lexicon belongs on the model's OUTPUT, or on template strings — the words a recipient actually reads | M5 §0.3 |
| ☐ | A **mis-escaped string template in a `testTag` renders literally**, so every row carries one unaddressable id. A tag is only real once it has been read back out of the view tree | M5 §0.4 — `upcoming_send_${entry.event.id}` as a literal |
| ☐ | **The public-repo rule covers SCREENSHOTS, not just fixtures.** A screenshot of a hand-off into somebody's own messaging app carries their real conversations and phone numbers behind the thing you meant to capture | M5 §0.5 — the one screenshot proving definition-of-done item 1 had to be deleted |
| ☐ | When a milestone cannot finish, mark the blocked items **BLOCKED rather than PARTIAL**. A partial is something half-proved; a blocked item is not proved at all, and rounding the two together makes a half-done milestone read like a weak pass | M5 §1 — four items blocked on a model that was never configured |

## What M6 added to this list

M6 finished, and it is the milestone that found the most defects in its own work — 36, of
which **not one was found by a passing test suite.** They were found by opening a PNG,
tapping a button on a handset, reading a CI log, timing a function, and handing the diff to
reviewers who had not seen the author's conclusions. That is the shape of every item below.

| ✅ | Item | Bought by |
|---|---|---|
| ☐ | **A committed fake provider is a blind spot the exact shape of the live path.** Where the fake is the default, the suite, the author and the device run all exercise the same half of the code, and every rule that only binds when the provider is live is unverified while reading as proved. Before claiming a cost, rate-limit or moderation property, re-run the suite with a fake that reports `isLive` | M6 §0.7 — nine blockers, almost all unreachable with the fake. Definition-of-done item 5 was asserted by a DB test that always injected `FakeLlmProvider`, so "a cache hit writes no ledger row" was true for the wrong reason |
| ☐ | **A fix that changes a code path invalidates every measurement that described it**, and the verification document is where that debt lands. Nothing fails; the number simply stops being about anything | M6 §4.7 — twice. The 85ms cache-hit figure survived two fixes that between them removed the path it measured, and was caught by reading, not by a test |
| ☐ | **A test assertion pinned to a platform-specific value passes on the machine that wrote it and fails where the code runs.** Pixel sizes, font metrics, locale formatting, path separators. Assert the PROPERTY — "wrapping buys a bigger font" — not the number | M6 §0.6 and §0.7 — 11 of 42 cases failed on CI after passing locally, then two `toBe(78)` assertions failed for the same reason after the fix |
| ☐ | **"Verified on Linux" is a claim about the installed packages, not the kernel.** A container that is Linux but has different fonts, locales or codecs than CI will pass where CI fails, which is worse than not checking, because it is recorded as a check | M6 §0.6 — the verification container had Noto installed, so the font stack resolved before it reached the DejaVu that CI uses |
| ☐ | **A comment that asserts an invariant the code does not hold is worse than silence**, because it tells the next reader the question is settled. The dangerous ones are written in the same sitting as the code, with no check in between | M6 §0.9 — four of them: "not the expensive part" (it was 8x the whole operation), "no existing route changes behaviour" (every route's pre-auth ceiling changed), "throwing rather than sanitising" (it sanitised), and a moderation contract stated 70 lines above the function implementing its opposite |
| ☐ | **Body parsers are middleware and run BEFORE the auth guard.** Registering one globally raises the amount an unauthenticated caller can make the server buffer, on every route, whatever the route was for. Scope it to the path that needs it | M6 §0.7 #7 — measured: `POST /v1/auth/google` with `Content-Type: image/png` read 3,000,000 bytes before rejecting, where JSON was cut off at 2 MB |
| ☐ | **Caching a FALLBACK under the key of the thing it replaces is permanent.** The lookup cannot tell them apart, nothing invalidates it, and the metrics report a healthy cache while the real path is dead | M6 §0.7 #5 — the library placeholder stored under the generated-artwork key, so every key first served while `IMAGE_PROVIDER=fake` was pinned to a gradient for the life of the deployment |
| ☐ | **A requirement clause carrying a number can be missed inside a rule that is otherwise honoured.** Walking the prose sections rule by rule (M5's lesson) is not enough if a rule states two targets and the plan picks up one | M6 §4.7 — FR-POS-09 asks p95 under 15s on a miss AND under 2s on a hit; the hit half had no definition-of-done item and no line in the verification at all |
| ☐ | **Handing untrusted bytes to an image or document decoder needs an explicit size ceiling AND a rate limit**, and the library's default ceiling is not one. The format the header claims is not the format the decoder found | M6 §0.7 #6 — a 0.72 MB PNG declaring 256 megapixels decoded in ~220 ms and ~143 MB on a route behind no limiter; an SVG sent as `image/png` was rasterised and stored |
| ☐ | **A measurement can cost more than the thing it measures.** Before replacing an estimate with a real measurement, time it on the worst input the field limits allow | M6 §0.8 — the fix for a clipping bug cost 229 ms on an ordinary name and 1,172 ms at the 120-character `displayName` cap, against a ~150 ms poster |
| ☐ | **Review the fix round, not just the change.** A fix lands under time pressure, with the reviewer's framing rather than the author's, and it is the least-reviewed code in the diff | M6 §0.7a — an independent reviewer found a hole in a guard written hours earlier: it was built from the motif list that was to hand rather than from FR-EVT-04, which names "celebrate", "wish", "congrats" and emoji outright |
| ☐ | **A rule implemented on two surfaces drifts into two different rules unless each surface derives its inputs from the thing it is protecting.** Not a shared constant — a shared derivation | M6 §0.7a — input moderation checked `avoid` + notes on one surface and name + notes on the other, so the name went unchecked where it was sent to a model and hobbies and likes went unchecked on both. Fixed by each prompt builder declaring the user text it carries, with a test that renders the prompt and fails if anything in it is unmoderated |
| ☐ | **A lesson already on this list does not prevent the repeat.** M5 recorded the mis-escaped `testTag`; M6 shipped the same `${'$'}` over-escaping twice more — once in a FileProvider authority, where it made every share fail silently, and once in the report dialog's tags | M6 §0.4 and §0.7 — the checklist is a review aid, not a guard. Where a mistake has a mechanical signature, write the grep or the test instead |

### The shape M6 would look for first

A milestone whose subject is **a third-party service behind a port** should assume the fake
hides the rules. Every property that only binds when money is being spent — a cost ledger, a
cap, a rate limit, a moderation verdict, a retry — is unverified by a green suite against the
committed default, and reads as proved. The cheapest way to find out is a fake that reports
`isLive` and a test that counts rows and Redis keys on both sides of the call.


## What M9 added to this list

M9 is the first milestone to merge **unfinished by instruction** rather than by blocker, and
the first whose most expensive findings were about the **verification apparatus** rather than
the product. Three of the entries below were bought by something stopping a write that was
already in flight; two were bought by checking a claim instead of acting on it.

| ✅ | Item | Bought by |
|---|---|---|
| ☐ | **PII arrives in a verification document through the HONESTY, not through carelessness.** Explaining precisely why an account was refused, or which host a check ran against, is exactly what this project asks for — and it is what puts real addresses and real names into a file about to be committed to a public repository. Describe the **reason** in full and the **identifier** never | M9 §14.1 — a draft of the deletion write-up named three email addresses and quoted two names that read as real family. Four written rules about the public-repo boundary (fixtures M3, screenshots M5 §0.5, export files §14, verification documents §14.1) had not prevented a real address sitting in `docs/M0_PREREQUISITES.md` since M0 |
| ☐ | **A guard that runs on SOME paths is not a guard.** A check that greps the whole tree is still blind wherever its workflow's path filter does not reach, and the place it is blind is likely to be the place the mistake happened — because the filter and the mistake are both about "the part of the repo nobody thinks of as code" | M9 §14.3 — `no-pii-in-repo.spec.ts` greps everything and ran only when `server/**` changed; the leak it was written for was in `docs/`. Fixed by moving the check to a dependency-free script behind an **unfiltered** workflow that runs in six seconds, with the spec calling the same script so there is one definition |
| ☐ | **A DB-backed suite sharing a database must own a unique marker, must never delete by a predicate broader than what it wrote, and must not assume it is alone.** The second clause is the one that bites: a too-wide cleanup fails in a *different file*, so the symptom and the cause are never in the same place | M9 §14.3a and §14.5 — one suite seeding `pricingVersion: 'spec'` (a literal another suite also used) made **two other suites** flaky: its queries saw their rows, and its cleanup deleted them, which moved the global spend total a third suite's cap tests read. Two failures in five runs, in two files, neither of them the one with the bug |
| ☐ | **A flake that fails locally and passes in CI is the worse direction**, because it trains people to re-run rather than to look. CI starting from an empty database narrows every race that accumulated state widens | M9 §14.5 — parallel workers against one Postgres failed 3 runs in 5 on a machine whose database had accumulated 1,543 ledger rows; CI had been green throughout. `--runInBand` fixed it and **cost nothing** — both modes take 7 seconds, because the suite is I/O-bound. Measure before assuming serialisation is a trade-off |
| ☐ | **Check the claim, not the conclusion — including a peer's.** A report that is right about the shape of a problem can be wrong about its resolution, and acting on the resolution destroys something | M9 — a peer session correctly identified an `add/add` merge conflict and described the file as *deleted* on this branch. `git log --name-status` said `M`. Taking the proposed fix would have removed the half of the guard that reaches a developer before they push |
| ☐ | **A revert-check that fails to COMPILE is not a revert-check.** `Tests: 0 total` is not red; it is the suite never running. Re-do the revert as the realistic bug — the one a person would actually write — rather than as whatever edit was quickest | M9 §2.4 — removing a null-guard left a type error, so the suite did not run and the "revert-check" proved nothing. Redone as `new Date(null)`, which is epoch 0 and therefore *very* overdue, it went red properly |
| ☐ | **A requirement sentence is a LIST of clauses, and each one needs a home.** M5 bought "walk the prose sections rule by rule"; M6 bought "a clause carrying a number can be missed inside a rule otherwise honoured". M9's is the third turn of the same screw: walking FR-SET-06 as three clauses rather than one sentence is what showed two of them had nothing behind them | M9 §2.1 — a committed comment asserted "M9 is a job rather than a job and a migration". The dates were expressible; the keep override only half was; **the warning had nothing at all**, so the sweep would either warn every night until it deleted or never warn |
| ☐ | **A confirmation that lives only in the UI is one a second client, a stale build or a curl does not have.** Where an action cannot be undone, the server re-checks what the screen asked for | M9 — `DELETE /v1/account` requires the typed email in the body and compares it server-side, and the screen compares it too. Two checks of one thing, with the authoritative one on the side that performs the deletion |
| ☐ | **Prove the NEGATIVE has a baseline before you assert it.** "X no longer happens" is worth nothing unless X was observed happening first, because a failure for any other reason reads as success | M9 §9.6 — item 3 ends "a push to that token no longer delivers". The procedure written for it puts "prove the push DOES arrive" **before** the deletion, specifically so the absence afterwards means something |
| ☐ | **An irreversible action aimed at a real account is refused until the account is verified, however it was named.** The address offered is not evidence about whose account it is | M9 §9.6 — the account offered for a deletion test was the owner's own, already in the database with six people in it, two reading as real family. Checking cost one query; the alternative was unrecoverable |

### The shape M9 would look for first

A milestone whose subject is **the apparatus rather than the product** — a guard, a lint, a
check, a CI job — should assume the apparatus has the same blind spot as the thing it guards.
The PII check greps every file and ran on some paths. The test suite that proves isolation
shared a database. The verification document that insists on naming what was not proved named
the people instead. In each case the reasoning was right and stopped one level short of where
it applied to itself.
