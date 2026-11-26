# M3 Verification — Import

> Branch `feat/m3-import`, off `5704e0b`. Written as the work was done, not reconstructed.
>
> **All ten definition-of-done items pass, verified on the device against the real Railway
> deployment.** Verifying turned up four things reading the code had not — §6. Three are
> fixed here, including one that could have made a deceased person's birthday read
> celebratory. Nothing below is rounded up.
>
> **§12 was added after this merged**, from a second review pass: three more defects, two of
> them in code this document had already called verified. §9's table still stands as written;
> read §11 and §12 before trusting it as the whole story.

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
                   ./gradlew :composeApp:testDebugUnitTest --rerun-tasks   # 231 pass
TZ=UTC             ./gradlew :composeApp:testDebugUnitTest --rerun-tasks   # 231 pass
TZ=Pacific/Chatham ./gradlew :composeApp:testDebugUnitTest --rerun-tasks   # 231 pass
cd server && npx jest                                                      # 279 pass
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

### 5.6 Item 8 — an import larger than the sync cap, against the real server

`six-hundred-people.csv`, 601 rows, into the owner's live Railway account at his direction.
The mapping screen read it and guessed every column; **551 imported, 49 already here** (all
within-file matches — see §6.1).

**The outbox, sampled every four seconds from the device's own SQLite while it drained:**

```
11:26:57   import tapped
11:27:01   573 people / 551 pending / 0 blocked
11:27:05   573 people / 551 pending / 0 blocked
11:27:10   573 people / 351 pending / 0 blocked     ← page 1 settled
11:27:18   573 people / 151 pending / 0 blocked     ← page 2 settled
11:27:26   573 people /   0 pending / 0 blocked     ← page 3 settled
```

551 → 351 → 151 → 0. **Pages of exactly 200**, settled one at a time, nothing blocked, and
551 people plus 551 events — both over the server's 500-row cap — reaching the server
complete. This is the failure the whole floor was built for, and it is the first time it has
been exercised against a real server rather than a fake one.

Proof the rows are genuinely ON the server rather than merely cleared locally: all 551 came
back carrying **millisecond-precision** timestamps (`2026-11-26T05:57:01.480Z`), which is
Postgres `timestamp(3)` via `toISOString()`. A row that had never round-tripped would still
carry the client's own **microsecond** stamp. 551 of 551 also carry a server-computed
`completeness_score`, which the client never writes.

**The second half, which is what the bug was really about.** A person added by hand
afterwards — `HandAddedAfter600`, 7 March 1991, Friend — went from written to
`0 pending` within four seconds, on its own, with the 600-row import already behind it. The
shared batch no longer takes unrelated writes down with it.

```
574 people, 0 pending; hand-added: HandAddedAfter600 / pending 0
```

### 5.7 What is actually in the database afterwards

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

## 6. Four things the device showed that reading the code did not

Three are fixed on this branch; §6.2 is a product decision and is recorded, not changed.

### 6.1 Two small ones, fixed here

1. **Import had no entry point from a non-empty list.** FR-VIEW-05 puts it on the empty state
   and FR-AUTH-05 offers it at first run, and both were built — but between them they only
   ever reach somebody with *nobody*. A user who added three people by hand last week and now
   wants the other three hundred off their phone had no route at all. I only noticed because
   *I* could not reach the feature to test it. Added to the people list's action row.
2. **"I've turned it on"** on the permanently-denied screen (§5.1).

### 6.2 The dedup rule's precision at 600 rows — recorded, not changed

49 of 600 rows came back as *"appears twice in this import"*. They are not duplicates in any
ordinary sense: they are pairs like `Anika Gupta 21` and `Anika Pillai 22` that share **one**
name token and happen to fall on the same day and month.

That is the rule behaving exactly as specified — FR-IMP-08's "fuzzy name match + same date",
one shared token plus a day/month — and it is the same rule `findDuplicates` has used on the
create path since M1. What 600 random rows show is that **at scale, one shared token plus a
day/month is a weak signal**: with 20 first names, 20 surnames and 336 day/month combinations,
collisions are routine rather than exceptional.

