# M3 Verification — Import

> Branch `feat/m3-import`, off `5704e0b`. Written as the work was done, not reconstructed.
>
> **Nine of the ten definition-of-done items pass, verified on the device.** Item 8 is not
> done and §10 says exactly what it is waiting on. Nothing below is rounded up.

---

## 0. Before anything else

### 0.0 The device procedure, followed — and what it caught

`MILESTONE_CHECKLIST.md` §E exists because it was skipped once in M2 and a blind tap sequence
went into the wrong app. It was followed here, in order, and it earned itself again on the
first attempt:

```bash
adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk   # → Success
adb logcat -c
adb shell am start -n in.wishkaro.app/.MainActivity                      # → Starting: Intent {...}
adb shell dumpsys window | grep -m1 mCurrentFocus                        # → NotificationShade  ✗
adb logcat -d -t 400 | grep -c "FATAL EXCEPTION"                         # → 0
adb exec-out screencap -p                                                # → LOOKED AT IT
```

`mCurrentFocus` was `NotificationShade`, not the app. Collapsing the shade, force-stopping and
relaunching did not change it. **The screenshot showed the lock screen.**

`am start` on a locked device *succeeds* — the activity starts behind the keyguard — so every
other signal says things are fine: the install says `Success`, the launch prints
`Starting: Intent`, and the fatal count is 0. Without the "look at the screen before the first
tap" step, the next thing to happen would have been a tap sequence aimed at an import screen,
landing on a lock screen, with the results read as if they meant something. That is the M2
incident with a different app.

**Worth adding to §E:** when `mCurrentFocus` names `NotificationShade` or a Keyguard window,
the device is locked, not broken — and the screenshot is the only thing that tells you.

Everything below was run after the owner unlocked the handset. Every screen was photographed
and read before the next tap; `FATAL EXCEPTION` stayed at **0** across the whole session.

### 0.1 The device, as actually found

| | |
|---|---|
| Handset | `4B201JEBF03493` — **Pixel 9a**, the only one attached |
| Build | Debug APK, base URL pointed at the Railway deployment (`local.properties`) |
| Its contacts | **515**, of which **2 have a usable birthday** |
| Disk on this machine | 5.4 GB free of 460 GB. M1 was blocked outright at 1.4 GB |

> **Correction, made in place per `MILESTONE_CHECKLIST.md` §F.** An earlier draft of this
> document said *three* contacts had a usable birthday, from counting `contact_event` rows
> over `adb`. The app's own review screen says **2**, and the app is right: one
> birthday-typed row has a `NULL` date, and the `adb` counts included a duplicate contact
> row. A wrong note is worse than none.
>
> The NULL-dated birthday row is not a curiosity — it is exactly the half-filled record the
> build prompt says M3 is the first milestone to meet, and it was there without my inventing
> it. It correctly lands in the dateless half of the review screen.

### 0.2 Suites run in a zone that is not this machine's

`MILESTONE_CHECKLIST.md` §D. This machine is `Asia/Kolkata`; CI is UTC.

```bash
                   ./gradlew :composeApp:testDebugUnitTest --rerun-tasks   # 211 pass
TZ=UTC             ./gradlew :composeApp:testDebugUnitTest --rerun-tasks   # 211 pass
TZ=Pacific/Chatham ./gradlew :composeApp:testDebugUnitTest --rerun-tasks   # 211 pass
cd server && npx jest                                                      # 275 pass
```

Nothing in M3's date handling reads an ambient clock — `today` is a parameter on every entry
point — which is what makes that third line uninteresting rather than lucky.

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

That distinction is load-bearing, because the fix aborts a run on a 5xx and keeps going on a
4xx. A 413 wearing a 500's clothes makes an oversized push retry for ever.

### 1.2 The fix, and the evidence

