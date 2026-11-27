# M9 Implementation Plan — Privacy (the half that needs no Play Console)

> Branch `feat/m9-privacy`, off `main` at `493f829`. One PR, not merged.
>
> **Nothing in this document decides the four decisions.** They are Dipen's, they are in §3,
> and §4 says which definition-of-done items each one blocks so the blocked set is known on
> day one. Everything in §5 is a collision between `docs/BUILD_PROMPT_M9.md` and
> `REQUIREMENTS.md` that I am raising rather than resolving. M5 raised three and all three
> were real; M6 raised seven.

---

## 0. What I read, and in what order

1. `docs/BUILD_PROMPT_M9.md`, in full.
2. `docs/REQUIREMENTS.md`, **all 699 lines** — not only §7.13. §12 is half the scope and has
   **no `FR-` ids at all**, which is M5's §0.6 lesson one step worse: a plan that quotes by id
   would skip the whole section. §12 is therefore walked box by box in §7 below, and §10 and
   §11 are walked rule by rule in §8.
3. `docs/MILESTONE_CHECKLIST.md`, including the "What M6 added" block committed as `493f829`
   while I was reading it. Its closing paragraph is the one that binds this milestone hardest
   and it is quoted in §9.
4. `docs/M6_VERIFY.md` §0.7, §0.7a, §0.7b — 31 defects found by five reviewers in code whose
   own suite and device run were green, then four more by a sixth reviewer who had not seen
   the first five, one of them a hole in a fix hours old.
5. The code: `schema.prisma` (all 15 models and every `onDelete`), `sync.service.ts`,
   `sync-cursor.ts`, `auth.service.ts`, `jwt-auth.guard.ts`, `blob-store.ts`,
   `local-blob-store.ts`, `media.controller.ts`, `photo.service.ts`,
   `poster-pipeline.service.ts`, `reminders.scheduler.ts`, the three rate limiters,
   `ImportStore.kt`, `ImportPlan.kt`, `ImportScreens.kt`, `SettingsScreen.kt`.

**What M6_VERIFY §0.7 means for M9 specifically.** M6's nine blockers were almost all
unreachable with the committed fake provider. M9 has the same shape in a different place:
**the committed `BLOB_STORE=local` is a fake for the store that would hold a deletion's
bytes**, and it is also *not durable* — `LocalBlobStore.isDurable = false`, Railway replaces
the container on deploy. A deletion test that passes against a store which loses its contents
anyway is a test that can be green for the wrong reason. §9 says what is done about that.

---

## 1. State of the world, as actually found

### 1.1 What exists

| Thing | Where | State |
|---|---|---|
| `asset.expires_at` | `schema.prisma`, written by M6 | **Already written.** 90 days at `photo.service.ts:202`, 180 at `poster-pipeline.service.ts:694`. Nothing reads either. |
| Repeatable BullMQ job | `reminders.scheduler.ts` | Two of them, on `upsertJobScheduler`, idempotent on id. The retention sweep copies this exactly. |
| Blob store port | `media/blob-store.ts` | `put/get/exists/delete/signedUrl`, `storeName`, `isDurable`. **No `list`.** See §6.4. |
| Signed URLs | `SIGNED_URL_TTL_SECONDS = 600`, `MediaController` `@Public` | The existing pattern for handing bytes to something with no session. Relevant to decision 2. |
| Contacts disclosure | `ImportScreens.kt:186` `PermissionRationale` | **Exists.** The prompt says M3 shipped without one. It did not. See C10 — but the copy has a problem that matters more than its absence would have. |
| Content report | `POST /v1/ai/report` (M6) | Built. §12 box ticked by M6, not by M9. |
| Moderation | `modules/moderation/`, both surfaces (M6) | Built, structurally. §12 box ticked by M6. |
| Minor flag | `isMinorFor`, `person.is_minor` | Suppresses relation in both prompt builders (`poster-prompt.ts:64,92`, `prompt-builder.ts:234`). Verify, do not rebuild. |

### 1.2 What does not exist

- Any `DELETE /v1/account`, any `GET /v1/account/export`. **Both are listed in §9 of
  `REQUIREMENTS.md` and neither has ever been written.**
- Any retention job, any "keep" flag, any record of having warned anybody about anything.
- Any public web page of any kind. The app serves `/v1/*` and `/health`. No `@Controller()`
  at the root, no static files, no view engine, no `app.setGlobalPrefix`.
- Any way to send email. No SMTP, no provider, no key, no dependency.
- Any link from a `poster` row to the `SOURCE_PHOTO` asset it was composited from. See C3 —
  this is the one that makes FR-SET-06 partly unexpressible.

### 1.3 Carried forward, named rather than quietly dropped (checklist §A)

M9 closes none of these and does not pretend to:

- **OEM battery helper** unexercised on a non-Pixel device — unchecked across **seven**
  milestones (M2 item 7).
- **Mobile-data leg** of the Railway deploy still unproven (M2 §0.2).
- **Instrumented suite still does not run in CI** — CI has no device (M4 item 5).
- **M5 definition-of-done item 4** — a real remembrance draft read by Dipen — neither done
  nor deferred; M6 did not touch it and neither does M9.
- **TBD-5** (visual identity) open since M4; every theme is still a visible placeholder.
- **FR-POS-09's cache-HIT latency target** moved to unmeasured by M6's C8 (`M6_VERIFY.md`
  §4.7).

---

## 2. The one requirement with a clock on it, and why it goes first

FR-SET-06 is the only thing in this milestone with interest accruing. M6 began storing
**photographs of real people** — uploaded, EXIF-stripped, composited — and nothing purges
anything. It is also the only piece of scope that **none of the four decisions block** (§4).

So the build order is:

1. **Retention (FR-SET-06)** — the sweep, the keep override, the warning. Blocked by nothing.
2. **The deletion inventory and the hard-delete service** — buildable as far as decision 3's
   `ai_usage` answer, which is the last line of the function rather than its shape. I will
   build it with the ledger's treatment behind a single named, tested seam so that whichever
   answer comes back is a one-line change and a test flip, not a rewrite.
3. **Prominent disclosure (§12)** — blocked by nothing; cheap; and C10 found something in the
   existing copy worth fixing whatever the decision.
4. **Export (FR-SET-03)** — the *production* of the JSON and CSV is blocked by nothing; the
   *delivery* is decision 2. I will build the producer and leave the delivery seam open.
5. **Policy and terms (FR-SET-05)** — content is blocked by decision 3 (the page has to say
   what deletion keeps); hosting is decision 1. I will write neither until both are answered,
   because a policy page that describes a deletion we did not build is the worst artifact in
   this milestone.
6. **The in-app surfaces** — the Settings rows, the typed confirmation.
7. **The public web deletion page** — decision 1 for where, and §6.2 for how identity works.

This is the checklist's "floor before features" ordering: the sweep and the inventory are what
everything else stands on.

---

## 3. The four decisions — stated, costed, NOT taken

### Decision 1 — where do the policy pages and the public deletion URL live?

FR-SET-05 wants a stable public URL before Play submission. FR-SET-04's deletion path is a web
page. This project has no website. The API is at
`wishkaro-production-0312ab77.up.railway.app`, a generated hostname that changes if the
service is recreated — the opposite of stable, and it is the host Play would be given as the
canonical location of the privacy policy.

| Option | What it costs | What it gives up |
|---|---|---|
| **A — serve from the existing NestJS app.** A root `@Controller()` with `@Public()` routes at `/privacy`, `/terms`, `/delete`. | Nothing. ~200 lines, no new infrastructure, no new deploy, no money. | **Stability.** The URL Play is given is the generated Railway hostname. Recreating the service breaks the privacy-policy link on a live Play listing, which is a policy violation, not an outage. Also ties the legal pages to the API's uptime: the server down means the deletion page is down, and Play requires the deletion path to work. |
| **B — a domain in front of Railway.** `wishkaro.in` or similar, CNAME to Railway. | Money (~₹800–1,200/yr for `.in`), a DNS decision, a registrar account, certificate handling (Railway does this automatically for custom domains). | Nothing technically, but **it is a purchase and a recurring commitment, and that is not a session's call.** It also still ties the pages to the API's uptime. |
| **C — static hosting elsewhere.** GitHub Pages / Cloudflare Pages for the policy pages. | A second thing to deploy and keep in sync. GitHub Pages is free and the repo is already public. | **It cannot host the deletion page**, because deletion needs a server. So C is really "C for policy + A or B for deletion", which means two URLs on two hosts, and the thing most likely to rot is the one nobody looks at. |

**What I would do if it were mine, stated so you can disagree with a position rather than a
blank:** A now, B before Play submission. A is reversible in an afternoon; the hostname only
has to be stable at the moment the Data Safety form is filled in, which is the launch half and
not this milestone. But **whether to buy a domain is yours**, and if the answer is B now, the
registrar and DNS work is not in this prompt's scope and I will say so rather than absorb it.

### Decision 2 — how is the export delivered?

FR-SET-03 says "delivered by **email link**". **This app cannot send email.** Firebase is here
for FCM only. There is no SMTP, no provider, no key, and no dependency in `server/package.json`
that could send one.

| Option | What it costs | What it gives up |
|---|---|---|
| **A — in-app download via a signed URL.** The export is produced, written to the blob store, and a `SIGNED_URL_TTL_SECONDS` URL is handed to the app exactly as a poster already is. | Nothing new. No vendor, no key, no secret, no dependency. It is a path this app already has and already tests. | **The literal wording of FR-SET-03**, and a real property with it: an email link survives the app being uninstalled, which is precisely the moment somebody wants their data. A signed URL that only the app can ask for is useless to a user who has already deleted the app — and FR-SET-04's own web path exists because that user is real. |
| **B — email.** A provider (Resend / SES / Postmark), a key, a verified sending domain (which drags in decision 1), bounce handling. | A new external dependency in a milestone whose stated point is that it needs none; a new secret in a public repo's deployment; ~₹0 at this volume but an account and a domain. | Nothing of the requirement. It is the requirement. |
| **C — both, later.** Build A now behind an interface; add B when decision 1 settles the domain. | A's cost now. | Honesty about the §12 tick until B exists. |

**Decision 2 is partly downstream of decision 1**: a verified sending domain is a domain.
If the answer is B, that is a new provider and a new key and it goes in this plan as added
scope, not absorbed quietly.

### Decision 3 — what does "irreversible" delete, and what survives? — **ANSWERED**

> **Dipen's answer, 2026-11-27: ANONYMISE the ledger and the reports; erase everything else.**
> `ai_usage`, `generation` and `content_report` keep their rows with `user_id` set to NULL.
> Migration `20261127160000_m9_account_deletion` makes those three foreign keys
> `ON DELETE SET NULL`, so **the database enforces it** rather than a service method
> remembering to run in the right order — `account-deletion.db.spec.ts` deletes a user
> directly, bypassing the service entirely, and asserts the rows survive.
>
> What it gives up, unchanged from the table below: per-user cost attribution for deleted
> accounts. What it buys: §10 rule 2 and FR-POS-10 hold, DPDP erasure holds, and the policy
> page can say one true thing instead of choosing between two.
>
> The original analysis is kept, because the reasoning is what anybody questioning this in
> three months will want.

### Decision 3 — what does "irreversible" delete, and what survives?

**This one is not open in the way it looks. The schema has already chosen, and it has chosen
the option §10 rule 2 forbids.**

`AiUsage.user` is declared `onDelete: Cascade` (`schema.prisma:706`). So a `prisma.user.delete()`
today **silently destroys the cost ledger** for that user. §10 rule 2 — "every call is wrapped
in a `generation` record with measured INR cost" — and FR-POS-10's "non-negotiable, without it
you cannot price the product" both say those rows must exist. Nobody decided this; it is the
default that was written in M5 when there was no deletion path to think about.

