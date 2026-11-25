# M2 Verification — Reminder Engine

> How to reproduce every claim M2 makes, and — just as important — which claims are
> **unchecked**. Written against commit `3759cbc` on `feat/m2-reminder-engine`.
> Device: Pixel 9a (`4B201JEBF03493`), Android 16, debug build pointed at Railway.
> Server: `https://wishkaro-production-0312ab77.up.railway.app`, deployed 2026-11-25 13:45Z.
>
> The honest summary is at the bottom, in §9. Read that first if you are reviewing.

---

## 0. Setup

```bash
# the phone
adb devices -l

# debug builds print the FCM token at launch; several checks below need it
adb shell setprop log.tag.Notifications VERBOSE
adb shell am force-stop in.wishkaro.app
adb shell monkey -p in.wishkaro.app -c android.intent.category.LAUNCHER 1
adb logcat -d -s Notifications:V | tail -1 | sed 's/.*FCM token: //' > /tmp/fcm-token
```

Reading the phone's database is how most of this is checked. It is a debug build, so:

```bash
adb shell am force-stop in.wishkaro.app
adb shell "run-as in.wishkaro.app cat databases/wishkaro.db" > /tmp/wk.db
sqlite3 /tmp/wk.db 'select offset_rung, scheduled_at, title, sentiment from scheduled_reminder order by scheduled_at;'
```

(There is no `sqlite3` on the device; pull the file and read it on the Mac.)

---

## 1. The M0 offline check — **PASSES**

Carried from M0 and deferred by `M1_PLAN.md` §2.5. Run 2026-11-25, 18:04–18:07 IST.

```bash
adb shell cmd connectivity airplane-mode enable
adb shell svc wifi disable
adb shell ping -c 2 -W 2 8.8.8.8          # Network is unreachable
adb shell ping -c 1 wishkaro-production-0312ab77.up.railway.app   # unknown host
```

Then, in the app: **Add person** → "Offline Check M2", Friend, 2 Dec 1990 → Save. The detail
screen drew "in 7 days / Turning 36" with no network.

```bash
adb shell am force-stop in.wishkaro.app
adb shell "run-as in.wishkaro.app cat databases/wishkaro.db" > /tmp/wk.db
# person 0b6c4ca3-… and event e73582ba-…, both pending_sync = 1
```

Reopen → both people and the new one still listed. Then restore the radio and press **Sync**.

**What makes this a pass rather than a hope:** afterwards the event carries
`sentiment = CELEBRATORY`, a column **the client never writes**, and `updated_at` has been
restamped to the server's clock. A value the client cannot invent came back from the other
side. Railway's own request log has the matching `POST /v1/sync` → 200 at 12:37:00Z.

---

## 2. The ladder, and the solemn ladder — **PASS**

### 2.1 Server-side, by curl

Against a local server with `ALLOW_DEV_LOGIN=true`:

```bash
U=http://127.0.0.1:3000
TOK=$(curl -s -X POST $U/v1/auth/dev-token -H 'content-type: application/json' \
      -d '{"email":"ladder@example.test"}' | jq -r .accessToken)

curl -s -X POST $U/v1/sync -H "Authorization: Bearer $TOK" -H 'content-type: application/json' -d '{
  "people":[{"id":"11111111-1111-4111-8111-111111111111","displayName":"Ladder Celebratory","updatedAt":"2026-11-25T13:00:00.000Z"},
            {"id":"22222222-2222-4222-8222-222222222222","displayName":"Ladder Solemn","updatedAt":"2026-11-25T13:00:00.000Z"}],
  "events":[{"id":"aaaaaaaa-1111-4111-8111-111111111111","personId":"11111111-1111-4111-8111-111111111111","type":"BIRTHDAY","day":15,"month":6,"year":1990,"updatedAt":"2026-11-25T13:00:00.000Z"},
            {"id":"bbbbbbbb-2222-4222-8222-222222222222","personId":"22222222-2222-4222-8222-222222222222","type":"DEATH_ANNIVERSARY","day":20,"month":7,"year":2010,"updatedAt":"2026-11-25T13:00:00.000Z"}]}'
```

Observed, after one materialiser tick:

| person | type | sentiment | rungs |
|---|---|---|---|
| Ladder Celebratory | BIRTHDAY | CELEBRATORY | **D7** 2027-06-08, **D1** 2027-06-14, **D0** 2027-06-15 |
| Ladder Solemn | DEATH_ANNIVERSARY | SOLEMN | **D1** 2027-07-19, **D0** 2027-07-20 — **no D7** |

