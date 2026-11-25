# M1 Build Prompt — People & Events

> Paste the block below into a **fresh Claude Code session** opened at the repo root.
>
> M0 is merged (`d8c2aed`). `main` now carries a working walking skeleton, so M1 starts from
> trusted code rather than a blank repo — which is exactly why the Railway deploy goes first.

---

## The prompt

```text
You are building M1 of WishKaro, a Compose Multiplatform + NestJS app. M0 is already merged
to main and working: Google sign-in, one person, one birthday, sync to Postgres, a BullMQ
scheduler, and an FCM push that lands on a real phone. Read docs/REQUIREMENTS.md for product
context and docs/M0_PLAN.md for the decisions M0 locked in. Build ONLY what this prompt
specifies.

## What M1 is

People and events, done properly. When M1 is done, this must be true on a REAL Android phone,
talking to a server running on Railway rather than localhost:

  A person can have several events of different kinds
    -> every field is editable, and deletes are recoverable
    -> a death anniversary makes the whole app change its tone for that person
    -> none of it needs a network connection to work

## Task zero: deploy to Railway, before any feature work

M0 was built local-only. REQUIREMENTS.md section 16 still claims M0 was "Deployed to Railway" —
it was not, and that row is wrong; correct it as part of this milestone.

Deploy the CURRENT main, unchanged, and prove the M0 chain again against it before you write a
line of feature code. Deployment bugs and feature bugs are different animals and you do not want
them in the same cage.

  - Railway project already exists. Managed Postgres and Redis, not containers you run.
  - FIREBASE_SERVICE_ACCOUNT as raw JSON in an env var. The code already prefers it over
    GOOGLE_APPLICATION_CREDENTIALS — this is the first time that path is actually exercised,
    so do not assume it works because there is a unit test.
  - Railway injects PORT. Bind to it. Do not hardcode 3000.
  - Migrations must run on boot, from the image, against a cold database.
  - The client's API base URL is a build config field. Debug points at the local server,
    release at Railway. Do not hardcode either.
  - Release builds must use https with no cleartext exemption. The debug
    network_security_config exists for adb reverse and must not leak into release.

Done when: the phone, on mobile data with no adb reverse and no port forward, signs in, adds a
person, and receives a push from the Railway server.

## Also carried over from M0

One M0 check was never performed on-device: offline persistence. Do it first, as the opening
move of the sync work — aeroplane mode, add a person, force-stop the app, reopen, confirm they
are still listed, restore the radio, sync, confirm the write reaches the server. If it fails,
that is an M0 bug and it blocks M1's sync work; say so rather than building on top of it.

## Scope

### Schema additions

All pure additions. Nothing in M0's tables changes shape — the deployed database now holds real
data and so does the phone's local one, so a destructive migration is not available to you.

  person        + photo_key (nullable, nothing writes it in M1), gender, phone, email,
                  notes, is_deceased, is_minor (derived, never user-set),
                  completeness_score
  event         + label (for CUSTOM), is_muted
  person_trait    NEW: id, person_id, kind (HOBBY|LIKE|DISLIKE|BUDGET_BAND),
                  value, source (USER|IMPORT|INFERRED), created_at

### People

- Full CRUD. PATCH /v1/people/:id is new; GET, POST and DELETE exist from M0.
- Relation is a chip picker with the curated list in FR-PPL-02, plus free text.
- Soft delete with a 30-day restore window (FR-PPL-08). Deleting stops reminders immediately,
  not at the end of the window.
- Search and filter by name, relation and month (FR-PPL-10).
- Person detail screen (FR-PPL-07): next event with countdown, age or years if the year is
  known, the profile, and the events list. The Wish / Poster / Gift buttons belong to later
  milestones — do not stub them in as dead UI.
- Duplicate detection on create (FR-PPL-06): fuzzy name plus same date prompts "Is this the
  same person?". Import-time dedup is M3 — create-time only here.
- Minor flag (FR-PPL-09) derived from a known birth year. It must be impossible to set from
  the client.

### Events

- Full CRUD, multiple events per person (FR-PPL-03). POST /v1/events exists; PATCH and a
  working DELETE are new.
- All five types. **sentiment is always derived from type on the server and never accepted
  from the client.** A client that posts sentiment=CELEBRATORY with type=DEATH_ANNIVERSARY
  must be rejected, not honoured.
- CUSTOM events take a user-supplied label and an arbitrary annual date (FR-EVT-06).
- is_muted stops reminders without deleting the event.
- Milestone labels (FR-EVT-03): wedding and engagement derive 1st / 10th / 25th Silver /
  50th Golden. Death anniversaries derive a plain year count — "12 years", never a
  celebratory milestone name, never Silver or Golden.
- Year unknown (FR-EVT-02) already works for birthdays. Keep it working as the surface grows:
  no age, no "turning N", no age-derived anything.
- 29 Feb still notifies on 28 Feb in non-leap years (FR-EVT-09).
- Per-occurrence override (FR-EVT-10). The override_date column exists and the effective date
  is already COALESCE(override_date, occurs_on) — wire the UI and the PATCH, and make sure
  the following year reverts to the base date.
- Timezone change reschedules pending reminders (FR-EVT-08).

### Death, deceased people, and tone

This is the part of M1 that matters most, and the part a later milestone cannot retrofit.

- Creating a DEATH_ANNIVERSARY sets is_deceased on the person (FR-EVT-04).
- A deceased person's birthday reminder is disabled by default, with an opt-in
  "remembrance birthday" that behaves as SOLEMN (FR-EVT-05).
- For SOLEMN events, copy never uses "celebrate", "wish", "congrats", or emoji. Not filtered
  at the last moment — a separate string set, chosen by sentiment.
- Make the wrong-tone case impossible by construction rather than by a copy tweak, and write
  a test that fails if a celebratory string can reach a SOLEMN event.

### Profile depth

Build this LAST. It is here rather than in M8 because gift suggestions launched against empty
profiles are worthless, and traits are collected progressively over months. If it is going to
be half-finished, stop and tell me instead — a partial version is worse than none.

- Optional fields: gender, phone, email, notes (FR-PPL-04). Photo is NOT in M1: the column
  exists, uploads wait for R2 in M6.
- person_trait entries for hobbies, likes, dislikes and budget band, as chips.
- Profile completeness score 0-100 (FR-PPL-05) with a one-tap improve affordance. Weight
  fields by their value to gift suggestions, and put the weights in one place.

### Sync

Everything above must sync. Traits and tombstones included, offline-first throughout, same
last-write-wins per field on updated_at and the same monotonic cursor. The cursor already
tracks person and event separately — extend that pattern rather than collapsing it.

## Explicitly NOT in M1

Import or CSV (M3). Reminder ladder, quiet hours, OEM helper, digest (M2) — M1 still ships
the single D0 step. Widget or calendar views (M4). Any AI (M5, M6). Billing (M7). Gift
suggestions or affiliate links (M8). Family tree (v2). Festivals (never). Photo upload (M6).

## Definition of done

1. The phone, on mobile data, runs the whole app against Railway with no port forward.
2. A person with a birthday AND a wedding anniversary: both editable, one deletable and
   restorable, all surviving a force-stop.
3. A death anniversary flips that person to solemn everywhere, and their birthday reminder is
   off unless opted in.
4. Aeroplane mode: add, edit and delete all reconcile after reconnect.
5. Year-unknown, 29 Feb, milestone labels, minor derivation and timezone reschedule each have
   unit tests.
6. CI green. docs/M1_VERIFY.md with exact reproduction steps.
7. REQUIREMENTS.md section 16's M0 row corrected.

## Engineering rules

Everything from docs/BUILD_PROMPT_M0.md still applies. In addition:

- The repo is PUBLIC. Railway values go in Railway, never in the repo.
- No destructive migrations. Real data now exists in two places.
- Derived values (sentiment, is_minor, completeness_score, milestone labels) are computed
  server-side and are never trusted from the client.
- Timezone remains the highest-bug-density area. It now has a second edge: a person's events
  spanning a timezone change mid-schedule.
- Conventional commits, small and reviewable. Branch off main, PR at the end.

## How to work

1. Write docs/M1_PLAN.md with your implementation order and show it to me before writing code.
2. Deploy to Railway and re-prove the M0 chain. Stop and tell me when that works.
3. The offline check that M0 left open.
4. Schema additions and the server CRUD, proven with curl.
5. The client: detail, edit, multiple events, search.
6. Death and tone.
7. Profile depth, last.
8. Stop and tell me when it works. Do not continue into M2.

If any requirement here conflicts with docs/REQUIREMENTS.md, stop and ask — do not silently
pick one. M0 hit exactly one of these (the Railway row) and raising it was the right call.
```

---

## Before you run this

| Check | Why |
|---|---|
| PR #1 merged, `main` green | M1 branches from it |
| Railway project exists, Postgres + Redis added | Task zero is the deploy |
| The service-account JSON available to paste as one line | `FIREBASE_SERVICE_ACCOUNT`, the env-var path |
| A phone that can leave Wi-Fi | The deploy is only proven on mobile data |

## Why the deploy is task zero

M0 deliberately stayed local so the chain could be proved without a hosting provider in the
way. That trade has a bill attached, and M1 pays it first. Everything that makes the deploy
cheap already exists — a production `Dockerfile`, env-var config with no file-path assumptions,
both Firebase credential paths, migrations that run from the image — so this should be a short
task against code you already trust. Doing it after the feature work would mean debugging
`PORT` injection and new CRUD at the same time.

## After M1

M2 is the reminder engine: the full D7/D1/D0 ladder, local alarm fallback, de-duplication,
quiet hours, the OEM battery-optimisation helper and the digest. Write its prompt in this same
shape — explicit scope, explicit non-scope, a definition of done verifiable on a real device,
and a "stop and tell me" at the end.
