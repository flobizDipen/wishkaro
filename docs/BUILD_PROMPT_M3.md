# M3 Build Prompt — Import

> Paste the block below into a **fresh Claude Code session** opened at the repo root.
>
> M2 is merged (`0ed742c`). `main` carries a deployed server on Railway, people and events
> CRUD, offline-first sync, and a reminder engine with a local alarm fallback. M3 is the first
> milestone where this app meets data it did not author: a contacts list full of half-filled
> records, and spreadsheets people have been maintaining by hand for years.

---

## The prompt

```text
You are building M3 of WishKaro, a Compose Multiplatform + NestJS app. M0, M1 and M2 are
merged to main and working: Google sign-in, people and events CRUD, deceased/solemn tone
handling, offline-first sync, a reminder ladder with local alarm fallback, quiet hours, a
digest, and delivery reporting. Read docs/REQUIREMENTS.md for product context, and
docs/M0_PLAN.md, docs/M1_PLAN.md and docs/M2_PLAN.md for the decisions those milestones
locked in. Build ONLY what this prompt specifies.

Read docs/MILESTONE_CHECKLIST.md as well, and work to it. It is the standing process for this
project, and every item in it was bought by a specific bug in M0, M1 or M2 — the milestone
that paid for each one is named beside it. Section E in particular is a procedure, not advice:
it exists because it was skipped once and a blind tap sequence went into the wrong app.

## What M3 is

Getting a user's existing people INTO the app without them typing. When M3 is done, this must
be true on a REAL Android phone:

  A user with 300 contacts and a spreadsheet of family birthdays ends up with the right
  people in WishKaro, once
    -> they choose who comes in; nothing arrives without being chosen
    -> the dates survive whatever format they were written in
    -> running the same import twice changes nothing the second time
    -> and when rows fail, the user is told which rows and why, in a file they can fix

Import is the first feature whose input is hostile. Every other milestone so far operated on
data this app created, in shapes this app chose. M3 does not get that. The deliverable is not
"an import screen" — it is an import that can be run twice by a confused person at 11pm and
leave the database correct.

## What already exists — do not rebuild it

Read these before planning. Several things M3 appears to need are already in place:

  - Duplicate detection already exists on BOTH sides, with the same rule.
    `people.service.ts findDuplicates` and `PeopleRepository.findDuplicates` both use
    normalised name tokens plus a day/month match. The client's version runs entirely against
    local SQLDelight — no network — which is what makes offline import dedup possible at all.
    FR-IMP-08 is this rule applied per row, not a new algorithm.
  - Year-unknown dates are already modelled and handled end to end. `event.year` is nullable
    and M1 built the "age unknown" paths. Android's contact birthday field very often carries
    no year, or carries 1604/1904 as a sentinel. Do not invent a second representation.
  - The relation chip list is already curated for Indian context — `Model.kt`, the RELATIONS
    list — and `RelationPicker` already renders it. FR-IMP-03's bulk "set relation for
    selected" is a new interaction over an existing component and an existing vocabulary.
  - The people list already has an empty state (`PeopleListScreen.EmptyState`). FR-VIEW-05
    wants it to lead to import; it currently leads nowhere. That is a small edit, not a
    screen.
  - Offline-first create is already the only way people are written. The client writes to
    SQLDelight, marks `pending_sync = 1`, and the sync engine drains the outbox. Import must
    use this path, not a new one. In particular do NOT add a direct POST-per-person path.
  - `person.photo_key` exists and is RESERVED. Photo upload is M6. FR-IMP-01 mentions reading
    the contact photo URI; in M3 you may read it to show a face on the review screen, but
    nothing is persisted into `photo_key` and nothing is uploaded.
  - BullMQ and Redis are already wired on the server (the reminder scheduler uses them), so if
    you decide file parsing goes server-side, the job infrastructure exists. Multer, an XLSX
    parser and any file storage do not.

## Decide this before you write code

**1. The outbox cannot carry an import, and this is the blocker.**

`Person.sq` has `pendingPeople: SELECT * FROM person WHERE pending_sync = 1` with no LIMIT.
`SyncEngine.push()` sends every pending row in ONE request. The server's `SyncPushSchema`
caps each array at `.max(500)` and the schema is `.strict()`, so validation runs and the WHOLE
batch is rejected before anything is applied.

So a user who imports 501 people produces a push that 400s. It will 400 on every retry, for
ever, because the outbox still holds the same 501 rows. And the batch is shared: the person
they added by hand afterwards is in it too, so the visible symptom is "the app stopped
syncing", with no mention of import anywhere.

SyncEngine already carries a comment about exactly this class of failure — settings were moved
into their own request because one bad blob took the user's data down with it, twice, during
M2's device verification. This is the same failure with a different trigger, and import is the
feature that makes it reachable.

Note the size that does NOT fail is also worth thinking about: 300 contacts is 300 people plus
300 events, both under the cap, one transaction, and then the materialiser has to build
occurrences and reminders for 300 events on a Railway hobby tier.

My recommendation is to chunk the push client-side and leave the server cap alone — the cap is
a protection, not a bug, and raising it just moves the cliff. That means draining the outbox in
bounded pages until it is empty, committing each page independently so a failure in page four
does not undo pages one to three. Work out what "committing independently" means for the
`pending_sync` flag and for a partial page the server accepted but the response was lost, write
it in docs/M3_PLAN.md, and show me before building on it.

**2. Where files are parsed — on the phone, on the server, or split.**

FR-IMP-04 says "parsed client-side where possible; large files go to the server", and the API
surface in REQUIREMENTS.md already lists `POST /v1/people/import/file` and
`GET /v1/import/:jobId`. But nothing for either path exists today: no file picker in the
client, no XLSX library in Kotlin Multiplatform, no multer or storage on the server.

The trap is building both. Column mapping, the dd/MM vs MM/dd toggle and the date-format
tolerance in FR-IMP-06 are INTERPRETATION, and two implementations of interpretation in two
languages is the thing this project has twice decided not to have — it is the reason the
reminder copy is server-rendered and the reason the dedupe key is derived rather than
duplicated.

If you need a third option, consider: the server converts FORMAT only (xlsx -> rows of
strings) and the client decides what those strings MEAN. One implementation of the semantics,
one place to test them, and the server never needs to know what a relation is.

Pick one, say what you are giving up, write it in docs/M3_PLAN.md, and show me. If you pick
client-only and defer the server endpoint, say so explicitly and update the API surface in
REQUIREMENTS.md rather than leaving a documented endpoint that does not exist.

**3. Dedup cost, and the two implementations of the name rule.**

`PeopleRepository.findDuplicates` loads ALL people and tokenises every name, per call. Called
once per imported row, a 500-row import against 400 existing people is 200,000 tokenisations.
Measure it before you optimise it, but do not call it per row without looking.

Separately: the Kotlin and TypeScript token rules are two implementations of one rule, and the
Kotlin one carries a comment saying it mirrors the other. FR-IMP-08 makes idempotency depend
on them agreeing. Build a shared table of cases and test both against it — including the case
the comment names, "Jansari Jaydatt" versus "Jaydatt Jansari".

## Also still carried over

  - **The OEM battery helper has never run on a non-Pixel device.** M2 definition-of-done item
    7 is UNCHECKED (`M2_VERIFY.md` §8), not passed. If a Xiaomi, Samsung, Oppo, Vivo or Realme
    device is available while you work, close it out and record it. If not, leave it recorded
    as unproven. Do not quietly mark it done.
  - **The mobile-data leg of the Railway deploy is still unproven** — the SIM would not
    register (`M2_PLAN.md` §0.2). Same rule: close it or leave it honest.
  - **FR-PPL-01 and the code disagree about relation.** The requirement says a person needs
    name, date AND relation; `AddPersonScreen` treats relation as optional (`String?`) and the
    schema allows null. FR-IMP-03 then says every imported row needs one. Decide whether import
    is stricter than manual add — which is defensible, since bulk is where unlabelled people
    pile up — or whether the requirement should be corrected. Either way, resolve it in writing
    rather than inheriting the ambiguity into a bulk path.

## Scope

### Contacts import (FR-IMP-01, FR-IMP-02, FR-IMP-03)

  - READ_CONTACTS as a runtime permission, with a rationale before the system dialog and a
    working path when it is denied — including "denied permanently", which on Android means
    the dialog never appears again and the only route is Settings.
  - Reads display name, phone, birthday, and photo URI for display only.
  - Review screen: contacts WITH a birthday pre-selected and sorted first; contacts without
    one listed but unselected. Nothing is written until the user confirms.
  - Bulk "set relation for selected". Without it the flow is unusable past about ten rows.
  - Entirely on-device. Only selected records are persisted or synced. A contacts list is the
    most sensitive thing this app will ever touch, and the ones the user did not pick must
    never leave the phone — not in a log line, not in an analytics event, not in a crash
    report.

### File import (FR-IMP-04, FR-IMP-05, FR-IMP-06)

  - `.csv` and `.xlsx`, per the decision you made above.
  - Column-mapping screen: source column -> Name / Date / Relation / Phone / Notes, with a
    live 5-row preview that updates as the mapping changes. The preview is what makes a wrong
    mapping obvious before it is applied, so it is not optional polish.
  - Date parsing tolerant of `dd/MM/yyyy`, `dd-MM-yy`, `MM/dd/yyyy`, `d MMM yyyy`,
    `yyyy-MM-dd`, and day-month with no year.
  - The dd/MM vs MM/dd ambiguity is resolved by an EXPLICIT user toggle, defaulted to dd/MM
    for India. Never guess from the data. A file where every row is under 13 is exactly the
    file where guessing is both possible and wrong.
  - Two-digit years need a stated pivot. `dd-MM-yy` with `65` is 1965 for a birth year and
    2065 is absurd; the rule you choose goes in a comment with its reasoning.

### Dedup and idempotency (FR-IMP-08, FR-PPL-06)

  - Every row is matched against existing people before it is written: fuzzy name tokens plus
    same day/month, the rule that already exists.
  - A match is surfaced, not silently merged — "Is this the same person?" — consistent with
    FR-PPL-06 on the create path.
  - Re-importing the same file produces zero new people. Prove this with a test that runs the
    same import twice against the same store, not by importing by hand and looking.
  - Duplicates WITHIN one file are also duplicates. A spreadsheet with the same aunt on rows
    4 and 51 must not produce two aunts.

### Result report (FR-IMP-07)

  - Imported / skipped / failed, with a per-row reason for every non-imported row.
  - Failures downloadable as a corrected CSV: the original columns, the original values, plus
    a reason column, so the user can fix it in the tool they already use and re-run it. The
    re-run then hits the idempotency path, which is why FR-IMP-07 and FR-IMP-08 are one
    feature and not two.

### Onboarding hook (FR-AUTH-05, FR-VIEW-05)

  - Contacts import offered at "add your first 3 people", and the people-list empty state
    leads to import rather than nowhere. Small, and it is the difference between a feature
    that exists and a feature that gets used.

## Explicitly NOT in M3

Calendar, timeline or widget (M4). Any AI, wish drafting or WhatsApp handoff (M5). Posters or
photo upload (M6) — including contact photos, which are displayed and discarded. Billing (M7).
Gift suggestions (M8). Export or account deletion (M9). Google Sheets live sync and Google
People API (both deferred to v2 by decision in REQUIREMENTS.md — device contacts only). Family tree (v2).

Merging two existing people is NOT in M3. Import surfaces a possible duplicate and lets the
user skip the row; it does not combine records. A real merge has to decide what happens to two
sets of events, traits, reminders and delivery history, and that decision does not belong
inside an import screen.

## Definition of done

1. A real contacts list on a real phone imports selected contacts, with relations set in bulk,
   and only the selected ones exist afterwards.
2. Permission denied, and denied permanently, both leave a usable screen that says what to do.
3. A CSV with messy real dates — at least `dd/MM/yyyy`, `d MMM yyyy`, `dd-MM-yy` and a
   day-month with no year in the same file — imports with correct dates.
4. The dd/MM vs MM/dd toggle changes the preview, and a file of all-ambiguous dates imports
   correctly under both settings.
5. The same file imported twice produces zero new people the second time, proven by a test.
6. A file with a duplicate inside it produces one person, not two.
7. A file with bad rows produces a report naming each bad row and its reason, and a corrected
   CSV that re-imports cleanly after editing.
8. An import larger than the sync batch cap reaches the server completely, and the outbox is
   empty afterwards. Then add one person by hand and confirm THAT syncs too — the failure this
   is guarding against is the one that takes unrelated writes down with it.
9. Unit tests for: every date format in FR-IMP-06, the dd/MM toggle, the two-digit-year pivot,
   dedup within a file, dedup against existing people, and the push chunking boundary exactly
   at the cap and one either side.
10. CI green. docs/M3_VERIFY.md with exact reproduction steps, the test files you used
    committed under docs/fixtures/, and an honest row per definition-of-done item — including
    anything you could not prove, with the reason, in the shape M2_VERIFY.md §9 uses.

## Engineering rules

Everything from docs/BUILD_PROMPT_M0.md, M1 and M2 still applies. In addition:

- The repo is PUBLIC. Railway values go in Railway, never in the repo. Test fixtures committed
  under docs/fixtures/ must contain INVENTED people — never the user's real contacts.
- No destructive migrations. Real data exists on Railway and on the phone.
- Derived values stay server-derived. Import does not get to tell the server a sentiment, a
  completeness score or a minor flag.
- Nothing is written without the user choosing it. "Select all" is a button the user presses,
  never a default.
- Contacts the user did not select do not appear in logs, analytics or crash reports.
- An import that fails halfway must leave the database in a state the user can understand and
  the next run can fix. Decide whether that means all-or-nothing or partial-with-a-report, and
  write the reasoning down.
- Conventional commits, small and reviewable. Branch off main, PR at the end. main is
  merge-only.
- docs/MILESTONE_CHECKLIST.md sections C, D, E and F apply to this milestone as written. If
  you decide an item does not apply here, say which and why rather than passing over it.

## How to work

1. Write docs/M3_PLAN.md — the outbox decision first, then the parse-location decision, then
   your implementation order. Show it to me before writing code.
2. The outbox fix, with the chunking-boundary tests. Everything else in M3 can produce enough
   rows to hit it, so it goes first.
3. Date parsing and the dd/MM toggle, as pure functions with the full table of cases. No UI.
4. Dedup and idempotency, as pure functions, against a fake store. Still no UI.
5. Contacts import: permission, read, review screen, bulk relation, write.
6. File import: picker, column mapping, preview, apply.
7. The result report and the corrected-CSV download.
8. The onboarding hook and the empty state.
9. Stop and tell me when it works. Do not continue into M4.

If any requirement here conflicts with docs/REQUIREMENTS.md, stop and ask — do not silently
pick one. M0, M1 and M2 each hit one of these and raising it was the right call all three
times.
```