| Change | Evidence |
|---|---|
| Body limit explicit at 2 MB | `configure-app.spec.ts`: a 500-row push at its legal worst case (~1.1 MB) returns 201. At `100kb` that test fails |
| `entity.too.large` → a real 413 | Same suite. Without the mapping the oversized body comes back 500 |
| Pages of 200, settled independently | `SyncChunkingTest`, 19 tests, against a fake server enforcing the REAL limits — the 500-row cap AND the 2 MB body. **13 of the 19 go red** when `PUSH_PAGE_SIZE` is raised to simulate the old push, with the error the bug produces: `the server caps each array at 500; got 501 people` |

Both server fixes were confirmed by reverting them and watching the test go red, not by
reading the diff.

### 1.3 The cases that are easy to get wrong, each with a test

- **Ordering.** A test walks the requests in the order sent and asserts no event arrives
  before its own person. Chunking the three arrays independently fails it immediately.
- **A person pending but outside the snapshot.** Deferred rather than sent. Tested both ways:
  held this run, sent the next.
- **A lost response.** Settles in two further rounds via the `STALE` re-stamp M2 built for a
  different reason, with `peopleReceived.distinct().size` asserting no duplicate.
- **Termination.** `STALE` and `UNKNOWN_PERSON` rows stay pending by design, so a drain
  written as "page until the outbox is empty" would spin on them.
- **The poison row.** A 400 naming `people.17` blocks that row with the server's own message,
  retries the page without it, and still sends page three. A 400 naming no row blocks nothing.

### 1.4 What the device showed

Not the over-cap case — that is item 8 and is still open — but every import below drained to
**0 pending, 0 blocked**, checked directly against the device's own SQLite:

```bash
adb shell "run-as in.wishkaro.app cat databases/wishkaro.db" > w.db
sqlite3 w.db "SELECT COUNT(*), SUM(pending_sync), SUM(sync_blocked) FROM person WHERE deleted_at IS NULL"
#  → 22|0|0
```

---

## 2. The name-token rule — a bug found by being asked to write the table

`MILESTONE_CHECKLIST.md` §C asks for one shared table of cases. Writing it found that **both**
implementations were wrong, differently, and disagreed on every Indian script. Verified by
running them — JDK 21 for Kotlin, node for TypeScript — not by reading.

| Name | Client, before | Server, before | Both, now |
|---|---|---|---|
| `जयदत्त जांसरी` | `जयदत`, `सर` | `जयदत्त`, `जांसरी` | `जयदत्त`, `जांसरी` |
| `જયદત્ત જાંસરી` | `જયદત`, `સર` | **nothing at all** | `જયદત્ત`, `જાંસરી` |
| `José Álvarez` | `josé`, `álvarez` | `jos`, `lvarez` | `josé`, `álvarez` |

The client's class excluded combining marks (`\p{M}`), which Indic scripts build words out of,
so every such name shattered into fragments. The server's covered ASCII plus the Devanagari
block only, so a Gujarati, Tamil, Telugu, Bengali, Kannada, Malayalam, Punjabi or Odia name
tokenised to the empty set — and `findDuplicates` returns `[]` on an empty set, so those
people had **no duplicate detection at all**, silently, in an India-first app.

M1 code. Recorded here per §F rather than quietly fixed, and fixed in M3 because FR-IMP-08
makes idempotency depend on the two agreeing.

`docs/fixtures/name-tokens.json` is read by **both** suites — the real file, not a copy.
Reverting the Kotlin rule turns three of its six tests red and prints the exact tokens.

**Proved on the device too**: the fixture carries the same person's name in Devanagari and in
Gujarati. Both imported, both matched themselves on the re-run (§6), neither matched the
other — different scripts share no tokens, which is a limit, recorded in
`docs/fixtures/README.md`, not a bug.

**Unproven:** CI runs the JVM tests and only *compiles* the iOS target, so Kotlin/Native's own
regex engine is never held to this table. iOS is compile-only in v1 (D15), but it is a real
gap and should not be found later as a surprise.

---

## 3. Dates — the table, and two bugs it caught while being written

24 tests over every format in FR-IMP-06. Two real bugs surfaced while writing them:

- A leftover guard rejected `"00"` as an unreadable year, so **nobody born in 2000 could be
  imported**.
- The two-digit pivot was keyed on the *value* rather than on how many digits the user wrote,
  so `0000-03-14` became the year 2000 instead of no year. `"0065"` and `"65"` are the same
  number and different statements.

The pivot's reasoning is in the code: a birth or anniversary date cannot be in the future, so
`yy` is `2000+yy` when that is at or before the current year and `1900+yy` otherwise. A test
at `today = 2030` pins that it moves with the year rather than being a constant that quietly
goes wrong.

---

## 4. Spreadsheets

`docs/fixtures/family-birthdays.xlsx` comes from a real spreadsheet library, not from my own
XML, so its date cell is genuine: a serial number whose only clue is a custom format two
indirections away in `styles.xml`. It is opened through the actual Android zip reader, with
nothing stubbed, and asserted row by row.

The two shapes that writer does *not* produce — a shared string table, and empty cells simply
missing — are covered by hand-written XML in `commonTest`, because Excel and Google Sheets
both produce them and the second silently corrupts a row: a contact with no relation jumps
from column B to column D and every later value lands one column left, for that row only.

---

## 5. On the device — exactly what was done, in order

Every step below was photographed. Dates and names from the owner's real contacts are not
reproduced here; the repository is public.

### 5.1 Item 2 — permission denied, and denied permanently

1. `adb shell pm revoke … READ_CONTACTS`, then Import → From your contacts.
2. **The rationale screen appeared before the system dialog**, saying what is read, that it
   stays on the phone, and that unpicked contacts are never saved.
3. Continue → the system dialog → **Don't allow**.
4. → *"No contacts, then. That is fine. You can add people by hand, or import a spreadsheet
   instead."* with **"Actually, let me pick contacts"**. Usable, and offers the way back.
5. Asked again → **Don't allow** a second time.
6. → the same screen **without** the ask-again button, saying Android will not ask again and
   where the switch is, with **Open Settings**.

Confirmed against the platform rather than against my own UI:

```
android.permission.READ_CONTACTS: granted=false, flags=[ USER_SET|USER_FIXED|… ]
```

`USER_FIXED` is Android's own permanent-denial flag, so the screen and the system agree.

7. **Open Settings** → `com.android.settings/.spa.SpaActivity`, on WishKaro's App info page
   with its Permissions entry. The route the copy points at is real.

**A gap this found, and the fix.** Granting the permission in Settings and coming back left
that screen exactly as it was, still saying no — the screen reads the permission only when it
first opens, and returning from Settings is not opening it. So the one route the copy sends
you down appeared to do nothing. Fixed by adding **"I've turned it on"**, which re-reads the
permission; re-verified on the device, and it goes straight to the review list.

### 5.2 Item 1 — a real contacts list

- Review screen: **"2 of 515 selected · 2 still need a relation"**. The two with dates first
  and pre-ticked; the other 513 below, unticked, each showing *"No date on this contact"* and
  an **Add date** affordance, their checkboxes inert.
- **Import was disabled**, with the reason in red underneath naming the count.
- **Set relation** → the curated chip list (FR-PPL-02) → *Set for all 2*. Both rows gained the
  relation and the Import button enabled. Two taps for the whole selection.
- Import → **"2 added · 2 imported"**.
- Database afterwards: **7 people** (5 pre-existing, 2 new), **0 pending, 0 blocked**. 2 of
  515 offered. Only what was ticked exists.

### 5.3 Items 3 and 4 — messy dates, and the toggle

`messy-birthdays.csv` picked through `ACTION_OPEN_DOCUMENT`. All three fixtures were
selectable in the picker, so the MIME list is wide enough for files real tools write.

The mapping screen read **17 rows**, detected the header, and guessed every column correctly
from the user's own words — `Full Name`→Name, `Birthday`→Date, `How I know them`→Relation,
and `Mobile`/`Notes` too (confirmed afterwards from the database, below).

