# M2 Build Prompt — Reminder Engine

> Paste the block below into a **fresh Claude Code session** opened at the repo root.
>
> M1 is merged (`722830d`). `main` carries a deployed server on Railway, full people and
> events CRUD, deceased/solemn handling and offline-first sync. M2 is the milestone the whole
> product rests on: if a reminder does not arrive, nothing else in this app matters.

---

## The prompt

```text
You are building M2 of WishKaro, a Compose Multiplatform + NestJS app. M0 and M1 are merged
to main and working: Google sign-in, people and events CRUD, deceased/solemn tone handling,
offline-first sync, and a server on Railway that sends one FCM push per event on the morning
of the day. Read docs/REQUIREMENTS.md for product context, and docs/M0_PLAN.md and
docs/M1_PLAN.md for the decisions those milestones locked in. Build ONLY what this prompt
specifies.

## What M2 is

The reminder engine, built to be trusted. When M2 is done, this must be true on a REAL
Android phone:

  A reminder arrives when it should, exactly once
    -> at each rung of the ladder the user asked for, in their timezone
    -> even when FCM does not deliver, or the phone has been off the network for days
    -> never in the middle of the night
    -> and the user can prove all of this to themselves, without waiting for a birthday

Reliability is the deliverable. The ladder is a morning's work; everything else here exists
because Indian OEMs kill background apps and this product dies quietly when they do.

## What already exists — do not rebuild it

Read these before planning. Several things M2 appears to need are already in place:

  - ReminderOffset enum already has D7, D1, D0. ReminderChannel already has FCM and LOCAL.
    Nothing writes LOCAL yet.
  - The materialiser creates ONLY the D0 rung. `reminders.service.ts` hardcodes
    `offset: 'D0'` and `dedupeKey = ${occurrence.id}:D0`. D7 and D1 are genuinely new.
  - `reminder.dedupe_key` is UNIQUE at the database level, and the client already claims
    keys: `LocalStore.claimReminder(dedupeKey)` in `SeenReminder.sq`, called from
    `WishKaroMessagingService`. The de-duplication mechanism is built — M2 has to make the
    local alarm produce the SAME key as the server.
  - Pushes are DATA-ONLY on purpose, so the client always sees them. Do not switch to a
    `notification` payload; a backgrounded app would never run the dedupe check.
  - All four notification channels are already created on first launch, including
    `self_test` — see NotificationChannels. An Android channel's importance cannot be
    raised after creation, which is why they were made up front. Reuse these ids.
  - `POST /v1/reminders/test` already queues a reminder two minutes out and returns its
    dedupe key. The backend half of FR-REM-07 is done; it has no UI.
  - The materialiser already honours per-occurrence `override_date`, timezone changes,
    29 Feb, and `is_muted`. It already suppresses reminders for deleted people and revives
    them on restore.
  - `WishKaroMessagingService.onNewToken` carries a deprecation note saying to revisit it
    when the reminder engine is rebuilt. This is that milestone.

## Decide this before you write code

**The local alarm has to compute the server's dedupe key, and today it cannot.**

The key is `${occurrence.id}:D0`, but `event_occurrence` is not a sync entity — the client's
only tables are Person, Event, PersonTrait, SeenReminder and SyncState. The phone has never
seen an occurrence id, so it cannot build that string, so FCM and the local alarm would
produce two different keys and the user would get two notifications for one birthday. That is
the exact failure FR-REM-02 forbids.

Two ways out:

  (a) Make the key derivable from what both sides already know — something like
      `${eventId}:${YYYY-MM-DD}:${offset}`, computed independently on each side from the
      effective occurrence date. No new sync entity, no cursor change, and the key becomes a
      pure function with a test.
  (b) Sync occurrences down to the client as a fourth entity.

I think (a) is right and (b) is a lot of machinery for an id. But `dedupe_key` is UNIQUE and
there are live rows in two databases — Railway and the phone — so changing the format is not
free. Work out what happens to reminders already PENDING under the old format, decide, write
it in docs/M2_PLAN.md, and show me before building on it.

## Also still carried over

The offline check from M0 has never been run on a device. M1_PLAN section 2.5 deferred it
deliberately and recorded that it remained a gap, so M2 inherits a true statement rather than
a quiet one: aeroplane mode, add a person, force-stop, reopen, confirm they are still listed,
restore the radio, sync, confirm the write reaches the server.

Do it in M2, early. M2's whole premise is that the app behaves when the network does not, and
the local alarm path is meaningless if local persistence is broken. If it fails, that is an M0
bug, it blocks the fallback work, and you should say so rather than building on top of it.

The mobile-data leg of the Railway deploy also remains unproven — the SIM would not register
and M1 proved the chain over Wi-Fi instead, which does reach the public internet. If the SIM
works by the time you run this, close it out. If not, leave it recorded and do not fake it.

## Scope

### Schema additions

All pure additions. Both databases hold real data.

  user          + reminder_hour (default 9), ladder toggles for D7/D1/D0, quiet_hours_start,
                  quiet_hours_end, digest_enabled, digest_hour
  person        + reminder_hour_override (nullable — FR-REM-03 allows per-person)
  reminder        nothing new, but `channel` finally gets LOCAL rows written to it

Settings are per-user, synced like everything else. A user who reinstalls must not lose their
quiet hours.

### The ladder (FR-REM-03)

- T-7 days, T-1 day, morning-of. Each individually toggleable.
- Delivery hour is user-configurable globally and overridable per person.
- All three rungs materialise as separate Reminder rows with distinct dedupe keys.
- **Solemn ladder (FR-REM-11): SOLEMN events get T-1 and morning-of only.** No T-7, because
  T-7 is framed around gifting. This is not a copy change — the row must not be created.
  Write the test that fails if a D7 row is ever materialised for a SOLEMN occurrence.

### Local fallback (FR-REM-02, FR-REM-10)

- The client schedules a local alarm per upcoming event, independently of the server.
- Same dedupe key as the server's push, per the decision above, so the user sees exactly one.
- It must fire when FCM does not deliver, and when the device has been offline > 48h.
- Choose AlarmManager vs WorkManager deliberately and write down why. Exact-time delivery and
  Doze are the constraints; a reminder that arrives at 2pm instead of 9am is a failure.
- Alarms must survive reboot and app update.

### Quiet hours (FR-REM-09)

No notification inside the user's window; deferred to the window's end, not dropped. Both
paths — server and local alarm — must honour it, or the fallback becomes the thing that wakes
someone at 3am. Midnight-spanning windows (22:00–07:00) are the case that breaks naive
implementations; test it.

### Digest (FR-REM-08)

One notification at a chosen hour listing everything happening today, instead of per-event
notifications. It is a mode, not an extra: when digest is on, per-event reminders for that day
do not also fire.

### OEM battery helper (FR-REM-06)

REQUIREMENTS.md calls this the single highest-impact reliability feature in the app, and it is
the reason this milestone is not just a cron job.

- Detect Xiaomi, Oppo, Vivo, Realme and Samsung.
- Deep-link to the correct autostart / battery settings screen for that OEM, with
  instructions a non-technical user can follow.
- Every one of those deep links is a vendor-specific intent that may not resolve. Guard each
  one and fall back to the generic battery-optimisation screen rather than crashing.
- Reachable from onboarding AND permanently from Settings (FR-SET-02).

### Self-test (FR-REM-07)

"Send me a test reminder in 2 minutes", in Settings. The endpoint exists; give it a UI that
reports what happened — queued, delivered, or never arrived — because the point is for the
user to convince themselves, not to fire and forget.

### Notification actions (FR-REM-04, FR-REM-11)

- Send wish, Gift ideas, Snooze 1h, Mark done. T-7 leads with Gift ideas; morning-of leads
  with Send wish.
- **SOLEMN events offer only Send message and Mark done. The Gift ideas action must be
  absent, not disabled.**
- Send wish and Gift ideas belong to M5 and M8. Wire Snooze and Mark done for real; for the
  other two, open the person's detail screen rather than inventing a half-feature or leaving
  a button that does nothing.

### Settings screen

There is no Settings screen in the app at all — this milestone creates it. Ladder toggles,
delivery hour, quiet hours, digest mode, per-channel control (FR-SET-01), and the battery
helper (FR-SET-02).

### Delivery reporting (FR-REM-10)

The client reports delivery outcome back to the server, so the G1 metric — delivery rate above
98% on the OEM matrix — is measurable rather than asserted. Build this EARLY, not last: every
other claim in this milestone is unverifiable without it, and you want the measurement in
place before the features it judges.

## Explicitly NOT in M2

Import or CSV (M3). Calendar, timeline or widget (M4). Any AI, wish drafting or WhatsApp
handoff (M5). Posters (M6). Billing (M7). Gift suggestions (M8). Export or deletion (M9).
Family tree (v2). Festivals (never). Photo upload (M6).

The Send wish and Gift ideas actions are the edge of this milestone: the buttons appear with
the right copy and the right absences, and they open the person. They do not draft anything.

## Definition of done

1. One event produces three notifications at T-7, T-1 and morning-of, at the user's hour, in
   the user's timezone.
2. A SOLEMN event produces two, never three, and neither offers Gift ideas.
3. Aeroplane mode from before T-1 through morning-of: the reminder still arrives, from the
   local alarm, and the user sees exactly ONE notification after the radio comes back and the
   server's push lands late.
4. A reminder due inside quiet hours arrives at the window's end, on both paths.
5. Digest mode on: one notification listing the day, and no per-event ones.
6. The self-test, run from Settings on a real phone, reports delivery.
7. The OEM helper opens the right screen on at least one real non-Pixel device, and does not
   crash on a device whose intent does not resolve.
8. Quiet hours spanning midnight, the solemn ladder, dedupe-key derivation, and digest
   grouping each have unit tests.
9. The offline check from M0, finally run and recorded.
10. CI green. docs/M2_VERIFY.md with exact reproduction steps, including how you simulated a
    failed FCM delivery.

## Engineering rules

Everything from docs/BUILD_PROMPT_M0.md and docs/BUILD_PROMPT_M1.md still applies. In
addition:

- The repo is PUBLIC. Railway values go in Railway, never in the repo.
- No destructive migrations. Real data exists on Railway and on the phone.
- Derived values stay server-derived. A client never tells the server what a reminder says.
- One notification per event per rung. If you cannot prove that by construction, say so
  rather than hoping.
- Timezone remains the highest-bug-density area, and quiet hours are a second clock on top of
  it. A user in Asia/Kolkata with quiet hours 22:00–07:00 and a reminder due at 09:00 is the
  case to reason about first.
- Conventional commits, small and reviewable. Branch off main, PR at the end. main is
  merge-only.

## How to work

1. Write docs/M2_PLAN.md — the dedupe-key decision first, then your implementation order.
   Show it to me before writing code.
2. The offline check that M0 and M1 both left open.
3. Delivery reporting, so everything after it is measurable.
4. The ladder and the solemn ladder, server-side, proven with curl.
5. Settings: schema, sync, and the screen.
6. Quiet hours and digest.
7. The local alarm fallback and the one-notification proof.
8. The OEM helper and the self-test UI.
9. Stop and tell me when it works. Do not continue into M3.

If any requirement here conflicts with docs/REQUIREMENTS.md, stop and ask — do not silently
pick one. M0 and M1 each hit one of these and raising it was the right call both times.
```

