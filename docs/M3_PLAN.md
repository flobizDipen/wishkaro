# M3 Implementation Plan — Import

> Branch: **`feat/m3-import`**, off `5704e0b`. PR at the end; `main` stays merge-only.
> Written 2026-11-25, before any code, per `docs/MILESTONE_CHECKLIST.md` §B.
>
> Two decisions in `BUILD_PROMPT_M3.md` were flagged "decide this before you write code".
> Both are below with their reasoning and with what each one gives up. A third thing turned
> up while reading the code that I think is worse than either of them, and it is §3.
>
> **Nothing in this document has been built yet.** It is for Dipen to read first.

---

## 0. State of the world, as actually found

Checked today against the branch, not against the milestone table.

| Thing | State |
|---|---|
| `main` | `5704e0b`, clean, CI green. M2 merged at `0ed742c` |
| Device | `4B201JEBF03493` — **Pixel 9a**. The only phone attached |
| Its contacts | **515 contacts**. Six or seven carry a `contact_event` row at all: 4 typed birthday (**one of them with a NULL date**), 1 anniversary, 2 custom. So **3 contacts have a usable birthday** |
| Disk | **5.4 GB free** of 460 GB. M1 was blocked at 1.4 GB (`M1_PLAN.md` §1) and this is the same cliff |
| Non-Pixel phone | None. M2 definition-of-done item 7 stays **UNCHECKED** |
| Mobile-data leg | Not retried yet; the SIM problem in `M2_PLAN.md` §0.2 is unchanged |

Two consequences for the definition of done, both worth knowing before you read the rest:

1. **515 contacts, 3 usable birthdays** is the real shape of a real contacts list, and it is
   a more honest test than a seeded one — a birthday row with a NULL date is exactly the kind
   of half-filled record the prompt says M3 is the first milestone to meet, and it was there
   waiting without my inventing it. But it means definition-of-done item 1 is proved on three
   birthday-bearing contacts plus whatever dateless ones I select by hand, and items 3, 4, 6
   and 7 have to be proved from CSV fixtures, which they would be anyway. Item 8 (an import
   larger than the 500-row sync cap) **cannot** be proved from contacts on this device at
   all; it needs a committed 600-row fixture of invented people. See §9.1 — there is a
   question for you there.

   Worth noting for the reader: no `1604`/`1904` sentinel and no `--MM-DD` year-less row
   appears on this handset, though both are what Google Contacts writes elsewhere. Both are
   still handled and both are still tested — from fixtures, not from the device, and
   `M3_VERIFY.md` will say which claims came from which.
2. The disk needs about 10 GB free to build both targets comfortably. If it tightens further
   mid-milestone I will say so rather than quietly failing a build.

### What I read before planning

`BUILD_PROMPT_M3.md` in full, `REQUIREMENTS.md` §7.4 / FR-PPL-01..08 / FR-VIEW-05 /
FR-AUTH-05 / §9 API surface, `MILESTONE_CHECKLIST.md`, and the decision sections of
`M0_PLAN.md`, `M1_PLAN.md` and `M2_PLAN.md`. On the code side: `SyncEngine.kt`,
`SyncContracts.kt`, `LocalStore.kt`, `Person.sq`, `sync.schemas.ts`, `sync.service.ts`,
`people.service.ts`, `PeopleRepository.kt`, `Model.kt`, `BirthdayMath.kt`, `WishKaroApi.kt`,
`zod-validation.pipe.ts`, `http-exception.filter.ts`, `main.ts`, both CI workflows.

The prompt's "what already exists — do not rebuild it" section is accurate in every
particular. I found nothing in it to correct.

---

## 1. Decision 1 — the outbox cannot carry an import

### 1.1 What actually fails, and in what order

The prompt names the 500-row cap. Reading the path end to end, the cap is the **second**
thing that breaks, not the first, and the first one fails worse.

`SyncEngine.push()` reads `local.pendingPeople()` — `SELECT * FROM person WHERE
pending_sync = 1`, no LIMIT, no ORDER BY — and sends the lot in one `POST /v1/sync`. On the
far side:

| # | Limit | Where | What the client sees |
|---|---|---|---|
| 1 | **~100 KB request body** | `body-parser`'s default, never configured — `server/src/main.ts` calls `NestFactory.create` and sets no body limit | A `PayloadTooLargeError`. It is an `http-errors` error, not a Nest `HttpException`, so by inspection `AllExceptionsFilter` falls through to its `catch`-all branch and returns **500 INTERNAL**. Read from the code, not yet observed against the running server — step 2 of §8 confirms it before the fix claims to have fixed it |
| 2 | **500 rows per array** | `SyncPushSchema` `.max(500)`, `.strict()`, validated before anything is applied | **400 VALIDATION_FAILED**. The issue path is the array itself — `"people"` — because `.max()` is an array-level check, not a row-level one. A *row* failure gives `"people.37.displayName"`, which is what §1.6 leans on; the cap failure names no row and that is fine, because chunking means it cannot happen |