The preview, **as WishKaro reads them**:

| Row in the file | Shown as |
|---|---|
| `04/01/1990` | 4 Jan 1990 ⟷ could be read either way |
| `22 Sep 1988` | 22 Sep 1988 |
| `14-03-65` | **14 Mar 1965** — the pivot, visible |
| `25/12` | 25 Dec · year unknown |
| `05/06/1992` | 5 Jun 1992 ⟷ could be read either way |

**Item 4, in one frame.** Tapping **Month first (6 May)** with the preview on screen:

- `04/01/1990` → **1 Apr 1990** — the ambiguous row flipped;
- `22 Sep 1988` → unchanged, because a named month cannot be read the other way;
- `14 Mar 1965` → unchanged, because 14 cannot be a month.

Only genuinely ambiguous rows follow the toggle, and only those carry the ⟷ mark.

### 5.4 Items 6 and 7 — the report, and the round trip

Imported day-first: **"10 added · 10 imported · 1 already here · 5 could not be read"**.

```
Already here
  Row 17: Aarav Shah — appears twice in this import          ← item 6

Could not be read
  Row 12: "31/04/1990" is not a real date
  Row 13: 2023 was not a leap year, so 29 February 2023 did not happen
  Row 14: could not read "not a date" as a date
  Row 15: no name
  Row 16: no date
```

Five different breakages, five different reasons, each naming its row.

**Save these 5 rows as a file to fix** → `ACTION_CREATE_DOCUMENT`, suggested name
`wishkaro-rows-to-fix.csv`, saved to Downloads. Pulled back and read:

```
<BOM>Full Name,Birthday,How I know them,Mobile,Notes,WishKaro: why this row was not imported
Diya Menon,31/04/1990,Colleague,,BROKEN: 31 April does not exist,"""31/04/1990"" is not a real date"
…
```

Their header, their values unmodified, one column added, quoting correct for the embedded
quotes and commas — and a UTF-8 BOM (`ef bb bf`), so the file opens as UTF-8 in the tool they
are being sent to rather than as mojibake.

The five rows were then fixed the way a user would (a real April date, a real leap year, a
readable date, a name, a date), the reason column dropped, and the file re-imported:
**"5 added · 5 imported"**, including `29/02/2024`, which is a leap year and does exist.

### 5.5 Item 5 — the same file twice, on the device

Re-imported the **original** `messy-birthdays.csv`:

> **Nothing was added** — 0 imported · 11 already here · 5 could not be read

with all eleven named, each *"already in WishKaro as …"*, including both the Devanagari and
the Gujarati spellings matching themselves. Re-importing the same two contacts gave
**"Nothing new — 0 imported · 2 already here"** with the line *"You have imported this before.
Running it again changed nothing, which is what should happen."*

### 5.6 What is actually in the database afterwards

```
22 people, 0 pending, 0 blocked        FATAL EXCEPTION count across the session: 0
```

```
display_name   rel      phone             notes            date
Aarav Shah     Cousin   +91 98200 11111   likes cricket    4/1/1990
Rohit Desai    Uncle    9820022222        two-digit year   14/3/1965
Kavya Nair     Friend                     day and month    25/12/none
Saanvi Joshi   Sister                     29 Feb with no   29/2/none
जयदत्त जांसरी    Father                     Devanagari       2/10/1985
જયદત્ત જાંસરી    Father                     Gujarati — the   2/10/1985
Vihaan Iyer    Boss                       BROKEN: 2023 w   29/2/2024
```

Phone and notes mapped; the pivot stored 1965; two year-unknown dates stored as `NULL` rather
than a guessed year; 29 February survives both with and without a year; both scripts intact
through zip, UTF-8, XML entities, the parser and SQLite.

---

## 6. Two things changed because of what the device showed

1. **Import had no entry point from a non-empty list.** FR-VIEW-05 puts it on the empty state
   and FR-AUTH-05 offers it at first run, and both were built — but between them they only
   ever reach somebody with *nobody*. A user who added three people by hand last week and now
   wants the other three hundred off their phone had no route at all. I only noticed because
   *I* could not reach the feature to test it. Added to the people list's action row.
