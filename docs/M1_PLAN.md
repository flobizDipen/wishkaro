# M1 Implementation Plan — People & Events

> Derived from `docs/BUILD_PROMPT_M1.md`, cross-read against `docs/REQUIREMENTS.md`,
> `docs/M0_PLAN.md` and `docs/M0_VERIFY.md`. Written before any code, for review.
> Date: 2026-11-25. Starting commit: `975fae1` on `main`.

What M1 must make true, on a real phone talking to **Railway**, not localhost:

```
A person has several events of different kinds
  -> every field editable, deletes recoverable
  -> a death anniversary changes the app's tone for that person, everywhere
  -> none of it needs a network connection
```

---

## 0. State of the world, as actually found

Checked on this machine today rather than assumed. Evidence in brackets.

| Thing | State |
|---|---|
| `main` | `975fae1`, clean, in sync with `origin/main`. M0 merged at `d8c2aed` |
| Secrets | `server/.env`, `composeApp/google-services.json`, `~/.secrets/wishkaro-service-account.json` all present and all matched by `.gitignore` |
| Local stack | `wishkaro-server-1`, `-redis-1`, `-db-1` all **up and healthy**, 17–18 h uptime |
| Phone | **Not connected.** `adb devices` lists nothing |
| Railway CLI | **Not installed.** `railway` is not on `PATH` |
| `PORT` | Already env-driven, already binds `0.0.0.0` (`server/src/main.ts`). Nothing to fix |
| Migrations on boot | Already in the image: `CMD npx prisma migrate deploy && node dist/main.js` |
| Both Firebase credential paths | Already implemented, env-var preferred (`src/firebase/firebase-credentials.ts`) |
| Docker build context | `./server`, per `.github/workflows/server.yml`. Railway's service root must be `server` |
| **Disk** | **1.4 GB free of 460 GB.** See §1 — this blocks everything |

### Already done, so M1 does not redo it

`BUILD_PROMPT_M1.md` asks for REQUIREMENTS §16's M0 row to be corrected (definition-of-done
item 7). **It already was**, in `975fae1`: the row now reads "against a **local** server …
✅ merged `d8c2aed`". Nothing to do; recording it so it is not "fixed" twice.

Deploy-side work the prompt anticipates that M0 already paid for: `PORT`, cold-boot
migrations, env-var-only config, both Firebase credential paths. The genuinely new deploy
surface is therefore narrow: the Railway service itself, and the client's base URL.

---

## 1. Blocker before any build: 1.4 GB of disk

```
/dev/disk3s5   460Gi  417Gi  1.4Gi  100%  /System/Volumes/Data
```

A Gradle Android build needs several GB of headroom; a Docker image build needs more. M0's
hand-over note recorded ~17 GB free and said the cause was uninvestigated. It is now 1.4 GB.

Largest directories under `$HOME`, read-only measurement:

| Size | Path |
|---|---|
| 95 G | `~/Desktop` |
| 54 G | `~/Downloads` |
| 39 G | `~/Library/Application Support` |
| 9.7 G | `~/Library/Containers` |
| 8.5 G | `~/Library/Caches` |
| 8.2 G | `~/Library/Android` |
| 3.4 G | `~/.gradle` |

I can reclaim about **3.7 GB** safely without touching your files — `docker builder prune`
(2.36 GB of build cache, 0 active) plus dangling images and volumes. That is enough to limp,
not enough to work. `~/Desktop` and `~/Downloads` hold 149 GB between them and are yours to
decide about; I will not delete anything there.

**I am not starting the Railway deploy until there is real headroom**, because a Gradle or
Docker failure caused by a full disk looks exactly like a deploy bug, and task zero exists
precisely to keep those apart.

---

## 2. Decisions — all resolved 2026-11-25

Four could not be decided silently. All four were put to the owner and **all four were
answered with the recommendation below**.

