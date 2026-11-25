# M2 Implementation Plan — Reminder Engine

> Derived from `docs/BUILD_PROMPT_M2.md`, cross-read against `docs/REQUIREMENTS.md`,
> `docs/M0_PLAN.md` and `docs/M1_PLAN.md`. Written before any code, for review.
> Date: 2026-11-25. Starting commit: `a87bc4f` on `main`. Branch: `feat/m2-reminder-engine`.

What M2 must make true, on a real Android phone:

```
A reminder arrives when it should, exactly once
  -> at each rung of the ladder the user chose, in their timezone
  -> even when FCM does not deliver, or the phone has been off the network for days
  -> never inside quiet hours
  -> and the user can prove it without waiting for a birthday
```

---

## 0. State of the world, as actually found

Checked on this machine today rather than assumed.

| Thing | State |
|---|---|
| `main` | `a87bc4f`, clean. M1 merged at `722830d` |
| Railway | Project `steadfast-education`, service `wishkaro` **● Online**, `https://wishkaro-production-0312ab77.up.railway.app` |
| Phone | **Pixel 9a connected** (`4B201JEBF03493`), `in.wishkaro.app` installed, updated today 15:48 — the M1 build. **A Pixel is the one device the OEM helper cannot be proved on** (§9.1) |
| Local stack | `wishkaro-db-1`, `wishkaro-redis-1` up and healthy. The `server` container is **not** running |
| Disk | **8.8 GB free** of 460 GB. Up from M1's 1.4 GB but still thin for Gradle + Docker together |
| Debug base URL | `local.properties` already points the debug build at Railway (M1 §2.4) |
| `targetSdk` | 36. This matters: see §5 on exact alarms |
| WorkManager | **Not a dependency yet** |

### The live reminder rows

Read from the local Postgres (`wishkaro-db-1`), which holds M0 and M1's verification data:

| status | offset | channel | count |
|---|---|---|---|
| PENDING | D0 | FCM | 74 |
| SUPPRESSED | D0 | FCM | 21 |
| SENT | D0 | FCM | 3 |
| FAILED | D0 | FCM | 2 |

96 `event_occurrence` rows. Every `dedupe_key` is `${occurrence.id}:D0`, except self-test rows
which are `test:${userId}:${epochMs}`.

**I could not read Railway's copy.** `railway ssh` needs an SSH key registered against the
account, and the Postgres service has no public TCP proxy (no `DATABASE_PUBLIC_URL`), so there
is no non-interactive path to it from here. I did not open one — exposing a database to the
internet on a public-repo project is not a thing to do quietly for a row count. What §1 needs
from Railway is the *shape* of the keys, not the count, and the shape is fixed by the code in
`reminders.service.ts`, which is the same code that wrote the local rows. The migration in §1
is written to be correct for any count. See §9.2 if you want the real numbers first.

---

### 0.1 The M0 offline check — **run today, and it passes**

M0 left it untried and `M1_PLAN.md` §2.5 deferred it deliberately, recording that it stayed a
gap. It is no longer one. Run on the Pixel 9a against Railway, 2026-11-25 18:04–18:07 IST:

| leg | evidence |
|---|---|
| Offline for real | `cmd connectivity airplane-mode enable`, Wi-Fi disabled. `ping 8.8.8.8` → *Network is unreachable*; the Railway host did not even resolve |
| Add a person offline | "Offline Check M2", Friend, birthday 2 Dec 1990. Saved, person detail drew "in 7 days / Turning 36" with no network |
| Force-stop | `am force-stop in.wishkaro.app`. The database pulled off the device (`run-as cat`) held `person 0b6c4ca3-…` and `event e73582ba-…`, both `pending_sync = 1` |
| Reopen | Both people and the new one listed, countdown intact, still offline |
| Radio back → sync | `POST /v1/sync` → **200**, in Railway's own request log at 12:37:00Z, 420-byte body. Afterwards `pending_sync = 0`, `updated_at` restamped to the server's `12:36:56.507Z`, and `sentiment = CELEBRATORY` present on the event — a column **the client never writes**, so it can only have come back from the server |

The last row is the part that matters: the proof is not that the client believes it synced, it
is that a server-derived value the client cannot invent came back down.

**So the local write path is sound and the local-alarm work in §7 step 7 is not built on sand.**

Two things noticed while doing it. Neither is M2's to fix; both are written down rather than
left in a screenshot:

