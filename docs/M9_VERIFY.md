# M9 Verification — Privacy (the half that needs no Play Console)

> Branch `feat/m9-privacy`, PR #9, off `main` at `493f829`. **MERGED `ff1119d` on 2026-11-27 — unfinished, by Dipen's explicit instruction ("Merge as-is"), with SIX of the ten items below NOT MET.**
>
> Verified here rather than taken on report: PR #9 state `MERGED`; `ff1119d` is a real merge commit whose parents are `1b3db4a` (the redaction, cherry-picked to `main` ahead of the merge) and `4f8723d` (this branch's head), and both are ancestors of `main`, so neither side was lost; `privacy`, `server` and `android` all report success on `ff1119d`; and `tools/check-no-pii.sh` run against `main`'s own tree reports clean.
>
> **Green CI on `main` does not mean this milestone is finished.** The code is merged and every workflow passes, and those two true statements together imply something false. §1 is the list that does not.
>
> Three different claims, kept apart throughout, as M5 and M6 did:
>
> - **PROVED** — I ran it and watched the result. The command and the output are here.
> - **READ-VERIFIED** — I read the code and followed the path. Nothing executed it.
> - **BLOCKED** — not proved at all, with what it is blocked on. Not rounded up to PARTIAL
>   (`MILESTONE_CHECKLIST.md`, bought by M5 §1).

---

## 0. The honest summary, first

**Two of the four decisions are answered and built; two are not, and they are what is left.**
`docs/M9_PLAN.md` §3 states all four, §4 maps each to the definition-of-done items it blocks,
and that mapping was written on day one rather than discovered on day six, which is what the
build prompt asked for.

**Answered on 2026-11-27 and built:** decision 3 — anonymise the ledger and the reports, erase
everything else — and decision 4 — a CI spec **and** a throwaway handset account, of which the
CI half is done. **Still open:** decision 1 (where the policy pages and the public deletion URL
live) and decision 2 (how the export is delivered).

Built and proved: **the retention job** (FR-SET-06), the only requirement here with a clock
already running, and **account deletion** — every row, every blob and every Redis key, in CI.
Built and partly proved: **the export** (produced and saveable, delivery undecided) and **the
prominent disclosure** (code and tests, not the handset).

**Nothing in this milestone requires an API key.** Definition-of-done item 9, stated explicitly
as the prompt asks: the retention sweep, the export, the disclosure and the breach note need no
`OPENAI_API_KEY`, no `ANTHROPIC_API_KEY` and no new secret of any kind. The one thing that would
change that is decision 2 — **if the export is delivered by email, item 9 becomes false**, which
is raised as C1 rather than discovered later.

### 0.1 This machine could not run a database for most of the milestone — **corrected**

For most of M9 the host disk was at **100%** (1.1 GiB free of 460 GiB) and Docker's VM had gone
read-only:

```
$ docker restart wishkaro-db-1
Error response from daemon: Cannot restart container wishkaro-db-1:
  open /var/lib/docker/.../hosts: read-only file system
```

So the DB-backed suites were verified **by CI rather than here**, which is the answer
`M6_VERIFY.md` §10 took at the same wall. I did not prune Dipen's images or build cache to make
room — that is his machine's state, not mine to reclaim.

**That changed late in the milestone and this section is corrected rather than left standing**
(`MILESTONE_CHECKLIST.md` §F: an incident note that turns out to be wrong is corrected in
place). The disk recovered to 6.4 GiB, Docker's daemon had stopped entirely, and starting it
brought both containers back with the port published this time. Everything below that said "CI
only" was then re-run here:

- the **full 682-test suite**, including all four `.db.spec.ts` suites, green locally;
- the same 682 in **three timezones** (§7.2), which closes the gap this section used to name —
  previously only the 573 non-DB tests had been run in `Pacific/Chatham`;
- and a **live end-to-end walkthrough over real HTTP** against a running server, which is
  §9.5 and is a stronger claim than any of the specs.

What is still true: **CI runs the DB suites in UTC only.** The three-timezone coverage of them
is this machine's, not CI's.

### 0.2 A test run I could not explain, and did not capture

The first `TZ=UTC` run of the server suite reported **21 failures**. Seven subsequent runs in the
same shell, same zone, were clean, as were every run in `Pacific/Chatham` and `Asia/Kolkata`.

**I did not capture which tests failed**, which is the mistake here — `grep -E "^Tests:"` kept
the count and threw away the names, and by the time I wanted them the run was gone. The most
plausible cause is the full disk: `ts-jest` writes its transpile cache to disk and a failed
cache write on a 100%-full volume would plausibly take out a block of suites at once. **That is
a hypothesis, not a finding.** It is recorded because 21 silent failures that disappear are
exactly the shape that gets rationalised away, and because the next person to see it should
know it has happened before.

---

## 1. Definition of done, item by item

| # | Item | Verdict |
|---|---|---|
| 1 | An export produced for the 575-person account, opened, and checked to contain what is in it | **BLOCKED** — decision 2 for delivery, and the live account is Dipen's with two real contacts in it. Producer built and unit-proved; the app now saves both files through the user's own save dialog. §4 |
| 2 | Its CSV re-imported through M3's importer without a mapping change | **PROVED, against a fixture — not against the 575-person account.** The real importer, the real guesser, no mapping touched, under both settings of the dd/MM toggle. §4.2 |
| 3 | An account deleted from the app and verified gone: no rows, no blob objects, no device token, no push | **PARTLY PROVED.** Every row, every blob and every Redis key — in CI, locally, and **by hand over real HTTP against a running server** (§9.5), including the ledger anonymised and the still-valid access token refused. The **push half is BLOCKED by decision** — half A withdrawn after three candidate accounts proved unusable. §9.6 |
| 4 | The same deletion from the public web URL, with no app session | **BLOCKED** — decision 1. The page is not built, deliberately: §10 |
| 5 | The retention job run against seeded data of the right ages, keep-override honoured, 90/180 boundaries exact, a photo attached to a saved poster surviving | **PROVED — against real Postgres and a real blob store**, in CI and then locally in three timezones once Docker came back. §2, §0.1 |
| 6 | Policy and terms reachable in-app and from a browser with no session | **BLOCKED** — decision 1. Not started, deliberately: a policy page describing a deletion that does not exist is the worst artifact this milestone could produce |
| 7 | The prominent disclosure shown before the contacts permission on the handset | **BLOCKED on a sign-in only Dipen can do.** The code, the copy and 7 tests are done; the APK installs and launches on the Pixel 9a with 0 fatal exceptions, and stops at the sign-in screen. §3 |
| 8 | §12's checklist updated, each tick traceable to `M9_VERIFY.md` | **DONE.** 5 of 13 ticked, 8 left with a reason. §6 |
| 9 | Nothing requires an API key, and this document says so explicitly | **DONE** — §0, and the caveat that decision 2 can falsify it |
| 10 | Both targets compile, server suite green across three timezones, CI green on the exact head | **DONE.** Android compiles and installs; iOS compiles in CI; **five** checks green on the merged head `4f8723d` — the fifth is the unfiltered `no-pii` guard, 6 s — and all three workflows green on the merge commit `ff1119d`; the **full 686-test suite, DB specs included**, green in UTC, Pacific/Chatham and Asia/Kolkata, and serially by default since §14.5. §7 |

**Counted against the definition of done rather than against effort: FOUR of ten met, SIX not
met.**