Table by table, with what I propose and what it gives up. **Choose per row.**

| Table | Holds | Proposed | What that gives up |
|---|---|---|---|
| `user` | email, name, photo URL, Google sub, timezone, every setting | **ERASE** | Nothing. |
| `person` | 566 invented people + 2 real contacts: names, phones, emails, notes, relation, deceased flag | **ERASE** | Nothing. |
| `person_trait` | hobbies, likes, dislikes, budget band | **ERASE** | Nothing. |
| `event` / `event_occurrence` | birthdays, death anniversaries | **ERASE** | Nothing. |
| `reminder` | schedules, delivery outcomes | **ERASE** | The G1 delivery-rate metric loses this user's history. It is per-user telemetry, not financial. |
| `device` | **FCM tokens** | **ERASE** | Nothing. This is the one that must not be missed — see §9. |
| `refresh_token` | session material | **ERASE** | Nothing. |
| `wish` | message text sent to named people | **ERASE** | Nothing. |
| `poster` | which theme, which person, which sentiment | **ERASE** | The FR-POS-13 audit trail (`family != CELEBRATORY WHERE sentiment = SOLEMN` as a query) loses this user's rows. |
| `asset` | rows pointing at photographs | **ERASE**, *bytes first* | Nothing — see §9.1 for the ordering. |
| `content_report` | the user's own reports of generated content | **ANONYMISE** (`user_id` → null) or **ERASE** | Play's GenAI reporting obligation is about having a mechanism, not a retained archive. But a report is evidence about *content*, and erasing it on the reporter's request removes the record of a complaint. `user_id` is currently `NOT NULL`, so anonymising needs a migration. |
| `generation` | status, cache hit, latency, `cost_inr`, moderation verdict, `prompt_hash`. **No prompt text, no person name.** | **ANONYMISE** — `user_id` → null | Per-user cost attribution. Aggregate economics survive intact, which is what §10 rule 2 is for. Needs a migration (`user_id` nullable). |
| `ai_usage` | provider, model, tokens, `cost_inr`, `usd_inr_rate`, `pricing_version`. **No user content whatsoever.** | **ANONYMISE** — `user_id` → null | Same. **The conflict lives here:** DPDP erasure says the row must not identify the user; §10 rule 2 says the row must exist. Nulling the FK satisfies both — the financial history survives and nothing points at a person. Needs a migration (`user_id` nullable) **and** a change from `onDelete: Cascade` to `onDelete: SetNull`, or the cascade will beat the anonymisation. |
| `theme_cache` | shared product art, person-independent | **UNTOUCHED** | Nothing. It is our art, not the user's media. Carrying forward M6's C6: a sweep that cannot tell the two apart deletes the cache, every later poster becomes a live generation, and the bill rises with nothing on screen having changed. |

**The argument for anonymise-not-erase on the two ledger tables, stated once.** Neither
`ai_usage` nor `generation` holds a single character the user typed or a single name. They hold
what a call cost. With `user_id` nulled, no row identifies anybody, the DPDP erasure right is
satisfied in substance, and the question "what does a user cost per year" is still answerable
in aggregate. The thing genuinely given up is **per-user** cost attribution for deleted
accounts, and I think that is the right trade — but "we deleted everything" and "we kept the
costs" are both defensible and **only one of them can be what the policy page says**. The page
text in decision 1 depends on this answer.

**Two things that follow mechanically from whatever you pick, and are not optional:**

- If any table anonymises, `onDelete: Cascade` must become `SetNull` on that relation *and*
  the column must become nullable. A migration. It is additive — nullable columns and a
  relaxed FK action — and nothing destructive (checklist §C).
- The policy page must state the answer in the user's words, because Play reads it.

### Decision 4 — how is deletion tested, given the only live account is yours? — **ANSWERED**

> **Dipen's answer, 2026-11-27: both A and B.** A DB-backed spec that runs on every future PR,
> **plus** a throwaway Google account on the handset for the real FCM-token and push check.
>
> **B is done and green in CI** (`M9_VERIFY.md` §9). **A has not happened**: it needs a Google
> account only Dipen can create, and this session does not create accounts. Item 3's push half
> and item 4 wait on that address.
>
> The original analysis is kept.

### Decision 4 — how is deletion tested, given the only live account is yours?

The Railway database holds your own account: **566 invented people and 2 real contacts**
(`M3_VERIFY.md`). Testing account deletion end to end means deleting a real account with real
data in it, and the feature's whole point is that it cannot be undone.

| Option | What it proves | What it gives up |
|---|---|---|
| **A — a throwaway second Google account.** Sign in on the handset as a second account, add a handful of invented people, upload a photo, generate a poster, then delete it from the app and from the web page. | **The most.** The real handset, the real Railway Postgres, the real blob store, a real FCM token, and the push-after-deletion check in definition-of-done item 3. | Creating a Google account — which is in the "explicit permission required" category and is **yours to do, not mine**. Also costs a sign-out/sign-in cycle on the phone, and M4 §0.0 is a warning about what `connectedAndroidTest` does to that device. |
| **B — a local stack with seeded data.** Docker Postgres + Redis, seeded with invented people, exercised by a DB-backed spec and by curl. | Every row, every blob, every Redis key, repeatably, in CI. It is the only option that **runs on every future PR**. | The handset. No real FCM token, so item 3's "a push to that token no longer delivers" is unprovable here. No Railway. |
| **C — verify against Postgres in CI only, never on the live deployment.** B without the manual half. | What B proves. | Everything B gives up, plus any evidence the deployed service behaves like the tested one. |