All at `03:30Z`, which is 09:00 Asia/Kolkata. Seven days, one day, zero days before.

### 2.2 The reconciliation, both directions

```sql
update "user" set ladder_d7 = false where id = '<user>';   -- then POST /v1/sync to trigger
-- D7 -> SUPPRESSED
update "user" set ladder_d7 = true  where id = '<user>';
-- D7 -> PENDING again
```

The second half is the one that was broken when first run: suppression worked and the reverse
did not, so a toggle was a one-way door until next year. Fixed in `e7dd89d`.

### 2.3 The back door into FR-REM-11

The sequence that found it, and the one to re-run after any change to `revivePending`:

1. a living person has a birthday with all three rungs PENDING;
2. add a death anniversary → person deceased, every event SOLEMN, birthday muted by default,
   all three rungs suppressed;
3. un-mute the birthday — the FR-EVT-05 remembrance opt-in.

At step 3 the D7 row is suppressed, unexpired and attached to a live unmuted event, so an
unfiltered revive brings it back: a "one week to go, buy them a present" notification for
someone who has died. Observed; `revivePending` now consults `ladderFor`.

```
 type      | sentiment | is_muted | offset | status
-----------+-----------+----------+--------+------------
 BIRTHDAY  | SOLEMN    | f        | D7     | SUPPRESSED   <- stays off
 BIRTHDAY  | SOLEMN    | f        | D1     | PENDING
 BIRTHDAY  | SOLEMN    | f        | D0     | PENDING
```

### 2.4 On the phone, against Railway

"Ladder Proof" (birthday 10 Dec) and "Solemn Proof" (death anniversary 15 Dec) added in the
app, then **Sync**:

```
 D1  2026-12-01T03:30:00Z  🎂 Offline Check M2's birthday is tomorrow    CELEBRATORY
 D0  2026-12-02T03:30:00Z  🎂 Offline Check M2's birthday is today       CELEBRATORY
 D7  2026-12-03T03:30:00Z  🎁 Ladder Proof's birthday is in a week       CELEBRATORY
 D1  2026-12-09T03:30:00Z  🎂 Ladder Proof's birthday is tomorrow        CELEBRATORY
 D0  2026-12-10T03:30:00Z  🎂 Ladder Proof's birthday is today           CELEBRATORY
 D1  2026-12-14T03:30:00Z  Remembering Solemn Proof tomorrow             SOLEMN
 D0  2026-12-15T03:30:00Z  Remembering Solemn Proof                      SOLEMN
```

Three rungs for the birthday; **two** for the remembrance, with no 8 December row in
existence. The solemn copy carries no emoji and no celebratory verb.

Offline Check M2 has no D7 because its T-7 was 25 November, already past — recorded
SUPPRESSED rather than fired late.

And the alarms, armed on the device from that plan:

```bash
adb shell dumpsys alarm | grep -A2 wishkaro | grep -oE 'origWhen=2026-[0-9-]+ [0-9:]+' | sort -u
# origWhen=2026-12-01 09:00:00
# origWhen=2026-12-02 09:00:00
# origWhen=2026-12-03 09:00:00
# origWhen=2026-12-09 09:00:00
# origWhen=2026-12-10 09:00:00
```

09:00 local, five alarms, set by the phone without the server's involvement.

### 2.5 The actions (FR-REM-04, FR-REM-11)

```bash
node server/scripts/m2-fake-push.cjs 'ladder-d7:2026:D7' /tmp/fcm-token   # with OFFSET=D7
SENTIMENT=SOLEMN node server/scripts/m2-fake-push.cjs 'solemn:2026:D0' /tmp/fcm-token
```

```bash
adb shell dumpsys notification --noredact | grep -oE 'channel=reminders_[a-z_]+ .*actions=[0-9]+'
# channel=reminders_week_before  … actions=3
# channel=reminders_morning_of   … actions=2
```

And on screen, the solemn one:

> **Remembering Solemn Proof**
> 11 years today.
> **Send message**    **Mark done**

Two actions. **Gift ideas is absent, not disabled. So is Snooze** — "remind me again in an
hour" is not a thing to offer about a death anniversary. The celebratory T-7 gets three, led
by Gift ideas; morning-of gets three, led by Send wish.

Each rung also lands on its own channel (FR-REM-05), which is what lets a user silence the
week-ahead nudge without losing the morning-of one.

