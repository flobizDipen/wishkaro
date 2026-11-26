# M6 Implementation Plan — AI Posters

> Branch `feat/m6-ai-posters`, cut from `main` at `52e842a`.
>
> **Nothing in this plan has been built.** No feature code is written and `git status` carries
> only this file. §9 lists what I need from Dipen; two of those are hard blockers and §2 says
> exactly which of the ten definition-of-done items each one blocks, so the blocked set is
> known today rather than on day six.

---

## 0. What I read first, and in what order

`docs/BUILD_PROMPT_M6.md` in full. Then `docs/REQUIREMENTS.md` **in full** — all 699 lines, not
§7.7 — because M5's single most expensive mistake was quoting requirements by `FR-` id and
thereby skipping every rule that has none. §10 "AI pipeline and cost control" governs this
milestone more directly than §7.7 does and its seven hard rules carry no ids. §8's data model,
§9's API surface, §11's non-functional table, §12's compliance checklist, §13's health metrics
and §18's TBD register all turned out to matter; §8 and §12 each produced a collision below
that §7.7 alone would not have shown.

Then `docs/MILESTONE_CHECKLIST.md` (§§B, C, D, E and F are mine; A, G and H are Dipen's),
`docs/M5_PLAN.md` and `docs/M5_VERIFY.md` in full, and `docs/M4_PLAN.md` §2.1 for navigation.

Then the code, because the prompt says to check rather than take its word:

`server/prisma/schema.prisma` (all 632 lines), `server/src/config/env.ts`,
`modules/wishes/**` (every file — the port, both providers, pricing, the ledger, the spend
cap, the rate limiter, the prompt builder, the controller, the module), `modules/events/derived.ts`,
`modules/sync/sync-cursor.ts`, `queue/queue.module.ts`, `server/package.json`,
`server/.env.example`, both `.github/workflows/*.yml`, `.gitignore`, `docs/fixtures/`,
and on the client `domain/Model.kt`, `domain/Tone.kt`, `ui/wish/WishStore.kt`,
`ui/nav/Screen.kt`, `platform/Contacts.kt`, `sqldelight/wishkaro/db/Person.sq`.

§1 is what that reading found. Several of its rows contradict what the build prompt assumes.

---

## 1. State of the world, as actually found

Every claim below was checked against this checkout, not against a milestone table.

| Claim | Verified |
|---|---|
| The cost ledger exists and is feature-tagged | `AiUsage` with `feature`, `provider`, `model`, `outcome`, four token counts, `costUsd`/`usdInrRate`/`costInr`/`pricingVersion`/`promptVersion`. `FEATURE_WISH_DRAFT = 'wish_draft'`. Fatal on write failure |
| The global spend cap exists and is cross-feature | `SpendCapService.spentToday()` sums `cost_inr` over **every** feature for the UTC day; `isCapped()` runs before the per-user limiter. `AI_DAILY_SPEND_CAP_INR` defaults to 200 |
| The per-user limiter exists | `DraftRateLimitService` — two fixed Redis windows, counted on attempts, fails closed. Keys are `wishdraft:h:` / `wishdraft:d:`, so a poster limiter needs its own prefix and its own ceilings, not a reuse |
| The provider port exists | `LlmProvider` + `FakeLlmProvider` (committed default) + `OpenAiProvider`. `env.ts` refuses to boot on a provider named without its key |
| Structured outputs exist | `strict: true`, validated our side, one retry on a malformed body only |
| Prompts are versioned | `PROMPT_VERSION = 'wish-2026-11-26'` in `prompt-builder.ts`, on every ledger row |
| Solemn derivation exists **once per side** | Server `sentimentFor(type, personIsDeceased)` in `events/derived.ts`; client `Person.isSolemn` ORing the person flag, the events' sentiment and the `DEATH_ANNIVERSARY` type. Both carry the M5 incident in their comments |
| `wish.poster_id` is reserved and unwritten | Column present, nullable, nothing writes it. M6 is what writes it |
| Redis and BullMQ are available | `QUEUE_MATERIALISE` and `QUEUE_SEND` run today; `REDIS_CONNECTION` is `@Global` |
| Docker is up | `wishkaro-db-1` and `wishkaro-redis-1` healthy. 13 GB free on `/` |
| The handset is attached | `adb devices` → `4B201JEBF03493` |
| **There is no photo anywhere in this app** | `person.photo_key` reserved since M1, written by nothing. `AndroidContacts` reads `photoUri` **for display only** and says so. No picker, no upload endpoint, no `asset` table, no `MediaStore`/`PhotoPicker` actual. See §3.1 |
| **There is no object storage** | No R2, no S3 client, no `AWS_*`/`R2_*` in `env.ts`, nothing in `.env.example`. §8 and §14 both name Cloudflare R2. See §3.2 |
| **There is no `sharp`** | Not in `server/package.json`. A new native dependency, and the Dockerfile builds on `ubuntu-latest` in CI |
| **There is no `theme_cache`, `asset` or `generation` table** | §8 names all three. The schema has eight M0–M2 tables plus `Wish` and `AiUsage`. See §3.3 |
| **There is no image key** | `OPENAI_API_KEY` is in `.env.example` with nothing after it. `WISH_PROVIDER=fake` is the committed default |
| **There is no moderation of any kind** | §10 rule 7 is unmet against shipped code. See §5 |

### 1.1 Carried forward, named rather than quietly dropped (checklist §A)

M6 touches none of these and cannot close any of them. Saying so is the point.

- **The OEM battery helper is unexercised on a non-Pixel device** — now **six** milestones.
- **The mobile-data leg of the Railway deploy is unproven** (the SIM will not register).
- **M4's tablet hardware, three empty states, two widget sizes and the instrumented suite in
  CI** are all still open.
- **M5's definition-of-done item 4 — a real remembrance draft, read by Dipen for TBD-4 — is
  not done and not deferred.** The build prompt is explicit that M6 does not inherit it and
  must not quietly close it. It stays M5's open item until a model writes a solemn draft and a
  person reads it. **M6 does not touch it.**
- **M5's item 1 is PARTIAL**: the hand-off was proved into Google Messages, not WhatsApp,
  because every route to WhatsApp's composer targets a real number or opens a picker of real
  contacts. M6's definition-of-done item 1 says "sent from a real handset" and inherits that
  constraint exactly — see §10.3, where I say now what I expect to be able to prove.

### 1.2 The public-repo rule gets a third half in M6, and it is the strictest yet

M3: fixtures contain invented people. M5: no generated *message about* a real person goes into
a document, a fixture or a test. M6: **no composited poster carrying a real person's face can
go into this repository at all** — not as a screenshot, not as a fixture, not as evidence in
`M6_VERIFY.md`. The build prompt already says "this now includes images"; I am writing down
the operational consequence, which is that definition-of-done items 1 and 2 produce evidence
that **cannot be committed**. `M6_VERIFY.md` will describe what was on the screen in words, as
M5 §0.5 had to for a screenshot it had to delete.

Fixture photos used in compositor tests are therefore **generated solid-colour and gradient
PNGs written by the test itself**, not a photograph of anybody. That also makes the
"unmodified photo" assertion exact: a known pixel pattern can be compared byte for byte in the
composited region, which a photograph cannot.

---

## 2. The two blockers, and exactly what each one blocks

The build prompt asks for this on day one rather than day six. Both items are Dipen's.

### 2.1 TBD-5 — visual identity and poster art direction (due at M4, still open)

Blocks **what the themes look like**, never **whether the pipeline works**. The distinction is
not a hedge: compositing geometry, cache behaviour, sentiment filtering and cost accounting are
all checkable by a machine, and none of them is improved by knowing the palette.

What it blocks is anything whose pass condition is a person's judgement, and a poster's output
is a picture — no fake tells you it is beautiful and no test tells you it is tasteful on a
remembrance.

| DoD item | Effect of TBD-5 |
|---|---|
| 1 — celebratory poster, real photo, real handset | **Pipeline PROVABLE, product BLOCKED.** A poster is produced and sent. Whether it is one anybody would want to send is TBD-5. The artwork will be *visibly* placeholder, so nobody mistakes it for art direction |
| 2 — solemn poster, solemn family, no celebratory theme reachable, **hand-checked by Dipen** | **Splits.** The *filtering* half is PROVABLE and gets the test the prompt asks for. The *hand-check* half is **BLOCKED** — "hand-approved" names a hand and it is not mine |
| 3 — three formats inspected at full size | **Not blocked.** Formats are geometry: crop, bleed, text overflow, safe areas. Provable against placeholders |
| 4 — no-photo path "good enough to send" | **Mechanism PROVABLE, judgement BLOCKED.** "Good enough" is the sentence TBD-5 answers |
| 5, 6, 7, 8, 10 | **Not blocked by TBD-5** |
| 9 — moderation + report | **Not blocked by TBD-5** |

### 2.2 There is no `OPENAI_API_KEY` for `gpt-image-1`

Images are billed **per image**, not per token. A "try another" pressed four times is four
whole images, and a retry loop with a bug in it is a bill rather than a log line.

| DoD item | Effect of the missing key |
|---|---|
| 5 — cache **hit** measured: no model call, no ledger row, no rate-limit slot | **Not blocked.** A hit makes no call by definition. This is the item the whole milestone is built around and it is fully provable today |
| 6 — cache **miss** measured end to end, p95 < 15s | **BLOCKED.** A miss *is* a live generation. Measuring the fake's latency would be measuring the fake |
| 7 — ledger row per generation with a **non-zero measured INR**, and image pricing in `pricing.ts` | **Splits.** Image pricing in `pricing.ts` and one row per generation are PROVABLE. "Non-zero **measured**" is **BLOCKED** — a fake image costs ₹0, and arithmetic from a rate table is what M5 was right to refuse to call a measurement |
| 8 — global spend cap degrading a poster request to cached themes | **Not blocked.** Seed the ledger past the cap and watch the request degrade. No key needed |
| 9 — moderation on both surfaces + "Report this content" reaching the server | **Splits.** The report action, the endpoint, the block path and the ledger rows are PROVABLE. A **live** moderation verdict is **BLOCKED**, because the moderation endpoint I would use is OpenAI's and needs the same key |
| 1, 2, 3, 4 | **Not blocked by the key** — they run off the pre-approved library, which is the whole FR-POS-04 argument |
| 10 — compiles, suite green in three timezones, CI green | **Not blocked** |

**Summary.** Of ten items: **6 and 7's measured half and 9's live half are blocked on the key.
2's hand-check, 4's judgement and 1's product judgement are blocked on TBD-5. 3, 5, 8 and 10
are blocked by neither** and are where the value of this milestone actually is.

That is not a consolation. Item 5 — a cache hit that costs nothing, consumes nothing and
writes nothing — is the measurement TBD-3 and `FR-MON-03a` are both waiting on, and it is the
one number only M6 can produce.

---

## 3. Collisions between this prompt and `REQUIREMENTS.md` — raised, not resolved

Per checklist §B and the prompt's own instruction. M5 raised three and all three were real.
There are **seven** here and the first two are large.

### 3.1 C1 — There is no photo in this app, and the prompt's Scope does not mention building one

FR-POS-01 composites "the person's **unmodified** photo". Definition-of-done item 1 is "a
celebratory poster, composited with a **real photo**, sent from a real handset."

There is no photo. `person.photo_key` has been reserved and unwritten since M1.
`AndroidContacts.kt` reads a contact's `photoUri` and its comment says, in as many words, that
it is display-only and "nothing is uploaded". There is no photo picker `expect/actual` (§14
lists one in the boundary; it was never built), no upload endpoint in §9, no `asset` table, and
no object storage to put bytes in.

So M6 must build a photo pipeline — picker → upload → storage → signed read → composite — and
**the build prompt's "Scope" section does not list it.** It lists the theme library, the
compositor, the solemn set, moderation, async generation, the ledger and attribution. A photo
is assumed, the way M5's prompt assumed a key.

I am raising this rather than resolving it because it is not a detail: it is a vertical slice
of its own size, it touches both targets, it adds a permission flow on Android 13+, it creates
the app's first user-uploaded binary, and it lands FR-SET-06's retention obligation (§3.6).

**What I will do unless told otherwise:** build the **minimum** that makes item 1 reachable —
a photo picker on the person screen, an upload endpoint, `person.photo_key` written, the
photo served back to the compositor server-side. Not: cropping, face detection, multiple
photos, re-upload on sync, or a gallery. And I will say in `M6_VERIFY.md` that this was
unscoped work absorbed to make a definition-of-done item reachable, so the decision is visible.

### 3.2 C2 — Cloudflare R2 is named in two places and configured nowhere

§8 gives `asset.r2_key` and `theme_cache.r2_key`. §14 lists Cloudflare R2 in the stack. §11
says "R2 objects served via short-lived signed URLs". There is no R2 account, no bucket, no
credentials, no S3-compatible client in `server/package.json`, and nothing in `env.ts` or
`.env.example`.

The build prompt does not mention storage at all, which means a poster has nowhere to live.

**What I will do unless told otherwise:** the same shape the `LlmProvider` port already proved
— a narrow `BlobStore` port with two adapters. `LocalBlobStore` writing under a gitignored
`server/.storage/` is the **committed default** so CI and a fresh clone work with no account;
`R2BlobStore` is an S3-compatible adapter that **refuses to boot without its credentials**,
exactly as `WISH_PROVIDER=openai` already refuses without a key.

**What that gives up, stated:** Railway's filesystem is ephemeral, so until R2 exists every
poster and every cached theme is **lost on redeploy**. That turns FR-POS-03's "generated once,
served thousands of times" into "generated once per deploy", which is a real hole in the
economic argument — and it is a hole in the *deployment*, not in the code. `M6_VERIFY.md` will
say the cache-hit measurement was taken against a store that survives a process restart and
not against one that survives a deploy, because those are different claims.

**Third item for §9:** R2 credentials, or a decision to accept local-only for M6.

### 3.3 C3 — §8 names a `generation` table; M5 built `AiUsage` instead

§8's `generation` is `id, user_id, feature, provider, model, prompt_hash, status, cost_inr,
latency_ms, cache_hit, created_at`. M5 built `AiUsage`, which has the cost half and **none** of
`status`, `latency_ms`, `cache_hit` or `prompt_hash` — the four columns FR-POS-09's progress
state and FR-POS-03's cache-hit rate both need.

So: is `AiUsage` the `generation` table, to be extended? Or is `generation` a separate record
with `AiUsage` rows beneath it?

**Raised, with my reading and the reason.** They are different cardinalities and collapsing
them loses the measurement this milestone exists to produce:

- `AiUsage` is one row **per model call**. That is its contract today ("one row per call,
  every outcome"), and the spend cap sums it.
- A poster generation is one user-visible **job** that makes **zero** model calls on a cache
  hit, **one** on a copy-only path, and **two** on a full miss (copy+theme, then image).

One table cannot be both without one of the two meanings becoming a lie. And the lie would be
the expensive one: definition-of-done item 5 requires that a cache hit "writes no ledger row
for a generation that did not happen". If `AiUsage` were also the generation record, a hit
would either write a zero-cost row — making the ledger's row count stop meaning "calls made" —
or write nothing, making the cache-hit rate unmeasurable, which is the number §13, TBD-3 and
`FR-MON-03a` are all waiting on.

**So:** a new `Generation` table (`status`, `cacheHit`, `latencyMs`, `promptHash`, `feature`,
`costInr` denormalised as the sum of its calls) with **zero or more** `AiUsage` rows under it,
and `AiUsage` gains a nullable `generationId`. A cache hit writes a `Generation` row with
`cacheHit = true` and **no** `AiUsage` row. That satisfies item 5 literally and makes the hit
rate a one-line query.

**What it gives up:** two tables where §8 drew one, and a reader of §8 will not find the
columns where the document says they are. §8 needs an amendment note, which is Dipen's to
accept — the same treatment D20 and FR-IMP-04 got.

### 3.4 C4 — FR-POS-01 still says "Claude picks/writes the copy"

D20 was amended on 2026-11-26 to provider-neutral behind `LlmProvider`, chosen by
`WISH_PROVIDER`. §10's providers paragraph carries that amendment. **FR-POS-01 does not** — it
still names Claude for step (a), exactly as D20 did before M5 found the conflict.

This is the same stale-vendor shape M5 raised, one section along. Step (a) is a structured text
call and the port for it already exists; pointing it at a named vendor would mean two ways to
choose a text provider in one codebase.

**What I will do unless told otherwise:** step (a) goes through the existing `LlmProvider`,
whatever `WISH_PROVIDER` names. FR-POS-01's wording needs the same correction D20 got, which is
Dipen's to make. No decision is needed for me to proceed — the amendment already covers it —
but the document should not be left saying something the code deliberately does not do.

### 3.5 C5 — FR-POS-04 says the library "ships with the app"; §9 serves it from the server

FR-POS-04: "A library of pre-generated, hand-approved themes **ships with the app**". §9:
`GET /v1/themes — pre-approved template library`. FR-POS-01(c): the server composites.

Read literally, the artwork is in the APK — but the server does the compositing, so it would
need the same artwork, and an APK carrying every theme's background at 1080×1920 in three
aspect ratios is tens of megabytes that cannot be updated without a release.

**What I will do unless told otherwise:** split it, because the two halves want opposite homes.

- The **manifest** — theme ids, display names, which sentiment family, palette, which formats
  — is a small JSON file **bundled in the app**, so the picker renders and filters offline,
  which is what FR-POS-04's sentence is actually protecting. It is also mirrored server-side,
  and like every other rule implemented twice it is held to **one shared table of cases** in
  `docs/fixtures/poster-themes.json` (checklist §C; the pattern is `wish-tones.json`).
- The **artwork** is served from `GET /v1/themes` and the blob store, and cached on the phone.

**What that gives up:** a brand-new install with no network sees the picker but no previews.
Acceptable — a poster cannot be composited offline anyway, because the compositor is the
server. Flagged rather than silently chosen.

### 3.6 C6 — §10 rule 5 says cached artwork is reused "indefinitely"; FR-SET-06 purges generated media after 180 days

§10 rule 5: "Background artwork is cached by theme key and **reused indefinitely**."
FR-SET-06: "generated media after 180 days with a warning".

These collide only if the two kinds of image are one kind. They are not: a `theme_cache` entry
is **product art**, owned by us, shared across every user; a composited poster is **a user's
media**, about a named person, and is what FR-SET-06 and the DPDP obligations in §12 are
about.

**What I will do unless told otherwise:** keep them in separate tables with separate lifetimes
— `theme_cache` has no `expires_at` and is never swept; `asset` carries `expires_at` and M9's
retention job reads it. M6 **writes** `expires_at` and does **not** build the job, so M9 has
something to sweep rather than a migration to write.

Worth saying out loud because the failure mode is quiet and expensive: a retention job that
cannot tell the two apart deletes the cache, every subsequent poster is a live generation, and
the bill goes up with nothing on screen having changed.

### 3.7 C7 — the ₹200/day cap now means something very different

`AI_DAILY_SPEND_CAP_INR = 200` was sized against text: §10's own table calls a wish draft
"fractions of a paisa", and `.env.example` says ₹200 is "≈ 13,000 drafts on the cheap tier".

Against images at §10's own "₹1–15 depending on quality tier", ₹200/day is **13 to 200 images
across all users, all day**. One enthusiastic user pressing "try another" can reach that in an
afternoon, and when they do, **every** user degrades to cached themes until UTC midnight —
because the cap is global by design (§3.3 of `spend-cap.service.ts` is explicit that a
per-feature cap would be the wrong thing).

That is not a bug. It is the cap working, and the degradation target now exists for the first
time, which is the half of §10 rule 6 M5 could not build. But the number was chosen for a
different cost shape and it is Dipen's to reconfirm. **Item 4 in §9.**

---

## 4. Decision 4 — is a poster a fifth synced entity? (mine, with reasons)

**No. A poster is not a cursor entity. It is a server-owned record, referenced by the wish that
already syncs, with a file cache on the phone.**

### 4.1 Why

The sync engine's three rules — last-write-wins per record on `updatedAt`, tombstones, a
monotonic per-entity cursor — exist to reconcile **concurrent edits**. All four current
entities are things the user creates and edits on a phone, possibly offline, possibly on two
devices.

A poster is none of that. It is generated **server-side**, it is **immutable** once composited,
and the user never edits it. Last-write-wins on a binary nobody writes is ceremony: there is no
second writer, so there is no conflict, so there is nothing for LWW to resolve.

Three facts decided it:

1. **The reference already syncs.** `wish.poster_id` has been reserved since M5 for exactly
   this. A wish is the fourth synced entity; a poster attached to one arrives with it for free.
   FR-HIS-01's "and the poster used" is satisfied by the column that is already there.
2. **A cursor would not make it usable offline anyway.** What the client actually needs offline
   is *the poster it already made, to send again* — which is **bytes**. A synced metadata row
   whose file is not on the phone is a row you cannot send. The thing that makes it work is a
   file cache keyed by poster id; the cursor adds a row next to a file that is already there.
3. **The cost of being wrong is one additive change, and `sync-cursor.ts` already proves it.**
   M1 added `trait`, M5 added `wish`, and both times an older client's cursor decoded the
   missing key as `null` and took a full resync, which is the correct delta for an entity it
   has never seen. If a later milestone wants a cross-device poster gallery, the fifth entity
   is the same mechanical shape — not a redesign.

### 4.2 What it gives up, stated

- **A poster made on one phone does not appear on a second phone** until it is attached to a
  wish that syncs, or the second phone calls `GET /v1/posters?personId=`. A user with two
  devices sees a gap that the other four entities do not have.
- **Deleting a poster is a server call, not a local write.** It cannot be queued offline the
  way deleting a person can. Posters are the one thing in this app you cannot tidy up in
  aeroplane mode.
- **One entity behaves differently from the other four**, which is exactly the objection M5's
  plan raised against local-only wish history. The difference is that M5's argument turned on
  "the server builds the prompt, so the server needs the history" — a *data* dependency. Here
  the dependency runs the other way: the server owns the artifact and the client holds a copy
  of a file. The schema comment will say so, because the next person to touch sync will ask.

So the server gets a `Poster` table with `userId`, `personId`, `themeId`, `format`,
`generationId`, `blobKey`, `expiresAt` and a `GET /v1/posters?personId=` listing — the record
exists and is queryable; it is simply not a cursor entity.

---

## 5. Decision — moderation covers BOTH surfaces, and I am asking rather than assuming

The build prompt is precise about this and I am not going to blur it: §10 rule 7's deferral to
M6 was **offered**, twice, and never **decided**. M5 asked in `M5_PLAN.md` §6.1 and again in
§12.1, and `M5_VERIFY.md` §4.6 records it as a knowingly unmet hard rule against shipped code.

So M6 does not open believing moderation was handed to it.

**What I will build unless told otherwise: both surfaces.** Posters *and* the wish drafting
path that already shipped. The reason is the one the prompt gives — build it for posters only
and rule 7 stays unmet for the feature that is already in users' hands, and the debt moves
silently to M7, which is exactly what `MILESTONE_CHECKLIST.md` §F exists to stop.

Concretely, across both:

- **Input moderation** on the user's own text before it reaches a model — the `avoid` list and
  any free text in a poster request.
- **Output moderation** on what comes back — the drafted message, the poster copy, and the
  generated background.
- **"Report this content"** on every generated asset (FR-POS-12, FR-SET-07), `POST /v1/ai/report`
  as §9 already lists it, reachable from the composer *and* the poster preview.
- A `Moderation` verdict stored per generation, and a blocked generation writing its ledger row
  like any other outcome — a call that was moderated still cost money.

**And the honest limit.** The moderation path I would use for real is OpenAI's, which needs the
same key that is missing. So the **mechanism** — the port, the fake, the block path, the stored
verdict, the report endpoint, the UI — is buildable and testable today, and a **live verdict is
not**. Rule 7 will be *structurally* met and not *actually* exercised, and `M6_VERIFY.md` will
say that in those words rather than ticking a box. That is the distinction M5 §4.6 was right
about and I will not round it up.

**Input moderation has one half that works with no key at all** and I will build it: a
deterministic check on the user's own typed text, held to a shared table of cases like every
other twice-implemented rule. It is not a classifier and will not be described as one.

---

## 6. §10, hard rule by hard rule — the walk M5 skipped

This is the table `MILESTONE_CHECKLIST.md` §A now requires, and it goes **before** the FR- ids
rather than after them, because §10's rules have no ids and that is precisely how M5 missed
all seven.

| # | Hard rule | What M6 does |
|---|---|---|
| 1 | No model is ever called from the client. No API key ever ships in the app | Met by construction. `POST /v1/ai/poster` is a server endpoint; `ImageProvider` and `LlmProvider` both live server-side; the APK gets a theme manifest and signed URLs, never a key. The blob store's credentials are server-side for the same reason |
| 2 | Every call is wrapped in a `generation` record with measured INR cost | `Generation` per job, `AiUsage` per call (§3.3). Fatal on write failure, inherited from M5 unchanged. Image pricing goes into `pricing.ts` **per image**, not per token, or every poster row reads ₹0 and the cap cannot see them — which is worse than no cap because it looks like one |
| 3 | Structured outputs only; a malformed response is retried once, then falls back to a template | Step (a), copy-and-theme, is a structured call: `strict: true`, validated our side, **one** retry on a malformed body only — not on a refusal, not on a timeout, not on a truncation — then the library's default copy for that theme. **The image call has no JSON to be strict about**, so its equivalent is validating the returned bytes (format, dimensions, byte count) and one retry on a malformed body, falling back to the cached or library artwork. Stated because the rule does not obviously apply to a binary and skipping it quietly would be the easy mistake |
| 4 | Prompts are versioned files in the repo | `POSTER_COPY_PROMPT_VERSION` and `THEME_ART_PROMPT_VERSION`, separate from M5's `PROMPT_VERSION`, each on the ledger rows it produced. A hand-review that cannot be traced to a revision stops meaning anything the first time the prompt is edited — and for TBD-5 that review is the entire deliverable |
| 5 | Background artwork is cached by theme key and reused indefinitely | `theme_cache` with a **UNIQUE** `theme_key` (§8's indexing note: "must be unique or caching silently fails"), key = `(theme, event type, milestone, palette, aspect ratio)`. Never swept — see C6 |
| 6 | Per-user rate limits, a global daily spend cap, automatic degradation to the pre-approved theme library | The cap exists and is cross-feature; M6 adds image pricing so posters are visible to it, a **separate** poster limiter with its own Redis prefix and ceilings, and — for the first time — a degradation target that is literally the pre-approved library. M5 degraded to the phone's template because no library existed |
| 7 | Input and output moderation on every generation, with user reporting | §5. Both surfaces. Mechanism buildable, live verdict blocked on the key, and both facts recorded separately |

### 6.1 The other prose sections, walked the same way

Because the lesson was "walk every prose section", not "walk §10":

- **§11 Tone safety** — "automated tests assert that no `SOLEMN` event can ever produce
  celebratory copy, **a celebratory template**, a gift suggestion or a T-7 reminder". The
  middle clause is M6's and it is a *correctness* requirement, not a content preference. §8
  below is how it is met.
- **§11 Security** — "R2 objects served via short-lived signed URLs". So a poster URL is signed
  and expiring, not a public path. The local adapter has to express the same contract or the
  two stores differ in the one way that matters.
- **§11 Offline** — "only AI and gifting require network". A poster requires network; the
  picker, the history and a previously made poster must not.
- **§12** — the two unchecked GenAI boxes are §5's. "Minor flag suppresses profiling" applies
  to poster copy exactly as FR-PPL-09 made it apply to wish drafts: relation, occasion,
  milestone and age band, nothing else.
- **§13** — "theme cache-hit rate" is a named health metric. C3 is what makes it a query.

---

## 7. Implementation order

The floor before the features, and the prompt's own argument: FR-POS-04 says the common path
makes no live call, so the library, the cache and the compositor **are** the product. Build them
first and the milestone's main path needs no key, the expensive path has something to degrade
*to*, and FR-POS-03's economics become measurable instead of asserted.

| # | Step | Done when | Needs the key |
|---|---|---|---|
| 1 | **The blob store** — `BlobStore` port, `LocalBlobStore` as the committed default, `R2BlobStore` refusing to boot without credentials, signed-URL contract on both, `asset` table with `expiresAt` | A round trip through both adapters in tests; a signed URL expires; `.storage/` is gitignored | no |
| 2 | **`theme_cache` + the theme library** — unique `theme_key`, the five-part key, placeholder artwork for both families, `docs/fixtures/poster-themes.json` as the one shared table, `GET /v1/themes` | The unique constraint rejects a duplicate key in a DB-backed test; both suites read the one fixture | no |
| 3 | **The compositor** — `sharp`, three formats (1080×1920 default, 1080×1080, 1200×630), unmodified photo, the no-photo typographic path, the attribution switch | The photo's pixels are **byte-identical** in the composited region; three formats come out at their exact dimensions; attribution present by default and absent when the (M7-owned) premium function is stubbed true | no |
| 4 | **The sentiment gate and its stale-window test** — before any picker exists | §8. The test constructs the stale window on purpose and goes red when the gate is removed | no |
| 5 | **`Generation`, the poster job and the cache** — `POST /v1/ai/poster` → `{generationId}`, BullMQ, `GET /v1/ai/generation/:id`, status and progress, poster rate limiter, spend-cap degradation | A cache **hit** makes no model call, writes **no** `AiUsage` row and consumes **no** rate-limit slot, proved by curl and by a DB-backed suite (DoD 5); a seeded over-cap ledger degrades a request (DoD 8) | no |
| 6 | **`ImageProvider`** — the port, the fake as committed default, the `gpt-image-1` adapter refusing to boot without its key, image pricing **per image** in `pricing.ts` | Type- and unit-verified; a ledger row per image at a real rate. **Live calls blocked** | step 6's live half |
| 7 | **Moderation and reporting, both surfaces** — port, fake, deterministic input check, stored verdict, `POST /v1/ai/report`, report UI on the composer and the poster | A blocked generation writes its ledger row and returns the degraded path; a report reaches the server from the app (DoD 9's provable half) | live verdict only |
| 8 | **The photo path** (§3.1, unscoped) — picker, upload endpoint, `person.photo_key` written, served to the compositor | A photo taken on the handset reaches a composited poster | no |
| 9 | **The client** — poster screen, picker **filtered** by sentiment, format selector, progress state, send, `wish.poster_id` written | `NavKeyRegistrationTest` green; a solemn person's picker offers no celebratory theme on the device | no |
| 10 | **Device verification, `docs/M6_VERIFY.md`, CI green, PR** | Pass / BLOCKED per item, with §1.2's constraint on what evidence can be committed | — |

Steps 1–5, 7 (mechanism), 8 and 9 are buildable today. Step 6's live half and step 7's live
verdict are the gates.

---

## 8. The thing most likely to go wrong, and the test that is built for it

**A celebratory theme on a death anniversary.** The build prompt is right that this is not
speculation — M5 shipped two bugs of exactly this shape in one milestone, `454ea75` and
`cb5919d`, both "derived state read one step too early", both invisible to a full passing suite
because every fixture in this repository builds a person the way the server returns one.

M6 reads that same flag to pick a **theme family**, and its failure mode is a picture of
balloons on a remembrance — worse than a word, because a picture is what gets sent and seen,
and it is what the recipient's family screenshots.

### 8.1 The rule, stated as code will enforce it

1. **Read the sentiment the server derived, after the derivation has run.** The poster job
   loads the person *inside* the job, after any push batch that created the event has been
   through `recomputeMany` — which is the precise ordering `cb5919d` fixed for wishes. A
   poster request that arrives in the same window as a new death anniversary therefore reads
   the recomputed value, not the pre-recompute one.
2. **One implementation, which already exists.** `sentimentFor()` on the server,
   `Person.isSolemn` on the client. **No third.** `is_deceased` is never read directly by
   anything M6 writes.
3. **The filter is a set choice, not a conditional.** The solemn family and the celebratory
   family are two separate collections, and the picker is handed one of them — the same
   arrangement `Tone.kt` uses and for the reason its comment gives: a conditional is a thing
   someone can forget; a set you have to pick is a thing the compiler makes you pick.
4. **Filtered OUT of the picker, not ranked lower** (FR-POS-13), and refused server-side on the
   way in, so a tampered request carrying a celebratory theme id for a solemn occasion gets a
   400 rather than a poster.

### 8.2 The test that constructs the stale window on purpose

The prompt asks for at least one, "because no fixture in this repo builds that shape on its
own, which is precisely why it survived twice."

So, against **real Postgres** (checklist §D — a fake store agrees with whatever the code does
to it, and everything this is looking for is a disagreement):

> A person who is **not** marked deceased. One sync push batch carrying **both** a new
> `DEATH_ANNIVERSARY` for them **and** a poster request for that same person, in the order
> that puts the poster request before the recompute.
>
> Assert: the generated poster is from the **solemn** family. Assert the `Generation` row's
> stored sentiment is `SOLEMN`. Assert no celebratory theme id is reachable for that person
> through `GET /v1/themes`.

And a client-side mirror in `androidUnitTest` against real in-memory SQLite (M3 §6.3's lesson),
constructing the same window locally: a death anniversary written by `addEventLocally` with
`person.is_deceased` still false, and the poster picker asked what it offers.

**The revert-check is not a formality** (M5 had three green ones and each was a real gap). Both
tests are confirmed by removing the gate and watching them go red, and if either stays green I
will find out why before moving on and record what it was.

---

## 9. What I need from Dipen

The first two are the ones the build prompt names. The rest came out of §3.

1. **TBD-5 — visual identity and poster art direction.** Blocks DoD 2's hand-check, 4's
   judgement and 1's product judgement (§2.1). I will build against **visibly placeholder**
   themes so nothing is mistaken for art direction, and the mechanism ships behind your
   reading of it — the same arrangement M5 used for FR-WSH-09.
2. **The `OPENAI_API_KEY` for `gpt-image-1`** — in `~/.secrets/` and in Railway's env, never in
   git. Blocks DoD 6, 7's measured half and 9's live half (§2.2).
3. **Moderation scope.** Both surfaces in M6 — which is what I will build unless told otherwise
   (§5) — or posters now and wishes in M7, with §10 rule 7 recorded as unmet for a **second**
   milestone. This has been asked twice and answered zero times.
4. **Poster rate-limit ceilings, and whether ₹200/day stands** now that images are billed per
   image (§3.7). My proposal: **5/hour and 20/day per user** for live generation, with cache
   hits consuming **no slot at all** (DoD 5 requires that), and the global cap reconfirmed
   rather than inherited.
5. **C1 — the photo path (§3.1).** It is not in the prompt's Scope and DoD item 1 cannot be met
   without it. Absorb the minimum into M6, or drop DoD 1 and 2 to the no-photo path and move
   photos to their own milestone?
6. **C2 — storage (§3.2).** R2 credentials, or accept a local store for M6 and the "posters do
   not survive a redeploy" consequence?
7. **C3 — `Generation` as a second table (§3.3)**, and the §8 amendment note that goes with it.
8. **C4 — FR-POS-01 still names Claude (§3.4).** Same correction D20 got. No decision needed
   for me to proceed; the document should not be left wrong.
9. **C5 — the theme library's split home (§3.5)** — manifest in the app, artwork from the
   server.

### 9.1 Already decided unless you say otherwise

- A poster is **not** a fifth synced entity (§4).
- `theme_cache` is never swept; `asset` carries `expiresAt` for M9 (§3.6).
- The blob store is a port with a local default and an R2 adapter that refuses to boot (§3.2).
- Step (a) goes through the existing `LlmProvider`, not a named vendor (§3.4).
- The theme manifest is one shared table of cases in `docs/fixtures/` (§3.5).
- The solemn and celebratory families are two collections, never one with a branch (§8.1).
- A cache hit makes no call, writes no `AiUsage` row and consumes no rate-limit slot (§3.3).

---

## 10. Explicitly not in M6

Per the build prompt, plus two I am adding because they would otherwise drift in.

- **Credits, paywall, Play Billing** (M7). Where M6 needs "is this user premium" it calls one
  function that returns `false`, and that function is where M7 plugs in.
- **The AI-blend path, FR-POS-07.** It costs credits M7 owns, and it is the one path that puts
  a face through a generative model. It must not be built in the milestone that establishes the
  rule it is the exception to.
- **Gift suggestions (M8), video (M10), family tree (M11).**
- **The retention *job*** (M9). M6 writes `expires_at`; it does not sweep (§3.6).
- **M5's open item 4** — a real remembrance draft read by Dipen. Not M6's, not closed by M6
  (§1.1).

### 10.1 Two things I expect to have to write down rather than prove

Said now, not on day six.

- **DoD 1's "sent from a real handset" inherits M5's WhatsApp constraint.** Every route to
  WhatsApp's composer targets a real number or opens a picker of real contacts, and a blind tap
  near a send button in somebody's real chat is the incident class checklist §E exists to
  prevent. I expect to prove the hand-off the way M5 did — a prepared message and image handed
  to another app, stopping there — and to say which app it was.
- **DoD 1 and 2's evidence cannot be committed** (§1.2). A composited poster carries a real
  face. What was on the screen gets described in words.

---

## 11. Test data

Every person used in a test, a fixture or an example in `M6_VERIFY.md` is **invented**
(checklist §F). M5's second half carries: no generated message about a real person goes into a
document, a fixture or a test. M6 adds the third (§1.2): **no composited poster carrying a real
face enters this repository in any form.**

Compositor fixtures are solid-colour and gradient PNGs generated by the test itself. That is
not only a privacy measure — it is what makes "the photo is unmodified" an exact assertion
rather than an eyeball, because a known pixel pattern can be compared byte for byte and a
photograph cannot.
