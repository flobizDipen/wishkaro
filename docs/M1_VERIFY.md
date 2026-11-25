# M1 — verifying people & events yourself

> Companion to `docs/M0_VERIFY.md`, which still describes the local stack. This document
> describes the **deployed** server and the things M1 adds on top of it.
>
> Written as it was done, not as it was planned. Where something bit, it is recorded here
> rather than in a commit message nobody re-reads.

---

## 0. What you need

| | |
|---|---|
| A Railway account | The project is `steadfast-education`; the service is `wishkaro` |
| `railway` CLI | `npm install -g @railway/cli`, then `railway login` |
| An Android phone | With a SIM, because task zero is only proved off Wi-Fi |
| Disk headroom | A Gradle build needs several GB. See §7 |

Nothing in this document needs `adb reverse`, a port forward, or a server running on your
machine. That is the point of it.

---

## 1. The deployment

**Base URL:** `https://wishkaro-production-0312ab77.up.railway.app`

### Service configuration (set once, 2026-11-25)

The service was already connected to `flobizDipen/wishkaro` but had never built, because its
root directory was the repository root: Railpack detected the Gradle files and tried to build
the *Android app* on a Linux container, which died on `SDK location not found … ANDROID_HOME`.

```bash
railway link --project 99046f80-1431-4fce-bff8-f2e7f48c1e3c \
             --environment production --service wishkaro

railway api 'mutation($serviceId: String!, $environmentId: String, $input: ServiceInstanceUpdateInput!) {
               serviceInstanceUpdate(serviceId: $serviceId, environmentId: $environmentId, input: $input)
             }' \
  --raw-var serviceId=f79e1fb7-4b67-4490-8b8e-dd9fb6ceacfa \
  --raw-var environmentId=c727358c-4951-43d7-9c64-c1f2e27196b3 \
  --var 'input={"rootDirectory":"server","healthcheckPath":"/health","healthcheckTimeout":120,
                "watchPatterns":["server/**"],"restartPolicyType":"ON_FAILURE",
                "restartPolicyMaxRetries":10}'
```

`rootDirectory: "server"` is the whole fix — Railway then finds `server/Dockerfile` and builds
it with `server/` as the context, exactly as `.github/workflows/server.yml` does.
`watchPatterns: ["server/**"]` keeps an Android-only commit from redeploying the server.

### Environment variables

Set with `railway variable set … --stdin --skip-deploys` so no secret ever appears in a shell
history or a command line.

| Variable | Value |
|---|---|
| `NODE_ENV` | `production` |
| `DATABASE_URL` | `${{Postgres.DATABASE_URL}}` |
| `REDIS_URL` | `${{Redis.REDIS_URL}}?family=0` — **see below** |
| `GOOGLE_CLIENT_ID` | carried from `server/.env` |
| `FIREBASE_PROJECT_ID` | `wishkaro-1c95e` |
| `JWT_ACCESS_SECRET` | **freshly generated**, 48 random bytes, not the local one |
| `JWT_REFRESH_SECRET` | **freshly generated**, 48 random bytes, not the local one |
| `FIREBASE_SERVICE_ACCOUNT` | the service-account JSON, **base64** |
| `DEFAULT_TIMEZONE` | `Asia/Kolkata` |
| `DEFAULT_REMINDER_HOUR` | `9` |
| `PORT` | **not set.** Railway injects it; the app binds whatever it is given |
| `ALLOW_DEV_LOGIN` | **not set, and must never be.** Not `false` — absent |

Two of those are worth the words:

**`?family=0` on the Redis URL.** Railway's private network is IPv6-only, and `ioredis`
defaults to an IPv4 lookup. Without `family=0`, `redis.railway.internal` never resolves,
BullMQ never connects, and nothing is scheduled or sent — with no error anywhere near the
word "Redis". It looks exactly like broken push. `Reminder queues started` in the boot log is
the line that proves it connected.

**Base64 for the service account.** `src/firebase/firebase-credentials.ts` accepts both raw
JSON and base64. Base64 cannot be mangled by a shell, a web form, or a copy-paste that eats a
newline, and the whole value stays on one line.

### Deploying a commit