**These are not exclusive and I would do B regardless** — a deletion whose completeness is
only ever checked by hand is a deletion that regresses on the next migration that adds a table.
The question is whether **A** happens on top of it. Item 3 and item 4 are marked **proved** or
**BLOCKED** by this answer, nothing else changes.

> **The one thing I will not do without you saying so in writing:** delete your live account.
> Not as a test, not "carefully", not with a backup taken first. If the answer is A, you create
> the throwaway and tell me its email; if the answer is B or C, item 3's live half is BLOCKED
> and says so.

---

## 4. What each unanswered decision blocks — the blocked set, on day one

| Definition-of-done item | Blocked by | Status if unanswered |
|---|---|---|
| 1 — export produced for the 575-person account, opened, checked | **Decision 2** (and 4, for whose account) | Producer buildable; *delivery* BLOCKED. If email: entirely BLOCKED. |
| 2 — its CSV re-imported through M3's importer, no mapping change | **Decision 2**, plus **C11** below, which may make the item partly unachievable by construction | Buildable to the point of a file on disk; BLOCKED past there |
| 3 — account deleted from the app, verified gone, push no longer delivers | **Decision 3** (what "gone" means) and **Decision 4** (whose account) | Code buildable; the *verification* BLOCKED |
| 4 — same deletion from the public web URL, no app session | **Decision 1** (where it lives) and **Decision 4** | BLOCKED |
| 5 — retention job run against seeded data, keep-override honoured, 90/180 boundaries exact, photo attached to a poster survives | **nothing** (but see **C3** — the "attached to a poster" half has no link in the schema to read) | **Buildable now. This is why it goes first.** |
| 6 — policy and terms reachable in-app and from a browser with no session | **Decision 1**, and **Decision 3** for what the page *says* | BLOCKED |
| 7 — prominent disclosure shown before the contacts permission, on the handset | **nothing** | **Buildable now** |
| 8 — §12's checklist updated, each tick traceable to `M9_VERIFY.md` | partially: boxes 1, 4, 5 depend on decisions 1–3 | Boxes 3, 6, 7, 8, 9, 10 tickable or already true |
| 9 — nothing needs an API key, and `M9_VERIFY.md` says so | **nothing** — unless decision 2 is email, which introduces one and makes item 9 **false** | See C1 |
| 10 — both targets compile, server suite green in three timezones, CI green on the head | **nothing** | **Buildable now** |

**Unblocked by all four: items 5, 7, 9, 10, and the §12 boxes M6 already made true.** That is
where I start.

---

## 5. Collisions between the prompt and `REQUIREMENTS.md` — raised, not resolved

### C1 — item 9 and decision 2 cannot both be satisfied if the answer is email

Definition-of-done item 9: *"Nothing in this milestone requires an API key."* FR-SET-03:
*"delivered by email link."* An email provider is an API key. If decision 2 is email, **item 9
becomes false** and the prompt's own framing of M9 as "the half that needs no key" goes with
it. Not a reason to pick the signed URL — a reason to say which one you are picking and let the
definition of done follow, rather than discovering the contradiction in `M9_VERIFY.md`.

### C2 — FR-SET-06's "overridable to keep" and its "warning" have nowhere to live, and a committed comment says otherwise

`poster-pipeline.service.ts:694` says, in a comment: *"M9's retention job is what reads it, so
M9 is a job rather than a job and a migration."* M6_PLAN §3.6 says the same.

**Corrected in place, after reading `schema.prisma:761-767` rather than only the service
comment.** My first draft of this section said the keep override had nowhere to live at all.
That was an overstatement and a wrong note is worse than none (checklist §F). The schema
comment on `expires_at` says: *"A null means 'keep' — the user-overridable case FR-SET-06
names."* So clause 2 is **half** expressible today. Walking FR-SET-06 as its three clauses:

1. purge after 90/180 days — `expires_at` carries this. ✅
2. **"both user-overridable to *keep*"** — expressible as `expires_at = NULL`, and **nothing
   has ever written a NULL**. It works one way only: once an asset is kept, the date it would
   otherwise have expired on is gone, so un-keeping has to re-derive it from `created_at` and
   the kind. It is also indistinguishable from "no policy was ever set". ⚠️
3. **"with a warning"** before generated media is purged — **nothing records having warned**,
   so a sweep would either warn every night until it deleted, or not warn at all. ❌

So M9 is still a job **and** a migration, for clause 3 if nothing else. My proposal:
`asset.retention` as an enum (`AUTO` | `KEEP`) rather than overloading a nullable date, so that
un-keeping is a one-field change and "kept" is distinguishable from "unset"; and
`asset.purge_warned_at` as a nullable timestamp, which is the only way clause 3 can be enforced
rather than attempted. Additive, nothing destructive. This is an instance of the checklist item
bought by M6 §4.7 — *a requirement clause carrying a number can be missed inside a rule that is
otherwise honoured* — found by walking FR-SET-06 as three clauses rather than as one sentence.

### C3 — "unless attached to a saved poster" is not expressible in this schema

FR-SET-06: source photos purged after 90 days **unless attached to a saved poster**.

There is **no link from a `poster` row to the `SOURCE_PHOTO` asset it was composited from.**
`poster.asset_id` points at the *output* PNG. The pipeline reads the photo at
`poster-pipeline.service.ts:651` by querying `asset` for the person's most recent
non-tombstoned `SOURCE_PHOTO` — and then records nothing about which one it used. Meanwhile
`photo.service.ts:186` tombstones the previous photo when a new one is uploaded, so "the
person's current photo" is not the one a six-month-old poster was made from.

Three ways out, none free:

| Option | Cost |
|---|---|
| **Add `poster.source_asset_id`** (nullable, `SetNull`) | A migration, and **it cannot be backfilled** — every poster M6 made has no record of its source. Those photos would be swept at 90 days with the rule unable to protect them. |
| **Approximate**: a `SOURCE_PHOTO` survives if the person has any non-deleted poster created after the photo's `created_at` | No migration, works for existing data, and is **wrong in both directions** — it keeps photos that no poster used, and it still cannot protect a photo that was replaced before a poster was made from it. |
| **Read it literally**: "attached" = it is the person's current `photo_key` and the person has any poster | Cheapest, most wrong. |