| # | Decision |
|---|---|
| 2.1 | **`sentiment` is derived from `type` AND `person.is_deceased`.** Any event on a deceased person is `SOLEMN` |
| 2.2 | **Per-record LWW stays.** `REQUIREMENTS.md` §11's "per field" is corrected to match the code |
| 2.3 | **Shared events across people are deferred**, by a path that stays a pure addition |
| 2.4 | **Task zero is proved on a debug build pointed at the Railway https URL.** Release signing and certificate pinning move to M9 |
| — | Branch: **`feat/m1-people-events`**, PR at the end. `main` stays merge-only |

The reasoning behind each, as it was put:

### 2.1 — The keystone. A deceased person's birthday must be SOLEMN, but `sentiment` is derived from `type` alone

This is the contradiction that matters most, and it sits exactly on the requirement you care
most about.

| Source | Says |
|---|---|
| `FR-EVT-01a`, and `server/src/modules/events/event-rules.ts` today | `sentiment` is a function of `type`. `DEATH_ANNIVERSARY` → `SOLEMN`, **everything else → `CELEBRATORY`** |
| `FR-EVT-05`, and `BUILD_PROMPT_M1.md` → "Death, deceased people, and tone" | A deceased person's birthday, if opted into, is a "remembrance birthday" that **behaves as `SOLEMN`** |

A remembrance birthday is `type = BIRTHDAY`. Under the current derivation it is
`CELEBRATORY`, and `buildReminderCopy` would emit `🎂 … is today / They turn 39 today.` for
someone who has died. That is the precise failure FR-EVT-05 calls the worst this app can have.

**Chosen: widen the derivation to two inputs, `sentimentFor(type, person.isDeceased)`.**
Any event belonging to a deceased person is `SOLEMN`, regardless of type. Still derived
server-side, still impossible to set from a client, still one function — so "impossible by
construction" stays literally true, and the test that no celebratory string can reach a
`SOLEMN` event covers the remembrance-birthday case for free.

Consequences I would accept with it:

- Marking a person deceased **re-derives sentiment on their existing events** and rewrites
  pending reminders. It is not a flag that only affects future writes.
- `sentiment` stops being a pure function of a single column, so it is recomputed on write
  rather than trusted from the row. The stored column stays, as a materialised derivation.

The alternative — keep `sentiment = f(type)` and add a second `remembrance` flag that the
copy layer must also consult — leaves two things to get right instead of one, and the copy
layer is exactly where you asked not to put the safety.

### 2.2 — Per-field last-write-wins: implement it, or keep per-record and say so

M0 flagged this and deferred it *to M1*, so M1 has to answer it.

| Source | Says |
|---|---|
| `REQUIREMENTS.md` §11 | "Last-write-wins **per field** on `updated_at`" |
| `M0_PLAN.md` §2 | Not implementable on this schema; one `updated_at` per row; M0 shipped per-**record** LWW; "belongs in M1 with the full sync slice" |
| `BUILD_PROMPT_M1.md` → Sync | "the **same** last-write-wins per field on `updated_at`" |

The M1 prompt's sentence is self-contradictory: what exists is per-record, so "the same …
per field" cannot both be satisfied. I am not picking.

**Chosen: keep per-record LWW for M1 and strike "per field" from §11.** Reasons: the
data is single-owner and almost always edited on one device, so the two are indistinguishable
in practice; making it per-field means a per-field version map or a change log, which is a new
table, a new wire format and a rewrite of `LocalStore.applyRemote` and `sync.service.ts` — a
milestone-sized slice on its own, landing in the same PR as all of M1's features. M1 already
widens sync to traits, profile fields, five event types and overrides. Doing both is how this
PR stops being reviewable.

If you want it properly: say so and I will scope it as its own slice, and M1's feature set
shrinks to match. What I will not do is write "per field" in a doc and ship per-record.

### 2.3 — "Multiple people may share one event" is not representable

`FR-PPL-03` has two halves: *a person may have multiple events* (M1 builds this) and
*multiple people may share one event — a couple's anniversary* (M1's prompt is silent on it).
`event.person_id` is a single column, so the second half cannot be expressed.