```bash
railway api 'mutation($serviceId: String!, $environmentId: String!, $commitSha: String) {
               serviceInstanceDeployV2(serviceId: $serviceId, environmentId: $environmentId, commitSha: $commitSha)
             }' \
  --raw-var serviceId=f79e1fb7-4b67-4490-8b8e-dd9fb6ceacfa \
  --raw-var environmentId=c727358c-4951-43d7-9c64-c1f2e27196b3 \
  --raw-var commitSha=$(git rev-parse origin/main)

railway deployment list --service wishkaro --json | head -5
railway logs --build  --service wishkaro
railway logs --deployment --service wishkaro
```

---

## 2. Proving the deployment, without the phone

```bash
U=https://wishkaro-production-0312ab77.up.railway.app

curl -sS "$U/health"
# {"status":"ok","db":"up","uptimeSeconds":17}

curl -sS -o /dev/null -w '%{http_code}\n' "$U/v1/people"
# 401 — the global guard is on

curl -sS -o /dev/null -w '%{http_code}\n' -X POST "$U/v1/auth/dev-token" \
     -H 'content-type: application/json' --data '{"email":"probe@example.com","name":"Probe"}'
# 404 — the dev login is gated off in production
```

### What the first boot log must contain

```
1 migration found in prisma/migrations
Applying migration `20261124131717_m0_walking_skeleton`
All migrations have been successfully applied.
...
Connected to Postgres
Firebase Admin ready — project wishkaro-1c95e, credentials from FIREBASE_SERVICE_ACCOUNT
Reminder queues started
WishKaro API listening on :8080 (production)
```

Four claims are proved by those lines and nothing else proves them:

1. **Migrations run from the image against a cold database.** The managed Postgres was empty;
   the container applied the M0 migration on start.
2. **The `FIREBASE_SERVICE_ACCOUNT` path works for real.** Until this deploy it had only ever
   been exercised by a unit test. `credentials from FIREBASE_SERVICE_ACCOUNT` is the server
   saying which branch it took.
3. **`PORT` is injected and honoured.** `:8080`, not `:3000`. Nothing is hardcoded.
4. **Redis is reachable over the private network.** `Reminder queues started`.

---

## 3. Known defect found by deploying — `dev-token` advertises itself

Found while probing the deployed server, 2026-11-25. **Not an authentication bypass.**

`DevAuthController` is registered unconditionally and gates itself *inside* the handler. The
`ZodValidationPipe` on `@Body()` runs **before** the handler, so a request with no body at all
fails validation and returns `400` — while a route that genuinely does not exist returns `404`:

```bash
curl -sS -o /dev/null -w '%{http_code}\n' -X POST "$U/v1/auth/dev-token"      # 400
curl -sS -o /dev/null -w '%{http_code}\n' -X POST "$U/v1/auth/no-such-route"  # 404
```

An unauthenticated prober can therefore tell that `/v1/auth/dev-token` exists on a public URL,
which is precisely what the comment in `auth.controller.ts` says it must not do:

> 404 rather than 403: a disabled endpoint should not advertise that it exists.

It is harmless today — with any parseable body the gate fires and returns 404, and
`ALLOW_DEV_LOGIN` is not set — but the right fix is to not register the controller at all
outside development, rather than to guard inside it. Logged here because it was only ever
findable once the server was public.

---

## 4. The client, pointed at the deployment

`composeApp/build.gradle.kts` reads two keys from the gitignored `local.properties`:

```properties
wishkaro.apiBaseUrl=https://wishkaro-production-0312ab77.up.railway.app
# wishkaro.releaseApiBaseUrl=…   defaults to the Railway URL; must be https or the build fails
```

- **debug** defaults to `http://127.0.0.1:3000` (the `adb reverse` setup from `M0_VERIFY.md`)
  and is overridden above to point at Railway.
- **release** defaults to the Railway URL and the build **fails** if it is not `https` — the
  cleartext exemption lives in `src/debug/` and cannot reach a release build, so a plaintext
  release URL would produce an app that installs and then fails every request.

Why task zero is proved on a *debug* build pointed at https rather than on a release build:
Google Sign-In needs an OAuth client registered against the signing key's SHA-1, and no
release keystore exists — nor should one reach this public repo. Every clause of the
done-condition (mobile data, no `adb reverse`, no port forward, TLS, real Google sign-in) is
met by the debug build. Release signing and certificate pinning belong to the milestone that
first puts a build in front of a tester. See `docs/M1_PLAN.md` §2.4.