| | Items |
|---|---|
| **Met** | 5 (retention), 8 (§12 updated), 9 (no API key), 10 (compile, suites, CI) |
| **NOT met** | 1, 2 (export against a real account, and *its* CSV) · 3 in part (the push) · 4 (public web deletion) · 6 (policy and terms) · 7 (disclosure on the handset) |

Of the six: **two wait on decision 1**, **two on decision 2**, **one on a Google sign-in only
Dipen can perform**, and **one — item 3's push clause — was deliberately withdrawn** after three
candidate accounts proved unusable (§9.6).

Item 2 is the one most likely to be miscounted, so it is stated twice: the round trip **is**
proved through the real importer, with no mapping touched, under **both** settings of the dd/MM
toggle — against a **fixture**. The item as worded is about the export produced in item 1, and
that export does not exist. A fixture passing is not that item being met.

---

## 2. FR-SET-06 — the retention sweep — PROVED

### 2.1 What was built

`retention.rules.ts` holds FR-SET-06 as a pure, total decision; `retention.service.ts` does the
I/O; `retention.scheduler.ts` is a daily repeatable BullMQ job on the same `upsertJobScheduler`
pattern as the reminder materialiser. §12 asks for a job rather than a sentence in a policy, in
as many words.

**The requirement is one sentence and three clauses**, which is how it was walked:

| Clause | Where it lives | State before M9 |
|---|---|---|
| purge after 90/180 days | `asset.expires_at` | Written by M6, read by nothing |
| "both user-overridable to **keep**" | `asset.retention` (new enum) | M6 proposed `expires_at = NULL`; nothing ever wrote one |
| "with a warning" | `asset.purge_warned_at` (new) | **Nothing at all** |

### 2.2 PROVED in CI, against real Postgres and a real blob store

`retention.db.spec.ts`, run by GitHub Actions on the branch head, after the hand-written
migration applied to a clean database:

```
build  Apply migrations to a clean database  Applying migration `20261127140000_m9_retention`
build  Unit tests                            PASS src/modules/retention/retention.db.spec.ts
build  Unit tests                            PASS src/modules/retention/retention.rules.spec.ts
build  Unit tests                            Test Suites: 41 passed, 41 total
build  Unit tests                            Tests:       640 passed, 640 total
```

What that suite asserts, each against bytes as well as rows:

- an overdue source photo is purged **and `blobs.exists(key)` is false** — the build prompt's
  first named failure, *"a deletion that leaves photographs in a blob store is not a deletion;
  it is a database change"*;
- `person.photo_key` is cleared when it pointed at the purged object, and **left alone when it
  points at a newer one** — the guard the `where: { photoKey: row.blobKey }` predicate exists
  for;
- a photo recorded as a poster's source **survives, however overdue**, and is purged once that
  poster is tombstoned;
- **one person's two photos are told apart**: the poster records the second, the first is
  purged and the second is not — which is the whole reason `poster.source_asset_id` was added;
- generated media is **warned** on the first sweep and purged only on a sweep at least
  `RETENTION_WARNING_DAYS` later, with the poster row tombstoned alongside its image;
- a kept asset is never purged; released, it is purged on the **original** deadline rather than
  a fresh 90 days;
- `theme_cache` and its artwork are untouched;
- a blob delete that throws leaves the row **unwritten**, and the next sweep finishes the job.

### 2.3 PROVED locally — 14 rules tests at the exact boundaries

```
$ npx jest src/modules/retention/retention.rules.spec.ts
Tests:       14 passed, 14 total
```

Definition-of-done item 5 asks for "the 90/180 boundaries hit **exactly**", which is wording
that gets tested at 89 and 200 and passes without touching the edge. Every date case is written
as a pair, one millisecond either side:

- `expiresAt === now` purges; `expiresAt === now + 1ms` does not.
- `expiryFor('SOURCE_PHOTO', uploadedAt) - uploadedAt === 90 * DAY_MS`, asserted rather than
  assumed; same for 180.
- warned at exactly `expiresAt - 14d`, not a millisecond earlier.
- purged at exactly `warnedAt + 14d`, not a millisecond earlier.

### 2.4 The four revert-checks, all red

`MILESTONE_CHECKLIST.md` §D, and M5's three vacuous tests:

| Reverted | Result |
|---|---|
| the attached-to-a-poster guard | `✕ never purges a photo a saved poster was composited from` — 1 failed |
| the warning precondition (purge on the date alone) | 3 failed, including the backlog sequence |
| the KEEP override | 2 failed |
| the null-expiry guard (null read as long overdue) | `✕ skips an asset with no expiry` — 1 failed |

**The fourth did not go red on its first attempt** — it reported `Tests: 0 total`, because
removing the guard left a type error and the suite never ran. A compile failure is not a
revert-check. It was redone as the realistic bug (`new Date(null)` is epoch 0, which in
JavaScript is *very* overdue) and then went red properly.

### 2.5 The structural claim, stated so it can be argued with

**The warning is a precondition of deletion, not a notification.** `decide()` refuses to purge
generated media unless `purge_warned_at` is set and at least 14 days old. Three things follow,
and all three are tested:

1. Generated media cannot be destroyed without notice *by construction*, not by the job
   remembering to send something first.
2. A deployment whose sweep was off for a month **warns** its backlog and purges it a fortnight
   later, instead of deleting a year of posters on the first night it comes back.
3. "Warned" is a row in a database, so `GET /v1/retention/warned` can show the user what is
   about to go while there is still time to press Keep.

### 2.6 What is NOT proved about retention

- **It has never run on Railway.** Nothing has been deployed. The scheduler is read-verified:
  it copies `RemindersScheduler` line for line, and that one demonstrably works.
- **`BLOB_STORE=local` is a fake, and it is not durable.** `isDurable = false`, and Railway
  replaces the container on every deploy. So a deletion test against it proves `delete()` was
  called and a file left a temp directory. It proves nothing about an object store with
  eventual consistency, versioning, or a delete that returns 204 for a key that never existed.
  This is M6 §0.7's lesson in a new place: the committed default is a fake the shape of the
  live path. On the deployed service today the bytes vanish on redeploy anyway, which would
  make a deletion look complete for a reason that has nothing to do with this code.
- **The backfill has never run against real data.** The migration's `UPDATE poster SET
  source_asset_id = …` applied to CI's empty database. Read-verified against the rule
  `PosterPipelineService.photoFor` actually used; unproved against the posters on Railway.

---

## 3. §12's prominent disclosure — built, and BLOCKED on the handset

### 3.1 The build prompt was wrong about this, and what was actually wrong was worse

> *"§12 asks for a prominent disclosure screen before the contacts permission, and M3 shipped
> contacts import without one."*

**M3 did ship one.** `ImportScreens.kt:186` has been a full-screen rationale before the system
dialog since M3, on the only route to `askForContactsPermission()`. What it said was the
problem:

> • It all happens on this phone.

True of the **reading**. False of what happens after you tap Import — FR-IMP-01 says the records
the user selects are "persisted **or synced**", and `PeopleRepository.importPeople` writes them
with `pending_sync = 1` for the sync engine to drain to the server. The selected contacts leave
the phone. The next bullet made it worse by implication: *"the people you do not pick are never
saved, never sent anywhere"* is accurate, and by contrast it says the ones you do pick stay put.

A disclosure that misdescribes the data flow is a worse Play finding than a missing one, and it
is also simply not true.

### 3.2 PROVED — the copy is held to FR-AUTH-06's clauses

```
$ ./gradlew :composeApp:testDebugUnitTest --tests '*ContactsDisclosureTest*'
tests/skipped/failures/errors = ('7', '0', '0', '0')
```

The words live in `ContactsDisclosure` rather than inside the composable, because they *are* the
requirement: FR-AUTH-06 asks for a screen "stating what is read, why, and that it is not
uploaded in bulk", and a value can be held to those clauses where a string literal in a
`@Composable` cannot.

One test is a **prohibition** rather than an assertion — the claim that made the old copy
misleading must not come back. That is how it would return: by somebody shortening the screen,
not by somebody lying.

**Revert-check: M3's exact copy restored → 4 of 7 fail**, including the prohibition.

### 3.3 PROVED on the handset — as far as a fresh install goes

Checklist §E, in order, output read at every step:

```
$ adb devices -l            4B201JEBF03493  device  model:Pixel_9a
$ adb shell dumpsys window | grep -m1 mCurrentFocus
    …nexuslauncher/…NexusLauncherActivity          (unlocked — not a keyguard window)