**Chosen: defer it, and record why it stays cheap.** Adding an `event_participant`
join table later is a pure addition: `person_id` stays on `event` as the primary participant
and the join table carries the others. No column changes, no destructive migration — which is
the constraint M1 is under. Building it now means a second write path through sync for a case
you cannot create in the UI until there is a people-picker.

### 2.4 — What build proves task zero

`BUILD_PROMPT_M1.md` says "Debug points at the local server, release at Railway", and
"Release builds must use https with no cleartext exemption". Done when: "the phone, on mobile
data with no `adb reverse`, signs in, adds a person, and receives a push".

The snag: `composeApp/build.gradle.kts` has no release signing config, and Google Sign-In in a
release build needs an OAuth client registered against the **release keystore's SHA-1** in
Firebase. So "sign in" and "release build" are not currently compatible, and a release
keystore is a secret that must never reach this public repo.

**Chosen: split the two.**

- Wire the build config exactly as the prompt asks — `debug` → local default, `release` →
  the Railway https URL, no cleartext exemption outside `src/debug`. That is a small change
  and it is the right shape.
- Prove task zero with a **debug build whose base URL is overridden to the Railway https
  URL** via `local.properties`. Mobile data, no `adb reverse`, no port forward, TLS, real
  Google sign-in on the already-registered debug SHA-1. Every clause of the done-condition is
  met except "the binary is a release build", which proves nothing about the deploy.
- Release signing + release SHA-1 + certificate pinning (§11) is a Play-track concern. It
  belongs with M9, or with whichever milestone first puts a build in front of a tester.

---

## 3. Smaller contradictions — recorded, resolved the prompt's way, not re-litigated

Flagging rather than hiding, per `BUILD_PROMPT_M1.md`'s closing line. None of these change
the shape of the work; tell me if you disagree with any.

| # | Documents | Resolution |
|---|---|---|
| a | `FR-PPL-07` lists wish history, gift history and Wish/Poster/Gift buttons on person detail. The M1 prompt says those belong to later milestones and must not be stubbed as dead UI | **Prompt wins**, and it is right — M5/M6/M8 own those surfaces and there is nothing to show |
| b | `FR-PPL-10` filters by name, relation, month **and completeness**. The M1 prompt lists name, relation, month | Build name/relation/month. Add the completeness filter only if §8 (profile depth) lands whole — otherwise it filters on a column nothing fills |
| c | `REQUIREMENTS.md` §8 gives `event_occurrence.milestone_label`; M0 omitted it; the M1 prompt's schema list does not re-add it | **Add the column**, matching §8. Milestones are derived by a pure function, and materialising the result on the occurrence keeps the send worker from re-deriving per push |
| d | `FR-PPL-08` wants a 30-day restore window; nothing purges after 30 days | M1 ships the soft delete, the restore and the immediate reminder stop. The **retention/purge job is M9** (§16 "retention jobs"). The window is enforced in the UI, not by deletion, until then |
| e | `FR-PPL-09` excludes minors from profiling; M1 adds traits and a completeness score | Minors get no trait collection and no completeness nudge. The score is computed but not used to push for more data on a minor |

---

## 4. Schema changes — all additive

No column that exists today changes type, nullability or name. Real rows exist in the Railway
database and on the phone.

### Server (one Prisma migration, `m1_people_events`)

```
person      + photo_key          text null        -- nothing writes it in M1 (upload is M6)
            + gender             text null
            + phone              text null
            + email              text null
            + notes              text null
            + is_deceased        bool not null default false
            + is_minor           bool not null default false   -- DERIVED
            + completeness_score int  not null default 0       -- DERIVED

event       + label              text null        -- CUSTOM only
            + is_muted           bool not null default false

event_occurrence
            + milestone_label    text null        -- DERIVED (§3c)

person_trait  NEW
            id, person_id, kind (HOBBY|LIKE|DISLIKE|BUDGET_BAND),
            value, source (USER|IMPORT|INFERRED),
            created_at, updated_at, deleted_at
            -- updated_at/deleted_at are not in §8's sketch; sync needs both, and the
            -- M1 prompt requires traits to sync with tombstones
```