I would do the migration **and** the approximation as a backfill for pre-M9 posters, and say in
`M9_VERIFY.md` that pre-M9 posters are protected by a heuristic and post-M9 ones by a recorded
link. **Raising it because it changes what item 5's "a photo attached to a saved poster must
survive" is actually testing** — against the migration it tests a link; against the
approximation it tests a guess.

### C4 — FR-AUTH-08 says deletion "purges all media from R2 within 30 days"; everything else says irreversible and immediate

FR-AUTH-08: *"Deletion is irreversible, confirmed by typing, and **purges all media from R2
within 30 days**."* FR-SET-04 and the prompt both say irreversible with explicit confirmation,
and definition-of-done item 3 demands that **no blob objects** remain, verified at deletion
time.

"Within 30 days" is a *retention allowance*, not a purge. It is probably a transcription of
Play's own wording (Play requires deletion to complete within 30 days, which is a ceiling, not
a target). Two further wrinkles: **there is no R2** — `BLOB_STORE=local`, and `isDurable =
false`, so on Railway the bytes are lost on the next deploy whatever we do; and if the intent
were a genuine 30-day grace period, that contradicts "irreversible, confirmed by typing".

**I propose: delete the bytes synchronously, in the same request, before the rows.** Item 3 then
means what it says, and FR-AUTH-08's 30 days becomes the outer bound we are comfortably inside
rather than a schedule. Confirm, because it is a sentence in a requirement with a number in it.

### C5 — `ai_usage` already cascades, so §10 rule 2 is already violated by any deletion

Stated in §3 decision 3 and repeated here because it is a collision and not only a choice:
**`onDelete: Cascade` on `AiUsage.user` means the first `user.delete()` anybody writes destroys
the ledger silently.** §10 rule 2 and FR-POS-10 both say those rows are non-negotiable. The
schema and the requirement already disagree, today, before M9 writes a line.

### C6 — §9's API surface lists two endpoints that have never existed, and omits the one Play requires

`REQUIREMENTS.md` §9 lists `DELETE /v1/account` and `GET /v1/account/export`. Neither exists.
§9 does **not** list the public web deletion page at all, which is the thing FR-SET-04 and §12
make a Play requirement. M3 set the precedent for this: it *removed* two documented-and-unbuilt
import endpoints rather than leaving them as fiction. M9 should add the web path to §9 and
build the two that are listed. Flagging because editing §9 is a requirements change.

### C7 — "no PII in logs, ever" and the fact that a retention sweep has to be operable

§11 and the prompt: *"Nothing in a log, ever. Not an export path, not a deletion confirmation,
not a filename."* A sweep that deletes 400 photographs at 03:00 and logs nothing at all is
unoperable — the first question anybody asks is "what did it do last night".

**Resolution I am taking** (small enough to take, recorded so it can be reversed): the sweep
logs **counts and durations only** — `swept 41 photos, 3 posters, 0 kept, 212ms`. No key, no
id, no filename, no user id. Same for deletion: a count of rows and blobs, never which. If you
want even counts gone, say so.

### C8 — `verifyAccessToken` never asks the database, so an access token outlives the account

`jwt-auth.guard.ts` → `auth.service.ts:214`: the guard verifies the JWT signature and **nothing
else**. There is no DB lookup. `JWT_ACCESS_TTL` defaults to `15m`.

So for up to fifteen minutes after a hard delete, a phone still holding its access token
authenticates successfully on **every** guarded route. `POST /v1/sync` would then attempt
upserts against a `user_id` that no longer exists — a foreign-key violation surfacing as a 500,
not a clean 401 — and `GET /v1/sync` would hit `sync.service.ts:147`'s
`NotFoundException(USER_NOT_FOUND)`, which the client (`SyncEngine`) does **not** special-case:
nothing in `composeApp` matches on `USER_NOT_FOUND` or on a 404 from sync.

This is the sync half of the prompt's warning and it needs a decision of its own:

| Option | Cost |
|---|---|
| **A — a user-exists check in the guard**, on every authenticated request | One indexed primary-key lookup per request. Honest, simple, and it closes the window to zero. It is a query on the hot path that is not there today. |
| **B — a deleted-user denylist in Redis**, keyed by user id, TTL = `JWT_ACCESS_TTL` | No DB hit; one Redis GET, on a connection that already exists. Fails open if Redis is down — and the limiter next door deliberately fails *closed*. |
| **C — accept the window** and make every write path tolerate a vanished user | Cheapest, and it means the 500 above is the behaviour. Not acceptable: a FK violation is not an answer to "my account is gone". |

**A was taken**, once decision 3 made deletion real: a stale token could otherwise push rows
against a vanished `user_id` and foreign-key-violate as a 500, which is not an answer to "my
account is gone". `verifyAccessToken` now checks the account exists and is not soft-deleted and
answers `USER_GONE` — the code `refresh()` already used, so the client has one string to match.
**The latency is UNMEASURED and the comment on it makes no claim**, which is the checklist item
bought by M5 §5.2; the Redis denylist stays available if the lookup ever shows up in a profile.
The client half is C9, and is closed too.

### C9 — the client has no handling for "your account no longer exists"

`AuthRepository` has `signOut()` (clears tokens + wipes the local DB) and `onSessionExpired()`
(clears tokens only, **leaves the local database intact**). Neither is wired to a
`USER_NOT_FOUND`. So after a web-page deletion, the phone keeps 566 people on disk and shows
them, signed out, forever — which is a privacy failure on the device even though the server
side is clean. FR-AUTH-07 already establishes that losing a session means wiping local data.
The web deletion path makes that reachable without the user ever touching the app, which is new.

