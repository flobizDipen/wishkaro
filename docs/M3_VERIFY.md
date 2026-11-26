# M3 Verification — Import

> Branch `feat/m3-import`, off `5704e0b`. Written as the work was done, not reconstructed.
>
> **This document is incomplete, and §0.1 says why.** The device half of the definition of
> done has not been run. Nothing below is rounded up to a pass to cover for that.

---

## 0. Before anything else

### 0.0 The device procedure, followed — and what it caught

`MILESTONE_CHECKLIST.md` §E exists because it was skipped once in M2 and a blind tap sequence
went into the wrong app. It was followed here, in order, and it earned itself again:

```bash
adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk   # → Success
adb logcat -c
adb shell am start -n in.wishkaro.app/.MainActivity                      # → Starting: Intent {...}
adb shell dumpsys window | grep -m1 mCurrentFocus                        # → NotificationShade
adb logcat -d -t 400 | grep -c "FATAL EXCEPTION"                         # → 0
adb exec-out screencap -p > s1.png                                       # → LOOKED AT IT
```

`mCurrentFocus` was `NotificationShade`, not `in.wishkaro.app/.MainActivity`. Collapsing the
shade, force-stopping and relaunching did not change it, which was the first sign that
something other than the app was wrong.

**The screenshot showed the phone's lock screen.** The handset is locked, and `am start`
cannot bring an activity to the front of a locked device — the launch "succeeds", the app is
started behind the keyguard, and `dumpsys` reports the keyguard's window. Had the checklist's
"look at the screen before the first tap" step been skipped, the next thing to happen would
have been a tap sequence aimed at an import screen, landing on a lock screen, with the
results read as if they meant something.

I am not unlocking somebody else's phone. **This blocks every device row below** and is the
one thing M3 needs from Dipen — see §9.

### 0.1 What is proven, and what is not

| | |
|---|---|
| **Proven** | Everything that can be proven off-device: 211 client tests and 275 server tests, including the chunking boundary, the full date table, the two-implementation name-token table, within-file and against-database dedup, and the same-file-twice idempotency run. The debug APK builds and installs |
| **NOT proven** | Any of it on a real phone with a real contacts list. Definition-of-done items 1, 2, 7 and 8 are device items and are **UNCHECKED**; items 3, 4, 5, 6 are proven as tests but not as a user doing it |

### 0.2 Suites run in a zone that is not this machine's

`MILESTONE_CHECKLIST.md` §D. This machine is `Asia/Kolkata`; CI is UTC.

```bash
                   ./gradlew :composeApp:testDebugUnitTest --rerun-tasks   # 211 pass
TZ=UTC             ./gradlew :composeApp:testDebugUnitTest --rerun-tasks   # 211 pass
TZ=Pacific/Chatham ./gradlew :composeApp:testDebugUnitTest --rerun-tasks   # 211 pass
cd server && npx jest                                                      # 275 pass
```

Nothing in M3's date handling reads an ambient clock: `today` is a parameter on every entry
point, which is what makes that third line uninteresting rather than lucky.

---

## 1. The outbox — the floor, and the thing that had to go first

### 1.1 What was actually broken, measured rather than assumed

The build prompt named the 500-row cap on `SyncPushSchema`. Reading the path end to end found
a **lower** limit that binds first, and fails worse. Observed, not inferred:

```
POST a 150 KB body to a Nest app configured the way main.ts configured it
  → 500 {"error":{"code":"INTERNAL","message":"Internal server error"}}
```

`main.ts` never set a body limit, so the API ran on body-parser's default of **100 KB** — a
limit nothing in this repo documents, sitting *below* the 500-row cap that is documented. A
person row is 200–300 bytes on the wire, so the transport limit bound at roughly 350–500
people. And `PayloadTooLargeError` comes from `http-errors`, not Nest, so
`AllExceptionsFilter` fell through to its catch-all: the client could not tell "your request
was too big" from "the server is broken".

That distinction is load-bearing for the fix, which aborts a run on a 5xx and keeps going on a
4xx. A 413 wearing a 500's clothes makes an oversized push retry for ever.

### 1.2 The fix, and the evidence it works