New enums `TraitKind`, `TraitSource`, carrying their full v1 value sets as M0 did.

### Client (SQLDelight) — a real trap

`WishKaroDb` is at schema version 1 with **no `.sqm` migration files**, and
`AndroidSqliteDriver(WishKaroDb.Schema, …)` replays those files on upgrade. Adding a column
to a `.sq` file without a matching `.sqm` gives an app that compiles, installs, and then
crashes on a device that already has data — which is the phone this milestone is proved on.

So: add `1.sqm`, turn on `verifyMigrations` in the SQLDelight block, and let CI fail on a
missing migration rather than the handset. Still no `ON CONFLICT … DO UPDATE` anywhere
(SQLite 3.24 / API 30 vs `minSdk 26`).

---

## 5. Derived values live in one place and are rejected on the wire

`BUILD_PROMPT_M1.md`: derived values are server-side only, and a client posting
`sentiment=CELEBRATORY` with `type=DEATH_ANNIVERSARY` must be **rejected, not honoured**.

Today's `PersonUpsertSchema` / `EventUpsertSchema` are non-strict Zod objects, so an unknown
`sentiment` key is silently **stripped** — the write succeeds and the client is told nothing.
That is "not honoured" but it is not "rejected". M1 makes every write schema `.strict()`, so
`sentiment`, `isMinor`, `completenessScore` and `milestoneLabel` arriving from a client are a
`400` naming the offending field.

One module, `server/src/modules/events/derived.ts` (extending `event-rules.ts`), pure, no
Prisma, no Nest:

```
sentimentFor(type, isDeceased)            -> Sentiment          §2.1
isMinorFor(birthYear, today)              -> boolean            FR-PPL-09
milestoneLabelFor(type, years)            -> string | null      FR-EVT-03
completenessScore(person, traits)         -> 0..100             FR-PPL-05
COMPLETENESS_WEIGHTS                      -> the single table   "weights in one place"
```

`milestoneLabelFor` returns `"12 years"` for `DEATH_ANNIVERSARY` and never `Silver`/`Golden`;
the test asserts that over the whole 1..100 year range, not on a sample.

### Tone: two string sets, not one function with a branch

`buildReminderCopy` today is one function that branches on sentiment. The prompt asks for
"a separate string set, chosen by sentiment". M1 splits it into `SOLEMN_COPY` and
`CELEBRATORY_COPY`, selected before any string is built, plus:

- a `BANNED_IN_SOLEMN` lexicon — `celebrate`, `wish`, `congrat`, `happy`, `many happy`, and
  any emoji by Unicode property;
- an exhaustive test over **every** `(type × isDeceased × years-known × milestone)`
  combination that resolves to `SOLEMN`, asserting no banned token appears in title or body.
  It enumerates the cross-product, so a sixth event type added later fails the test until
  someone writes its solemn copy.

---

## 6. Implementation order

Each step is provable before the next depends on it. Steps 1–2 are the prompt's task zero and
carry-over, and nothing in 3+ starts until they pass.