### C10 — the prominent disclosure exists; what is wrong with it is worse than its absence would be

The prompt says *"M3 shipped contacts import without [a prominent disclosure]."* **It did not.**
`ImportScreens.kt:186` is a full-screen `PermissionRationale`, shown before the system dialog,
naming the app, the data and the purpose, with an affirmative action and a decline. It is the
only route to `askForContactsPermission()`.

What is wrong with it is this line:

> "• It all happens on this phone."

That is true of the *reading*. It is **false of what happens after you tap Import**: FR-IMP-01
says only the records the user explicitly selects are "persisted **or synced**", and
`PeopleRepository.importPeople` writes them with `pending_sync = 1` and drains them to the
server. The selected contacts **leave the phone**. The next bullet reinforces the impression —
"the people you do not pick are never saved, never sent anywhere" — which is accurate and, by
contrast, implies the ones you *do* pick are also staying put.

FR-AUTH-06 requires the disclosure to state *"what is read, why, and **that it is not uploaded
in bulk**"*. The current copy says something adjacent and, on the point that matters, the
opposite of what the code does. A disclosure that misdescribes the data flow is a worse Play
finding than a missing one, and it is also simply not true. **This is M9's real work on that
box**, and it is the kind of thing found by a reviewer rather than by the author — which is
precisely what §0.7 says about itself.

### C11 — the export CSV cannot round-trip through M3's importer without losing event type, and the loss is the single most dangerous one in this product

Definition-of-done item 2: *"Its CSV re-imported through M3's importer without a mapping
change."*

M3's importer creates **exactly one event per row, and its type is hard-coded `BIRTHDAY`**
(`LocalStore.kt`, `importPeopleLocally`, `type = EventType.BIRTHDAY`). `ColumnMapping` has five
targets — name, date, relation, phone, notes — and **no type**. So:

- A person with a birthday *and* a wedding anniversary cannot be represented in one row.
- **A death anniversary, exported and re-imported, comes back as a birthday.**

FR-EVT-05 calls that outcome *"the worst failure this app can have — it must be impossible by
construction, not by a copy tweak."* An export feature that reintroduces it through the front
door is not an acceptable reading of item 2.

| Option | What it gives up |
|---|---|
| **A — the CSV is birthdays only**, with the type column present and non-birthday rows omitted; JSON is the lossless artifact | Item 2 passes honestly but the CSV is not "your data" — it is part of it. Must be said on the screen and in the file. |
| **B — the CSV carries every event with a `Type` column** the importer ignores | Item 2 "passes" and **silently converts death anniversaries into birthdays on re-import.** I will not build this. |
| **C — teach the importer a type column** | Out of this milestone's scope; a change to M3's mapping screen, the plan builder, the dedup rule and `LocalStore`; and it makes item 2's "without a mapping change" ambiguous. |

**I propose A** and will not build B. Raising it because item 2's wording assumes a round trip
that this importer cannot do losslessly, and because the lossy version is dangerous in the one
specific way this product has twice decided must be impossible.

### C12 — FR-PPL-08's 30-day restore window versus a hard account delete

`person` rows soft-deleted yesterday are inside a 30-day restore window (FR-PPL-08) and
`DeletedPeopleScreen.kt` exists to restore them. A hard account delete takes them too. That is
correct — the account is going — but it means "delete my account" destroys things the UI is
currently telling the user it is holding for them. The typed confirmation should name it.

---

## 6. The deletion inventory — every table and every store, written before the code

> The prompt: *"enumerate every table and every store that holds something belonging to a user,
> write the list in the plan before writing the deletion, and make the test assert on the
> absence of each one rather than on the endpoint returning 204."* This is that list. The test
> in §9.2 is generated from it rather than written alongside it, so a table added in M10 that
> nobody adds to the deletion fails the test instead of leaking.

### 6.1 Postgres — all 15 models

| # | Table | Reaches the user how | Today's `onDelete` | After deletion |
|---|---|---|---|---|
| 1 | `user` | is the user | — | gone |
| 2 | `person` | `user_id` | Cascade | gone |
| 3 | `person_trait` | `user_id`, `person_id` | Cascade ×2 | gone |
| 4 | `event` | `user_id`, `person_id` | Cascade ×2 | gone |
| 5 | `event_occurrence` | `event_id` only — **no `user_id`** | Cascade via event | gone **transitively**; a test must assert it directly, because it is the one table with no direct route to the user |
| 6 | `reminder` | `user_id`, `event_occurrence_id` | Cascade | gone |
| 7 | `device` | `user_id` — **holds the FCM token** | Cascade | gone |
| 8 | `refresh_token` | `user_id` | Cascade | gone |
| 9 | `wish` | `user_id`, `person_id` | Cascade | gone |
| 10 | `poster` | `user_id`, `person_id`, `asset_id` | Cascade | gone |
| 11 | `asset` | `user_id`, `person_id` | Cascade | gone — **bytes first**, §9.1 |
| 12 | `generation` | `user_id` | Cascade | **decision 3** |
| 13 | `ai_usage` | `user_id`, `generation_id` | **Cascade** ← the conflict | **decision 3** |
| 14 | `content_report` | `user_id` | Cascade | **decision 3** |
| 15 | `theme_cache` | nothing — no user column | — | **untouched, deliberately** (M6 C6) |

### 6.2 Outside Postgres

