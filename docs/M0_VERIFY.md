# M0 — verifying the walking skeleton yourself

Everything below is reproducible from a clean checkout. If any step does not behave as
described, that is a bug, not a quirk — M0 exists precisely so these steps are boring.

The chain being proved:

```
Google sign-in → add one person with one birthday → persists locally
  → syncs to Postgres → server materialises the occurrence → schedules a reminder
  → FCM push arrives on the phone → tapping it opens the app on that person
```

---

## 0. What you need

| Thing | Check |
|---|---|
| Node 22 (fnm default) | `node -v` → v22.x |
| JDK 21 pinned for Gradle | `grep java.home ~/.gradle/gradle.properties` |
| Docker Desktop running | `docker info` succeeds |
| Android SDK + device | `adb devices` lists `4B201JEBF03493` |
| `server/.env` | gitignored, already populated |
| `composeApp/google-services.json` | gitignored, already in place |

`server/.env` additionally needs this line for the curl walkthrough in §3. It is absent from
`.env.example` on purpose, so a deployment can never inherit it by copy-paste:

```
ALLOW_DEV_LOGIN=true
```

The server **refuses to boot** if that is set while `NODE_ENV=production`.

---

## 1. Bring up the stack

```bash
docker compose -f infra/docker-compose.yml up -d db redis
```

```bash
cd server && npm ci && npx prisma migrate deploy && npm run start:dev
```

You should see, in order:

```
Connected to Postgres
Firebase Admin ready — project wishkaro-1c95e, credentials from GOOGLE_APPLICATION_CREDENTIALS
Reminder queues started
WishKaro API listening on :3000 (development)
ALLOW_DEV_LOGIN is on — POST /v1/auth/dev-token mints sessions without Google
```

That Firebase line is load-bearing: it names which of the two credential paths was used.

To run everything in containers instead — which is what the definition of done asks for:

```bash
docker compose -f infra/docker-compose.yml up --build
```

Then:

```bash
curl -s http://127.0.0.1:3000/health
```

→ `{"status":"ok","db":"up","uptimeSeconds":...}`

---

## 2. Run the tests

```bash
cd server && npm test
```

87 tests. The ones that matter are in `src/common/date/occurrence.spec.ts`: leap-day
birthdays, unknown birth years, DST-correct 09:00-local → UTC, and a user who changes
timezone with a reminder already scheduled.

```bash
./gradlew :composeApp:testDebugUnitTest
```

13 tests, covering the client's copy of the same recurrence rules. The two implementations
are deliberately tested against the same cases — if they ever disagree, the countdown on the
phone and the reminder the server sends will disagree too.

---

## 3. Prove the server on its own, with curl

No app required. Mint a session:

```bash
ACCESS=$(curl -s -X POST http://127.0.0.1:3000/v1/auth/dev-token -H 'content-type: application/json' --data '{"email":"you@example.test","name":"You","timezone":"Asia/Kolkata"}' | python3 -c 'import sys,json;print(json.load(sys.stdin)["accessToken"])')
```

Add a person with a birthday whose year you know:

```bash
curl -s -X POST http://127.0.0.1:3000/v1/people -H "Authorization: Bearer $ACCESS" -H 'content-type: application/json' --data '{"displayName":"Aarav","relationToOwner":"Brother","event":{"day":14,"month":3,"year":1990}}' | python3 -m json.tool
```

Note `turningAge`, and that `nextOccurrenceOn` is in the future, not the past.

Now one with the year unknown, on a leap day:

```bash
curl -s -X POST http://127.0.0.1:3000/v1/people -H "Authorization: Bearer $ACCESS" -H 'content-type: application/json' --data '{"displayName":"Meera","relationToOwner":"Friend","event":{"day":29,"month":2,"year":null}}' | python3 -m json.tool
```

`turningAge` must be **null**, never 0, and in a non-leap year `nextOccurrenceOn` is
**28 February**.

A date that does not exist must be refused, not stored:

```bash
curl -s -X POST http://127.0.0.1:3000/v1/people -H "Authorization: Bearer $ACCESS" -H 'content-type: application/json' --data '{"displayName":"Nope","event":{"day":31,"month":4,"year":1990}}'
```

→ `{"error":{"code":"VALIDATION_FAILED",...,"details":[{"path":"event.day","message":"day 31 does not exist in month 4"...`

Check what the scheduler wrote. Reminders are stored in UTC and must land on 03:30 for a
09:00 Asia/Kolkata delivery:

```bash
docker exec wishkaro-db-1 psql -U wishkaro -d wishkaro -c 'SELECT "offset", status, scheduled_at_utc FROM reminder ORDER BY scheduled_at_utc;'
```

Sync must be idempotent — run this twice and the second run applies nothing:

```bash
curl -s "http://127.0.0.1:3000/v1/sync" -H "Authorization: Bearer $ACCESS" | python3 -m json.tool | head -20
```

---

## 4. Build and install the app

```bash
./gradlew :composeApp:assembleDebug
```

```bash
adb install -r composeApp/build/outputs/apk/debug/composeApp-debug.apk
```

**The one step people forget.** The phone's `127.0.0.1` is the phone, not your Mac. This
forwards it:

```bash
adb reverse tcp:3000 tcp:3000
```

Re-run it after every reconnect or reboot of the phone. If the app can sign in with Google
but then fails to sync, this is almost always why.

To point at a machine on your LAN instead, put this in `local.properties` (gitignored) and
rebuild:

```
wishkaro.apiBaseUrl=http://192.168.1.20:3000
```

---

## 5. The chain, on the phone

1. Launch WishKaro. You get the sign-in screen.
2. **Continue with Google** → pick your account.
   - A `user` row and your own `person` row (`is_self = true`) are created server-side.
   - Android 13+ then asks for notification permission. Allow it.
   - The FCM token is registered and the first sync runs.
3. **Send me a test reminder in 2 minutes** — the button on the list screen.
   Within about two minutes a notification arrives. This is FR-REM-07, and it is the fastest
   honest proof that push delivery works on *this* handset.
4. **Add person** → a name, a day and month, a relation. Tick **I don't know the year** on
   one of them and confirm the list shows no age for that person.
5. Turn on aeroplane mode, add another person, kill the app, reopen it. They are still
   there — SQLDelight is the read source of truth and writes go local-first.
6. Turn the radio back on and press **Sync**. Confirm the person reached Postgres:

   ```bash
   docker exec wishkaro-db-1 psql -U wishkaro -d wishkaro -c 'SELECT display_name, relation_to_owner FROM person ORDER BY created_at DESC LIMIT 5;'
   ```

7. Confirm a reminder was scheduled for them, at 03:30 UTC:

   ```bash
   docker exec wishkaro-db-1 psql -U wishkaro -d wishkaro -c 'SELECT p.display_name, r.scheduled_at_utc, r.status FROM reminder r JOIN event_occurrence o ON o.id = r.event_occurrence_id JOIN event e ON e.id = o.event_id JOIN person p ON p.id = e.person_id;'
   ```

8. Tapping a reminder opens the app with that person highlighted in the list.

### Verifying a real birthday reminder without waiting for 09:00

Make the reminder **already due**, so the next send-worker scan claims it:

```bash
docker exec wishkaro-db-1 psql -U wishkaro -d wishkaro -c "UPDATE reminder SET scheduled_at_utc = now() - interval '5 seconds' WHERE status = 'PENDING' AND event_occurrence_id IS NOT NULL;"
```

The send worker scans every 30 seconds, so the push lands within about a minute — this time
carrying the real birthday copy and a deep link to that person, not the self-test copy.

**Do not move it into the future instead.** Two background jobs disagree about that row:

| Job | Period | What it does |
|---|---|---|
| `reminders-send` | 30 s | sends every `PENDING` reminder whose time has passed |
| `occurrences-materialise` | 10 min | resets any `PENDING` reminder whose time disagrees with the one computed from the event |

