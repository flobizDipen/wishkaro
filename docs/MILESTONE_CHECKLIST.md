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