| # | Store | What of the user's is in it | Removed how |
|---|---|---|---|
| 16 | **Blob store, `photos/`** | photographs of real people (`SOURCE_PHOTO` assets) | explicit `delete(key)` per key, **before** the rows, §9.1 |
| 17 | **Blob store, `posters/`** | composited posters: a named person's face and name | same |
| — | Blob store, `themes/` | nothing of the user's — shared product art | untouched |
| 18 | **Redis — rate-limit keys** | `wishdraft:h\|d:{userId}:{bucket}`, `poster:…`, `postercopy:…`, `photo:…` — the user id, nothing else, TTL ≤ 24h | deleted explicitly. They expire anyway, but "it expires eventually" is not an answer to an erasure request |
| 19 | **Redis — BullMQ `posters-generate`** | queued job payloads carry `userId`, `personId`, `eventId` | any queued/active job for the user removed; §9.1 says why the order matters |
| 20 | **FCM** | nothing is registered remotely; the token lives only in `device` | covered by #7 |
| 21 | **Logs (pino)** | should hold no PII by §11; request ids and user ids may appear in error paths | **not erasable, and said so in `M9_VERIFY.md`.** Railway log retention is what it is. Naming it rather than claiming a clean sweep |
| 22 | **The phone's own SQLDelight database** | everything | C9 — the client has no path for this today |

### 6.3 Not in the inventory, and why

- `theme_cache` — shared, person-independent, our art.
- The Android `EncryptedSharedPreferences` token store — covered by C9's client half.
- Analytics (PostHog) — **not built yet**, in any milestone. Nothing to erase. Recorded so M9's
  policy page does not claim an analytics deletion path that has no code behind it.

### 6.4 The gap this inventory has: the port cannot list

`BlobStore` has `put/get/exists/delete/signedUrl` and **no `list`**. So there is no way to ask
"what bytes exist under this user that no row points at" — the exact failure the prompt names
first ("undiscoverable *and* undeleted"). This matters in one direction only: if a row is ever
deleted before its bytes, those bytes become unreachable **forever**, by anything. §9.1 is the
ordering rule that prevents it; adding `list` to the port is not in this milestone's scope and
I am not adding it speculatively.

---

## 7. §12 walked box by box — the section with no ids

| # | Box | State before M9 | M9 |
|---|---|---|---|
| 1 | Privacy policy and terms at a stable public URL | nothing | **decision 1** |
| 2 | Play Data Safety form | no Play account | **not M9.** The launch half. Do not tick |
| 3 | Prominent disclosure before contacts permission | **exists** (`ImportScreens.kt:186`) and misdescribes the data flow — **C10** | fix the copy, prove on the handset, tick |
| 4 | In-app **and** public web deletion path | nothing | in-app buildable; web is **decision 1** |
| 5 | Data export (DPDP access right) | nothing | producer buildable; delivery is **decision 2** |
| 6 | Retention limits enforced by a scheduled job | nothing. `expires_at` written, never read | **build it. First.** |
| 7 | Minor flag suppresses profiling/personalisation/affiliate | built in both prompt builders | **verify, do not rebuild**; tick with a pointer to the verification |
| 8 | No behavioural advertising anywhere | there is none — no ad SDK, no tracking | tick, with the grep that proves it |
| 9 | GenAI content reporting mechanism | **M6 built it** (`POST /v1/ai/report`) | tick, credited to M6 |
| 10 | Input/output moderation on all generated content | **M6 built it**, both surfaces, structurally; never exercised (no key) | tick **with M6's own caveat carried**, not rounded up |
| 11 | Affiliate relationship disclosed on the gift surface | **there is no gift surface** (M8) | leave unticked |
| 12 | Music licensing cleared before video | **no video** (M10) | leave unticked |
| 13 | Breach response note in `/docs` | nothing | **cheap, needs no decision — M9 writes `docs/BREACH_RESPONSE.md`.** Not in the prompt's scope list; it is in §12's, and §12 is the scope |

Box 13 is the one a plan quoting `FR-` ids would have missed entirely, which is the trap the
prompt opens by naming.

---

## 8. §10 and §11 walked as lists — M5's §0.6 lesson

**§10's seven hard rules, against M9:**

1. *No model called from the client; no key in the app.* M9 calls no model. If decision 2 is
   email, a key arrives server-side — still not in the app. Holds.
2. *Every call wrapped in a `generation` record with measured INR cost.* **Decision 3 is
   entirely about this rule.** C5.
3. *Structured outputs only.* M9 generates nothing. N/A.
4. *Prompts are versioned files.* N/A.
5. *Background artwork cached and reused indefinitely.* **The retention sweep must not touch
   `theme_cache`** — M6's C6, carried. A test asserts it.
6. *Rate limits, global daily spend cap, degradation.* M9 adds no spend. But the **export
   endpoint needs its own limiter**: an export walks every row a user has, and at 575 people it
   is the most expensive read in the app. Not §10's subject — §10 is about money — but the same
   shape, and nothing else in this app will refuse it.
7. *Input and output moderation on every generation.* M9 generates nothing. The **export
   carries previously-moderated text** (wish messages) out of the system; it does not create
   any.

**§11's rows that bind M9:**

- **Sync** — "soft deletes as tombstones, monotonic cursor". A hard delete is the thing this row
  does not describe. §9.3.
- **Security** — "no PII in logs or analytics". C7.
- **Secrets (public repo)** — if decision 2 is email, a new key, and `.env.example` gets a name
  with nothing after it.
- **Testing** — "integration tests on sync"; the deletion's sync consequences get one.
- **Observability** — a sweep with no counters is unoperable. C7's resolution.

---

## 9. The thing most likely to go wrong, and what is built for it

> *"A deletion that reports success and leaves something behind."*

### 9.1 The ordering rule, stated before the code

**Bytes before rows. Always. In one direction only.**

If a row is deleted first and the blob delete then fails, the bytes are orphaned with **nothing
left that knows their key** — and §6.4 says the port cannot list, so they are unreachable by
any code that will ever run. If the blob is deleted first and the row delete then fails, the
row points at a missing object, which is visible, queryable and fixable by re-running.

So the deletion is, in order:

1. Collect every blob key the user owns (`asset.blob_key WHERE user_id = ?`, tombstoned rows
   **included** — a tombstoned photo is still a photograph).
2. Remove queued/active BullMQ jobs for the user, so a job cannot write a *new* asset between
   steps 3 and 4.