- A never-synced person's detail screen reads **"Profile 0% complete"** directly above
  **"Nothing left to add."** `completeness_score` is server-derived, so it is 0 until the first
  sync, while the suggestion list is computed locally — the two disagree for exactly as long as
  the record is offline. Cosmetic, M1's, and a one-line fix whenever someone is in there.
- Client-generated ids are **UUIDv4** (`0b6c4ca3-2173-4cc6-…`), not the UUIDv7 that `M1_PLAN.md`
  attributes to D23. Harmless — `id` is only the tiebreaker in the `(updated_at, id)` cursor, so
  nothing depends on it sorting by time — but the doc and the code disagree.

### 0.2 The mobile-data leg — **checked today, still unproven, still not faked**

M1 §2.6 left it open because the SIM would not register. It still will not. With Wi-Fi off:
`mVoiceRegState=OUT_OF_SERVICE`, `mDataRegState=OUT_OF_SERVICE`, the CS domain
`registrationState=DENIED` on CellOne, the PS domain `NOT_REG_OR_SEARCHING`, and
`gsm.sim.state=LOADED,NOT_READY`. `ping` over mobile data: *Network is unreachable*.

The sync above therefore travelled over Wi-Fi, which does reach the public internet — the
request arrived at Railway from a public IP and the response came back through TLS. What
remains unproven is the specific clause "on mobile data", and it stays unproven. Nothing in
this repo should say otherwise.

---

## 1. The blocker: the dedupe key

### 1.1 The problem, restated

`dedupe_key` is `${occurrence.id}:D0`. `event_occurrence` is not a sync entity — the client's
tables are Person, Event, PersonTrait, SeenReminder and SyncState. The phone has never seen an
occurrence id, so a local alarm cannot produce the server's key, so FCM and the alarm would
produce two keys and the user would get two notifications for one birthday.

### 1.2 Decision: `${eventId}:${occurrenceYear}:${offset}` — not the date

The prompt recommends option (a), a derivable key, and I agree. I am departing from the
*format* it suggests (`${eventId}:${YYYY-MM-DD}:${offset}`) and using the occurrence **year**
rather than the effective date. The reason is not neatness.

Three things move an occurrence's effective date after its reminder row already exists:

1. the user edits the event's `day`/`month`;
2. the user sets a per-occurrence `override_date` (FR-EVT-10) — which the client cannot see at
   all, because it lives on `event_occurrence`;
3. 29 Feb falls back to 28 Feb in a non-leap year (FR-EVT-09).

With a date in the key, every one of those turns one reminder into two keys. The server can
clean up its own stale row. **The client cannot.** A phone that has not synced since the edit
still holds the old date, fires `E:2027-04-04:D0`, and the server sends `E:2027-04-05:D0` — two
notifications for one birthday, which is the exact failure the key exists to prevent, and it is
invisible to any test that exercises one path at a time.

`occurrence_year` is immutable for the life of the occurrence. It is already the uniqueness key
of `event_occurrence(event_id, occurrence_year)`. Under it, a stale client fires on the wrong
*day* — a visible staleness bug that self-corrects at the next sync — but the user still sees
exactly one notification. **Wrong-day-once is strictly better than right-day-twice.**

It is also derivable on the client with no new data: `BirthdayMath.nextOccurrence(day, month,
zone)` already returns the occurrence date, and its `.year` *is* the occurrence year — the same
rule as the server's `nextOccurrence`, including the 29 Feb fallback, which keeps the year and
moves only the day. The key becomes a pure function on both sides, with a test on each.

```
dedupeKeyFor(eventId, occurrenceYear, offset) = "${eventId}:${occurrenceYear}:${offset}"
```

Self-test keys keep their `test:` prefix and are untouched.

### 1.3 But a derivable key is only half of it

Option (a) makes the *key* derivable. It does not make the *schedule* or the *copy* derivable.
For the local alarm to fire at the right instant the client would have to reimplement, in
Kotlin: ladder toggles, the solemn ladder, the per-person hour override, quiet-hours deferral,
digest suppression, and `override_date` — which it cannot see. To draw the notification it
would have to reimplement `buildReminderCopy`, including the SOLEMN string set that M1 wrapped
in an exhaustive cross-product test. Two implementations of the tone rules, in two languages,
is the thing this project has twice decided not to do, and the one it decided it for was
exactly this: not saying "Happy Birthday" to someone who has died.

