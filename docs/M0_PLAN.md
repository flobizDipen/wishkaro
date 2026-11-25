# M0 Implementation Plan — Walking Skeleton

> Derived from `docs/BUILD_PROMPT_M0.md`. Written before any code, for review.
> Date: 2026-11-24.

The chain M0 must prove, on a real phone:

```
Google sign-in → add one person with one birthday → persists locally
  → syncs to Postgres → server materialises an occurrence → schedules a reminder
  → FCM push arrives → tapping it opens the app on that person
```

Nothing else ships. No import, AI, gifts, billing, widget, calendar or tree.

---

## 1. Decisions — all resolved 2026-11-24

Four things could not be decided silently. All four were put to the owner and **all four were
answered with the recommendation below**:

| # | Decision |
|---|---|
| 1.1 | **M0 is local-only.** The Railway deploy moves to the start of M1 |
| 1.2 | The three schema additions are approved |
| 1.3 | **`luxon` approved** as a server dependency |
| 1.4 | **Dev-login shortcut approved**, double-gated |
| — | Branch: **`feat/m0-walking-skeleton`**, PR at the end. `main` stays documentation-only |

### 1.1 Conflict: is a Railway deploy inside M0? — **must resolve**

The two documents disagree, so I am not picking one:

| Source | Says |
|---|---|
| `BUILD_PROMPT_M0.md` → Definition of done | 5 items: APK, `docker compose up`, chain on device, CI green, `M0_VERIFY.md`. **No deploy.** |
| `REQUIREMENTS.md` §16, M0 row | "… notification lands on a real phone. **Deployed to Railway**, CI green." |

**My recommendation: build M0 local-only, and treat the Railway deploy as the first task of M1.**
The chain is proved against a local server over `adb reverse`; nothing about the proof gets
stronger by adding a hosting provider, and deploy-day problems (cold DB, migration on boot,
`FIREBASE_SERVICE_ACCOUNT` as an env var, Railway's `PORT` injection) are a separate class of bug
from the ones M0 exists to flush out. The prompt's own argument — don't debug deployment and
feature logic at the same time — applies to itself.

Everything that makes the deploy cheap *is* in M0 regardless: a production `Dockerfile`, env-var
based config with no file-path assumptions, both Firebase credential paths, and migrations that
run from the image. So the deploy becomes a 30-minute task, not a rebuild.

### 1.2 Three additions to the M0 schema

The prompt lists six tables and says "only these". These three deviations are needed to make the
listed behaviour actually work. All are pure additions — nothing in `REQUIREMENTS.md` §8 has to
change later.

| # | Deviation | Why it is unavoidable |
|---|---|---|
| A | New table `refresh_token` (`id, user_id, token_hash, expires_at, revoked_at, replaced_by_id, created_at`) | The prompt requires a **rotating** refresh token. Rotation means the server must know which token is current and invalidate its predecessor — that is server-side state by definition. A stateless JWT refresh token cannot be rotated or revoked. Hashed at rest, so a DB dump does not yield usable tokens. |
| B | `reminder.event_occurrence_id` becomes **nullable** | `POST /v1/reminders/test` has to work *before* any person, event or occurrence exists — that is the entire point of building it first. With a non-null FK, the test endpoint cannot create a row. The send worker falls back to generic test copy when the FK is null. |
| C | `user.locale` column included now | §8 has it; the prompt's list omits it. Adding a column is free today and a migration later. No code reads it in M0. |

### 1.3 One new server dependency: `luxon`

Not in the stack list, so I am asking. The server must convert "09:00 on 14 March in
`Asia/Kolkata`" into a UTC instant, and must do it correctly across DST for users who are not in
India. Node 22 has no IANA-aware date arithmetic: `Temporal` is not in stable Node, and the
`Intl.DateTimeFormat` offset-probe trick is about twenty lines of exactly the fiddly code that
`REQUIREMENTS.md` §11 names as the highest-bug-density area in the product.

`luxon` is ~70 KB, has no transitive dependencies, and is the standard answer. **Recommend: add
it.** If you would rather hold the line on the dependency list, I will hand-roll the offset probe
and cover it with the same test suite — it is more risk for no benefit, but it is your call.

The client needs nothing extra: `kotlinx-datetime` already does this properly.

### 1.4 A development-only login shortcut

The prompt says prove the server with curl before the client exists. Every endpoint except
`/v1/auth/google` needs a session, and minting a real Google ID token requires the Android app —
the thing that does not exist yet.

**Recommend:** `POST /v1/auth/dev-token`, which issues a session for a fake Google subject. It is
registered **only** when `NODE_ENV === 'development'` **and** `ALLOW_DEV_LOGIN=true`, and the
server **refuses to boot** if `ALLOW_DEV_LOGIN=true` while `NODE_ENV=production`. The flag is
absent from `.env.example`, so a deploy cannot inherit it by copy-paste.

This is a real attack surface in a public repo, which is why it is a question and not a footnote.
The alternative is that the whole server stays unverifiable until the Android client signs in for
the first time — which collapses the two hardest parts of M0 into one debugging session.

---

## 2. Known gap I am recording rather than solving

**"Last-write-wins per field" is not implementable on this schema.** `REQUIREMENTS.md` §11 and
the prompt both specify per-**field** LWW, but `person` and `event` each carry a single
`updated_at`. Per-field resolution needs a per-field version map (or a change log) that §8 does
not have.

M0 implements **per-record LWW on `updated_at`**, with the client sending whole records. For
single-owner data edited on one device at a time, the two are indistinguishable in practice. If
per-field is genuinely wanted, it is a schema change and belongs in M1 with the full sync slice —
flagging it now rather than quietly shipping something narrower than the spec says.

---

## 3. Architecture

### Server — NestJS

```
server/
├── prisma/schema.prisma          6 tables + refresh_token, full enums
├── src/
│   ├── main.ts                   bootstrap, Pino, global Zod pipe, /health
│   ├── config/                   Zod-validated env. Boot fails on bad config
│   ├── prisma/                   PrismaService
│   ├── firebase/                 Admin init: env-var JSON preferred, file path fallback
│   ├── queue/                    BullMQ connection, queue registration
│   └── modules/
│       ├── auth/                 /v1/auth/google · /refresh · /logout, JWT guard
│       ├── people/               /v1/people CRUD (thin — sync is the real write path)
│       ├── events/               /v1/events CRUD
│       ├── devices/              /v1/devices register FCM token + timezone
│       ├── reminders/            /v1/reminders/test, materialise job, send worker
│       └── sync/                 GET/POST /v1/sync
└── src/common/date/              PURE date logic — the unit-tested core
```

`src/common/date/` has no NestJS, no Prisma and no I/O. Occurrence computation, leap-day
handling, year-unknown handling and local-09:00→UTC conversion live there as plain functions, so
the highest-risk logic is testable without a database.

**Auth.** `google-auth-library`'s `OAuth2Client.verifyIdToken` against `GOOGLE_CLIENT_ID` (the
Web client). Google's certs are fetched and cached by the library — the client's token is never
trusted. First sign-in creates `user` + a self `person` (`is_self = true`). Access JWT 15 min,
refresh token 60 days, rotated on every use, old one revoked.