```bash
./gradlew :composeApp:assembleDebug
adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

**Then, before touching the phone:**

```bash
adb reverse --remove-all     # there must be no port forward
```

Turn Wi-Fi off on the handset. Mobile data only. Sign in, add a person, and watch the server:

```bash
railway logs --deployment --service wishkaro
```

---

## 5. Task zero: what is proved, and what is not

Proved on handset `4B201JEBF03493` (Pixel 9a), 2026-11-25, against Railway with the debug
build pointed at the https URL, **no `adb reverse` and no port forward**:

```
POST /v1/auth/google   -> 200     real Google sign-in, real user row on Railway
POST /v1/devices       -> 200     FCM token registered
GET  /v1/sync          -> 200
POST /v1/sync          -> 200     the person reached Postgres
GET  /v1/sync?cursor=… -> 200
```

The phone's local database afterwards, both rows `pending_sync = 0`, meaning the outbox
drained and the server acknowledged the writes:

| id | display_name | relation | |
|---|---|---|---|
| `01a211dc-…` | dipen jansari | Self | |
| `744b8df1-…` | Jaydatt Jansari | Brother | 22/9/1988 |

### What task zero proves, and what it does not

Split precisely, because the two halves are not the same claim.

**Proved, over Wi-Fi, with `adb reverse --list` AND `adb forward --list` both empty:**

- the APK's compiled base URL is the deployed https host, not a local one;
- TLS end to end, with the cleartext exemption confined to `src/debug/`;
- real Google sign-in against the deployed server;
- writes reaching Postgres on Railway, and FCM registration from it;
- the whole feature set of §5b, driven through the app on the handset.

**Not proved: independence from the home network.** The traffic went out over broadband, so
the client IP in the Railway log is the same public address as this machine's `curl` calls.
Nothing *reached* a local server — there was no tunnel and the base URL is the Railway domain —
but the prompt asks for mobile data specifically, and that clause exists to remove exactly this
ambiguity rather than to be argued around.

**Why it could not be run.** The handset's Airtel SIM is refused by the network:

```
domain=CS transportType=WWAN  registrationState=DENIED              availableServices=[EMERGENCY]
domain=PS transportType=WWAN  registrationState=NOT_REG_OR_SEARCHING
domain=PS transportType=WLAN  registrationState=HOME                availableServices=[DATA]
```

With Wi-Fi off the device has no default network at all and `ping 8.8.8.8` returns
`Network is unreachable`. This is a carrier-side matter, not an app one.

> **The trap, for whoever checks this next.** With Wi-Fi ON the same dump reports
> `mDataRegState=0(IN_SERVICE)` and `mIsIwlanPreferred=true`, which reads exactly like working
> mobile data. It is not — it is Wi-Fi calling. The only `availableServices=[DATA]` is on
> `transportType=WLAN`; the WWAN packet domain is `NOT_REG_OR_SEARCHING`. Trust the
> per-transport registration lines, never the summary `mDataRegState`.

To finish it once the SIM is in service:

```bash
adb reverse --remove-all && adb reverse --list && adb forward --list   # both must be empty
adb shell svc wifi disable
adb shell settings get global wifi_on                                   # 0
adb shell dumpsys telephony.registry | grep -oE 'domain=PS transportType=WWAN registrationState=[A-Z_]+'
#   must read HOME or ROAMING — not NOT_REG_OR_SEARCHING
adb shell ping -c 2 8.8.8.8                                             # must succeed
```

Then sign in, add a person, and tap **"Send me a test reminder in 2 minutes"**. Restore Wi-Fi
afterwards with `adb shell svc wifi enable`.

To finish it:

```bash
adb reverse --remove-all
adb shell settings get global wifi_on      # must be 0
adb shell dumpsys connectivity | grep -m1 'NetworkAgentInfo.*CELLULAR'
```

Then on the handset, add a person and tap **"Send me a test reminder in 2 minutes"** on the
people list — it posts to `/v1/reminders/test`, which queues through BullMQ and sends via the
`FIREBASE_SERVICE_ACCOUNT` credential path. A notification arriving with Wi-Fi off closes it.

---

## 6. Offline persistence — still unverified on a device

M0 never ran the aeroplane-mode check, and M1 does not either: the owner chose to drop the
offline *tests* from this milestone while keeping the offline-first code unchanged
(`docs/M1_PLAN.md` §2.5). SQLDelight is still the only read source and every write still
goes local-first through the outbox, so the behaviour is presumed working and untested.

Stated plainly so the next milestone inherits a true sentence: **nobody has watched this app
survive aeroplane mode on a real handset.**

---

## 5a. The server, proven with curl

Two scripts, checked in, so "proven with curl" survives the terminal they were run in.

```bash
cd server && npm run start:dev          # needs ALLOW_DEV_LOGIN=true in server/.env
bash scripts/m1-crud-check.sh           # 56 assertions
bash scripts/m1-sync-check.sh           # 25 assertions
U=https://wishkaro-production-0312ab77.up.railway.app bash scripts/m1-sync-check.sh
```

The CRUD script needs the local Postgres container for four assertions that read
`reminder.status` directly — the API does not expose it, and "the reminder stopped NOW" is
precisely the claim a 200 response cannot make. It skips those and passes the rest without it.

What they cover, and why each one is there rather than a unit test:

| Section | The claim it would be embarrassing to get wrong |
|---|---|
| 1 | `sentiment`, `isMinor`, `completenessScore`, `milestoneLabel` from a client are **400s naming the field**, not silently stripped |
| 9 | One death anniversary turns every one of that person's events SOLEMN, strips the wedding's "25th Silver", nulls every `turningAge`, and mutes the birthday |
| 9 | Un-muting that birthday is the FR-EVT-05 opt-in, and it is still SOLEMN |
| 9 | Un-marking the person while a death anniversary is live is refused |
| 10 | A minor gets no suggestions and `POST /traits` returns `MINOR_PROFILING_BLOCKED` |
| 14 | PENDING → mute → SUPPRESSED → un-mute → PENDING → delete → SUPPRESSED, immediately |
| sync 4 | The same four derived fields are refused on the sync path too, and a trait claiming `source=INFERRED` is refused |
| sync 6 | A birthday pushed for an already-deceased person arrives muted, and re-pushing it without `isMuted` leaves it muted |
| sync 8 | An M0-shaped cursor with no `trait` key still works |

### Two bugs these scripts found

**An impossible override date returned 500.** `PUT /v1/events/:id/occurrences/:year/override`
with `2026-02-31` reached `assertValidSpec`, which throws a plain `Error`; letting it escape a
controller turned a user's typo into a server error. Now a 400 naming the date.

**"Disabled by default" was not a default.** `PersonDerivationService` muted birthdays only on
the TRANSITION to deceased — correct for events that already exist, and what stops an opt-in
being undone by the next write. But a birthday ADDED to someone already marked deceased
arrived unmuted. `defaultMutedFor()` is now the creation-time half of the rule, applied at all
three write paths and deliberately not on update.

---

## 5b. The client, on the handset

### The SQLDelight migration — the one that would crash a real install

```bash
adb exec-out run-as in.wishkaro.app cat databases/wishkaro.db > before.db
sqlite3 before.db "PRAGMA user_version;"          # 1 on an M0 install