**So the server ships the plan and the client replays it.**

`GET /v1/reminders/plan` returns the already-materialised PENDING reminders for the next 60
days: `dedupeKey`, `scheduledAtUtc`, `offset`, `title`, `body`, `personId`, `sentiment`,
`deepLink`, `actions`. The client caches them in a new local table and arms one alarm each. All
derivation stays server-side. Both paths carry **identical** keys by construction rather than by
two implementations agreeing.

This is not option (b). There is no new sync entity, no cursor change, no occurrence id on the
client. It is a read-only cache of the server's output, refreshed on every sync, on every push,
and by a daily worker. 60 days comfortably covers FR-REM-10's "offline > 48h".

**The degraded path, stated honestly.** An event created offline that the server has never seen
has no plan row, so it has no server-authored alarm. For those — and *only* those, identified by
`pending_sync = 1` — the client arms a D0 alarm at the user's global hour with tone-neutral copy
that names the person and makes no claim about the occasion. It uses the same key, so when the
server later materialises and pushes, the claim already exists and the push is suppressed. Once
a row has successfully pushed, the plan governs and the local fallback for it is cancelled. This
keeps the client from ever authoring celebratory copy.

### 1.4 What happens to the reminders already PENDING

`dedupe_key` is a plain UNIQUE TEXT column. Rewriting a value is an `UPDATE` — a data migration,
not a destructive one. No column changes type, nullability or name.

| rows | what happens | why |
|---|---|---|
| `status IN (PENDING, SUPPRESSED)` and `event_occurrence_id IS NOT NULL` | **rewritten** to `${event_id}:${occurrence_year}:D0` | SUPPRESSED is included because `revivePending()` can bring it back to PENDING |
| `status IN (SENT, DELIVERED, FAILED)` | **untouched** | history. Their keys are already claimed in `seen_reminder` on the phone; rewriting would un-claim them |
| `event_occurrence_id IS NULL` (self-test) | **untouched** | not event-derived, no local alarm |

The rewrite is injective: `event_occurrence` is already UNIQUE on `(event_id, occurrence_year)`,
so no two rewritten D0 rows can collide. It runs as SQL inside the Prisma migration, in one
transaction, so `prisma migrate deploy` applies it on Railway's cold boot with everything else,
exactly once per database.

**Nothing is needed on the phone, and nothing would be safe.** `seen_reminder` holds claims only
for reminders that were actually *shown*. A reminder that is PENDING has by definition never been
shown, so no claim exists under either format — the rewrite cannot orphan one. Deleting claims to
"migrate" them would resurrect notifications the user has already seen. The old-format rows in
`seen_reminder` simply age out as dead weight; they are keyed by a string and cost nothing.

**Therefore a double is impossible by construction here:** the only way the phone could show a
rewritten reminder twice is if it had already claimed the old key, which would mean it had
already been shown, which would mean the row was not PENDING, which would mean it was not
rewritten.

---

## 2. Two contradictions I am not resolving silently

### 2.1 Does digest replace the whole ladder, or only morning-of? — **needs your call**

| Source | Says |
|---|---|
| `FR-REM-08` | "one notification at a chosen hour listing **everything happening today**, instead of per-event notifications" |
| `BUILD_PROMPT_M2.md` | "when digest is on, **per-event reminders for that day** do not also fire" |

Those two halves pull apart as soon as the ladder exists. A T-7 reminder is not about something
happening today — it is about something happening in a week.

- **Reading (i): digest replaces D0 only.** D7 and D1 still fire per-event. A user who turned on
  digest to stop being pinged still gets two per-event pings for every birthday. That is not a
  digest; it is a digest plus a ladder.
- **Reading (ii): digest replaces all three rungs.** One notification a day, whose body is
  grouped — *Today · Tomorrow · In 7 days*. Nothing else fires.

**My recommendation is (ii).** Someone who chooses a digest is choosing "one notification a day",
and (i) does not deliver that. It also keeps the implementation honest: digest mode becomes "take
every reminder due today at any rung, group them, send one", rather than a special case that
knows about D0.

It changes what the feature *is*, so I am not picking it alone. Tell me (i) or (ii).

### 2.2 `channel = LOCAL` cannot be a second row — **recommended, not blocking**