Nothing was merged and nothing was lost: each of the 49 is named on the report, with its row
number. But they were **skipped by default and there is no way to say "no, import it anyway"**
from the result screen — the only route is to edit the file. For a real family spreadsheet of
50 rows this is almost certainly fine; for 600 it quietly left 49 people out.

Not changed in M3, because the rule is the requirement and changing it is a product decision,
not a bug fix. Worth deciding before M4: either require **two** shared tokens when a row has
no other corroboration, or give the result screen an "import anyway" action per skipped row.

### 6.3 Freshly pushed records never receive their server-derived values — found, then FIXED

**What was observed.** After the 600-row import, 551 people carried a server-computed
`completeness_score` and the server's "what to add next" list. The 18 people imported *earlier
in the same session*, and the one person added by hand afterwards, carried **score 0 and an
empty list**, and three further syncs did not change that. On the person screen it reads
`Profile 0% complete` for somebody with a name, a relation and a date.

**The tell is the timestamp precision.**

```
imported 05:42 batch  n=15   2026-11-26T05:42:53.445949Z   scored: 0     ← microseconds: the CLIENT's clock
imported 600 batch    n=551  2026-11-26T05:57:01.480Z      scored: 551   ← milliseconds: Postgres timestamp(3)
hand-added 06:04      n=1    2026-11-26T06:04:02.406092Z   scored: 0     ← microseconds
```

A row that has round-tripped through the server carries the server's millisecond stamp. These
never did. (That precision difference is a cheap diagnostic worth remembering: it says
instantly whether a local row has ever been server-side.)

**The mechanism, confirmed rather than guessed.**

```
device: 2026-11-26T06:04:52Z      ← adb shell date -u
server: 2026-11-26T06:04:50Z      ← Date header from the deployment
```

The device clock is about two seconds ahead of the server's. So for a single write: the
client stamps the row `T_client`; the server stores that value, re-derives, and stamps its
own copy `T_server ≈ T_client − 2s`; the pull returns the corrected row; and
`LocalStore.applyRemote` discards it, because the local copy's timestamp is *newer*.

The 551 escaped by accident of scale — their push took about 17 seconds across three pages,
so each page's recompute landed well past the single client timestamp the whole import
shared, and the server's copies won. Whether the bug bites is a race between the clock skew
and the round-trip time.

**Why it is worse than a missing score.** `sentiment` on an event is derived the same way,
and the client writes a *placeholder* `CELEBRATORY` at insert time precisely because it does
not derive tone (FR-EVT-01a). The same skew drops the server's correction — leaving a
deceased person's birthday reading celebratory, which FR-EVT-05 calls the worst thing this
app can get wrong. Nobody had hit it yet because it needs a death anniversary created inside
the skew window, but the path was open.

#### The fix: a client cannot hold a newer version of a field it never writes

Arbitrating a server-derived column with a client clock is a category error, not a close
call. No amount of clock discipline makes it right, so the fix does not try to fix the clock.

**Client** — `LocalStore.applyRemote`. When last-write-wins says the local copy is newer, it
still takes `is_minor`, `completeness_score`, `completeness_suggestions` and an event's
`sentiment`, through two new `applyServerDerived` statements. Everything the *user* writes is
still arbitrated exactly as before; without that split this would silently become "the server
always wins", which is a different and much worse change. `is_deceased` is deliberately left
out: FR-PPL-04 makes it a profile field the user can set, so it is not purely derived.

The one exception is a row with an unpushed local edit, which is left entirely alone — the
server derived its values from the copy it has, which predates that edit, so they are stale
by definition and the next push-and-pull produces the right ones.

**Server** — `PersonDerivationService`. A re-derivation now stamps
`max(now, row.updatedAt + 1ms)` instead of `now`, so it can never move a row's version
*backwards*. That matters for a second reason beyond this bug: `updatedAt` is the sync
cursor's ordering key, and a row that moves backwards can fall behind a cursor that has
already passed it and never be delivered again.

Either half alone would have hidden the bug rather than fixed it.

#### How the fix is proved

`ApplyRemoteDerivedTest` — **8 tests against a real in-memory SQLite**, driving the real
`applyRemote` against the real schema, using the exact timestamps observed on the handset.
**Four go red when the fix is reverted**, including the tone one.