./gradlew :composeApp:assembleDebug
adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
adb shell monkey -p in.wishkaro.app -c android.intent.category.LAUNCHER 1

# The driver migrates LAZILY, on the first query. Give the app a moment to draw the list
# before pulling the file, or you will read the pre-migration database and think it failed.
adb exec-out run-as in.wishkaro.app cat databases/wishkaro.db > after.db
sqlite3 after.db "PRAGMA user_version;"           # 3
sqlite3 after.db "SELECT display_name FROM person;"
```

Verified on `4B201JEBF03493`, 2026-11-25: **1 → 2 → 3 across two installs, no crash, both
people and the birthday intact each time, new columns defaulted.**

`verifyMigrations` is on, and it failed the build the first time: `ALTER TABLE` can only
append columns while `Person.sq` declared them in the middle, so every ordinal position after
`pending_sync` disagreed. That is a difference no test would have caught and the device would
have. The M1 columns now sit at the END of `person` and `event` with a comment saying they
cannot be moved.

### Tone

`sentiment` is mirrored into SQLite and never derived on the client. A UI that inferred tone
from the event type gets a remembrance birthday wrong — type `BIRTHDAY`, sentiment `SOLEMN` —
which is the exact failure FR-EVT-05 names. `Sentiment.from()` falls back to `SOLEMN` for an
unrecognised value, because saying too little is recoverable and "Happy Birthday!" to someone
grieving is not.

`ToneTest` enumerates every event type against a banned lexicon and an emoji check rather than
sampling, so a sixth type fails it until someone writes its solemn copy.

### Two bugs the walkthrough found that the tests did not

Both were found by using the app on the handset, not by reading it.

**Restoring a person returned them empty.** Delete → Recently removed → Restore brought the
person back with no dates, no countdown and no reminders. `POST /v1/people/:id/restore`
un-deleted their events; the local path and the sync path did not. The same user action meant
three different things depending on which route it took.

**Underneath it: sync was comparing timestamps as strings.** The device's database showed one
moment spelled two ways:

```
person   2026-11-25T10:16:21.410233Z   kotlinx Instant.toString(), microseconds
event    2026-11-25T10:16:21.410Z      the same instant after Prisma timestamp(3)
```

The client writes nanosecond precision, Postgres stores milliseconds, and JavaScript's
`toISOString()` always emits exactly three decimals while kotlinx trims them. `applyRemote`
compared those spellings with `>=` on the strings, and `"…410233Z"` sorts BEFORE `"…410Z"`
because `'2' < 'Z'` — so the client would treat its own fresh write as older than the
server's echo of it. **Last-write-wins losing to string formatting, latent since M0.** The
tombstone match was simply the first thing to trip over it.

Sync now parses both sides. Ordering comparisons keep full precision, which is the right
answer for "is the local row at least as new"; tombstone matching truncates to milliseconds,
because "were these written by the same delete" is a different question. `TimestampComparisonTest`
pins the formats, including the whole-second case where the two ends disagree the other way.

---

## 6a. Derived values repair themselves

Two of them change with no write at all:

- **`is_minor` flips on a person's eighteenth birthday.** Derived only at write time, a child
  flagged at thirteen stays flagged until somebody happens to edit them, and FR-PPL-09's
  profiling exclusions stay on for years after they should lift.
- **`completeness_score` is new in M1**, so every row written before it existed reads 0 until
  it is next touched — visible in the app as a profile claiming 0% while showing a relation
  and a birth year.

The materialiser already walks every live event on a schedule, which makes it the only part of
this system that notices time passing. It re-derives each person it touches. To see it:

```sql
UPDATE person SET completeness_score = 0, is_minor = true WHERE id = '<id>';
```

then wait for a pass (or write anything for that user) and read the row back: 25 and false.

---

## 7. What "it's broken" usually means

| Symptom | Cause |
|---|---|
| Build fails with `SDK location not found … ANDROID_HOME` on Railway | The service's root directory is not `server`. §1 |
| Nothing is ever scheduled or sent, no Redis error anywhere | `REDIS_URL` is missing `?family=0`. §1 |
| `Invalid environment configuration` at boot | A variable above is missing. The server refuses to start rather than fail at 09:00 |
| Gradle dies in odd, inconsistent ways | Disk. Check `df -h /System/Volumes/Data` before believing anything else |
| The phone reaches `127.0.0.1` and times out | `local.properties` still has the debug default, or the APK predates the change |
| `PRAGMA user_version` still reads the old number after installing | The driver migrates lazily. Open the app, let it draw the list, then pull the file |
| `verifySqlDelightMigration` fails on ordinal positions | A new column was declared in the middle of a `.sq` table. `ALTER TABLE` can only append — move it to the end |
| A profile shows 0% with fields plainly filled in | A row written before M1. The next materialiser pass repairs it; §6a |
| The phone says `mDataRegState=0(IN_SERVICE)` but has no mobile data | Wi-Fi calling. Check the per-transport `domain=PS transportType=WWAN` line, not the summary; §5 |
| A person restored from Recently removed comes back with no dates | Fixed in M1. If it returns, suspect timestamp comparison again; §5b |