`BUILD_PROMPT_M2.md` §Scope says `reminder` gets nothing new "but `channel` finally gets LOCAL
rows written to it". `dedupe_key` is UNIQUE *across the table*, so one rung cannot have both an
FCM row and a LOCAL row. The two statements cannot both hold.

Resolutions:

- **(a) `channel` becomes the channel that actually *delivered* it.** One row per rung, created
  as FCM, flipped to LOCAL when the client reports that the local alarm won the race. The column
  stops meaning "how it was scheduled" and starts meaning "how it arrived".
- (b) Make the uniqueness `(dedupe_key, channel)`. That is an index change on live data in two
  databases, and it weakens the one guarantee the whole design rests on.

**I am going with (a)** unless you say otherwise. It needs no migration beyond a comment, and it
turns the G1 metric into one query: `count(channel = LOCAL) / count(*)` *is* the FCM failure
rate, measured rather than asserted, which is the point of putting delivery reporting first.

### 2.3 Smaller things — resolved, recorded, not re-litigated

| # | Finding | Resolution |
|---|---|---|
| a | Digest has no notification channel. The four existing ones were created up front because an Android channel's importance cannot be raised later | Add `reminders_digest` at `IMPORTANCE_HIGH`, matching `MORNING_OF`, which it replaces. A *new* id on an existing install is fine — the rule only forbids raising an existing channel |
| b | `FR-REM-04` lists Snooze 1h for all reminders; `FR-REM-11` says SOLEMN offers "only Send message and Mark done" | FR-REM-11 wins for SOLEMN: no Snooze, no Gift ideas. Absent, not disabled |
| c | A per-person `reminder_hour_override` is meaningless while digest is on | Digest wins; the Settings copy says so. The column is still honoured the moment digest is off |
| d | `REQUIREMENTS.md` §8's `user` sketch has no settings columns | §8 gains them in the same tidy-up commit, as M1 did for `milestone_label` |
| e | `WishKaroMessagingService.onNewToken` carries an M0 note saying to revisit it here | Not yet checked. Step 7 reads what Firebase's current SDK actually offers for token rotation and either migrates or replaces the note with the finding. Either way the note stops saying "revisit in M2" |
| f | `USE_EXACT_ALARM` is granted at install but Play restricts it to apps whose core function is an alarm clock or calendar | Not claiming it. Using user-grantable `SCHEDULE_EXACT_ALARM` (§5). The Play-policy question belongs to the milestone that first submits a build |

---

## 3. Schema changes — all additive

### Server (one Prisma migration, `m2_reminder_engine`)

```
user    + reminder_hour        int  not null default 9
        + ladder_d7            bool not null default true
        + ladder_d1            bool not null default true
        + ladder_d0            bool not null default true
        + quiet_hours_start    int  null          -- local hour 0..23, null = no quiet hours
        + quiet_hours_end      int  null
        + digest_enabled       bool not null default false
        + digest_hour          int  not null default 9

person  + reminder_hour_override int null         -- FR-REM-03

reminder  no new columns. `channel` gains meaning (§2.2) and `DELIVERED` finally gets written
```

Quiet hours are a pair of **local hours**, not timestamps: the window is a property of the
user's day, not of any instant. Both nullable together — a half-set window is rejected at the
schema level.

Settings are per-user and sync like everything else, so a reinstall does not lose quiet hours.

### Sync

`user` is not a sync entity today; settings reach the client another way. Adding a fourth
cursor entity for a single row the user owns is the wrong shape. Instead:

- `GET /v1/me/settings` and `PATCH /v1/me/settings`, and the settings blob rides on the
  `/v1/sync` pull response alongside the cursor. One row, always sent, last-write-wins on
  `updated_at` exactly as everything else.
- `user` gains `settings_updated_at` so the client can tell whose copy is newer.

### Client (SQLDelight, migration `3.sqm`)

```
user_settings      one row, mirrors the server's settings blob + pending_sync + updated_at
scheduled_reminder dedupe_key PK, scheduled_at_utc, offset, title, body, person_id,
                   sentiment, deep_link, actions, source (PLAN|LOCAL_FALLBACK)
delivery_report    dedupe_key PK, outcome, channel, reported_at, pending_sync
```

`verifyMigrations` is already on from M1, so a missing `.sqm` fails CI rather than the handset.

---

## 4. Delivery reporting, first

Built before the things it judges, per the prompt.