| Change | Evidence |
|---|---|
| Body limit explicit at 2 MB | `configure-app.spec.ts`: a 500-row push at its legal worst case (~1.1 MB) returns 201. At `100kb` that test fails |
| `entity.too.large` → a real 413 | Same suite. Without the mapping, the oversized body comes back 500 |
| Pages of 200, settled independently | `SyncChunkingTest`, 19 tests, against a fake server that enforces the REAL limits — the 500-row cap and the 2 MB body. **13 of the 19 go red** when `PUSH_PAGE_SIZE` is raised to simulate the old unchunked push, with the error the bug produces: `the server caps each array at 500; got 501 people` |

Both server fixes were confirmed by reverting them and watching the test go red
(`MILESTONE_CHECKLIST.md` §D), not by reading the diff.

### 1.3 The cases that are easy to get wrong, each with a test

- **Ordering.** A test walks the requests in the order they were sent and asserts no event
  arrives before its own person. Chunking the three arrays independently fails this
  immediately, which is why pages are filled with units.
- **A person pending but outside the snapshot.** Deferred rather than sent — sending it earns
  a guaranteed `UNKNOWN_PERSON`. Tested both ways round: held this run, sent the next.
- **A lost response.** Settles in two further rounds via the `STALE` re-stamp M2 built for a
  different reason, with `peopleReceived.distinct().size` asserting no duplicate.
- **Termination.** `STALE` and `UNKNOWN_PERSON` rows stay pending by design, so a drain
  written as "page until the outbox is empty" would spin on them. One test asserts one page
  is sent exactly once per run.
- **The poison row.** A 400 naming `people.17` blocks that row with the server's own message,
  retries the page without it, and still sends page three. A 400 naming no row blocks nothing
  — "could not attribute this" is not "nothing was wrong".

---

## 2. The name-token rule — a bug found by being asked to write the table

`MILESTONE_CHECKLIST.md` §C asks for one shared table of cases. Writing it found that **both**
implementations were wrong, differently, and disagreed on every Indian script. Verified by
running them — JDK 21 for Kotlin, node for TypeScript — not by reading them.

| Name | Client, before | Server, before | Both, now |
|---|---|---|---|
| `जयदत्त जांसरी` | `जयदत`, `सर` | `जयदत्त`, `जांसरी` | `जयदत्त`, `जांसरी` |
| `જયદત્ત જાંસરી` | `જયદત`, `સર` | **nothing at all** | `જયદત્ત`, `જાંસરી` |
| `José Álvarez` | `josé`, `álvarez` | `jos`, `lvarez` | `josé`, `álvarez` |

The client's class excluded combining marks (`\p{M}`), which is what Indic scripts build words
out of, so every such name shattered into fragments. The server's covered ASCII plus the
Devanagari block only, so a Gujarati, Tamil, Telugu, Bengali, Kannada, Malayalam, Punjabi or
Odia name tokenised to the empty set — and `findDuplicates` returns `[]` on an empty set, so
those people had **no duplicate detection at all**, silently, in an India-first app.

This is M1 code. Recorded here per checklist §F rather than quietly fixed, and fixed in M3
because FR-IMP-08 makes idempotency depend on the two agreeing.

`docs/fixtures/name-tokens.json` is read by **both** suites — the real file, not a copy.
Reverting the Kotlin rule turns three of its six tests red and prints the exact tokens.

**Unproven, and recorded as such:** CI runs the JVM tests and only *compiles* the iOS target,
so Kotlin/Native's own regex engine is never held to this table. iOS is compile-only in v1
(D15), but this is a real gap and it should not be discovered later as a surprise.

---

## 3. Dates — the table, and two bugs it caught while being written

24 tests over every format in FR-IMP-06 plus the ones that came free. Two real bugs surfaced:

- A leftover guard rejected `"00"` as an unreadable year, so **nobody born in 2000 could be
  imported**.
- The two-digit pivot was keyed on the *value* rather than on how many digits the user wrote,
  so `0000-03-14` became the year 2000 instead of no year. `"0065"` and `"65"` are the same
  number and different statements.

The pivot is stated with its reasoning in the code: a birth or anniversary date cannot be in
the future, so `yy` is `2000+yy` when that is at or before the current year and `1900+yy`
otherwise. There is a test at `today = 2030` pinning that it moves with the year rather than
being a fixed constant that quietly goes wrong.

---

## 4. Spreadsheets

`docs/fixtures/family-birthdays.xlsx` is written by a real spreadsheet library, not by my own
XML, so its date cell is genuine: a serial number whose only clue is a custom format two
indirections away in `styles.xml`. It is opened through the actual Android zip reader, with
nothing stubbed, and asserted row by row.