---

## 3. One notification from two paths — **PASS**, and this is the milestone

Definition-of-done item 3. The two delivery paths have to race on a real handset.

### How a failed FCM delivery is simulated

Three ways, and all three were used:

1. **Aeroplane mode.** The radio off end to end — §1 and §5 below. Nothing involving FCM can
   possibly have happened.
2. **The alarm wins the race.** Arm a local alarm, let it fire, then hand-send the server's
   push afterwards with the same key. This is the real two-path test, because both paths are
   real: `scripts/m2-fake-push.cjs` sends a genuine data-only FCM message through Google's
   servers to the handset.
3. **Force-stop.** A force-stopped app receives no FCM at all, which is not a simulation —
   it is the exact failure mode an OEM battery manager produces. Note that force-stop also
   cancels alarms, which is why `ReminderUpkeepWorker` exists.

### The run

```bash
# 1. seed a plan row ~75s out, push the db back, relaunch so rearmAll() picks it up
#    (full script in the transcript; it is an INSERT into scheduled_reminder)
# 2. wait
adb logcat -d -s WishKaroAlarm:V
# I WishKaroAlarm: Local alarm dup-proof:2026:D0 -> SHOWN

# 3. now the server's push, carrying the SAME key
node server/scripts/m2-fake-push.cjs 'dup-proof:2026:D0' /tmp/fcm-token
adb logcat -d -s WishKaroFcm:V
# I WishKaroFcm: Push dup-proof:2026:D0 -> SUPPRESSED_DUPLICATE

# 4. count what the user can actually see
adb shell dumpsys notification --noredact | grep -oE '0\|in\.wishkaro\.app\|[0-9-]+' | sort -u
# 0|in.wishkaro.app|628273760      <- exactly one
```

One notification. Two real paths. The delivery report afterwards:

```
('dup-proof:2026:D0', 'SHOWN', 'LOCAL', '2026-11-25T13:36:49.315313Z', 1)
```

**This test found a bug on its first run.** The duplicate's `SUPPRESSED_DUPLICATE` overwrote
the alarm's `SHOWN`, so the only record of a real delivery was replaced by a record of a
suppression — and because reports sit in an outbox until the device has a network, neither had
reached the server yet. Every successful de-duplication would have erased the delivery that
proved the engine worked, and the G1 number would have fallen as reliability rose. Fixed in
`bf04188`; the re-run above is the fixed behaviour.

---

## 4. The local alarm fires with no network — **PASS**

Covered by the run in §3, where the alarm fired and drew the notification before any push
existed. Two details worth recording:

- It fired **4 seconds** after its target, on the **inexact** path, because
  `SCHEDULE_EXACT_ALARM` is denied by default at `targetSdk 36`:

  ```
  type=RTC_WAKEUP origWhen=2026-11-25 18:59:57.000 window=+1m10s428ms
  ```

  That window is the degradation working as designed, not failing. Settings offers the
  permission that removes it.
- The notification carried the server's own copy and the right three actions, because the
  alarm replays a plan the server rendered rather than composing anything itself.

---

## 5. Quiet hours — **PASS, on the device**

```bash
curl -s -X PATCH $U/v1/me/settings -H "Authorization: Bearer $TOK" \
     -H 'content-type: application/json' -d '{"reminderHour":8,"quietHoursStart":22,"quietHoursEnd":7}'
# pending reminders move from 03:30Z to 02:30Z within seconds

curl -s -X PATCH $U/v1/me/settings -H "Authorization: Bearer $TOK" \
     -H 'content-type: application/json' -d '{"quietHoursStart":22}'
# 400: "quietHoursStart and quietHoursEnd must be sent together — a half-set window would
#       silence every reminder"
```

A digest hour of 06:00 under a 22:00–07:00 window lands at **07:00 local** (01:30Z), not
06:00 — observed on the digest rows.

The table of cases is in `M2_PLAN.md` §6 and is asserted twice: `quiet-hours.spec.ts` on the
server and `QuietHoursTest.kt` on the client, which exist as two implementations only because
the client needs the rule for a local fallback the server has never seen. **The case the build
prompt names first** — Asia/Kolkata, 22:00–07:00, a reminder due at 09:00 — is *outside* the
window and is pinned not to move.

### On the phone, against Railway

Settings → delivery time **11pm**, **Stay quiet overnight** on (22:00–07:00). After the sync,
the plan the device holds:

```
 D7  2026-11-26T01:30:00Z  🎁 Offline Check M2's birthday is in a week
 D1  2026-12-02T01:30:00Z  🎂 Offline Check M2's birthday is tomorrow
 D0  2026-12-03T01:30:00Z  🎂 Offline Check M2's birthday is today
 D7  2026-12-04T01:30:00Z  🎁 Ladder Proof's birthday is in a week
 …
```

`01:30Z` is **07:00 Asia/Kolkata** — every rung deferred from 23:00 to the end of the window,
and the dates rolled forward by one because 23:00 is on the near side of midnight. Switching
the window off puts them all back to `03:30Z`.

Note the first row: at a 09:00 delivery hour, Offline Check M2 had no D7 because its T-7 had
already passed. At 23:00 it had not, so the rung materialised — and was then deferred to the
next morning. Both behaviours fall out of the same two rules.

**An observation, not a bug, but worth a decision later.** Choosing a delivery hour that sits
inside your own quiet window pushes *morning-of* into the following day: a 2 December birthday
notified at 07:00 on the 3rd. FR-REM-09 says deferred and not dropped, and that is what
happens — but the Settings screen says nothing about it, and it probably should.

**Unchecked on the device:** a deferred reminder actually arriving at 07:00. That needs a real
overnight wait.

---

## 6. Digest — **PASS, on the device**

```bash
curl -s -X PATCH $U/v1/me/settings -H "Authorization: Bearer $TOK" \
     -H 'content-type: application/json' -d '{"digestEnabled":true,"digestHour":9}'
```

Observed: every per-event rung becomes SUPPRESSED and one `DIGEST` row appears per local day
that has anything to say, keyed `digest:${userId}:${localDate}`. Turning it off restores the
ladder on the same materialiser pass.

Digest replaces the **whole** ladder, not just morning-of — the decision in `M2_PLAN.md` §2.1.
Grouping is asserted in `tone-safety.spec.ts`:

```
Today: Aai (remembrance)
Tomorrow: Sita (birthday)
In 7 days: Ravi (birthday)
```

with no emoji and no celebratory verb anywhere, because that list has to be safe on a line
directly above a death anniversary.

### On the phone, against Railway

Settings → **One notification a day**. After the sync, the plan the device holds:

```
 DIGEST  2026-12-01T03:30:00Z  1 date coming up   Tomorrow: Offline Check M2 (birthday)
 DIGEST  2026-12-02T03:30:00Z  1 date coming up   Today: Offline Check M2 (birthday)
 DIGEST  2026-12-03T03:30:00Z  1 date coming up   In 7 days: Ladder Proof (birthday)
 DIGEST  2026-12-09T03:30:00Z  1 date coming up   Tomorrow: Ladder Proof (birthday)
 DIGEST  2026-12-10T03:30:00Z  1 date coming up   Today: Ladder Proof (birthday)
 DIGEST  2026-12-14T03:30:00Z  1 date coming up   Tomorrow: Solemn Proof (remembrance)
 DIGEST  2026-12-15T03:30:00Z  1 date coming up   Today: Solemn Proof (remembrance)
```

Not one per-event rung left, including the T-7 and T-1 ones — which is the decision in
`M2_PLAN.md` §2.1 made visible. The solemn entry reads "(remembrance)", not "anniversary",
and nothing in the list carries an emoji.

Switching it off puts the full ladder back on the next sync, D7 included.

The Settings screen also greys out the delivery-time picker while digest is on, with "The
daily summary decides the time while it is on." — the FR-REM-03 / FR-REM-08 interaction said
out loud rather than left for the user to discover.

**Unchecked on the device:** a digest notification actually arriving at 09:00.

---

## 7. Self-test — **PASS**

Observed on the phone, 19:26–19:28 IST:

> **Check it works**
> Arrived after **145 seconds**. Delivery works on this phone.

with `WishKaroFcm: Push test:01a211dc-…:1795615088632 -> SHOWN` in the log and the
notification on screen. The server had queued it two minutes out, so 145 seconds is the
two-minute delay plus the send worker's 30-second tick.


Settings → **Send a test reminder**. The screen moves through *Asking the server…* →
*Waiting for it to arrive — keep this screen open.* → the outcome.

The evidence it reports on is `seen_reminder` on the device, not the server's view: the server
only knows it handed a push to FCM, and that is precisely the step that fails on the phones
this feature exists for. A timeout reports **"It never arrived"** and points at the battery
helper directly above it.