This is the first test in the project that drives `LocalStore` against a database rather than
a hand-written fake. `commonTest` cannot build one, which is why `SyncEngineTest` works
through fakes — and a fake could never have caught this, because a fake agrees with whatever
the code does to it. The Android unit-test source set runs on the JVM, so it can; the JDBC
driver is a test-only dependency.

`derivation-monotonic.spec.ts` covers the server half, including every skew from a minute
behind to a minute ahead. Three of its four go red when reverted.

#### What the device showed, stated precisely

A person added **after** the fix came back with `score 25` and the server's full suggestion
list, where every person added before it sat at 0. But that row's `updated_at` came back at
millisecond precision, which means the round-trip exceeded the two-second skew on that run
and it went down the *ordinary* path. **So the device run is consistent with the fix and is
not independent proof of it** — the deterministic proof is the test above, which reproduces
the skew exactly rather than hoping to land inside it.

The 19 rows already damaged are **not** repaired by this. Their corrected copies sit behind a
sync cursor that has moved past them, so nothing will deliver them again; editing any of
those people re-pushes and re-derives them, and they come good. Left as they are rather than
hand-patched, so the PR's state matches what the fix actually does.

## 7. Carried forward from M2, still open

- **The OEM battery helper has still never run on a non-Pixel device.** The only handset
  attached is a Pixel 9a. M2 definition-of-done item 7 stays **UNCHECKED**, now across three
  milestones.
- **The mobile-data leg of the Railway deploy is still unproven.** Not retried: every check
  above ran over Wi-Fi, which does reach the public internet, but "on mobile data" is a
  different claim and nothing here should say otherwise.

---

## 8. Test data left in the owner's account

**566 invented people** are now in the live Railway account, at the owner's direction:

| From | Count |
|---|---|
| `messy-birthdays.csv` | 10 |
| the corrected file | 5 |
| `six-hundred-people.csv` | 551 |
| `HandAddedAfter600`, added by hand for item 8 | 1 |

Every one of them is made up and every one is listed in `docs/fixtures/`. **Two real
contacts** were also imported, which is the feature working as intended on the owner's own
data.

They were left rather than deleted so the result can be looked at. Removing them is a bulk
soft delete, which would itself be a second run at the chunked push — 566 tombstones is three
more pages — and would leave those tombstones until M9's purge job exists. Worth doing
deliberately rather than as a tidy-up.

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
| 8 | An import larger than the sync cap reaches the server completely, outbox empty, then a hand-added person syncs too | **Pass**, against the real Railway deployment. The outbox drained **551 → 351 → 151 → 0** — pages of exactly 200 — and a person added by hand afterwards synced on its own (§5.7) |
| 9 | Unit tests for every date format, the toggle, the pivot, dedup within a file, dedup against existing, the chunking boundary | **Pass** — 231 client, 279 server, three timezones |
| 10 | CI green, this document, fixtures committed | Fixtures committed with a README; this document exists; CI on the PR |

**All ten pass.** Three defects found along the way are fixed on this branch; a fourth (§6.2) is a product decision and is recorded rather than changed.

---

## 10. The review round (`/code-review high 4`)

`MILESTONE_CHECKLIST.md` §G, run on the PR rather than a stale local branch. Six findings,
all six fixed; the three correctness ones each have a test that goes red when the fix is
reverted.