$ adb shell pm list packages | grep -i wishkaro
    (nothing — the app was NOT installed)
$ ./gradlew :composeApp:assembleDebug            BUILD SUCCESSFUL
$ adb install -r …/composeApp-debug.apk          Success
$ adb shell am start -n in.wishkaro.app/.MainActivity
    Starting: Intent { cmp=in.wishkaro.app/.MainActivity }
$ adb shell dumpsys window | grep -m1 mCurrentFocus
    …in.wishkaro.app/in.wishkaro.app.MainActivity
$ adb logcat -d -t 400 | grep -c 'FATAL EXCEPTION'
    0
$ adb exec-out screencap -p > …/01-launch.png    (looked at before any input)
```

The screenshot is the **sign-in screen**. The app was not installed on the handset at all when I
started, so this is a first launch with no session.

### 3.4 BLOCKED — why item 7 stops here

The disclosure is behind the import flow, which is behind sign-in. Signing in means
authenticating as Dipen on his own Google account, which is his to do and not a session's —
exactly as `M6_VERIFY.md` §0.3 records ("Dipen unlocked the Pixel 9a and signed in").

There is a second obstacle after that one, worth knowing before the attempt: `startContacts()`
goes straight to reading when the permission is already granted, so **the disclosure is only
reachable with `READ_CONTACTS` revoked**. On a fresh install it will be. If it is not:
`adb shell pm revoke in.wishkaro.app android.permission.READ_CONTACTS`.

The build is installed and waiting on the device.

---

## 4. FR-SET-03 — the export — produced, not delivered

### 4.1 BLOCKED on decision 2

FR-SET-03 says "delivered by **email link**". This app cannot send email: no SMTP, no provider,
no key, no dependency that could. The alternative already built is the blob store's short-lived
signed URL — nothing new, and a weaker reading, because an email link survives the app being
uninstalled, which is exactly when somebody wants their data.

Both readings need the bytes produced first. That is what is built:
`GET /v1/account/export` (JSON) and `GET /v1/account/export.csv?dataset=people|events|wishes`,
both authenticated, both `Cache-Control: private, no-store`. **Nothing here forecloses either
decision**, and no delivery mechanism is committed to.

### 4.2 PROVED — the round trip, both halves, one shared table

```
$ npx jest src/modules/account              Tests: 13 passed, 13 total     (the writer, TS)
$ ./gradlew :composeApp:testDebugUnitTest --tests '*ExportRoundTripTest*'
    tests/skipped/failures/errors = ('5', '0', '0', '0')                   (the reader, Kotlin)
```

The export format is a rule implemented twice in two languages, which
`MILESTONE_CHECKLIST.md` (bought by M3) says is held to **one shared table of cases** rather
than a copy. `docs/fixtures/export-roundtrip.json` is that table; both suites read it.

The Kotlin half never re-implements the export. It takes the fixture's rows, writes them with
`Csv.write`, and runs the **real** importer — `Csv.parse`, `guessMapping`, `planImport` —
without touching the mapping, because *"without a mapping change"* is only tested by not making
one.

### 4.3 The finding that makes item 2 true for every user rather than one

`dd/MM/yyyy` satisfies item 2 and is still wrong. `03/05/1990` is **ambiguous**, and M3's
importer resolves it with a **user preference**: FR-IMP-06's day-first toggle, which FR-SET-08
makes a setting. So an export written that way re-imports as 3 May for one user and 5 March for
another — silently, no row marked failed, nothing on screen to notice.

The export writes `3 May 1990` / `17 Nov`, because `ImportDates.fromNamedMonth` returns
`ambiguous = false` unconditionally. The test imports the same file twice, once under each
setting of the toggle, and asserts the people are identical.

**Revert-check: the fixture and the writer switched to `dd/MM/yyyy` → 2 server tests and 2
Kotlin tests fail.**

### 4.4 `people.csv` carries birthdays only, and that is C11

M3's importer hard-codes `type = BIRTHDAY` and `ColumnMapping` has five targets with no type
among them. A death anniversary exported and re-imported comes back as a **birthday** — the
outcome FR-EVT-05 calls *"the worst failure this app can have ... impossible by construction,
not by a copy tweak"*.

So `people.csv` is birthdays only and says so; `events.csv` and the JSON carry every event of
every type. `M9_PLAN.md` C11 puts the three readings of item 2 to Dipen. **The reading that
silently turns a remembrance into a celebration is not being built in the meantime**, and a
test asserts the header has no type column.

### 4.5 NOT proved

- **No export has ever been produced from a real account.** Item 1 needs the 575-person account,
  which is Dipen's, on Railway, with two real contacts in it. Unit tests prove the shape; they
  do not prove the content of a real export, and an export file of 566 people is the most
  PII-dense artifact this project can make — it does not go in the repo or in a document.
- **The export endpoint has no rate limit.** An export walks every row a user has; at 575 people
  it is the most expensive read in the app and nothing in this codebase would refuse it. Named
  in `M9_PLAN.md` §8 and not built.

---

## 5. §12's minor flag — verified, and the two surfaces disagree

The build prompt says *"Verify it, do not rebuild it."* Verifying it found two things.

**First, the poster surface had no test at all.** `isMinor` appears in `prompt-builder.spec.ts`
and `drafting.db.spec.ts`, and nowhere under `modules/posters` — so M6's suppression at
`poster-prompt.ts:92` had never been asked whether it still happens. M9 adds
`minor-flag.spec.ts` (5 tests). **Revert-check: remove `!input.isMinor &&` → 3 fail.**

**Second, and raised rather than resolved:**

| Surface | For a flagged minor |
|---|---|
| Wish drafting | **Keeps** the relation; drops hobbies, likes, notes |
| Poster copy | **Drops** the relation, which is the only profile field it carries |

FR-PPL-09 excludes a flagged record from "any profiling, recommendation personalisation **beyond
age band**". Both are defensible readings of that sentence, and **only one can be what the Data
Safety form says.** There is also a comment at `poster-prompt.ts:46` that argues for the
*drafting* rule — "relation, occasion, milestone and age band, nothing else" — and then says
"here that means the relation goes too", which does not follow from what it just said. That is
M6 §0.9's shape: a comment asserting an invariant the code does not hold.

§12's box is left **unticked** for this reason. `minor-flag.spec.ts` pins the divergence so that
settling it is a visible change in one place.

---

## 6. §12, box by box

Updated in `REQUIREMENTS.md` §12. **5 of 13 ticked**; the other 8 each say what is missing
rather than being left blank, because a blank box and a box nobody looked at read identically.

Ticked: retention (M9), no behavioural advertising (M9's grep over both targets' dependency
trees found no ad, tracking or analytics SDK), GenAI content reporting (**M6**), input/output
moderation (**M6**, with M6's own "structurally met, never exercised" caveat carried), breach
response note (M9).

Three of those five were already true before M9 started, and are credited to the milestone that
built them.

---

## 7. Item 10 — compile, suites, CI

### 7.1 PROVED

```
$ ./gradlew :composeApp:assembleDebug               BUILD SUCCESSFUL
$ adb install -r …                                  Success          (and launched, 0 FATAL)
$ gh pr checks 9
  build   pass  1m23s
  docker  pass  1m39s