---

## Before you run this

| Check | Why |
|---|---|
| PR #3 merged, `main` green | M3 branches from it |
| A phone with a REAL contacts list on it | A seeded emulator with five tidy contacts proves nothing about this milestone |
| A spreadsheet of real birthdays, however messy | The messiness is the test |
| A non-Pixel phone, if one has appeared | M2's definition-of-done item 7 is still open |

## Why the outbox fix goes first

M2 put delivery reporting first so that everything after it was measurable. The same logic puts
the outbox first here, for a harsher reason: it is not a feature, it is a floor. Every other
piece of M3 — contacts, files, dedup, the report — produces enough rows to fall through it, and
a bug there does not look like an import bug. It looks like the app quietly stopping syncing,
days later, for writes that have nothing to do with import.

Build the floor, then stand on it.

## The thing most likely to go wrong

Silent partial success. An import is the first operation in this app with a middle: 300 rows
where 280 worked, 15 were duplicates and 5 had unparseable dates. Every tempting shortcut —
swallowing the failures, reporting a count without the reasons, marking the outbox clean on a
response you did not read — produces a screen that says "Imported!" over a database that is
wrong in a way the user cannot see and cannot fix.

The result report is not the polish at the end of this milestone. It is the thing that makes
the rest of it honest, which is why it has a definition-of-done item of its own and why the
corrected-CSV path has to actually round-trip.

## After M3

M4 is views and the widget: calendar, timeline, Glance widget and the notification actions M2
deliberately left opening the person instead of doing anything. It is the first milestone that
is mostly surface, and the first one where a user with 300 imported people will notice whether
the list is usable at that size. Write its prompt in this same shape — explicit scope, explicit
non-scope, a definition of done verifiable on a real device, and a "stop and tell me" at the
end.
