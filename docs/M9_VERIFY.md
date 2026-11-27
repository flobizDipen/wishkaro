# M9 Verification — Privacy (the half that needs no Play Console)

> Branch `feat/m9-privacy`, PR #9, off `main` at `493f829`. **Not merged.**
>
> Three different claims, kept apart throughout, as M5 and M6 did:
>
> - **PROVED** — I ran it and watched the result. The command and the output are here.
> - **READ-VERIFIED** — I read the code and followed the path. Nothing executed it.
> - **BLOCKED** — not proved at all, with what it is blocked on. Not rounded up to PARTIAL
>   (`MILESTONE_CHECKLIST.md`, bought by M5 §1).

---

## 0. The honest summary, first

**M9 is a third of itself, and the missing two thirds are blocked on four decisions that are
Dipen's.** `docs/M9_PLAN.md` §3 states them, §4 says which definition-of-done items each one
blocks, and that mapping was written on day one rather than discovered on day six, which is
what the build prompt asked for.

What is built and proved: **the retention job** (FR-SET-06), which is the only requirement in
this milestone with a clock already running. What is built and partly proved: **the export**
(produced, not delivered) and **the prominent disclosure** (code and tests, not the handset).
What is written and not built: **account deletion** — its full inventory is in `M9_PLAN.md` §6,
and its central question is decision 3.

**Nothing in this milestone requires an API key.** Definition-of-done item 9, stated explicitly
as the prompt asks: the retention sweep, the export, the disclosure and the breach note need no
`OPENAI_API_KEY`, no `ANTHROPIC_API_KEY` and no new secret of any kind. The one thing that would
change that is decision 2 — **if the export is delivered by email, item 9 becomes false**, which
is raised as C1 rather than discovered later.

### 0.1 This machine could not run a database, and that shaped the verification

The host disk is at **100%** (1.1 GiB free of 460 GiB) and Docker's VM has gone read-only:

```
$ docker restart wishkaro-db-1
Error response from daemon: Cannot restart container wishkaro-db-1:
  open /var/lib/docker/.../hosts: read-only file system
```

Postgres is running but its port is not published, and the container cannot be recreated. This
is the same wall `M6_VERIFY.md` §10 hit, and I took M6's answer: **the DB-backed suites are
verified by CI rather than here.** I did not prune Dipen's Docker images or build cache to make
room — that is his machine's state, not mine to reclaim.

It is worth saying what that costs, because "CI ran it" and "I ran it" are different claims.
CI runs on a clean checkout in **one timezone (UTC)**, so the three-timezone requirement in
definition-of-done item 10 covers the **non-DB** suites only. Nobody has run `retention.db.spec.ts`
in `Pacific/Chatham`.

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
| 1 | An export produced for the 575-person account, opened, and checked to contain what is in it | **BLOCKED** — decisions 2 and 4. The producer is built and unit-proved; no export has been produced from the live account, and the live account is Dipen's with two real contacts in it. §4 |
| 2 | Its CSV re-imported through M3's importer without a mapping change | **PROVED, against a fixture — not against the 575-person account.** The real importer, the real guesser, no mapping touched, under both settings of the dd/MM toggle. §4.2 |
| 3 | An account deleted from the app and verified gone: no rows, no blob objects, no device token, no push | **BLOCKED** — decision 3 (what "gone" means for `ai_usage`) and decision 4 (whose account). The inventory of 15 tables and 7 stores is written. `M9_PLAN.md` §6 |
| 4 | The same deletion from the public web URL, with no app session | **BLOCKED** — decision 1 (where it lives) and decision 3 |
| 5 | The retention job run against seeded data of the right ages, keep-override honoured, 90/180 boundaries exact, a photo attached to a saved poster surviving | **PROVED — in CI, against real Postgres and a real blob store.** §2 |
| 6 | Policy and terms reachable in-app and from a browser with no session | **BLOCKED** — decision 1. Not started, deliberately: a policy page describing a deletion that does not exist is the worst artifact this milestone could produce |
| 7 | The prominent disclosure shown before the contacts permission on the handset | **BLOCKED on a sign-in only Dipen can do.** The code, the copy and 7 tests are done; the APK installs and launches on the Pixel 9a with 0 fatal exceptions, and stops at the sign-in screen. §3 |
| 8 | §12's checklist updated, each tick traceable to `M9_VERIFY.md` | **DONE.** 5 of 13 ticked, 8 left with a reason. §6 |
| 9 | Nothing requires an API key, and this document says so explicitly | **DONE** — §0, and the caveat that decision 2 can falsify it |
| 10 | Both targets compile, server suite green across three timezones, CI green on the exact head | **PARTIAL.** Android compiles and installs; server CI green on the head. Three timezones covers the **non-DB** suites only. §7 |

**Two of ten proved, one done, one partial, six blocked — four of those on decisions and two on
a sign-in.**

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

```
UTC                Tests: 565 passed, 565 total     (after §0.2's unexplained run)
Pacific/Chatham    Tests: 565 passed, 565 total
Asia/Kolkata       Tests: 565 passed, 565 total
```

**565, not 640** — these exclude the four `.db.spec.ts` suites, which need the Postgres this
machine cannot start. CI runs all 640 in **UTC only**. So nobody has run a DB-backed suite in a
second timezone, and item 10's three-timezone clause covers the pure suites alone.

### 7.3 iOS

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

## 9. Carried forward, named rather than quietly dropped

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

## 10. Test data

Invented people only, in every fixture, test and line of this document.
`docs/fixtures/export-roundtrip.json` contains seven invented people. The two real contacts in
the 575-person account appear nowhere, and no export file was produced, saved or quoted. The
public-repo rule covers fixtures, screenshots (M5 §0.5) and now **export files**, which are a
new third thing in that family and the most PII-dense artifact this project can produce.

The one screenshot taken on the handset is the app's own sign-in screen and shows no personal
data; it lives in the session scratchpad, not in the repo.

---

## 11. What Dipen owes, and what each blocks

| # | Decision | Blocks |
|---|---|---|
| 1 | Where the policy pages and the public deletion URL live — and whether that means buying a domain | items 4, 6; §12 box 1 |
| 2 | How the export is delivered: email (a new vendor, a new key, **and item 9 becomes false**) or an in-app signed URL (nothing new, weaker reading of FR-SET-03) | items 1, 2; §12 box 5 |
| 3 | What deletion erases and what it anonymises, table by table — particularly `ai_usage`, where DPDP erasure and §10 rule 2 collide, and where **`onDelete: Cascade` has already chosen the option rule 2 forbids** | items 3, 4; §12 box 4; the policy page's text |
| 4 | How deletion is tested, given the only live account is his | items 3, 4's *proof* |

And four smaller ones, each taken as a default pending his answer and each one line to reverse:

| # | Taken | Reversible by |
|---|---|---|
| C2 | `asset.retention` + `asset.purge_warned_at` — FR-SET-06's warning clause had nowhere to live | a migration |
| C3 | `poster.source_asset_id`, backfilled by the pipeline's own rule | a migration |
| C8 | **Not taken.** The auth guard still never asks the database, so an access token outlives a deleted account by up to `JWT_ACCESS_TTL` | — |
| C11 | `people.csv` is birthdays only | changing the header and teaching the importer a type column |