A person serialises to roughly 200–300 bytes with a name, relation and phone, so **the 100 KB
limit binds at around 350–500 people** — before the documented cap, and sometimes after it,
depending on how much `notes` the rows carry. Which one trips first is a function of the
user's data, and the two produce different symptoms:

- The 400 at least names the problem and names the row index.
- The 500 says `{"error":{"code":"INTERNAL","message":"Internal server error"}}` and nothing
  else. On the client that is indistinguishable from the server being broken.

Either way the outbox still holds the same rows, so it fails identically on every retry,
for ever — and because the batch is shared, the person added by hand afterwards is in it too.
The visible symptom is "the app stopped syncing", with no mention of import anywhere. This is
the same shape as the settings-blob failure that `SyncEngine` already carries a comment about,
and `MILESTONE_CHECKLIST.md` §C names it as its own line: *validation that runs before
anything is applied cannot let one bad field block unrelated data.*

I am recording the 500 because it changes the fix: a page size chosen only from the row cap
would still be wrong.

### 1.2 Decision: chunk client-side, leave the row cap alone, make the body limit explicit

Your recommendation, taken, with one addition.

- **`.max(500)` stays.** It is a protection and raising it moves the cliff rather than
  removing it.
- **The client drains the outbox in pages of 200**, each page a complete `POST /v1/sync`,
  each settled on its own response before the next page is sent.
- **The server's JSON body limit becomes explicit at 2 MB**, in `main.ts`, with a comment.
  This is not raising a cap — it is replacing an undocumented default that currently binds
  *below* the documented one. 500 rows × ~2.5 KB worst case (a 2,000-character `notes` field
  is the largest a `PersonUpsertSchema` row can legally be) is about 1.25 MB, so 2 MB makes
  the zod cap the binding constraint, which is what the cap is for. A request that exceeds
  the row cap then returns a 400 that names the row, instead of a 500 that names nothing.

**Why 200 and not 500.** Three reasons, in order of weight. A page of 200 people plus their
events is about 50–100 KB, which is a reasonable request on a hobby tier and on Indian mobile
data. A page that fails costs 200 rows of progress, not 500. And 200 is already this
codebase's page size in two other places — `SyncPullQuerySchema`'s default limit and
`DELIVERY_REPORT_BATCH` — so there is one number to remember rather than three.

### 1.3 What a page is, and why a person is never in a later page than their events

`upsertEvent` returns `UNKNOWN_PERSON` when the event's person is not on the server yet.
Within one request that cannot happen, because `push()` applies `batch.people` before
`batch.events`. Across pages it can, and naively chunking the three arrays independently
would produce it constantly: page 1 carries events 1–200, whose people are in people-page 2.

So pages are built from **units**, not rows. A unit is one of:

- a pending person together with every pending event and trait of theirs, or
- a pending event or trait whose person is **not** pending (an event added to someone the
  server already knows).

Units are filled into a page greedily and the page is flushed when adding the next unit would
take any one of its three arrays past 200. A person is therefore always in the same page as
their dependents, or in an earlier one — never a later one.

One rule falls out of the snapshot being bounded (below): **an event or trait whose person is
pending but is _not in this snapshot_ is not sent at all this run.** It waits. Sending it
would earn a guaranteed `UNKNOWN_PERSON`, which is handled correctly but is a wasted row in a
request and a row the user's data does not need to spend. This is exactly the
checklist's "reconciliation loop meeting rows that arrived by a route it was not written
for", so it gets its own test rather than a comment.

### 1.4 What "committing each page independently" means for `pending_sync`

Nothing about the per-row settlement changes. `settle()` already applies four outcomes —
applied → clear, `STALE` → re-stamp and hold, `UNKNOWN_PERSON` → hold unchanged,
`NOT_OWNED` → clear — and its reasoning (the long comment on `push()`) survives paging
untouched. What changes is only *when* it runs: once per page, against that page's ids and
that page's `serverTime`, before the next page is sent.

The guarantees that gives:

| | |
|---|---|
| Page 4 fails | Pages 1–3 are already cleared. They are not re-sent, not re-applied, and not undone |
| Page 4 fails | Pages 5+ are not sent this run (see §1.5 for the one exception) |
| The run fails | Whatever cleared, stays cleared. There is no all-or-nothing to roll back, and there was never a transaction spanning pages to begin with — `sync.service.ts push()` is not one either; it upserts row by row |

**The loop is bounded by a snapshot, not by "until empty".** At the start of a push the
engine reads the outbox once, with `ORDER BY updated_at, id LIMIT 10000` (`MAX_PUSH_PAGES`
50 × 200), and pages *that list*. It does not re-read the outbox between pages.