3. `delete(key)` each blob. Failures collected, not swallowed; the request fails and is safe to
   retry, because step 4 has not run.
4. The Postgres transaction: anonymise what decision 3 says to anonymise, then delete the user
   and let the cascades run.
5. Delete the Redis rate-limit keys.

Step 2 before step 3 is the concurrent-pair check the checklist asks for: a poster job that
completes between "collect the keys" and "delete the user" writes an asset row and a blob that
the sweep has already walked past.

### 9.2 The test is generated from the inventory, not written beside it

The failure this guards is not "we forgot a table today". It is **M10 adding a table and nobody
adding it to the deletion.** So the DB-backed spec:

- seeds a user with **a row in every one of the 14 user-bearing tables** and a real blob for
  each of two assets,
- runs the deletion,
- then asserts **per table, by name, that the count is zero** (or that `user_id IS NULL` where
  decision 3 says anonymise) — including `event_occurrence`, which has no `user_id` and is the
  easiest one to lose,
- asserts `blobs.exists(key)` is **false** for every collected key,
- asserts `device` is empty and that a push attempt to the token finds no row to send to,
- asserts `theme_cache` is **unchanged**,
- and asserts the Redis keys are gone.

Plus a guard test that reads the Prisma DMMF for every model carrying a `userId` field and fails
if it is not named in the deletion's own exported table list. That is the checklist's "write the
grep or the test instead" — a lesson on a list does not prevent the repeat.

**Every one of those assertions gets the revert-check** (checklist §D, and M5's three vacuous
tests): comment out the device deletion, watch the device assertion go red. A green revert-check
is a signal to investigate, not a formality.

### 9.3 What a client with a stale cursor sees after its account is gone

Four entities have independent `(updated_at, id)` cursors — person, event, trait, wish — and all
four assume rows do not vanish. `sync-cursor.ts` is explicit that the cursor either repeats a
record forever or skips one if ordering breaks.

A hard delete is not a tombstone. The rows do not go to `deleted_at`; they cease. A client that
pulls after its account is gone gets `404 USER_NOT_FOUND` from `sync.service.ts:147` — the owner
lookup fails before any entity query runs, so there is **no partial state and no cursor
confusion**: the cursor never advances because the request never succeeds. That is the right
shape and it is already there.

What is *not* there:

- the client does nothing with that 404 (**C9**) — it must wipe local data and sign out, the way
  FR-AUTH-07 already does for sign-out;
- a **push** during the 15-minute token window hits a vanished `user_id` and FK-violates
  (**C8**);
- and `signInWithGoogle` after a hard delete creates a **brand-new user** with a new id, because
  the `google_sub` row is gone. That is correct for a hard delete — the same person signing up
  again, not the old account returning — and it is worth one assertion, because the same code
  path with a *soft* delete resurrects the account (`auth.service.ts:121` sets `deletedAt: null`).

### 9.4 The fake-store blind spot, named before it bites

M6_VERIFY §0.7's lesson, applied here: **`BLOB_STORE=local` is a fake, and it is also not
durable.** A deletion test against `LocalBlobStore` proves the deletion calls `delete()` and that
the file is gone from a temp directory. It cannot prove anything about an object store with
eventual consistency, versioning, or a delete that returns 204 for a key that was never there.

`M9_VERIFY.md` will say this in those words rather than claiming "blobs verified deleted". And
on Railway today the bytes are lost on every deploy regardless — which makes the deployed
deletion look complete for a reason that has nothing to do with the code.

---

## 10. Explicitly not in M9

- Play Data Safety, the 14-day closed test, the Play Console account. Calendar time, Dipen's.
- M7 (monetisation), M8 (gifts), M10 (video), M11 (tree).
- An R2 adapter. Still no account, still no credential; M6's reasoning stands.
- A `list` on the blob store (§6.4).
- Teaching M3's importer an event-type column (C11 option C).
- Anything needing `OPENAI_API_KEY` or `ANTHROPIC_API_KEY`.

---

## 11. Test data

Invented people only, in fixtures and in this document. The 575-person account is M3's seeded
set **plus two real contacts**, and neither of those two appears in any fixture, any test, any
screenshot or any line of this plan. The public-repo rule covers screenshots as well as
fixtures (M5 §0.5) — and an **export file** is a new third thing in that family: a JSON or CSV
of 566 people is the single most PII-dense artifact this project has ever produced, and it does
not go in the repo, in a scratch directory inside the repo, or in a document.

---

## 12. What I need from you

1. **Decision 1** — where the policy pages and the public deletion URL live, and whether that
   means buying a domain. Blocks items 4 and 6.
2. **Decision 2** — email (a new vendor and a new key, and item 9 becomes false — C1) or an
   in-app signed URL (nothing new, weaker reading of FR-SET-03). Blocks items 1 and 2.
3. **Decision 3** — the table-by-table answer in §3, and particularly `ai_usage`, `generation`
   and `content_report`. Blocks item 3's meaning and item 6's page text.
4. **Decision 4** — a throwaway Google account, a local seeded stack, or CI only. Blocks
   items 3 and 4's *proof*. **I will not delete your live account under any of them.**

And four smaller ones I would otherwise take myself, flagged because each has a cost:

5. **C2** — may M9 add the migration FR-SET-06 needs (`asset.retention`, `asset.purge_warned_at`)?
   Without it, two of FR-SET-06's three clauses cannot be built.
6. **C3** — the `poster.source_asset_id` migration plus a heuristic backfill for M6-era posters,
   or the heuristic alone?
7. **C8** — a user-exists lookup in the auth guard (one indexed query per request), or the Redis
   denylist, or accept the 15-minute window?
8. **C11** — birthdays-only CSV with the loss stated, which is what I will build unless told
   otherwise. I will not build the version that silently turns a death anniversary into a
   birthday.

**I am starting on the retention job now**, because nothing above blocks it and it is the only
requirement in this milestone with a clock already running.