A reminder parked 30 seconds in the future is a row the reconciler considers wrong, and it will
put it back — silently, with no error anywhere — if it happens to run first. That is correct
behaviour (edit someone's birthday and their reminder follows it), but it means the obvious
version of this trick fails perhaps one time in twenty, and the failure looks exactly like a
broken push. Setting the time in the *past* closes the window: the row is claimed on the next
scan, seconds later. It must still be inside the two-hour late grace, or it is `SUPPRESSED`
rather than sent.

Afterwards, **put the row back**, or that person's real reminder will never fire:

```bash
docker exec wishkaro-db-1 psql -U wishkaro -d wishkaro -c "UPDATE reminder SET status = 'PENDING', scheduled_at_utc = '2027-09-22 03:30:00', sent_at = NULL WHERE id = '<the reminder id>';"
```

Use the date the row had before you touched it. The reconciler will not fix this for you: it
only reschedules rows that are still `PENDING`, so one left `SENT` stays spent for good.

---

## 6. Pairing a device by hand (no Google account)

Useful when you want to test push delivery without signing in — and it is how push was first
proved on `4B201JEBF03493` during the build.

Debug builds log the FCM token at launch:

```bash
adb logcat -c && adb shell am force-stop in.wishkaro.app && adb shell am start -n in.wishkaro.app/.MainActivity && sleep 8 && adb logcat -d | grep -o "FCM token: .*" | tail -1
```

Then grant notifications, register the token against a dev session, and fire a test:

```bash
adb shell pm grant in.wishkaro.app android.permission.POST_NOTIFICATIONS
```

```bash
curl -s -X POST http://127.0.0.1:3000/v1/devices -H "Authorization: Bearer $ACCESS" -H 'content-type: application/json' --data '{"fcmToken":"PASTE_TOKEN","platform":"ANDROID","timezone":"Asia/Kolkata"}'
```

```bash
curl -s -X POST http://127.0.0.1:3000/v1/reminders/test -H "Authorization: Bearer $ACCESS"
```

That token log is `BuildConfig.DEBUG`-only. It must never reach a release build: the token
plus the server key is enough to push to that phone.

---

## 7. What "it's broken" usually means

| Symptom | Cause |
|---|---|
| Sign-in works, everything else fails | `adb reverse` not set after reconnecting the phone |
| `DEVELOPER_ERROR` / no account sheet | The debug SHA-1 is not registered on the Firebase Android app, or `google-services.json` is stale |
| Server rejects the ID token | `GOOGLE_CLIENT_ID` is the **Android** client id. It must be the **Web** one |
| Reminder row is `FAILED`, log says `registration-token-not-registered` | A stale FCM token; the send worker deletes it. Relaunch the app to register a fresh one |
| Reminder `SENT` but nothing on the phone | `POST_NOTIFICATIONS` not granted, or the OEM killed the app. The battery-optimisation helper is M2 |
| Reminder stuck `PENDING` past its time | The send worker is not running — check `Reminder queues started` in the log and that Redis is up |
| A reminder you moved by hand jumps back to its real date | `occurrences-materialise` reconciled it. See the note in §5 — move it into the past, not the future |
| Reminder is `SUPPRESSED` | Its 09:00 passed more than two hours ago. Deliberate: a morning-of reminder at 4pm is worse than none |

---

## 8. What M0 deliberately does not do

No import, AI, gifts, billing, widget, calendar or timeline, no family tree, no person detail
screen, no search, no photos. One ladder step only (morning-of at 09:00); T-7 and T-1, quiet
hours, the digest, the OEM battery helper and the on-device alarm fallback are M2. The only
event type the API will create is `BIRTHDAY`, though the enums already carry their full v1
value sets so later milestones add behaviour rather than altering columns.

`docs/M0_PLAN.md` §1 records the four decisions taken during the build, §2 the one place the
implementation is narrower than `REQUIREMENTS.md` (per-record rather than per-field
last-write-wins), and §6 the full non-scope list.