The two shapes that writer does *not* produce — a shared string table, and empty cells simply
missing from the file — are covered by hand-written XML in `commonTest`, because Excel and
Google Sheets both produce them and the second one silently corrupts a row: a contact with no
relation jumps from column B to column D, and every later value lands one column to the left,
for that row only.

---

## 5. Idempotency

Definition-of-done item 5 asks for a test, "not by importing by hand and looking". There is
one: the real planner runs over the real file **twice** against a store that accumulates, then
a third time, because idempotent means idempotent and not "stable after two".

Recorded as a test rather than left to be discovered: flipping the dd/MM toggle and
re-importing makes an already-imported row look new, because the rule is keyed on
`(name, day, month)` and under the other setting that genuinely is a different date. The
duplicate prompt catches it for the user; the rule cannot.

---

## 6. What the code does that is worth checking in review

- Import writes through `LocalStore.importPeopleLocally` in **one transaction**, after
  `planImport` has already decided every row. There is no POST-per-person path.
- Contacts become rows of strings and go through the **same** `planImport` as a file.
- A year-less contact date is emitted as `4 Jan`, not `01-04`. ISO would be two numbers under
  13 — exactly the shape the dd/MM toggle arbitrates — and a January birthday the device was
  certain about would flip to April under one setting. There is a test that runs the round
  trip under both settings.
- `--MM-DD` is parsed month-first, per RFC 6350. A test caught this; the first version read it
  with the default day-first and turned 4 January into 1 April.
- Nothing is written to `person.photo_key`. Contact photos are shown and discarded (M6).
- There is not one `Log` call in `AndroidContacts.kt`.

---

## 7. Fixtures

All invented people; the repo is public. `docs/fixtures/README.md` says what each file is for
and why each deliberately-broken row is broken.

---

## 8. Carried forward from M2, still open

- **The OEM battery helper has still never run on a non-Pixel device.** The only handset
  attached is a Pixel 9a. M2 definition-of-done item 7 stays **UNCHECKED**.
- **The mobile-data leg of the Railway deploy is still unproven.** Not retried yet, because
  the device is locked.

---

## 9. The honest summary

| # | Definition of done | State |
|---|---|---|
| 1 | A real contacts list imports selected contacts, relations set in bulk, only those exist after | **UNCHECKED** — the phone is locked (§0.0). The code path is covered by tests, which is not the same claim |
| 2 | Permission denied, and denied permanently, both leave a usable screen | **UNCHECKED** on the device. Both states are built and are separate screens; neither has been seen |
| 3 | A CSV with messy real dates imports with correct dates | **Pass as a test**, over `docs/fixtures/messy-birthdays.csv`'s formats. Not yet run through the app on the phone |
| 4 | The dd/MM toggle changes the preview; an all-ambiguous file imports correctly both ways | **Pass as a test** for the parse and the plan. The *preview changing* is a UI claim and is **UNCHECKED** |
| 5 | The same file twice produces zero new people, proven by a test | **Pass** (§5) |
| 6 | A file with a duplicate inside it produces one person | **Pass** (§5) |
| 7 | Bad rows produce a report naming each row and reason, and a corrected CSV that re-imports cleanly | **Pass as a test** including the round trip. Saving the file through the system picker is **UNCHECKED** |
| 8 | An import larger than the sync cap reaches the server completely, outbox empty, then a hand-added person syncs too | **Pass as a test**, against a fake server enforcing the real limits. **UNCHECKED against Railway** — and §10 has a question about where those 600 people are allowed to land |
| 9 | Unit tests for every date format, the toggle, the pivot, dedup within a file, dedup against existing, the chunking boundary | **Pass** — 211 client, 275 server |
| 10 | CI green, this document, fixtures committed | Fixtures committed; this document exists and says what is missing; CI pending on the PR |

Four of ten are device items and none of them is claimed. Six are claimed **as tests**, and
three of those say explicitly which part of the claim the test does not reach.

---

## 10. What I need from Dipen

1. **Unlock the phone.** It blocks every row above that says UNCHECKED. Nothing else does.
2. **Where may 600 invented people land for item 8?** `docs/M3_PLAN.md` §9.1 put this and it
   is still open. The recommendation stands: a second Google account on the handset, so a real
   Railway hobby tier materialises 600 events for real without any of it touching your data.
   The alternative is your own account plus 600 soft-deleted tombstones that live until M9's
   purge job exists.
3. **A non-Pixel phone**, if one has appeared, for M2 item 7.