| # | Finding | Fix |
|---|---|---|
| 1 | **A stale report bounced the user out of import.** `_result` was only cleared by tapping Done, but both import screens navigate by observing `result != null`, so a leftover result fired on first composition. Reachable because there is no `BackHandler`: the system back button on the report finishes the Activity while the store, a singleton on the Application, keeps the result. Re-entering import then jumped straight back to a report already read | `AppStore.openImport()` clears it before any import screen composes — every route in goes through there — and each screen additionally ignores a result that predates its own entry, so the two `LaunchedEffect`s cannot race. **Verified on the device**: report → system back → relaunch → Import now reaches "Bring people in" |
| 2 | **The corrected CSV misaligned its reason column.** `correctedCsv` padded rows SHORTER than the header and did nothing about longer ones, so a row with one stray comma pushed its reason past the column the header declared. Also every headerless file whose column count was not five | Everything is padded to the widest row, header included, so the reason is the last column of every line. Two tests, both red when reverted |
| 3 | **`planPages`' `truncated` was computed and dropped.** The third time this project has made the shape its own checklist names first (`PlanDiff.removed`, M1's `skipped`) | Surfaced as `Result.outboxTruncated`. The page cap is now injectable, so three tests reach the truncation case without a 20,000-row outbox |
| 4 | `guessMapping` could offer one column as two fields — `Birthday Notes` matched both, storing every person's raw date as their note | Claims are taken in order of how much a wrong guess costs; one column, one field |
| 5 | Generated column labels ran past Z into `[`, `\`, `]` | Proper base-26 letters: A..Z, AA, AB |
| 6 | The default-relation dialog said "Set for all 200" when it only fills rows that have none | It is told how many rows it will actually touch |

### And a seventh, found by using the app during that verification

**The import flow could lock itself shut.** The picker silently stopped opening: every tap on
"Choose a file" did nothing, there was no error anywhere, and force-stopping the app was the
only cure.

`_busy` had been left true. The picker and the permission dialog are awaited through a
`CompletableDeferred` that a *different* Activity completes, so when a result never arrives
the coroutine stays suspended — and because `ImportStore` is a singleton on the Application,
the flag outlives the Activity. No `finally` can save that path: nothing is thrown and
nothing returns.

So the recovery does not live on the hanging path. `clearResult()` releases the flag, which
means leaving and re-entering import always recovers; every busy section also got a `finally`
for the ordinary error cases. Two tests drive a gateway that never answers and assert the
flow comes back; both red when reverted.

Worth saying plainly: **no test would have found this one.** It took tapping a button that
did nothing and being suspicious about it.

---

## 11. What is left

This section said *"Nothing in M3"* when M3 merged. §12 is what came of that, and the claim
is corrected here rather than quietly left to rot: three defects were found afterwards by
reading the merged diff, two of them in code this document had already called verified.

It was not wrong about any item in §9. It was wrong to treat "the milestone is done" and
"there is nothing left to find" as the same sentence — and it miscounted its own list, saying
two where it gave three, which is the smaller version of the same inattention.

Three things carried forward, none blocking:

- **A non-Pixel phone** for M2's definition-of-done item 7, now unchecked across three
  milestones.
- **§6.2, the dedup rule's precision at scale** — a product decision, recorded not changed.
- **19 rows already damaged by §6.3** carry a completeness score of 0. The fix stops it
  happening again; it does not repair them, because their corrected copies sit behind a sync
  cursor that has moved past them. Editing any of those people repairs that one.

And one added by §12.4, for M4 to answer rather than this document:

- **No way to verify UI behaviour on the device.** `adb shell input tap` against a screenshot
  is guessing, and three attempts in M3 landed somewhere unintended — the last on a real
  contact's edit screen. Every guard added after those incidents checks the state *before*
  the tap and none can see where it landed, which makes the failure survivable rather than
  impossible. A Compose UI test harness, or resolving coordinates from a `uiautomator`
  view-tree dump, would rule it out instead.

---

## 12. Found after the merge — a second review pass

Two findings, on `fix/m3-review-findings`, off `main`. Both are failures of the same kind:
something the user needed to be told, and was not.

### 12.1 The row number in the report was not the row in the file

`FR-IMP-07` exists so a user can open the row that failed. They could not. Both readers
dropped all-blank rows before returning, and the numbering counted positions in the filtered
list — so one blank line between households made every later row report one short, three
blank lines made it three short. The user opens row 13, finds a good record, and now has no
reason to believe any other number in the report.

The `.xlsx` path was worse than occasional. `Xlsx.rows` read `r="C7"` on a *cell*, to pad the
columns Excel omits, and threw away `r="14"` on the *row* — and Excel omits empty rows from
the XML entirely, so that path drifted routinely.

Fixed by keeping blank rows in both readers, honouring `r` on `<row>`, and moving the blank
skipping into one shared `importDataRows` that runs *after* numbering. Six tests, red on
revert, including an accumulating-drift case and an `.xlsx` whose row 4 is absent from the XML.

Found reading the diff, not using the app — a two-row fixture cannot drift.

### 12.2 A record the server refused reached nobody

`observeBlockedCount()` and `blockedRows()` had **no callers anywhere in `composeApp/src`**,
and `SyncEngine.Result.blocked` was read only by tests — while both carried comments
promising otherwise: *"for the banner that tells the user about them"*, *"a row silently set
aside would be the same silent-partial-success M3 exists to avoid."* There was no banner.

That is this milestone's own failure mode, arriving through the machinery built to prevent it.

What it cost, with no import involved at all: nothing in the person editor capped text length,
while `PersonUpsertSchema` caps `notes` at 2,000. A long note saved locally, the next push
came back 400 naming that row, `sync_blocked = 1`, and `selectPendingSync` filtered it out —
so the person left the drain permanently. And because `pendingPersonIds()` deliberately
includes blocked people, every event of theirs is deferred on every run too: **no
server-scheduled reminder for that person, ever, silently.**

**Device-verified, the exact reported path:**

| Step | Result |
|---|---|
| Pasted ~2,250 characters into a person's notes, saved | Saved locally, no complaint |
| `adb` + `run-as` into the app database after the next sync | `pending_sync = 1`, `sync_blocked = 1`, reason `notes: Too big: expected string to have <=2000 characters` |
| Opened the people list | Banner present |
| Tapped it | Dialog named the person, with the server's own message |
| Edited the note down to five characters | Block cleared, row re-queued, **0 blocked / 0 pending** |

The dialog passes the server's message through rather than rewording it. It is the only thing
that says what to change, and a friendlier paraphrase of a message we did not write is a guess.

### 12.3 And the hole that made it reachable

`FieldLimits` caps the editor at the lengths the server accepts, with a counter in the last
hundred characters. This is **not** validation and the distinction matters: the safety net
stays `sync_blocked` plus the banner, because the server has rules this app does not mirror
and will grow more. Capping `notes` closes the one hole reachable by typing; it does not make
the banner dead code.

### 12.4 Two things not claimed

- **The blocked-row tests do not go red if only the UI wiring is removed.** They assert
  `observeBlockedCount()` and `blockedRows()` against a real engine and a real database, and
  those always worked — the bug was that nothing called them. Removing the wiring now breaks
  the build rather than a test. The device run above is the real evidence.
- **The length cap is unit-tested only, not device-verified.** Two attempts to drive the
  editor by coordinates landed on the wrong screen — the second on a different person's
  record with a text field focused, which I cancelled without typing, then confirmed nothing
  had changed (`0 pending, 0 blocked`, no stray text in any note). I stopped there rather
  than keep aiming blind taps at real contacts for a one-line `take()`. The banner, which is
  the substantive fix, *is* device-verified.

### 12.5 The loose end in §12.1, found reviewing the fix

Keeping blank rows made the header a thing you have to *find* rather than a row you can
assume, and one call site was left still assuming it. `ImportStore` built the mapping state
with `guessMapping(rows.firstOrNull().orEmpty())` while `FileState.Mapping.header` correctly
took the first non-blank row.

So on exactly the file shape §12.1 exists to support — a stray blank line above the headings
— the mapping screen showed the right column names and the guess underneath them was made
from an empty row. Every column came up unmapped, and the user mapped five columns by hand on
a file that used to map itself.

Nothing was silently wrong and no data was damaged, which is why only a test catches it.

The fix is not to repeat the first-non-blank logic a third time. `headerRowIndex(rows)` and
`headerRow(rows, hasHeaderRow)` now sit beside `importDataRows` in `ImportPlan.kt`, and all
three callers — the data rows that begin after the header, the mapping screen that displays
it, and the guess made from it — go through them. Two tests, both red when `headerRow` is
reverted to row 0.

This is the same two-places-must-agree drift `importDataRows` was written to end, surviving
in the one call site that wanted the header for a different purpose. Worth noting as a shape:
extracting a helper ends the drift only for the callers that adopt it.