---

## 8. OEM battery helper — **UNCHECKED**

`FR-REM-06`, and `REQUIREMENTS.md` calls it the single highest-impact reliability feature in
the app.

What is built: `AndroidOemBatteryHelper` detects Xiaomi/Redmi/POCO, Oppo/OnePlus, Vivo/iQOO,
Realme and Samsung from `Build.MANUFACTURER` and `Build.BRAND`, and walks a list of candidate
`ComponentName`s per vendor — several each, because the names move between MIUI and ColorOS
versions. Every candidate is checked with `resolveActivity` and every launch is wrapped,
because `resolveActivity` can succeed and `startActivity` still throw. The chain falls back to
Android's battery-optimisation list, then to this app's own settings page.

**What was verified:** the fallback, on the Pixel. None of the vendor intents resolve there,
which is exactly the "intent does not resolve" branch — it does not crash, and it opens the
generic screen.

**What was NOT verified:** that any vendor deep link lands on the right screen. That cannot be
checked on a Pixel or an emulator, and no Xiaomi, Samsung, Oppo, Vivo or Realme was available.
**Definition-of-done item 7 is unchecked.** It is not "probably fine"; it is untested, and the
component names are the part most likely to be wrong.

---

## 9. The honest summary

| # | Definition of done | State |
|---|---|---|
| 1 | Three notifications at T-7, T-1, morning-of, user's hour and timezone | **Pass** as scheduled rows and armed alarms on the device (§2.4). The 09:00 firing on 3 Dec has not been waited for |
| 2 | SOLEMN produces two, never three, neither offering Gift ideas | **Pass**, on the device (§2.4, §2.5) |
| 3 | Aeroplane mode through morning-of; exactly ONE notification | **Pass** — both paths real, one notification (§3). The multi-day version has not been run |
| 4 | Quiet hours deferral on both paths | **Pass** on the device for the schedule (§5); a deferred reminder has not been watched arriving at 07:00 |
| 5 | Digest on: one notification, no per-event ones | **Pass** on the device for the schedule (§6); a digest has not been watched arriving |
| 6 | Self-test reports delivery from a real phone | **Pass** (§7) |
| 7 | OEM helper opens the right screen on a real non-Pixel | **UNCHECKED** — no such device (§8) |
| 8 | Unit tests: midnight quiet hours, solemn ladder, dedupe key, digest grouping | **Pass** — 205 server, 55 client |
| 9 | The M0 offline check, run and recorded | **Pass** (§1) |
| 10 | CI green, this document | CI green locally; see below |

### Also still open

- **Mobile data.** Checked again on 2026-11-25 and the SIM still will not register:
  `mVoiceRegState=OUT_OF_SERVICE`, `mDataRegState=OUT_OF_SERVICE`, the CS domain `DENIED`,
  `gsm.sim.state=LOADED,NOT_READY`. M1 §2.6 stays open. Every check above travelled over
  Wi-Fi, which does reach the public internet — the requests arrive at Railway from a public
  address over TLS — but "on mobile data" is still unproven and nothing in this repo should
  say otherwise.
- **A real 09:00.** Several checks would be stronger for an overnight run. None of them are
  blocked on code.

### Four bugs this milestone found, three of them in earlier code

Recorded because all four were invisible to every test that ran one thing at a time:

- **The delivery report downgrade** (§3), found by the two-path test on its first run.
- **Two settings-push field mismatches** (`a7e3408`). The client sent `timezone`, which the
  strict schema rightly refuses; and it OMITS a null quiet-hours window rather than sending
  null, because its Json uses `explicitNulls = false`, so a required-but-nullable pair
  rejected almost every push. Both presented as "my edits stopped syncing", because
  validation runs before anything is applied and one bad settings blob took the people and
  events in the same batch with it. Settings now go in a request of their own.
- **Token rotation under concurrency** (`3759cbc`). The phone signed itself out within a
  minute of the M2 server deploying. `authed` had no mutual exclusion, so two coroutines that
  both hit a 401 on the same expired access token both posted the same refresh token: one
  succeeded, one got a 401 for a token that had just been replaced, and that path clears the
  session. M0 and M1 never saw it because they only ever made one authenticated call at a
  time; M2 fetches the plan alongside a sync. It fires whenever the access token expires
  between two parallel calls — every fifteen minutes of use — and would have shipped as "the
  app keeps logging me out".