`POST /v1/reminders/delivery` — body `{ reports: [{ dedupeKey, outcome, channel, shownAtUtc }] }`.

`outcome` is one of `SHOWN`, `SUPPRESSED_DUPLICATE`, `SUPPRESSED_QUIET`, `SUPPRESSED_DIGEST`.
Batched, because the device is frequently offline at the moment that matters — the reports go
into `delivery_report` with `pending_sync = 1` and drain on the next successful sync, exactly
like the people/events outbox. A report that never drains is itself a signal.

The server maps `SHOWN` to `status = DELIVERED` and sets `channel` to whichever path won
(§2.2). Reports for a key the server does not know (a local fallback for an event created
offline) are accepted and logged, not rejected — that case is real and dropping it would hide
precisely the failures this exists to count.

---

## 5. AlarmManager vs WorkManager — decided, with the reason

**`AlarmManager`, with WorkManager as a net underneath it. Not WorkManager alone.**

WorkManager cannot express an exact time. Its constraints are batched against Doze maintenance
windows, and a 09:00 reminder arriving at 14:00 is the failure the prompt names explicitly. The
only APIs that pierce Doze at an approximately exact instant are
`setExactAndAllowWhileIdle` and `setAlarmClock`.

The complication is `targetSdk = 36`. Since Android 14, `SCHEDULE_EXACT_ALARM` is denied by
default for apps targeting 33+. The install-time alternative, `USE_EXACT_ALARM`, is restricted
by Play policy to apps whose core function is an alarm clock or calendar (§2.3f) — a birthday
reminder is a plausible but not a certain fit, and that is not a bet to make silently in a
milestone about reliability. So:

| condition | what fires |
|---|---|
| `canScheduleExactAlarms()` true | `setExactAndAllowWhileIdle` — on time |
| false | `setAndAllowWhileIdle` — still pierces Doze, drifts by minutes not hours |

and Settings gets a row that deep-links to `ACTION_REQUEST_SCHEDULE_EXACT_ALARM`, sitting
directly beside the OEM helper, because they are the same conversation with the user.

Underneath both: a daily `PeriodicWorkRequest` that refetches the plan and re-arms every alarm.
Alarms do not survive force-stop, and on several of the OEMs in §9.1 force-stop is what the
battery manager does to you overnight. The worker is the thing that notices.

Alarms must also survive reboot and app update, which AlarmManager does not do for you:

- `RECEIVE_BOOT_COMPLETED` + a receiver on `BOOT_COMPLETED`
- the same receiver on `MY_PACKAGE_REPLACED`
- both re-arm from `scheduled_reminder`, which is the durable record. The alarms are derived
  state; the table is the truth.

---

## 6. Quiet hours

Computed **server-side** when the reminder is materialised, so `scheduled_at_utc` is already
the deferred instant and both delivery paths honour it with one implementation. That is the
main reason §1.3's plan-push design earns its keep.

The one place it exists twice is the local fallback for an event the server has never seen
(§1.3). That path applies the same rule client-side in ~15 lines. Both implementations are
tested against the same written-down table of cases:

| window | due | result |
|---|---|---|
| none | 09:00 | 09:00 |
| 22:00–07:00 | 09:00 | 09:00 — *outside*; the midnight-spanning test that catches naive `start <= h < end` |
| 22:00–07:00 | 23:30 | 07:00 next day |
| 22:00–07:00 | 03:00 | 07:00 same day |
| 22:00–07:00 | 22:00 | 07:00 next day — boundary is inclusive at the start |
| 22:00–07:00 | 07:00 | 07:00 — boundary is exclusive at the end |
| 09:00–17:00 | 12:00 | 17:00 — a non-spanning window still works |
| 00:00–00:00 | any | rejected at the API; a zero-width or full-day window is a user error, not a silent all-day mute |

Deferral moves the instant. **It never moves the dedupe key** — another consequence of §1.2
that would not hold with a date in the key.

---

## 7. Implementation order

Each step is provable before the next depends on it.