**Reminders.** Two BullMQ jobs:

- `occurrences:materialise` — repeatable, every 10 minutes (M0 cadence; nightly in M2). For each
  live event it computes the next occurrence in the owner's timezone, upserts `event_occurrence`,
  and creates the morning-of `reminder` at 09:00 local → UTC. Also enqueued on demand after an
  event is written, so adding a person schedules immediately instead of up to 10 minutes later.
- `reminders:send` — repeatable, every 30 seconds. Claims due `PENDING` rows with
  `FOR UPDATE SKIP LOCKED`, sends FCM to the user's devices, marks `SENT`/`FAILED`. A DB scan
  rather than one delayed job per reminder, so a Redis flush loses nothing.

Effective date is `COALESCE(override_date, occurs_on)` from the first line of code, even though
nothing writes `override_date` in M0.

`dedupe_key` is `{occurrenceId}:{offset}` (`test:{userId}:{epochMs}` for test sends), unique in
Postgres, and carried in the FCM data payload so the client can ignore a repeat.

**Sync.** Cursor is `updated_at` + `id` as a tiebreak, base64-encoded — monotonic and stable
across equal timestamps. `POST` accepts batched upserts and tombstones with **client-generated
UUIDv7 ids**, so a record created offline keeps its identity forever and a replayed batch is a
no-op. Idempotency verified by a test that posts the same batch twice.

### Client — Compose Multiplatform

```
composeApp/src/
├── commonMain/kotlin/in/wishkaro/
│   ├── ui/        signin · people list · add person (Material 3, deliberately plain)
│   ├── domain/    models, next-birthday maths (shared, unit-tested)
│   ├── data/      SQLDelight, Ktor client, repositories, sync engine, outbox
│   └── di/        Koin modules
├── androidMain/   actuals: SecureStorage (EncryptedSharedPreferences), FCM service,
│                  notification channel, Credential Manager sign-in, SQLDelight driver
└── iosMain/       actuals: stubs that compile and throw NotImplementedError
```

SQLDelight is the only read source. Every write goes local-first and lands in an **outbox** table
that the sync engine drains; the list renders with the radio off. `API_BASE_URL` is a
`BuildConfig` field fed from `local.properties`, never a literal.

### Infra

- `infra/docker-compose.yml` — Postgres 17, Redis 7, and the server built from
  `server/Dockerfile` (multi-stage, runs `prisma migrate deploy` on start).