| # | Step | Proof |
|---|---|---|
| **1** | **Railway deploy of `main`, unchanged.** CLI install, service with root `server`, managed Postgres + Redis, env vars set in Railway (incl. `FIREBASE_SERVICE_ACCOUNT` as raw JSON — first real exercise of that path), `NODE_ENV=production`, **no** `ALLOW_DEV_LOGIN` | `/health` over https; boot log names the env-var credential path; `prisma migrate deploy` applies to a cold database. Then: **phone on mobile data, aeroplane-toggled off Wi-Fi, no `adb reverse`** — sign in, add a person, receive a push. **I stop here and tell you.** |
| **2** | **The offline check M0 never ran.** Aeroplane mode → add a person → force-stop → reopen → still listed → radio back → syncs | A `person` row in the Railway database with the id the phone generated. If it fails this is an **M0 bug**, and I say so and fix it before any M1 sync work rather than building on it |
| **3** | Prisma migration + the derived-values module + its tests. No endpoints yet | `prisma migrate deploy` against the Railway database with real rows in it; unit tests on sentiment, minor, milestone, completeness |
| **4** | Server CRUD: `PATCH /v1/people/:id`, traits, soft delete + restore, search/filter, duplicate check; `PATCH`/`DELETE /v1/events/:id`, mute, `CUSTOM` + label, per-occurrence override | curl, against Railway. Including the negative cases: client-sent `sentiment` → 400; `DEATH_ANNIVERSARY` → person flips deceased; mute → pending reminders suppressed within one scheduler tick |
| **5** | Sync widened: all five event types, profile fields, traits + trait tombstones, cursor extended with a third entity. Local SQLDelight `1.sqm` | Existing cursor tests still green; a new test posts the same widened batch twice and asserts a no-op; the installed app upgrades over its existing database without losing rows |
| **6** | Client: person detail with countdown, edit, multiple events, relation chips, search/filter, delete + restore, duplicate prompt | On the phone, against Railway |
| **7** | Death and tone: `DEATH_ANNIVERSARY` creation flips `is_deceased`, re-derives sentiment on existing events, disables the birthday reminder, offers the remembrance opt-in | The tone cross-product test; and on the phone, the person's whole detail screen changes register |
| **8** | Profile depth, **last**: optional fields, trait chips, completeness score + one-tap improve | On the phone. **If this is going to be half-finished I stop and tell you instead of shipping a partial one** |
| **9** | `docs/M1_VERIFY.md`, REQUIREMENTS tidy-up, CI green, PR | Both workflows pass; you reproduce the chain from the doc alone |

### Commits

Conventional, one or two per step, each buildable. M0 was 8; M1 is a larger milestone, so
realistically **12–14**. If it starts heading past that I will say so rather than quietly
producing a 40-file drop.

Branch: **to be confirmed with you before the first commit** — not assumed. `main` has stayed
documentation-only so far.

---

## 7. Timezone, the highest-bug-density area

M0's cases all stay. M1 adds the edge the prompt names — *a person's events spanning a
timezone change mid-schedule*:

- Two events on one person, pending reminders for both, owner's timezone changes between
  them. Both `scheduled_at_utc` move; neither `dedupe_key` changes, so nothing double-sends.
- A timezone change that moves a reminder **into the past** — it must not fire retroactively
  for an occurrence that has already passed in the new zone, and must not be silently lost.
- The trap M0 found the hard way, now a test rather than folklore:
  `occurrences:materialise` rewrites any `PENDING` reminder whose time disagrees with the
  instant recomputed from its event. With `is_muted`, `is_deceased` and `override_date` all
  now able to change that instant, the reconciler has three new ways to undo a write. The
  test asserts the reconciler is **idempotent** against each.
- 29 Feb (FR-EVT-09) and year-unknown (FR-EVT-02) keep their M0 tests and gain the new
  surfaces: no age on a year-unknown person's detail screen, no milestone label, no
  "turning N" anywhere.

---

## 8. Explicitly not in M1

Import/CSV (M3) · reminder ladder, quiet hours, OEM helper, digest, local alarm fallback (M2) —
M1 still ships the single D0 step · widget, calendar, timeline (M4) · any AI (M5/M6) ·
billing (M7) · gift suggestions and affiliate links (M8) · photo upload (M6; the column
exists, nothing writes it) · retention/purge job (M9) · family tree (v2) · festivals (never) ·
release signing and certificate pinning (§2.4) · shared events across people (§2.3) ·
per-field LWW (§2.2, pending your call) · iOS beyond "it compiles".

---

## 9. What I still need from you

§2 is settled and the branch is `feat/m1-people-events`. Outstanding:

1. **Disk headroom (§1).** I will reclaim the ~3.7 GB of Docker cache on your say-so; the
   149 GB in `~/Desktop` and `~/Downloads` is yours to decide about. Nothing builds until
   there is room.
2. **Railway.** The CLI needs to authenticate as you (browser login); the project already
   exists under your account.
3. **The phone connected, and willing to leave Wi-Fi** — task zero is only proved on mobile
   data, with `adb reverse` explicitly not set.