| # | Step | Proof |
|---|---|---|
| **1** | ~~The M0 offline check~~ | **Done — §0.1. It passes.** Step 7 is not blocked |
| **2** | Prisma migration: settings columns, the dedupe-key rewrite (§1.4), `dedupeKeyFor()` and its tests. No behaviour change yet | Migration applied to the local database with its 96 occurrences and 95 live reminders; unit tests on the key; a test asserting the rewrite is injective |
| **3** | **Delivery reporting**, server + client outbox | `curl` a report, see `DELIVERED`; a report queued in aeroplane mode drains on reconnect |
| **4** | The ladder and the **solemn ladder**, server-side | `curl`: one event → three reminder rows with distinct keys. A SOLEMN event → two. The test that fails if a D7 row is ever materialised for a SOLEMN occurrence |
| **5** | Settings: schema, `/v1/me/settings`, sync, and the screen the app does not have yet | Change the hour on the phone, see pending reminders move on Railway |
| **6** | Quiet hours (§6) and digest (§2.1, once you have answered) | The table in §6 as unit tests, on both sides |
| **7** | The local alarm fallback, the plan endpoint, boot/update re-arm, and **the one-notification proof** | The aeroplane-mode run in §8 |
| **8** | OEM helper and the self-test UI | §9.1 — needs a device I do not have |
| **9** | `docs/M2_VERIFY.md`, REQUIREMENTS tidy-up, CI green, PR | You reproduce the chain from the doc alone |

Conventional commits, small. M0 was 8, M1 was 14; M2 is realistically **14–16**. If it heads
past that I will say so rather than quietly producing a 60-file drop.

---

## 8. How I will fake a failed FCM delivery

Definition-of-done item 3 is the whole milestone, so the method matters more than the result.

1. **Aeroplane mode across the window.** Radio off before T-1, through morning-of. The local
   alarm must fire on time with no network at all. Then the radio comes back, FCM delivers both
   backlogged pushes, and `claimReminder` must suppress both. One notification, observed.
2. **FCM reachable but the app unable to receive.** Force-stop the app and send a data-only
   push: a force-stopped app does not receive FCM at all, which is the real-world OEM failure
   rather than a simulated one. The alarm, re-armed by the daily worker, still fires.
3. **Server-side suppression.** A debug-only env flag that makes `sendDue` claim the row and
   deliberately not call Firebase, so the server believes it sent and the client never hears.
   That is the pure FCM-silently-drops case, and it is the one the first two do not cover.

Each is recorded in `M2_VERIFY.md` with the exact commands.

---

## 9. What I need from you

### 9.1 A real non-Pixel phone — blocks step 8

Only a Pixel 9a is connected. The OEM helper (FR-REM-06) is a set of vendor-specific intents —
`com.miui.securitycenter`, `com.coloros.safecenter`, `com.samsung.android.lool` and the rest —
and whether they resolve is exactly the thing that cannot be checked on a Pixel or an emulator.
I will build it with every intent guarded and falling back to the generic battery screen, and I
can prove the *fallback* on the Pixel because none of those intents resolve there. What I cannot
prove without a Xiaomi, Samsung, Oppo, Vivo or Realme in hand is that the deep links land on the
right screen. **If no such device appears, `M2_VERIFY.md` will record item 7 as unchecked.** I am
not going to describe it as verified on an emulator.

### 9.2 Railway database access, if you want §0's numbers to be real

`railway ssh` needs a key (`railway ssh keys add`) and the Postgres service has no public proxy.
Either is a minute's work for you and neither is mine to decide — opening a database port on a
public-repo project is your call, not something to do for a row count. The §1.4 migration does
not depend on it.

### 9.3 Two answers

1. **§2.1 — digest: reading (i) or reading (ii)?** This one changes what the feature is.
2. **§2.2 — `channel` as delivered-channel?** I will proceed with (a) unless you object.

### 9.4 Two things to be willing to do

- Leave the phone alone overnight. Several checks need a real 09:00.
- A working SIM, if you want the mobile-data leg closed. Checked today and it is still
  `OUT_OF_SERVICE` / registration `DENIED` — §0.2. Not mine to fix and not mine to fake.

---

## 10. Explicitly not in M2

Import/CSV (M3) · calendar, timeline, widget (M4) · any AI, wish drafting, WhatsApp handoff
(M5) · posters (M6) · billing (M7) · gift suggestions (M8) · export and deletion (M9) · family
tree (v2) · festivals (never) · photo upload (M6) · retention/purge job (M9) · release signing
and certificate pinning (M1 §2.4, still open) · shared events across people (M1 §2.3) ·
per-field LWW (M1 §2.2) · iOS beyond "it compiles".

Send wish and Gift ideas are the edge: the buttons appear with the right copy and the right
absences, and they open the person. They draft nothing.