- `.github/workflows/server.yml` — `paths: server/**`. Lint, typecheck, unit tests, Prisma
  validate, build.
- `.github/workflows/android.yml` — `paths: composeApp/**, gradle/**`. `assembleDebug`, shared
  unit tests, and the iOS target's compile task so it cannot silently rot.
- `contracts/openapi.yaml` — hand-written for the M0 endpoints only.

---

## 4. Implementation order

Ordered so each step is provable before the next one depends on it, and so the two genuinely
risky things — FCM delivery on a physical device, and Google sign-in — happen early rather than
on the last day.

| # | Step | Proof it works |
|---|---|---|
| 1 | Repo scaffolding, `docker-compose.yml`, CI skeleton | `docker compose up` starts Postgres + Redis |
| 2 | Nest bootstrap, Zod env config, Pino, `/health`, Prisma schema + initial migration | `curl /health` returns ok; migration applies to a clean DB |
| 3 | Firebase Admin both-ways credential loading; `POST /v1/devices` | Boot logs confirm which credential path was used; both paths exercised by a test |
| 4 | **`POST /v1/reminders/test`** + `reminders:send` worker | curl queues a reminder 2 min out; worker wakes, calls FCM, and FCM rejects the dummy token with `registration-token-not-registered` — which proves scheduling, the worker loop and real credentials. Actual delivery waits for step 8. |
| 5 | Auth module (+ dev-token shortcut, §1.4) | curl gets a session; refresh rotates; a reused refresh token is rejected |
| 6 | `src/common/date/` + its unit tests; people/events modules; `occurrences:materialise` | Tests green on year-unknown, 29 Feb, and a timezone change with a reminder already pending. curl: create person+event → occurrence and reminder rows appear |
| 7 | Client scaffold: KMP + CMP, Android + iOS targets, Koin, Ktor, SQLDelight | `./gradlew :composeApp:assembleDebug` installs on `4B201JEBF03493`; iOS target compiles |
| 8 | Sign-in screen → Credential Manager → `/v1/auth/google`; tokens in EncryptedSharedPreferences. Then FCM service, notification channel, `POST_NOTIFICATIONS` with rationale, token registration | Sign in on the phone, run the step-4 curl → **a push lands on the device**. This is the moment M0 is no longer theoretical. |
| 9 | SQLDelight schema, repositories, people list + add person (working year-unknown checkbox), offline read | Add a person with the radio off, kill the app, reopen — it is still there |
| 10 | Sync engine: outbox drain, `POST /v1/sync`, `GET /v1/sync?cursor=`, tombstones, idempotency | Add a person on the phone → row in Postgres → occurrence → reminder scheduled for 09:00 |
| 11 | Deep link: notification tap opens the person; client-side `dedupe_key` suppression | Tap the push, land on the right person. Send the same `dedupe_key` twice — one notification |
| 12 | CI green, `docs/M0_VERIFY.md` | Both workflows pass; you reproduce the chain from the doc alone |

A reminder scheduled for 09:00 tomorrow is verified by reading the `reminder` row's
`scheduled_at_utc` and by the unit tests, not by waiting until morning. `POST /v1/reminders/test`
is what proves the delivery path.

### Commits

Conventional commits, one per step above, each one buildable. Branch decided with you before the
first commit — not assumed.

---

## 5. Test plan for the date logic

Pure functions in `src/common/date/`, mirrored in `commonMain` for the client's countdown. The
cases from the prompt, plus the ones that bite in practice:

- Year known, birthday later this year → this year's date.
- Year known, birthday already passed → next year.
- Birthday **today** → today, not next year (the classic off-by-one).
- **Year unknown** → occurrence still computed, age/"turning 30" copy suppressed everywhere.
- **29 Feb** in a leap year → 29 Feb. In a non-leap year → **28 Feb** (`FR-EVT-09`).
- 29 Feb with an unknown year → still resolves.
- 09:00 `Asia/Kolkata` → 03:30 UTC. 09:00 `America/New_York` → 13:00 or 14:00 UTC depending on
  the date, proving DST is handled rather than an offset being cached.
- **Timezone change with a reminder already pending** → pending reminders are recomputed, their
  `scheduled_at_utc` moves, and `dedupe_key` does not change so no duplicate is sent.
- A reminder whose 09:00 local has already passed today → scheduled for the next occurrence, not
  fired retroactively.

---

## 6. Out of scope — stated so it does not creep in

Import · AI wishes or posters · gifts · billing · widget · calendar and timeline views · family
tree · contacts permission · T-7 and T-1 ladder steps · quiet hours · digest · OEM battery
helper · on-device alarm fallback (M2) · multiple events per person · event types other than
`BIRTHDAY` · person detail screen · search · photos · iOS beyond "it compiles" · UI tests.

Enums carry their full value sets now, so later milestones add behaviour instead of migrating
columns.