```

Server CI green on the branch head, including the migration applied to a clean Postgres and
`retention.db.spec.ts` against it: **41 suites, 640 tests**.

### 7.2 Three timezones — PARTIAL, and honestly so

**The full suite, DB-backed specs included**, once Docker came back (§0.1):

```
UTC                Tests: 682 passed, 682 total
Pacific/Chatham    Tests: 682 passed, 682 total
Asia/Kolkata       Tests: 682 passed, 682 total
```

`--runInBand`, because the four DB suites share one database and jest's parallel workers are
what §12 is about.

**682, not 573** — these now include `retention.db.spec.ts`, `account-deletion.db.spec.ts`,
`photo.db.spec.ts`, `poster-pipeline.db.spec.ts`, `drafting.db.spec.ts` and
`wish-sync.db.spec.ts`. Item 10's three-timezone clause is met for the whole suite rather than
for the pure half, which is what this section said while the disk was full.

CI still runs them in **UTC only**, so the other two zones are this machine's claim, not CI's.

Earlier runs, kept rather than dropped: 573 in each zone before the DB suites could run, 565
before the DI-graph suite existed, and the unexplained 21-failure UTC run in §0.2 — which has
not recurred in any of the eleven runs since.

### 7.3 CI does not boot this app, and M9 added two modules to its graph

`npm run build` compiles; it does not run `main.ts`. So a Nest module that cannot resolve a
dependency passes install, generate, migrate, lint, typecheck, test and build on the pull
request, and fails on Railway at boot on deploy day — the same gap M2 proved on the client
side with 308 green tests and an app that could not start.

`MILESTONE_CHECKLIST.md` §D asks for a DI-graph test on a graph change.
`retention.module.spec.ts` compiles `RetentionModule` and `AccountModule` against stubs for the
three `@Global` modules they rely on and nothing else — which is also what would notice if
`@Global` were ever removed from `PrismaModule`, `MediaModule` or `QueueModule`, since neither
new module declares any `imports`.

**Revert-check: drop `RetentionService` from the module's providers → 2 of 3 fail.**

### 7.4 iOS

Not compiled here — the iOS target needs a macOS runner and Xcode, and `android.yml`'s `ios` job
is what covers it. Read-verified only: nothing M9 added to `commonMain` is platform-specific
(`ContactsDisclosure` is a `String` object; `ExportRoundTripTest` is in `androidUnitTest`, which
Kotlin/Native never runs — the same gap M3 recorded for `NameTokensTest`).

---

## 8. A gap found by a revert-check that did not go red

`MILESTONE_CHECKLIST.md`: *"when it does NOT go red, find out why before moving on."*

Reverting the export's date format **in the fixture** reported success without running a single
test. The cause: `docs/fixtures/` is **not a declared input** to `:composeApp:testDebugUnitTest`,
so a fixture-only change leaves the task `UP-TO-DATE`. It only failed under `--rerun-tasks`.

**This affects every shared-table suite since M3** — `NameTokensTest`, `WishToneRulesTest`,
`PosterThemesTest` and now `ExportRoundTripTest`. Any local revert-check performed by editing a
fixture has been vacuous.

CI is unaffected: a clean checkout runs everything, and `android.yml` already path-filters on
`docs/fixtures/**`. The hole is local, which is worse — it is where somebody edits a shared
table and believes the green.

**I could not fix it.** Three formulations of `inputs.dir` / `inputs.files` on
`tasks.withType<Test>().configureEach` in `composeApp/build.gradle.kts` left the task
`UP-TO-DATE`, and a bare `println` at the top of that block never printed — with the
configuration cache cleared and `--no-configuration-cache` — so the block does not appear to run
at all. I did not get to the bottom of it. The attempt is **reverted rather than shipped
unverified**, and this section is the record. Until it is fixed: **a revert-check that edits a
fixture must pass `--rerun-tasks`, or it proves nothing.**

---

## 9. FR-SET-04 — account deletion — PROVED, except the push

### 9.1 Decision 3, and the conflict that was not hypothetical

Dipen's answer: **anonymise `ai_usage`, `generation` and `content_report`; erase everything
else.** `docs/M9_PLAN.md` decision 3 holds the reasoning.

What matters about it is that the schema had **already** resolved this conflict, in §10 rule 2's
disfavour, without anybody deciding: `AiUsage.user` has been `onDelete: Cascade` since M5. The
first `user.delete()` anybody wrote would have destroyed the cost ledger that rule 2 and
FR-POS-10 both call non-negotiable — silently, with every test green, because nothing deleted a
user.

The migration changes all three foreign keys to `ON DELETE SET NULL`, so **the database enforces
it**. There is a test that deletes a user directly, bypassing `AccountDeletionService` entirely,
and asserts the ledger rows survive with a null id. A deletion written in a later milestone that
forgets to anonymise first cannot take the ledger with it.

### 9.2 PROVED in CI — the absence of each thing, by name

```
build  Unit tests  PASS src/modules/account/account-deletion.db.spec.ts (6.674 s)
build  Unit tests  PASS src/modules/account/deletion-coverage.spec.ts
build  Unit tests  Test Suites: 46 passed, 46 total
build  Unit tests  Tests:       682 passed, 682 total
```

The build prompt is explicit: *"make the test assert on the **absence** of each one rather than
on the endpoint returning 204."* **There is no 204 anywhere in that suite.** Every assertion
counts rows in a named table, asks the blob store whether an object exists, or reads a Redis key.
The table list is **iterated from `DELETION_INVENTORY`**, not typed out, and each failure names
the table it is about.

What it asserts:

- every inventory table has rows **before** the deletion (or the assertions after it pass for
  the wrong reason) and none pointing at the user after it;
- **`event_occurrence`**, which has no `user_id` at all and is the one table a
  `WHERE user_id = ?` sweep cannot see, asserted by id through its parent;
- the **blob objects** are gone, including a **tombstoned** asset's — a replaced photo is still
  a photograph;
- **no device row anywhere holds that FCM token**, asserted on the token rather than the user
  id, because `fcm_token` is UNIQUE across the table, so this says nothing anywhere can push to
  that device;
- the four **Redis** rate-limit prefixes are gone;
- `ai_usage`, `generation` and `content_report` **survive with `user_id` null**, and the money
  is unchanged;
- a raw `user.delete()` that never touched the service leaves them standing;
- another account is **entirely untouched**, and `theme_cache` and its artwork with it;
- a **failing blob store destroys nothing at all** and the retry finishes the job.

### 9.3 The ordering, and the one direction it can fail in

`BlobStore` has no `list()`. A row deleted before its object leaves those bytes unreachable by
anything that will ever run — undiscoverable *and* undeleted. So: soft-delete and revoke every
refresh token (the door shuts, and a crash after it is recoverable rather than half-erased),
drain the user's poster jobs, **then** collect the keys — after the drain, because a job
finishing in between writes a new object — delete the objects, and only then anonymise and
hard-delete in one transaction. A failing store throws before the transaction and the request is
safe to repeat.

### 9.4 The guard against the table that does not exist yet

`deletion-coverage.spec.ts` reads **Prisma's own DMMF**, finds every model carrying a `userId`,
and fails if it is not named in the inventory. The failure this guards is not "somebody forgot a
table today" — it is M7 adding a subscriptions table, M8 adding gift click-outs, and nobody
adding either to the deletion.

**Revert-check: adding a user-owned model to the schema fails it by name** (`"SubscriptionProbe"`
in the diff), and removing a disposition or making a cascading column nullable fails the
disposition check.

### 9.5 PROVED by hand, over real HTTP, against a running server

The DB spec drives the service. This drives **the whole stack** — the HTTP layer, the auth
guard, the Zod pipe, the controller, the confirmation check, the service, Postgres, the blob
store on disk and Redis — with curl, against `npm run start:dev` on this machine. It is the
strongest claim in this document and it needed no Google account, because
`ALLOW_DEV_LOGIN=true` mints a session for an **invented** address.

Seeded over the real API: a person (`Meera Iyer`, invented, with a birthday), a device with an
FCM token, and a photo uploaded as real PNG bytes.

**The export first**, because an export of an account about to be deleted is the one chance to
see what was in it:

```
formatVersion 1
people 2 -> ['M9 Walkthrough', 'Meera Iyer']
events [('BIRTHDAY', 3, 5, 1990)]
media [('SOURCE_PHOTO', 'AUTO', '2027-02-25')]
no token leaked: True
```

The media row carries FR-SET-06's state — `AUTO`, expiring 90 days out — and the JSON contains
**no FCM token and no `googleSub`**, which is asserted rather than assumed: an export is a file
that leaves the system, and a device token in one is a capability to push to a phone.

The CSV, byte for byte:

```
b'Name,Date,Relation,Phone,Notes\r\nMeera Iyer,3 May 1990,Friend,,'
CRLF: True   BOM: False
```

`3 May 1990`, not `03/05/1990` — §4.3's whole argument, now visible in the bytes the server
actually sends. CRLF as `Csv.write` produces, and **no BOM**, which is right for this route:
the BOM is added by `saveCsv` on the device, where Excel needs it, and would be a syntax error
in the JSON sibling.

**The wrong confirmation is refused:**

```
HTTP 400  {"error":{"code":"CONFIRMATION_MISMATCH", ...}}
```

**Then the deletion**, with the database counted on both sides:

```
BEFORE  user=1 person=2 person_trait=0 event=1 event_occurrence=1 reminder=3 device=1
        refresh_token=1 wish=0 poster=0 asset=1 generation=0 ai_usage=0 content_report=0
        blobKeys: photos/f3/f39a101504e347068faaae9932e3a8a3.jpg
        deviceTokens: m9-walkthrough-fake-token

DELETE /v1/account  ->  HTTP 204

AFTER   user=0 person=0 person_trait=0 event=0 event_occurrence=0 reminder=0 device=0
        refresh_token=0 wish=0 poster=0 asset=0 generation=0 ai_usage=0 content_report=0
        blobKeys: (none)
        deviceTokens: (none)
```

And the bytes, which are the point:

```
$ ls .storage/photos/f3/f39a101504e347068faaae9932e3a8a3.jpg
ls: No such file or directory
```

**C8, observed rather than unit-tested.** The access token minted before the deletion is still
cryptographically valid. Every route refuses it:

```
GET /v1/people      -> HTTP 401   {"code":"USER_GONE","message":"Account no longer exists"}
GET /v1/sync        -> HTTP 401
GET /v1/account/export -> HTTP 401
```

**And the observation that justifies C9's design.** The refresh token answers something
different:

```
POST /v1/auth/refresh -> {"code":"INVALID_REFRESH_TOKEN","message":"Unknown refresh token"}
```

Because the `refresh_token` row went with the user. That is exactly why the client reads the
error code **before** refreshing: if it refreshed first, a deleted account would be
indistinguishable from an ordinary expired session, and the local database would survive a
deletion. The design decision in §11 was reasoned from the schema; this is the observation.

**The log line, checked against §11's "nothing in a log, ever":**

```
Account deleted: 1 object(s), 0 row(s) anonymised, 0 job(s) dropped, 2 key(s)
```

Counts and nothing else. No key, no id, no filename, no email.

**A second account for the anonymise half**, because the first had never called a model and
`0 row(s) anonymised` proves nothing about decision 3. Seeded with a generation, an `ai_usage`
row at ₹7.12 and a content report, then deleted through the same endpoint:

```
HTTP 204
user rows           : 0
ai_usage FOR user   : 0
ai_usage KEPT       : [{"userId":null,"costInr":"7.12","model":"gpt-image-1"}]
generation KEPT     : [{"userId":null,"feature":"poster","costInr":"7.12"}]
content_report KEPT : [{"userId":null,"feature":"poster","reason":"OTHER"}]
```

**That is decision 3, working.** The user is gone, nothing points at them, and the ₹7.12 and
the model name survive — which is what §10 rule 2 and FR-POS-10 need and what the schema's
`onDelete: Cascade` would have destroyed.

Every row created by this walkthrough was removed afterwards; the local database is back to
what it was.

### 9.6 BLOCKED — the push, and the decision not to prove it

Definition-of-done item 3 ends *"and a push to that token no longer delivers"*.

**What is established.** The device row is gone, and the assertion is made on the TOKEN rather
than on the user id — `fcm_token` is `UNIQUE` across the table, so "no row anywhere holds that
token" is a statement about the whole database rather than about one account's rows. That is
**read-verified** to be sufficient: `RemindersService.sendDue` reaches a token only through a
device row, and there is no other path from a token to a send.

**What is not.** Nobody has watched a push fail to arrive. That is a different claim and it is
not being rounded up into the first one — `MILESTONE_CHECKLIST.md`, bought by M2 items 1, 4 and
5: *"scheduled correctly" and "watched arriving" are written as different claims.*

**Why it stopped here — Dipen's call, 2026-11-27, and a reasonable one.** Decision 4's half A
needed a throwaway Google account. Three candidates were considered and none was usable:

1. **The owner's own Google account**, offered and refused by this session. It was already in
   the local database from an earlier milestone, holding six people, six events, a wish, two
   posters and three media files — and two of those names read as real family rather than as
   fixtures, the same shape `M3_VERIFY.md` records for the deployed account. The feature under
   test destroys an account irreversibly; that is not an account to point it at.
2. **A second personal account** present on the handset, plainly somebody else's. Never a
   candidate.
3. **An address added for the purpose**, which went onto the phone as an IMAP mailbox inside
   the mail app rather than as a device Google account — `type=com.google.android.gm.legacyimap`
   instead of `type=com.google`. Credential Manager only offers the latter, so the sign-in sheet
   would never have listed it. D5 and FR-AUTH-02 make Google Sign-In the only way in, and a
   non-Gmail address is a Google identity only if a Google account was created under it. That
   distinction is subtle and is the useful thing to carry forward.

Rather than create a fourth account for one assertion, the push half is **BLOCKED and named**.
Everything else about the deletion is proved three ways — in CI, locally, and by hand over real
HTTP (§9.5).

**What would close it**, for whoever picks it up: any genuine `type=com.google` account on the
handset, then the six steps in §9b — of which **step 2 is the one that matters.** Prove the push
DOES arrive before deleting anything. Without that baseline a push failing for any other reason
reads as success, which is the green-for-the-wrong-reason shape this project keeps finding.

### 9.7 The handset environment was built, and is recorded rather than wasted

The setup worked; only the account was missing. It is in §9b so the next attempt starts from
there, and the short version is that **Railway cannot be the target**: it runs `main`, has none
of M9 in it, and its database holds the real account.

The APK's own dex was checked rather than assumed:

```
$ unzip -p …/composeApp-debug.apk classes*.dex | strings | grep -E '^https?://'
http://127.0.0.1:3000
```

That check mattered. Had the build still carried the deployed URL, a deletion driven from the
app would have reached the account with 566 people in it — and the feature under test is
irreversible by design.

---

## 9b. The handset is set up and waiting on one sign-in

Decision 4's half A. Dipen is creating the throwaway Google account; everything else is ready,
so the only step left is his.

What is in place on the Pixel 9a, checked by §E's procedure in order:

```
$ adb reverse tcp:3000 tcp:3000              3000
$ adb install -r …/composeApp-debug.apk      Success
$ adb shell am start -n in.wishkaro.app/.MainActivity
$ adb shell dumpsys window | grep -m1 mCurrentFocus
    …in.wishkaro.app/in.wishkaro.app.MainActivity
$ adb logcat -d -t 300 | grep -c 'FATAL EXCEPTION'   0
```

The build points at **`http://127.0.0.1:3000`**, reached through `adb reverse`, because the
Railway deployment runs `main` and has none of M9 in it — no `DELETE /v1/account`, no retention
sweep, no `USER_GONE` guard, no migration. `local.properties` was edited to build it and
**restored immediately afterwards**, within the same command; the URL is baked into the APK at
compile time, so the installed build keeps pointing at localhost while Dipen's own config is
untouched.

That a local server is used rather than Railway is also the safer choice by some distance: the
Railway database holds his real account with 566 invented people and **two real contacts** in
it, and a deletion test belongs nowhere near it.

### Outcome: withdrawn, and the environment torn down

No usable account was available (§9.6), so the run did not happen and Dipen withdrew half A.
The handset was left in a better state than it was found in, not the state it was borrowed in:

- a debug build pointing at the **deployed** URL is installed, verified from its own dex, so
  the app on the phone works without a server on this machine;
- `adb reverse tcp:3000` removed;
- the local dev server this session started is stopped. Postgres and Redis are left running,
  because Docker was down when this session began and a working local stack is more useful to
  the next one than an empty port.

### What the six steps would have been

In order, and the first step is the one that matters:

1. He signs in on the handset with the throwaway account, and adds a person or two.
2. **Prove the push DOES deliver first** — `POST /v1/reminders/test`, FR-REM-07's self-test,
   watched arriving. Without this step "no longer delivers" proves nothing: a push that fails
   for any other reason would read as success, which is the green-for-the-wrong-reason shape
   this project keeps finding.
3. Record the `device.fcm_token` the server now holds for that account.
4. Delete the account from the app, through the typed confirmation.
5. Assert the row counts, the blob objects and the token are gone — as §9.5 does, but for an
   account whose token is real.
6. Send to that token again and watch it **not** arrive.

---

## 10. The public deletion page is NOT built, and that is the decision

FR-SET-04 and §12 both require a deletion path reachable from a browser with no app session.
It is not here.

Not an omission: it is **decision 1**, and the build prompt names why it is the one to be
careful with — *"the public deletion page is unauthenticated by definition. Whatever proves
identity there is the single most attackable surface this app has."* Putting one up before
somebody has decided (a) what URL it lives at and (b) how it proves who is asking would be
shipping that surface by default.

`DELETE /v1/account` exists and is authenticated. The web path needs decision 1 first.

---

## 11. C8 and C9 — the account that outlived itself on both sides

Both raised in the plan, both closed once decision 3 made deletion real.

**C8, the server.** `verifyAccessToken` checked the signature and nothing else, so for up to
`JWT_ACCESS_TTL` — fifteen minutes — a phone holding its last token authenticated on **every**
guarded route after its owner had deleted everything. `POST /v1/sync` would then upsert against
a `user_id` that no longer exists and foreign-key-violate as a **500**, which is not an answer to
"my account is gone". It now checks the account exists and is not soft-deleted, and answers
`USER_GONE`. Two tests in the deletion suite: a token valid a moment ago is refused after the
deletion, and refused **from the moment the deletion starts** rather than only when it finishes.

**The cost is unmeasured and unclaimed.** One primary-key lookup per authenticated request is
the structural fact; the latency has not been measured and the comment on it says so, which is
the checklist item bought by M5 §5.2. The Redis-denylist alternative remains open.

**C9, the client.** `onSessionExpired()` clears tokens and deliberately keeps the local
database, because an expired session is reversible. A deleted account is not — and after a
deletion from another device the phone would have gone on holding 566 people, offline, for ever.
There are two callbacks now, and the error code is read **before** refreshing, because after a
hard delete the `refresh_token` row is gone too, so `refresh()` answers
`INVALID_REFRESH_TOKEN` and a deleted account would otherwise be indistinguishable from an
ordinary expired session.

---

## 12. A test failure CI found that was not mine

The deletion commit went red on three tests. Two were my own and dull: `event_occurrence` was
missing from the deletion spec's row-count map — the one table with no `user_id`, which is
exactly why it is in the inventory and exactly why it fell out of the code that counts.

The third is worth recording. `drafting.db.spec.ts`'s *"lets a draft through while there is
budget left"* failed in CI and passed locally. §10 rule 6's cap is **global by design** —
`SpendCapService.spentToday()` sums every row in `ai_usage` for the day — and **jest runs suites
in parallel workers against one database**. The deletion spec seeds ledger rows to prove they
are anonymised rather than erased, and ₹7.12 a row was enough to push the day's total past the
200 that test assumed it started below.

The fix is not cheaper rows, though they are cheaper now. **Every test in that block hard-coded
`cappedService(200)` on the assumption of an empty ledger**, and the five that assert the cap IS
hit were never going to fail this way — extra spend only helps them — which is worse, not
better: they would have passed for the wrong reason against a database that already held ₹250.
All six now express their ceiling as **headroom above the live baseline**, read through
`SpendCapService` itself, so each one tests the cap's behaviour rather than the database's
emptiness.

This is the fake-provider lesson in a different costume: a suite that is correct about the thing
it names and wrong about the environment it assumes.

---

## 13. Carried forward, named rather than quietly dropped

M9 closes none of these:

- **OEM battery helper** unexercised on a non-Pixel device — now **eight** milestones.
- **Mobile-data leg** of the Railway deploy, still unproven.
- **Instrumented suite still does not run in CI** — no device.
- **M5's definition-of-done item 4** — a real remembrance draft read by Dipen — neither done nor
  deferred; M6 did not touch it and neither does M9.
- **TBD-5** (visual identity), open since M4.
- **FR-POS-09's cache-HIT latency target**, moved to unmeasured by M6's C8.

And one new one: **the 566-person local database is no longer on the handset.** The app was not
installed when I started. M4 §0.0 records `connectedAndroidTest` wiping it once; I do not know
whether that is what happened again, and I am not going to guess.

---

## 14. Test data

Invented people only, in every fixture, test and line of this document.
`docs/fixtures/export-roundtrip.json` contains seven invented people. The two real contacts in
the 575-person account appear nowhere, and no export file was produced, saved or quoted. The
public-repo rule covers fixtures, screenshots (M5 §0.5) and now **export files**, which are a
new third thing in that family and the most PII-dense artifact this project can produce.

The one screenshot taken on the handset is the app's own sign-in screen and shows no personal
data; it lives in the session scratchpad, not in the repo.

### 14.1 The public-repo rule has a fourth half, and M9 nearly broke it

M5 §0.5 extended the rule from fixtures to **screenshots**. §14 above extends it to **export
files**. This milestone found a fourth, by being stopped on the way to doing it:

**A verification document must not carry the identifiers the verification touched.**

The first draft of §9.6 explained why three candidate accounts were unusable by naming all
three email addresses, and explained why one of them was refused by quoting two of the names it
held — names that read as real family. Every one of those strings is a real person's data, and
`docs/` is in a public repository. The reasoning in §9.6 is worth keeping; the identifiers add
nothing to it and could not be taken back once pushed.

The shape is worth stating plainly because it is not obvious in the moment: **PII arrives in a
verification document through the honesty, not through carelessness.** Explaining precisely why
an account was refused is exactly the behaviour this project asks for, and it is what put three
real addresses and two family names into a file about to be committed. The rule that covers it:
describe the *reason* in full and the *identifier* never.

Checked the same way across the rest of M9: no fixture, test, commit message, document or
screenshot in this branch carries a real address, a real name, a real phone number, an FCM
token or a `google_sub`. The scan:

```
$ git diff main..HEAD | grep -niE "@gmail\.com|@yahoo\.com|<surname>|<surname>"
(nothing)
```

### 14.3 The guard was blind to exactly the case it was written for

Found by a peer session hours after the guard was written, and it is the better half of this
whole episode.

`no-pii-in-repo.spec.ts` greps the **whole tree** — but it only **runs** when the server suite
runs, and `.github/workflows/server.yml` is path-filtered to `server/**`,
`.github/workflows/server.yml` and `docs/fixtures/**`. **The leak it was written for was in
`docs/M0_PREREQUISITES.md`.** A pull request that adds an address to `docs/` and touches nothing
under `server/` never runs the server suite, so the guard would have stayed silent on precisely
the case that motivated it.

It is the same shape as the thing it guards, one level up: the commit message for the guard
argued that `--untracked` matters because a brand-new document is "exactly how M9 nearly did
it" — and a brand-new document in `docs/` is also exactly what the path filter excludes. The
reasoning was right and did not go far enough.

**Verified before acting on it**, rather than taken on the peer's word: `server.yml`'s filter
reads as reported, and the spec is reached only through that workflow.

**The fix, and the one that was not taken.** Widening `server.yml` to `docs/**` is obvious and
cheap, and it makes every docs-only pull request run the whole server suite — migrations,
database, Redis and all — which is the thing those filters exist to prevent. Instead:

- `tools/check-no-pii.sh` holds the check. Pure `git grep`, no toolchain, no `npm ci`, nothing
  to install.
- `.github/workflows/privacy.yml` runs it with **no path filter at all**, because a guard that
  runs for some paths is not a guard. A checkout and under a second.
- `no-pii-in-repo.spec.ts` now **calls that script** instead of reimplementing it, so a
  developer running the server suite exercises the same code CI does and there is exactly one
  definition of what counts as a real address. `MILESTONE_CHECKLIST.md` says a rule implemented
  twice is held to one shared table of cases; where it is available, the cheaper answer is not
  to implement it twice.

**Revert-checked in all three shapes**, since the point is the paths it reaches:

| Planted in | Caught |
|---|---|
| `docs/leak-probe.md` — the case the server filter misses | ✅ `docs/leak-probe.md:1` |
| `PROBE.md`, untracked, at the root | ✅ `PROBE.md:1` |
| nothing | ✅ `no-pii: clean`, exit 0 |

And the output names the file and line and **never the match**, so the check cannot leak the
address into a CI log while looking for it.

### 14.3a One marker bug made two other suites flaky, and §0.2 nearly repeated itself

Running the full suite ten times to confirm the guard change, two runs failed — **different
tests each time**, and neither in the file I had just touched:

```
run 1: ● drafting — the global spend cap › degrades to the template path once the day is spent
run 5: ● what a deletion must keep: the ledger, anonymised › keeps ai_usage and generation …
```

**The near-miss first.** My instinct was to note "flaky, did not reproduce" and move on — which
is exactly what §0.2 records doing with 21 unexplained failures whose names I did not capture.
This time the names were captured, and they were enough.

**One bug, two suites.** `account-deletion.db.spec.ts` seeded ledger rows with
`pricingVersion: 'spec'` — the same literal `poster-pipeline.db.spec.ts` uses — and jest runs
suites in **parallel workers against one database**. Two consequences, the second much worse
than the first:

1. `findMany({ where: { pricingVersion: 'spec' } })` returned the other suite's rows as well, so
   `expect(usage).toHaveLength(1)` failed whenever the two overlapped.
2. The `afterEach` cleanup `deleteMany({ where: { pricingVersion: 'spec' } })` **deleted the
   other suite's ledger rows mid-test** — and since §10 rule 6's cap is a sum over the whole
   table for the day, that moved the global total `drafting.db.spec.ts`'s cap tests read. A
   cleanup wider than the thing it seeded is a cleanup that destroys another suite's fixtures.

So the fix I made in §12 — expressing the cap tests' ceiling as headroom above the live baseline
— was correct and insufficient: it made them robust to rows being **added** by other suites and
left them exposed to rows being **removed**, which is what my own cleanup was doing.

**The fix.** Everything this suite writes to a shared table carries a `MARKER` unique to the run
(`deletion-spec-<uuid>`), on `ai_usage.pricing_version`, `generation.prompt_version` and
`content_report.detail`; every query and every cleanup is scoped to it.

**Ten consecutive full-suite runs, 686 tests, no failures** — against two in five before.

The generalisable rule, which is now the thing worth carrying out of this milestone: **a DB-backed
suite sharing a database must own a unique marker, and must never delete by a predicate broader
than what it wrote.** The first half is obvious once stated; the second is the one that bites,
because a too-wide cleanup fails in a *different file*.

### 14.5 The suites were flaky before anybody touched them, and the fix is free

§14.3a fixed a marker collision and ten consecutive runs went green, which looked like the end
of it. Merging `main` in and re-running — because a merge is a good moment for a flaky suite to
look green — gave **three failures in five runs**, in three different tests:

```
leaves theme_cache and its shared artwork alone
does not cache the library placeholder under the generated-artwork key
lets a draft through while there is budget left
```

The merge changed one file and no behaviour, so the cause is not the merge. It is the
environment, and the marker fix had addressed one instance of a general problem rather than the
problem.

**What is actually shared.** Six `.db.spec.ts` suites talk to one Postgres and one Redis, and
jest runs them in parallel workers:

- **§10 rule 6's spend cap is a SUM over `ai_usage` for the day.** Global by design — it bounds
  the bill, not a feature — so every suite that writes a ledger row moves the number every
  other suite's cap test reads.
- **`theme_cache.theme_key` is UNIQUE**, and cleanup predicates written a little wider than what
  a suite seeded delete another suite's fixtures.

And the local database had accumulated **1,543 ledger rows totalling ₹44,430 for the day**,
because suites leak rows they do not clean up. That accumulation is what widens the race until
it bites.

**Measured, not assumed:**

| Mode | Result |
|---|---|
| parallel | **3 failures in 5 runs** |
| `--runInBand` | 5 in 5, then 8 more through `npm test` — **13 clean** |
| parallel wall clock | 7 s |
| serial wall clock | **7 s** |

So `package.json` now passes `--runInBand`, and `jest.config.js` carries the reasoning. The
suite is I/O-bound on Postgres rather than CPU-bound, so serialising it costs nothing
measurable. Determinism for free is not a trade-off.

**Why CI never saw it, which is the uncomfortable part.** CI starts from an empty database
every run, so the window is narrow and it has been green throughout. This is a flake that fails
on a developer's machine and passes in CI — the worse way round, because it trains people to
re-run rather than to look. It was found only because a merge prompted a re-run that nobody
needed to do.

Per-suite database isolation is the thorough fix and is not worth it at this size. What is
worth carrying forward is the rule in §14.3a, now with a second half: **a DB-backed suite
sharing a database must own a unique marker, must never delete by a predicate broader than what
it wrote — and must not assume it is alone.**

### 14.4 `main` already carries the removal

The commit that strips the two addresses was cherry-picked onto `main` as `1b3db4a` with `-x`,
on Dipen's instruction, by the session that raised it. **Verified here rather than taken on
report:** the trailer names the source commit, and `git grep` over `origin/main` returns nothing.

PR #9 therefore carries a commit whose content is already on `main`; that part of the merge is a
no-op. The work in §14.3 above is additive and is only on this branch.

---

### 14.2 Two things the same scan found on `main`, which are NOT M9's

Recorded rather than quietly fixed (`MILESTONE_CHECKLIST.md` §F: bugs found in earlier
milestones' code are recorded). Neither is on this branch; both are already committed and
public.

**`docs/M0_PREREQUISITES.md` contained two real addresses** — the owner's personal and work
Google accounts — in a table about which one the Firebase project lives under. They had been in
the public repository since M0. Not credentials, so the exposure is enumeration and spam rather
than account access, but exactly the class of thing §11's public-repo rule exists for.

**Removed on Dipen's instruction, with the limitation stated rather than glossed:** deleting the
lines does **not** unpublish them. Git history is permanent and public repositories are scraped
continuously, exactly as with a leaked secret — and unlike a secret, an address cannot be
rotated. It stops future readers and future scrapes of the current tree, and nothing more. The
table's actual content survives without the addresses, because what it was for is the
instruction "if Firebase says the project is missing, check the active account first", and
`firebase login:list` answers "which account" without anybody writing one down.

**And a guard, because four written rules did not prevent this one.** §F has said since M3 that
fixtures carry invented people; M5 §0.5 extended it to screenshots; §14 to export files; §14.1
to verification documents. A real address still sat in `docs/` across four milestones.
`server/src/common/no-pii-in-repo.spec.ts` now greps the tree for addresses at the consumer mail
hosts a real person's account lives on, plus the work domain.

Three decisions in it are the whole value:

- **Not a general email regex.** `.env.example`, the fixtures and the specs are full of
  `example.invalid` and `example.test`, and a check that flagged those would be switched off
  within a week — which is the real failure mode for a test like this. RFC 2606 and RFC 6761
  reserve those domains precisely so they can be used this way, and a test asserts they stay
  unmatched.
- **`git grep --untracked`.** Plain `git grep` searches tracked files only, so a brand-new
  document carrying an address — which is exactly how this milestone nearly did it — would not
  be searched until after it was committed, one push too late. The revert-check plants one in
  an untracked file and it is caught by name.
- **The failure names the file and line and does not print what it found.** A test that echoed
  the address would put it into CI logs and into the terminal of everybody who ran the suite.

It checks the working tree, not history, and says so in its own header.

**One name in `docs/fixtures/name-tokens.json` may not be invented.** The fixture's own header
states that every person in it is invented, and that claim is load-bearing — it is the sentence
that makes the file safe to commit. One of the names shares the owner's surname, and the same
name appears as a person row in his local account. That is entirely consistent with him having
typed the *fixture's* name into the app as a test person, which would make it invented and the
header true. It is also consistent with the reverse. **Unverified either way**, flagged because
the fixture asserts it rather than demonstrates it, and because M3, M5 and M6 all read that file.

---

## 15. What this close-out deliberately did NOT do

Written down because each one is a thing a reader might reasonably expect to find, and its
absence should be a decision rather than an oversight.

**The definition of done in `docs/BUILD_PROMPT_M9.md` was not amended.** Item 3 still ends "and
a push to that token no longer delivers", which is now a clause that was withdrawn rather than
met. M6's close-out hit the same wall with its own item 5 and drew the same line: *amending a
definition of done so that it matches the code is not a session's to do.* The withdrawal is
recorded in §9.6 and in §16; the sentence it withdraws from is left standing, so the gap stays
visible.

**The four decisions were not resolved.** Two are answered (3 and 4); two are open and are
Dipen's. Nothing in this close-out picks between them, and §16 states what each blocks.

**No §16 row was written for the launch half.** M9's build prompt is explicit that the Data
Safety form and the 14-day closed test with 12+ testers are calendar time on Dipen's account,
not build time, and they are not ticked in §12 and not claimed here.

**`main` was not pushed.** The close-out is committed locally. Pushing `main` has not been
asked and is not assumed.

**The two pre-existing findings in §14.2 were not both acted on.** The address was removed on
instruction. The name in `docs/fixtures/name-tokens.json` that shares the owner's surname is
**still unverified** — the fixture's header asserts every person in it is invented, which is the
sentence that makes the file safe to commit, and nobody has confirmed it. M3, M5 and M6 all read
that file. It is one question to Dipen and it has not been asked.

---

## 16. What Dipen owes, and what each blocks

| # | Decision | Blocks |
|---|---|---|
| 1 | Where the policy pages and the public deletion URL live — and whether that means buying a domain | items 4, 6; §12 box 1 |
| 2 | How the export is delivered: email (a new vendor, a new key, **and item 9 becomes false**) or an in-app signed URL (nothing new, weaker reading of FR-SET-03) | items 1, 2; §12 box 5 |
| 3 | ~~What deletion erases and what it anonymises~~ | **ANSWERED 2026-11-27: anonymise the ledger and the reports, erase everything else.** Built, proved in CI, enforced by the database. §9 |
| 4 | ~~How deletion is tested~~ | **ANSWERED: both a CI spec and a throwaway handset account**, then **half A withdrawn** on 2026-11-27 after three candidate accounts proved unusable (§9.6). The CI half is done, plus a live HTTP walkthrough (§9.5). Item 3's push half is **BLOCKED and named** |

**So two decisions remain: 1 and 2.** Between them they block items 1, 2, 4 and 6, §12's boxes
1, 4 and 5, and the text of the policy page.

And four smaller ones, each taken as a default pending his answer and each one line to reverse:

| # | Taken | Reversible by |
|---|---|---|
| C2 | `asset.retention` + `asset.purge_warned_at` — FR-SET-06's warning clause had nowhere to live | a migration |
| C3 | `poster.source_asset_id`, backfilled by the pipeline's own rule | a migration |
| C8 | **Taken**, once deletion made it load-bearing. One primary-key lookup in the guard; the latency is unmeasured and the comment makes no claim about it | the Redis denylist option |
| C11 | `people.csv` is birthdays only | changing the header and teaching the importer a type column |