2. **"I've turned it on"** on the permanently-denied screen (§5.1).

---

## 7. Carried forward from M2, still open

- **The OEM battery helper has still never run on a non-Pixel device.** The only handset
  attached is a Pixel 9a. M2 definition-of-done item 7 stays **UNCHECKED**, now across three
  milestones.
- **The mobile-data leg of the Railway deploy is still unproven.** Not retried: every check
  above ran over Wi-Fi, which does reach the public internet, but "on mobile data" is a
  different claim and nothing here should say otherwise.

---

## 8. Test data left in the owner's account

**15 invented people** are now in the live Railway account from §5: 10 from
`messy-birthdays.csv` and 5 from the corrected file. They are listed in
`docs/fixtures/messy-birthdays.csv` and every one of them is made up.

They were left rather than deleted so the result can be looked at; soft-deleting them is a
tap each and leaves tombstones until M9's purge job exists. **Two real contacts** were also
imported, which is the feature working as intended on the owner's own data.

---

## 9. The honest summary

| # | Definition of done | State |
|---|---|---|
| 1 | A real contacts list imports selected contacts, relations set in bulk, only those exist after | **Pass**, on the device — 515 offered, 2 ticked, 2 imported, 22 people and 0 pending afterwards (§5.2) |
| 2 | Permission denied, and denied permanently, both leave a usable screen | **Pass**, both states on the device, cross-checked against `USER_FIXED`, plus the Settings route and the way back from it (§5.1) |
| 3 | A CSV with messy real dates imports with correct dates | **Pass**, on the device and in the database (§5.3, §5.6) |
| 4 | The dd/MM toggle changes the preview; an all-ambiguous file imports correctly both ways | **Pass** — the preview changing was watched on the device; the all-ambiguous file both ways is a test (§5.3) |
| 5 | The same file twice produces zero new people, proven by a test | **Pass** — the test runs the planner over the file three times; also watched on the device (§5.5) |
| 6 | A file with a duplicate inside it produces one person | **Pass**, test and device (§5.4) |
| 7 | Bad rows produce a report naming each row and reason, and a corrected CSV that re-imports cleanly | **Pass**, the whole round trip on the device including the saved file's bytes (§5.4) |
| 8 | An import larger than the sync cap reaches the server completely, outbox empty, then a hand-added person syncs too | **NOT DONE.** Passes as a test against a fake server enforcing the real 500-row cap and 2 MB body. **Never run against Railway**, because it puts 600 invented people in a real database and §10 is still unanswered |
| 9 | Unit tests for every date format, the toggle, the pivot, dedup within a file, dedup against existing, the chunking boundary | **Pass** — 211 client, 275 server, three timezones |
| 10 | CI green, this document, fixtures committed | Fixtures committed with a README; this document exists; CI on the PR |

**Nine of ten pass. Item 8 is not done**, and it is not blocked on code.

---

## 10. The one thing still needed

**Where may 600 invented people land?** `docs/fixtures/six-hundred-people.csv` exists and the
chunked push is tested against a fake server that enforces the real limits — but item 8 asks
for it to reach *the* server, and that means 600 people, 600 events and the reminders the
materialiser builds from them, in a real Railway database.

| | |
|---|---|
| **A second Google account** *(recommended)* | Real Railway, real hobby tier, and none of it touches the owner's data. Two other Google accounts are already signed in on this handset, so this costs one sign-out and one sign-in |
| The owner's account, then soft-delete all 600 | Honest and reversible, but leaves 600 tombstones until M9's purge job exists, and the delete is another 600 pending rows — which is, admittedly, a second test of the chunking |
| A local server | Clean, but proves nothing about Railway's hobby tier materialising 600 events, which the build prompt specifically raises |

Also still open, and not blocked on this: **a non-Pixel phone** for M2's item 7.
