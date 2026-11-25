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

## 7. What "it's broken" usually means

| Symptom | Cause |
|---|---|
| Build fails with `SDK location not found … ANDROID_HOME` on Railway | The service's root directory is not `server`. §1 |
| Nothing is ever scheduled or sent, no Redis error anywhere | `REDIS_URL` is missing `?family=0`. §1 |
| `Invalid environment configuration` at boot | A variable above is missing. The server refuses to start rather than fail at 09:00 |
| Gradle dies in odd, inconsistent ways | Disk. Check `df -h /System/Volumes/Data` before believing anything else |
| The phone reaches `127.0.0.1` and times out | `local.properties` still has the debug default, or the APK predates the change |