---

## Before you run this

| Check | Why |
|---|---|
| PR #2 merged, `main` green | M2 branches from it |
| A real non-Pixel phone — Xiaomi, Samsung, Oppo, Vivo or Realme | The OEM helper cannot be verified on an emulator or a Pixel |
| Willingness to leave a phone alone overnight | Several checks need a real 09:00 to pass |
| A way to make FCM fail on purpose | Definition-of-done item 3 is the whole milestone |

## Why delivery reporting goes early

M1's Railway deploy went first because deployment bugs and feature bugs should not share a
cage. The same logic puts measurement before features here. "Reminders are reliable" is the
claim this entire product makes, and without the receipt path it is a claim no one can check —
including the next session, which will otherwise be asked to trust a walkthrough done once by
hand. Build the instrument, then build the thing it measures.

## The thing most likely to go wrong

Double notifications. Two independent delivery paths with a shared de-duplication key is the
correct design, and it is only correct if both sides compute the same key. The key format
today depends on an id the phone has never seen, which is why that decision sits at the top of
the prompt rather than inside the local-alarm section. Get it wrong and the bug is invisible
in every test that runs one path at a time.

## After M2

M3 is import: contacts and CSV/XLSX, column mapping, dedup against existing people, and a
result report. It is the first milestone where the app meets messy real-world data, and the
dedup work in M1 (`findDuplicates`, create-time only) is the foundation it builds on. Write
its prompt in this same shape — explicit scope, explicit non-scope, a definition of done
verifiable on a real device, and a "stop and tell me" at the end.