This matters more than it looks. A `STALE` row is re-stamped and **stays pending**, and an
`UNKNOWN_PERSON` row stays pending unchanged — so a loop that re-read "is the outbox empty
yet" after each page would never terminate on either of them. Snapshot-and-page terminates by
construction: every page is sent at most once per run, and rows still pending at the end
simply go on the next run, which is what they do today. The `LIMIT` also answers the thing
the prompt flagged — the query is no longer unbounded — and `ORDER BY updated_at, id` makes
the drain oldest-first and deterministic, so a 20,000-row outbox makes progress every run
instead of re-reading the same arbitrary 10,000.

### 1.5 A partial page the server accepted but whose response was lost

This already works, and chunking does not change it — but it happens more often with pages,
so it is written down and tested rather than assumed.

The rows stay pending with their original `updated_at`. On the next push the server finds
`existing.updatedAt >= updatedAt` (equal — it stored the client's own timestamp) and returns
**`STALE`**. The client re-stamps to `serverTime + 1 ms` and keeps them pending. The push
after that is strictly newer, so the server applies it and the rows clear.

So: **two extra rounds, no data loss, no duplicate.** No duplicate because ids are
client-generated (D23) and every write is an upsert on that id — replaying a batch is the
idempotency property `sync.service.ts` already documents. The cost is one redundant write and
one `updated_at` bump per affected row, which re-sends the row on the next pull. Harmless.

What makes this safe is the `STALE` rule M2 already built, for a different reason. Had
`STALE` cleared the flag, a lost response would be indistinguishable from a rejected edit.

### 1.6 The poison row — a 400 that is not about size

Chunking removes the *cap* 400. It does not remove every 400, and M3 is the milestone that
makes the others reachable, because a spreadsheet can carry a 400-character name and
`PersonUpsertSchema` allows 120.

Without something more, a single such row rejects its whole page of 200 and does so on every
retry for ever — the original bug, one page smaller. 200 unrelated rows is still "the app
stopped syncing".

The fix is cheap and exact, because the server already tells us which row. `ZodValidationPipe`
returns `details: [{ path: "people.37.displayName", message, code }]` and
`AllExceptionsFilter` passes `details` through. The client currently throws that away —
`ApiErrorDetail` has only `code` and `message`. So:

1. Carry `details` on `ApiErrorBody` / `ApiException` (three added fields, no behaviour
   change anywhere else).
2. On a `400 VALIDATION_FAILED`, map each `details[].path` back to the row at that index in
   the page, mark those rows **blocked** — a new `sync_blocked` column, additive — with the
   server's own message as the reason, and retry the page once without them.
3. A blocked row leaves the outbox, so it cannot block anything else, and is surfaced in the
   UI rather than being silently dropped. (The surface is small: a banner on the people list,
   "3 records could not be saved to the server", leading to a list with each row's reason.)
4. A 400 the client cannot attribute to a row — no `details`, or a path it cannot parse —
   blocks nothing and the page is left pending. Guessing would be worse than retrying.

**5xx and network failures abort the run.** They are not about the data; the next page will
fail the same way, and burning a battery and a hobby-tier request budget on 49 more pages
helps nobody. A 400 does not abort — the remaining pages are sent.

This is the part of the decision I would most want you to push back on if you are going to,
because it is the part that adds a column and a UI surface rather than just a loop. The
argument for it: without it, the only thing standing between a hostile spreadsheet and a
wedged outbox is the client validating rows against the server's rules before writing them —
and that is a second implementation of `sync.schemas.ts` in Kotlin, which is precisely the
duplication this project has twice decided not to have. Making one row isolable is the
structural fix; mirroring zod is the fragile one.

Import still validates rows, but for *import* reasons — no name, no parseable date, no
relation — and those produce a row in the FR-IMP-07 report, not a blocked sync row.

### 1.7 What this gives up

- **Up to 50× more requests per drain.** A 10,000-row outbox is 50 requests instead of one
  that fails. Accepted: the one request does not work.
- **A page boundary is a visible seam.** Between pages the local database holds some rows
  acknowledged and some not. Nothing reads that state today, but anything added later that
  does — a "sync complete" indicator, say — has to cope with "partly".
- **No transaction across pages.** If page 3 fails, the server holds pages 1–2 of an import
  and the phone knows it. The user's view of their own data is unaffected (reads are local),
  but a second device would see a partial import until the next run. One device per account
  in v1 (`M1_PLAN.md` §2.2), so this costs nothing today.
- **`sync_blocked` is a new state with a new failure mode of its own**: a row blocked by a
  server bug stays blocked until someone clears it. Mitigated by showing it, and by clearing
  the flag whenever the row is edited again.
- Making the body limit explicit is a server change in a milestone about the client.

### 1.8 Tests (checklist §D — the seam, not the pure function)

| Test | Why |
|---|---|
| Boundary at exactly 200, 199 and 201 units | The page-size boundary the prompt asks for, one either side |
| Boundary at exactly 500, 499, 501 **rows** | The server cap itself, through the fake, proving no single request ever carries more than 200 |
| 501 people + 501 events through the full engine with a fake API that enforces `.max(500)` and a 100 KB body | The actual reported failure, red before the fix |
| Page 4 of 6 throws a 5xx → pages 1–3 cleared, 4–6 still pending, run reports failure | Independent commit, and abort-on-5xx |
| Page 2 of 3 returns a 400 naming `people.17` → that row blocked with its reason, page retried without it, page 3 still sent | §1.6, the whole of it |
| A 400 with no `details` → nothing blocked, page left pending | The unattributable case |
| Page 2's response "lost" (throws after the server applied it) → next run's `STALE` re-stamp clears it in two rounds, no duplicate | §1.5 |
| An event whose person is pending but outside the snapshot is not sent; it is sent on the next run once the person has gone | §1.3, the route the loop was not written for |
| An import of 600 and then one hand-added person: the hand-added row reaches the server | Definition-of-done item 8's second half, as a test as well as on the device |
| `STALE` + `UNKNOWN_PERSON` rows do not make the drain loop | Termination |

All of them run through `SyncEngine` with the existing hand-written `SyncApi` / `SyncStore`
fakes. `SyncEngineTest` already has them.

---

## 2. Decision 2 — where files are parsed

### 2.1 The options, and what each actually costs

| | Client-only | Server converts format only | Server parses and interprets |
|---|---|---|---|
| CSV parser | Kotlin, ~120 lines | Kotlin anyway (the file is already rows) | `csv-parse` |
| XLSX parser | **The problem** | `exceljs` / SheetJS | same |
| Interpretation (mapping, dd/MM, pivot, dedup) | Kotlin, once | **Kotlin, once** | TypeScript, once — but dedup must then also exist in Kotlin for offline contacts import, so: twice |
| Works offline | Yes | No, for `.xlsx` | No |
| File leaves the phone | No | **Yes** | Yes |
| New server surface | None | multer, storage, a BullMQ job, `GET /v1/import/:jobId`, polling | all of that, plus relations and dates |

The third column is out on the duplication argument alone: it puts the dd/MM toggle and the
dedup rule in TypeScript while contacts import still needs both in Kotlin, on-device, offline.
That is the trap the prompt names, and `M2_PLAN.md` §1.3 already refused the same trade once.

So the real choice is between the first two, and it is a narrow one. The second column is
genuinely attractive: it is the one that makes `.xlsx` cheap.

### 2.2 Decision: client-only, with the hostile part pure and in `commonMain`

**`.csv` and `.xlsx` are both parsed on the phone. The server endpoint is not built in M3
and comes out of the documented API surface.**

Two reasons, and the second is the one that decides it.

**Reason one — XLSX is less frightening than it looks, if it is split correctly.** An `.xlsx`
is a zip of XML. The part that needs a platform is opening the zip; the part that is hostile
is everything after. So:

- `commonMain`, pure, no I/O: `xl/worksheets/sheet1.xml` + `xl/sharedStrings.xml` +
  `xl/styles.xml` as **strings** in, a `List<List<String>>` of cell text out. Shared-string
  indirection, inline strings, empty-cell skipping (`r="C7"` jumps columns), the 1900 date
  system and its leap-year bug, and the `numFmt` lookup that decides whether `45292` is a
  date or a number — all of it pure, all of it tested in `commonTest` against committed
  fixtures.
- `androidMain`, ~30 lines: `ZipInputStream` over the three entries. iOS gets the actual
  that every other platform seam in this project gets — a stub that compiles and reports
  unsupported (D15, iOS is compile-only).

Everything that can be wrong about a spreadsheet is in the tested half. The untested half is
"read three entries from a zip", which fails loudly or not at all.

**Reason two — a file of family birthdays is the second most sensitive thing this app
touches, and not uploading it is strictly better.** The engineering rules in this prompt go
out of their way about contacts: not in a log line, not in an analytics event, not in a crash
report. A spreadsheet of the same people is the same data in a different wrapper. Uploading
it means it exists in a server process, in multer's temp directory, in a BullMQ payload in
Redis, and in whatever Railway logs on the way. Every one of those is a thing to get right
for a feature whose entire purpose is to avoid typing.

And the third reason, which is not decisive but points the same way: offline. Import is the
onboarding path (FR-AUTH-05). "Add your first 3 people" failing because the user is on a
train is a bad first five minutes.

### 2.3 What this gives up — and the REQUIREMENTS change it needs

- **FR-IMP-04's "large files go to the server" is not implemented.** Parsing happens in
  memory on the phone. I will cap it explicitly — **5,000 rows or 10 MB**, whichever comes
  first — and say so on screen with the file's actual size, rather than letting a 200 MB
  export OOM the app. A user with more than 5,000 birthdays is not a user this app has.
- **`POST /v1/people/import/file` and `GET /v1/import/:jobId` do not exist.** Per the
  prompt, rather than leave two documented endpoints that are fiction, I will **remove both
  from `REQUIREMENTS.md` §9** and amend FR-IMP-04 to say what is true: *parsed on-device;
  files above the stated limit are rejected with a message, and a server-side path is
  deferred until a real file exceeds it.* This is a requirements edit, so it is here for you
  rather than in a commit you find later.
- **One XLSX edge case I am not promising:** a workbook with the dates stored under a custom
  `numFmt` the heuristic does not recognise. The mapping preview is the backstop — a date
  column showing `45292` instead of `04/01/2024` is visible in five rows before anything is
  applied, which is what FR-IMP-05's preview is for.
- **`.xls`** (the old binary format) is not supported and will be rejected by name. It is not
  in FR-IMP-04 either.

---

## 3. The thing I found, which I think is worse than either decision above

`MILESTONE_CHECKLIST.md` §C: *a rule implemented twice in two languages is held to one shared
table of cases* — bought by M3, name tokens, Kotlin + TS. The prompt asks me to build that
table. Building it found that **both implementations are broken, in different ways, and they
disagree on every Indian script including the owner's own.**

```
Kotlin      PeopleRepository.kt:165   name.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
TypeScript  people.service.ts:570     name.toLowerCase().split(/[^a-z0-9ऀ-ॿ]+/)
```

Run, not reasoned about — the Kotlin rule through `java.util.regex` on JDK 21, the TypeScript
rule through `node`:

| Name | Kotlin (client) | TypeScript (server) |
|---|---|---|
| `Jansari Jaydatt` | `jansari`, `jaydatt` | `jansari`, `jaydatt` |
| `जयदत्त जांसरी` (Devanagari) | **`जयदत`, `सर`** | `जयदत्त`, `जांसरी` |
| `જયદત્ત જાંસરી` (**Gujarati**) | **`જયદત`, `સર`** | **none at all** |
| `José Álvarez` | `josé`, `álvarez` | **`jos`, `lvarez`** |
| `Zoë` | `zoë` | **`zo`** |

Two separate bugs:

**The client shatters every Indic name.** `\p{L}` is Unicode letters but **not** combining
marks — those are `\p{M}` — and Devanagari and Gujarati build words out of them: the virama
`्`, the vowel signs `ा ी ं`. Each one is therefore treated as a *separator*. `जयदत्त`
splits into `जयदत` + `त`, and the `त` is dropped by the two-character filter. `जांसरी`
becomes `ज` (dropped) + `सर` + `ी` (dropped), so a surname reduces to the fragment `सर`.
Matching then happens between fragments, which is neither the rule as documented nor a rule
anyone would choose.

**The server drops every non-Devanagari Indic script entirely.** Its class is ASCII plus
U+0900–U+097F. A Gujarati name produces an empty token set, and `findDuplicates` returns `[]`
when the set is empty — so on the server, **a Gujarati, Tamil, Telugu, Bengali, Kannada,
Malayalam, Punjabi or Odia name has no duplicate detection at all.** It reports "no
duplicates" for every such person, in an India-first app, silently.

The accented-Latin rows fail a third way: the two sides produce *different* tokens, so client
and server can disagree about whether two records are the same human.

**The fix is one character class on each side, verified to produce identical output on all
seven cases above:**

```
[^\p{L}\p{N}\p{M}]+        // Kotlin
/[^\p{L}\p{N}\p{M}]+/u     // TypeScript — the u flag is required for \p{…} at all
```

Both then give `જયદત્ત`, `જાંસરી`, `जयदत्त`, `जांसरी`, `josé`, `álvarez` — whole words, same
words. It is a widening on both sides, so nothing that matches today stops matching.

This is pre-existing (M1 code, not M3) and per checklist §F it is recorded here rather than
quietly fixed. But FR-IMP-08 makes idempotency depend on the two agreeing, so I intend to fix
it inside M3:

- One shared table of cases, committed as `docs/fixtures/name-tokens.json`, asserted by a
  Kotlin test and a Jest test reading **the same file**. `Jansari Jaydatt` /
  `Jaydatt Jansari` as the prompt names, every script in the table above, hyphens,
  apostrophes, initials, `Dr.` prefixes, double spaces, and the empty-token case.
- A known limit, written down rather than fixed: `José` and `Jose` still do not match, because
  stripping diacritics would mean stripping `\p{M}` — which is exactly what breaks Indic
  names. Diacritic-insensitive Latin matching would need per-script normalisation, and that
  is not worth it for an India-first app.
- A caveat on the Kotlin side: CI runs `compileKotlinIosSimulatorArm64` but no iOS *tests*,
  so Kotlin/Native's own regex engine is never exercised against this table. The table is
  therefore proved on JVM and on Node, and iOS is unproven — recorded in `M3_VERIFY.md` as
  such, not rounded up.

Tell me if you would rather have this as its own PR before M3. I think it belongs in M3 —
FR-IMP-08 is built on it, and it is six lines — but it is a behaviour change in merged code
and that is your call.

### 3.1 Dedup cost

`PeopleRepository.findDuplicates` calls `allPeopleOnce()` — two full table scans and a full
object mapping — and re-tokenises every existing name, per call. Called once per imported row,
600 rows against 400 existing people is 1,200 table scans and 240,000 tokenisations. I will
measure it before claiming a number, and report the measurement in `M3_VERIFY.md`.

The fix is not a cleverer algorithm, it is calling it once: an **index built once per import
run** — `token -> person ids` and `(day, month) -> person ids` from a single `allPeopleOnce()`
— and queried per row. Rows accepted earlier in the same run are added to the index as they
go, which makes **within-file duplicates the same code path as against-existing duplicates**
(definition-of-done item 6 and item 5, one mechanism). `findDuplicates` itself is left alone
for the create path, where one call is one call.

---

## 4. Three contradictions I am not resolving silently

### 4.1 FR-PPL-01 says relation is required; the code says optional; FR-IMP-03 says import requires it

| Source | Says |
|---|---|
| `FR-PPL-01` | "Adding a person requires exactly three inputs: **name**, **event date**, **relation**" |
| `AddPersonScreen.kt:56`, `PersonUpsertSchema` | `relation: String?`, nullable, optional. Shipped in M1 |
| `FR-IMP-03` | "Each imported row needs a relation before it saves" |

**Chosen: import is stricter than manual add, and FR-PPL-01's wording is corrected to match
the code.** Not the other way round, for two reasons. Making manual add require a relation is
a behaviour change to a shipped screen that nothing in M3 asks for. And relation is already
handled as a *nudge* rather than a gate — it is the first entry in the completeness
suggestions (`CompletenessField.RELATION`), which is the mechanism M1 chose for "we would
like this but will not block you on it".

Bulk is different, and your prompt says why: bulk is where unlabelled people pile up. So:

- **Contacts review screen:** a selected row with no relation blocks the Import button, which
  says how many rows are missing one and jumps to the first. The bulk "set relation for
  selected" (FR-IMP-03) is how you clear 200 of them in two taps.
- **File import:** the mapping screen carries a **"relation for rows that have none"** picker
  alongside the column mapping. A file with no relation column therefore still imports in one
  extra tap, instead of producing 300 failed rows. A row with no relation and no default set
  is a *failed* row with reason "no relation", which lands in the corrected CSV with a
  Relation column for the user to fill in — and re-importing it then hits the idempotency
  path. That is FR-IMP-07 and FR-IMP-08 behaving as one feature, which the prompt says they
  are.

The `REQUIREMENTS.md` edit: FR-PPL-01 becomes "requires **name** and **event date**; relation
is strongly encouraged and is the first completeness prompt, and **import requires it**
(FR-IMP-03)".

### 4.2 A selected contact with no birthday

FR-IMP-02 requires contacts without a birthday to be listed and unselected. It does not say
what happens if the user selects one — and on this device that is 509 of 515 contacts, so it
is not a corner.

**Chosen: a date is required before the row can be selected.** The row shows "Add date",
tapping it opens the date entry inline, and until then the checkbox is inert rather than
hidden. The reason: a person with no event is invisible to every surface this app has. The
list sorts by next occurrence, the countdown is null, the reminder engine has nothing to
materialise. Importing 300 of them would report "Imported 300" over a database that shows
nothing, which is precisely the silent-partial-success failure the prompt calls the thing
most likely to go wrong.

### 4.3 FR-IMP-04's "where possible" and "large files"

Covered in §2.3. Resolved by amending the requirement rather than by reading "where possible"
loosely.

Nothing else in `REQUIREMENTS.md` conflicts with this prompt as far as I can tell. The
remaining FR-IMP rows, FR-PPL-06, FR-VIEW-05 and FR-AUTH-05 are all buildable as written.

---

## 5. Partial or all-or-nothing

The prompt asks for this in writing. **Partial, with a report — but with no partial
_transaction_.**

Precisely: every row is classified before anything is written. The rows that pass are written
in **one SQLDelight transaction**; the rows that do not were never attempted. So the database
is never half-written, and the outbox goes from 0 to N pending atomically — no interleaved
sync can observe half an import.

All-or-nothing over the *file* was the alternative and it is worse. One unparseable date out
of 300 would throw away 299 good rows and ask a confused person at 11pm to fix the file and
do it again — when the corrected-CSV path (FR-IMP-07) exists precisely so they can fix the 1
and re-run it against an idempotent importer. Rejecting the whole file would make that path
pointless.

What the user sees afterwards is a count of imported / skipped / failed with a per-row reason
for every row that is not in the first bucket, and that screen is reachable again from
Settings rather than being a transient toast — a report you cannot get back to is a report
that did not exist.

---

## 6. Date interpretation — the rules, stated

All pure functions in `commonMain`, all tested against a table, no UI. Step 3 of the build
order, before any screen exists.

| Rule | Decision |
|---|---|
| Formats | `dd/MM/yyyy`, `dd-MM-yy`, `MM/dd/yyyy`, `d MMM yyyy`, `yyyy-MM-dd`, day-month with no year — plus `d MMMM yyyy`, `yyyy/MM/dd` and `.`/space separators, because they cost nothing once the tokeniser exists |
| dd/MM vs MM/dd | **Explicit toggle, default dd/MM.** Never inferred from the data, per the prompt. The toggle changes the preview live, which is how the user sees they got it wrong |
| Unambiguous rows ignore the toggle | `25/12/1990` is 25 December under both settings, because 25 is not a month. Only genuinely ambiguous rows follow it |
| Two-digit years | **A birth or anniversary date cannot be in the future.** `yy` → `2000+yy` if that is ≤ the current year, else `1900+yy`. So in 2026: `65` → 1965, `10` → 2010, `26` → 2026, `27` → 1927. The rule, and this reasoning, go in a comment on the function |
| Four-digit years are left alone | A user who genuinely typed 2027 gets 2027. The pivot applies to two-digit years only |
| Year below 1900 | Treated as **year unknown**, not as an error. `PersonUpsertSchema` requires 1900–2200, and 1604 / 1904 are the sentinels Android and Mac exporters use for "no year" (the prompt names both). `event.year` is already nullable and M1 built the paths (`M1_PLAN.md`) — no second representation |
| Impossible dates | `31/04`, `29/02/2023` → failed row with a reason. `BirthdayMath.isRealDayMonth` and the server's `assertValidSpec` already agree on this, including 29 Feb with no year, which is a real birthday and survives |
| Excel serial numbers | `45292` → 2024-01-04 via the 1900 system, including its deliberate 1900-02-29 bug. Only when the cell's `numFmt` says it is a date; a bare number in a date column is a failed row, not a guess |
| Timezone | None of it touches one. A birthday is a day and a month, not an instant — the only place a zone enters is `BirthdayMath.nextOccurrence`, which already has it. Suites still run under `TZ=UTC` and `TZ=Pacific/Chatham` per checklist §D |

---

## 7. Schema changes — all additive

No column changes type, nullability or name. Real rows exist on Railway and on the phone.

**Client** (SQLDelight migration `5.sqm`; current schema version is 5, snapshots `1.db`–`5.db`):

- `person`, `event`, `person_trait`: `sync_blocked INTEGER NOT NULL DEFAULT 0` and
  `sync_block_reason TEXT` — §1.6. **Appended at the end of each table**, because
  `verifyMigrations` compares ordinal positions and `ALTER TABLE` can only append. The
  comment at the top of `Person.sq` says this already; it is repeated here so it is not
  rediscovered.
- `selectPendingSync` on all three gains `AND sync_blocked = 0`, `ORDER BY updated_at, id`
  and `LIMIT ?` — §1.4.
- One new query per table for blocking and unblocking a row, and one for listing blocked rows
  for the banner.
- No table for import state. An import run is finished when its transaction commits; the
  report is held in memory and in the app store, not in SQLite. If you want the report to
  survive a force-stop, say so — it is a table, and it is not in the prompt.

**Server**: no migration. `main.ts` gains an explicit body limit (§1.2) and
`people.service.ts` gains one corrected character class (§3). That is the whole server diff,
unless you ask for the import endpoint.

---

## 8. Implementation order

The prompt's order, which is the right one — the floor first.

| # | Slice | Commit |
|---|---|---|
| 1 | This document | `docs: M3 plan — the outbox decision and where files are parsed` |
| 2 | Body limit explicit; `details` carried on `ApiException` | `fix(server): make the JSON body limit explicit instead of inheriting 100 KB` |
| 3 | `sync_blocked` migration + queries | `feat(client): a sync-blocked flag, so one rejected row cannot wedge the outbox` |
| 4 | **Chunked push**, with every test in §1.8 | `fix(sync): drain the outbox in pages of 200, settling each page on its own response` |
| 5 | Name tokens: `\p{M}` on both sides, shared fixture, both suites | `fix: name tokens shattered Indic names on the client and dropped them on the server` |
| 6 | Date parsing + dd/MM toggle + pivot, pure, full table | `feat(import): date interpretation for the formats people actually type` |
| 7 | CSV reader (RFC 4180: quotes, embedded newlines, BOM, CRLF, `;`) | `feat(import): a CSV reader that survives real exports` |
| 8 | XLSX pure core + `commonTest` fixtures; Android zip actual | `feat(import): read .xlsx without leaving the phone` |
| 9 | Dedup index + idempotency, pure, against a fake store | `feat(import): dedup within a file and against existing people, in one index` |
| 10 | Contacts: permission (incl. denied-permanently), read, review screen, bulk relation, write | `feat(import): contacts import` |
| 11 | File import: picker, column mapping, live 5-row preview, apply | `feat(import): file import with column mapping` |
| 12 | Result report + corrected-CSV save | `feat(import): the result report, and failures you can fix and re-run` |
| 13 | Onboarding hook + people-list empty state | `feat(import): offer import at first run and from the empty list` |
| 14 | `docs/M3_VERIFY.md`, fixtures, `REQUIREMENTS.md` edits | `docs: M3 verification` |

Steps 6–9 produce no UI at all, per the prompt. Steps 2–5 are the floor and I would like them
reviewable on their own even though they land in one PR.

**Platform details worth stating now**, so they are not surprises in review: the file picker
is `ACTION_OPEN_DOCUMENT` (not `GET_CONTENT` — scoped storage, and a persistable URI); the
corrected CSV is written with `ACTION_CREATE_DOCUMENT`, so the user picks where it goes and
no `FileProvider` is needed; "denied permanently" is detected as *asked before* **and**
`shouldShowRequestPermissionRationale == false` **and** not granted, which needs a persisted
"we have asked" flag because that API returns false before the first ask too; contacts are
read through a `ContactsGateway` interface in `commonMain` shaped like the existing
`NotificationGateway`, with the Android actual over `ContentResolver` and an iOS stub. Contact
photo URIs are read for the review screen and discarded — nothing reaches `photo_key`, which
stays reserved for M6. Any Koin change is covered by `ModulesTest` (checklist §D).

---

## 9. What I need from you

### 9.1 Where the 600-row import for definition-of-done item 8 is allowed to land

Item 8 needs an import larger than the 500 cap to reach the server completely. The fixture is
600 invented people, committed under `docs/fixtures/`. The question is which account it goes
into, because they will be real rows in a real database afterwards.

| | |
|---|---|
| **A second Google account on the phone** *(my recommendation)* | Real Railway, real hobby tier, real materialiser — and none of it touches your data. `ALLOW_DEV_LOGIN` is double-gated off in production, so a second real sign-in is the only way to get a throwaway account on the deployed server |
| Your account, then soft-delete all 600 | Honest and reversible, but leaves 600 tombstones in your data until M9's purge job exists, and the delete is another 600 pending rows |
| A local server | Clean, but proves nothing about Railway's hobby tier materialising 600 events, which the prompt specifically raises |

Do you have a second Google account I can sign in with on `4B201JEBF03493`?

### 9.2 Three things to confirm or refuse

1. **§1.6 — the `sync_blocked` column and its banner.** The part of decision 1 with the most
   surface. Say if you want chunking alone and the poison row left for later.
2. **§3 — fixing the name-token rule inside M3**, rather than as its own PR first. Note that
   this is not only a divergence: duplicate detection is currently broken for Gujarati and
   Devanagari names on both sides, and for every other Indic script on the server.
3. **The two `REQUIREMENTS.md` edits**: removing `POST /v1/people/import/file` and
   `GET /v1/import/:jobId` from §9 and amending FR-IMP-04 (§2.3), and amending FR-PPL-01 on
   relation (§4.1).

### 9.3 Two things already decided unless you say otherwise

- The 5,000-row / 10 MB file cap (§2.3).
- The import report is not persisted across a force-stop (§7).

### 9.4 Carried forward, honestly

- **The OEM battery helper has still never run on a non-Pixel device.** No such phone is
  attached. It stays UNCHECKED in `M3_VERIFY.md` unless one appears.
- **The mobile-data leg of the Railway deploy is still unproven.** I will retry the SIM once
  before the PR and record whichever way it goes.
- **The M0 offline-persistence check** (aeroplane mode → add → force-stop → reopen →
  reconnect → sync) has never been run on a device, through M0, M1 and M2. M3 makes it more
  interesting, because an import done offline is the largest outbox this app will ever hold.
  I will run it as part of item 8 and record it, but it is not one of M3's ten items and I am
  not claiming it as one.

---

## 10. Explicitly not in M3

Calendar, timeline, widget (M4). AI, wish drafting, WhatsApp (M5). Posters and photo upload,
including contact photos, which are displayed and discarded (M6). Billing (M7). Gifts (M8).
Export and account deletion (M9). Google Sheets live sync and the Google People API (v2 by
decision). Family tree (v2).

**Merging two existing people is not in M3.** Import surfaces a possible duplicate and lets
the user skip the row; it does not combine records. Two sets of events, traits, reminders and
delivery history is a decision that does not belong inside an import screen.

And `.xls`, per §2.3.
