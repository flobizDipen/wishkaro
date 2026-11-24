# M0 Build Prompt — Walking Skeleton

> Paste the block below into a **fresh Claude Code session** opened at the repo root.
> Read `docs/REQUIREMENTS.md` first if you want context; the prompt points at it.
>
> **Why one prompt per milestone:** a single prompt covering all 11 milestones reliably produces
> 11 half-finished features and a codebase nobody can review. One milestone at a time produces a
> working app you can install at every step. Resist the urge to merge prompts.

---

## The prompt

```text
You are building M0 of WishKaro, a Compose Multiplatform + NestJS app. Read
docs/REQUIREMENTS.md for full product context before writing any code — but build
ONLY what this prompt specifies. Do not implement features from later milestones.

## What M0 is

A walking skeleton: the thinnest possible slice that proves the entire chain works
end to end. When M0 is done, this must be true on a REAL Android phone:

  Sign in with Google
    → add one person with one birthday
    → it persists locally and syncs to Postgres
    → the server schedules a reminder
    → an FCM push arrives on the phone at the scheduled time
    → tapping it opens the app on that person

Nothing else. No import, no AI, no gifts, no billing, no widget, no calendar view,
no family tree. A deliberately ugly but completely working vertical slice.

## Definition of done

1. `./gradlew :composeApp:assembleDebug` produces an installable APK.
2. `docker compose up` brings up server + Postgres + Redis locally.
3. The full chain above works on a physical Android device, verified by me.
4. CI is green on GitHub Actions (build + lint + test, path-filtered).
5. A `docs/M0_VERIFY.md` with exact steps for me to reproduce the chain myself.

## Stack — do not substitute

Client: Kotlin Multiplatform + Compose Multiplatform, Android target building and
iOS target compiling (not shipped). minSdk 26, targetSdk latest stable.
Koin for DI, Ktor client, SQLDelight, kotlinx-serialization, kotlinx-datetime.

Server: NestJS + TypeScript, PostgreSQL via Prisma, Redis + BullMQ, Zod validation,
Pino logging. Firebase Admin for FCM.

Infra: Docker + docker-compose for local. GitHub Actions with path filters so an
Android-only change does not run server CI and vice versa.

## Repo layout

wishkaro/
├── composeApp/
│   ├── src/commonMain/kotlin/in/wishkaro/   ui, domain, data, di
│   ├── src/androidMain/kotlin/in/wishkaro/  actuals: notifications, secure storage
│   └── src/iosMain/kotlin/in/wishkaro/      actuals, stubs acceptable in M0
├── server/src/modules/                      auth, people, events, reminders, sync
├── contracts/openapi.yaml
├── infra/
├── docs/
└── .github/workflows/

Package: in.wishkaro.app (Android applicationId and iOS bundle id).

## Scope detail

### Auth
- Google Sign-In on Android via Credential Manager.
- POST /v1/auth/google exchanges the Google ID token (verify it server-side against
  Google's certs — do not trust the client) for a short-lived JWT access token plus
  a rotating refresh token.
- Tokens stored in EncryptedSharedPreferences on Android, behind an expect/actual
  SecureStorage interface.
- First sign-in creates a `user` row and a self `person` row.

### Data
Only these tables in M0. Model them so the full schema in REQUIREMENTS.md §8 is a
pure addition later, not a migration of existing columns:

  user              id, google_sub, email, name, photo_url, timezone,
                    created_at, deleted_at
  person            id, user_id, display_name, relation_to_owner, is_self,
                    created_at, updated_at, deleted_at
  event             id, person_id, user_id, type, sentiment, day, month,
                    year (nullable), calendar_system default 'GREGORIAN',
                    created_at, updated_at, deleted_at
  event_occurrence  id, event_id, occurs_on, override_date (nullable), computed_at
  reminder          id, event_occurrence_id, user_id, offset, scheduled_at_utc,
                    channel, status, dedupe_key, sent_at
  device            id, user_id, fcm_token, platform, timezone, last_seen_at

In M0 `type` only ever takes BIRTHDAY and `sentiment` only CELEBRATORY — but define
both as enums with the full value sets from REQUIREMENTS.md §8 now, so later
milestones add behaviour rather than altering columns.

### Client
Three screens, plain Material 3, no design polish:
1. Sign-in screen.
2. People list — shows name, relation, next birthday, days until.
3. Add person — name, date picker (with a "year unknown" checkbox that must actually
   work end to end), relation chip picker.

SQLDelight is the read source of truth. Writes go to the local DB first and are
queued for sync. The list must render with the network off.

### Sync
- GET /v1/sync?cursor= returns changes since the cursor.
- POST /v1/sync accepts batched upserts and tombstones.
- Last-write-wins per field on updated_at. Soft deletes as tombstones.
- Monotonic cursor. Must be safe to call repeatedly — idempotent.

### Reminders
- A BullMQ repeatable job materialises the next occurrence for every event and
  creates reminder rows.
- A send worker picks due reminders and sends FCM.
- M0 ships ONE ladder step: morning-of at 09:00 in the user's timezone.
- Effective date is COALESCE(override_date, occurs_on) — wire this now even though
  nothing sets override_date in M0.
- Every reminder carries a dedupe_key; the client must ignore a duplicate.
- POST /v1/reminders/test queues a reminder 2 minutes out. This is how I verify the
  chain without waiting until tomorrow morning — build it first, it is your own
  fastest feedback loop.
- Android 13+ POST_NOTIFICATIONS runtime permission, requested with a rationale.

## Engineering rules

- No secrets in the repo. `.env.example` committed, `.env` gitignored. The app reads
  the API base URL from a build config field, never a hardcoded string.
- Every endpoint validates input with Zod and returns typed errors.
- Timezone handling is the highest-bug-density area in this app. All instants stored
  UTC; all user-facing dates computed in the user's timezone; unit tests for
  year-unknown birthdays, leap-day (29 Feb) birthdays, and a user who changes
  timezone with a reminder already scheduled.
- Write tests as you go for date/recurrence logic specifically. Skip UI tests in M0.
- Conventional commits. Small, reviewable commits — not one giant drop.
- Do not add dependencies beyond the stack listed above without telling me why first.

## How to work

1. Start by writing docs/M0_PLAN.md with your implementation order and show it to me
   before writing code.
2. Build the server first, up to the test-reminder endpoint, and prove it with curl.
3. Then the client, up to receiving that push.
4. Then sync.
5. Stop and tell me when the chain works. Do not continue into M1.

If any requirement here conflicts with docs/REQUIREMENTS.md, stop and ask — do not
silently pick one.
```

---

## Before you run this

| Check | Why |
|---|---|
| Firebase project created, `google-services.json` downloaded | FCM will not work without it, and it is the long pole in M0 |
| Google Cloud OAuth client IDs (Android + Web) created | Google Sign-In needs both; the Web client id is what the server verifies against |
| Play Console developer account created | The 12-tester / 14-day closed-test gate is calendar time — start it now, not at M9 |
| Railway or Fly account (`TBD-7`) | M0's definition of done includes a deployed server |
| Docker Desktop running | Local Postgres + Redis |

## After M0

Milestones M1–M11 are listed in `docs/REQUIREMENTS.md` §16. Write a fresh prompt per
milestone, in the same shape as this one: explicit scope, explicit non-scope, a definition
of done that is verifiable on a real device, and a "stop and tell me" instruction at the end.
