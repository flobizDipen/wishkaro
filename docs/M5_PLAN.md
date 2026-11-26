# M5 Implementation Plan — Wish Sending

> Branch `feat/m5-wish-sending`, cut from `main` at `90a2c15`.
>
> **Nothing in this plan has been built.** No feature code is written and `git status` carries
> only this file. §8 lists what I need from Dipen before step 1, and one of those items is a
> hard blocker: there is no Anthropic API key on this machine.

---

## 0. What I read first

`docs/BUILD_PROMPT_M5.md` in full, `docs/MILESTONE_CHECKLIST.md` (sections C, D, E and F apply
to me as written; G and H are Dipen's), `docs/REQUIREMENTS.md` §7.6 `FR-WSH`, §7.10 `FR-HIS`,
FR-PPL-07, FR-REM-04/11, FR-POS-10, FR-VIEW-05/08, §11 and §12, `docs/M0_PLAN.md` through
`docs/M4_PLAN.md`, and `docs/M3_VERIFY.md` / `docs/M4_VERIFY.md` for the standard
`M5_VERIFY.md` has to meet.

Then the code, because the prompt says to check rather than take its word:
`server/prisma/schema.prisma`, `server/src/modules/sync/*`, `server/src/modules/events/derived.ts`,
`server/src/config/env.ts`, `server/src/queue/*`, `composeApp/src/commonMain/sqldelight/wishkaro/db/*`,
`LocalStore.kt`, `SyncEngine.kt`, `SyncPaging.kt`, `SyncContracts.kt`, `WishKaroApi.kt`,
`domain/Model.kt`, `domain/Tone.kt`, `ui/nav/Screen.kt`, `ui/nav/WishKaroNavigation.kt`,
`ui/AppStore.kt`, `MainActivity.kt` and `push/ReminderPresenter.kt`.

---

## 1. State of the world, as actually found

Every claim below was checked against the checkout, not against a milestone table.

| Claim | Verified |
|---|---|
| There is no wish anywhere | `schema.prisma` holds exactly eight models — User, Person, PersonTrait, Event, EventOccurrence, Reminder, Device, RefreshToken. `sqldelight/wishkaro/db/` holds exactly eight tables — person, event, person_trait, delivery_report, scheduled_reminder, seen_reminder, sync_state, user_settings. Neither is a wish |
| There is no LLM client | No `@anthropic-ai/sdk` in `server/package.json`, no key in `.env.example`, no cost table, no rate limiter, no `ANTHROPIC_*` in `env.ts` |
| Tone is already solved, once | `toneFor(person)` / `toneFor(sentiment)` in `domain/Tone.kt`, with `ToneTest` asserting the solemn set against a banned lexicon. The composer joins this; it does not get a second one |
| Sync has three cursor entities | `SyncCursor` is `{person, event, trait}`. A fourth is an additive extension — an M4 client's cursor decodes `wish: null` and gets a full resync, exactly as M0's cursor did for traits |
| The push is unit-packed, not chunked | `planPages()` packs a person **with** their events and traits so a person is never in a later page than their own rows. Any new entity has to answer that question before it can join |
| The notification action is a stub | `ReminderPresenter.actionsFor()` builds **Send wish** (celebratory) and **Send message** (solemn) and both call `openIntent` — the same `wishkaro://person/<id>` link. Labels and placement are already decided and M2 already distinguishes the two; I must not regress that |
| Redis is available | `QueueModule` exports a `REDIS_CONNECTION` `IORedis` singleton, `@Global`. The rate limiter has somewhere to live |
| The device is attached | `adb devices` → `4B201JEBF03493`. Disk is **16 GB free** (M4's note recorded 244 MB; that has been cleared and an emulator is now creatable) |

### 1.1 BLOCKER — there is no Anthropic API key

```
$ ls ~/.secrets/
wishkaro-service-account.json
```

That is the Firebase key and nothing else. `ANTHROPIC_API_KEY` is not in the environment,
not in `server/.env`, and not on this machine anywhere I am willing to go looking.

Steps 1 and 2a of §7 (wish history, and the endpoint's skeleton with the ledger and the rate
limit) can be built and tested without it. **Step 2b onwards cannot**: the prompt builder's
tests must see real output (checklist §D — "a test that asserts on a fake model's output
tests the fake"), the five-tones-by-three-languages matrix is fifteen real calls, and
definition-of-done items 1 through 8 are all downstream of a real draft.

What I need, in §8: a key at `~/.secrets/wishkaro-anthropic.key` (or exported in this shell),
and the same key in Railway's env. It never enters git — `.gitignore` already covers `.env*`
and I will add nothing that weakens that.

### 1.2 Carried forward, named rather than quietly dropped

Per checklist §A, the previous milestone's unproven items get said out loud:

- **The OEM battery helper is still unexercised on a non-Pixel device** — now across five
  milestones. M5 does not touch it and cannot close it. If a non-Pixel phone appears during
  this milestone I will run it and say so; otherwise it carries again.
- **The mobile-data leg of the Railway deploy is still unproven** (SIM will not register).
  M5 adds a feature that is *useless* without a network, so this matters more than it did:
  the offline path in §4 is what stands in for it, and I will say in `M5_VERIFY.md` that
  "offline" was proved with the radio off on Wi-Fi-less aeroplane mode, not on a failing
  mobile connection, because those are different claims.
- **M4's tablet verification was a resized phone**, the empty states were never seen, two
  widget sizes were never placed, and the instrumented suite does not run in CI. M5 inherits
  all four. The composer is single-pane at every width (FR-VIEW-08), so the tablet gap
  touches it least of any milestone so far — I will verify its layout at 1484×928 via
  `wm size` and say that is what it was.

---

## 2. Decision 1 — wish history: shape, and synced or not

**Recommendation: a fourth synced entity, `wish`, following the same three rules as the other
three — last-write-wins per record on `updatedAt`, soft delete as a tombstone, a monotonic
per-entity cursor — written by the CLIENT when the user confirms a send, and keyed by
`(personId, eventType, occurrenceYear)` rather than by a foreign key to the event row.**

### 2.1 Why synced rather than local-only

The prompt says local-only is a real answer and "the user's own record of what they said" is
an argument for it. I took it seriously and it loses on one fact:

**The server builds the prompt.** FR-WSH-02's "must not repeat" and FR-HIS-03's "fed back
into every AI prompt" are conditioning that happens where the prompt is assembled, and that is
server-side, because the key is server-side (§3) and because "derived values stay
server-derived" is a standing rule here. Local-only history means the client ships its own
history up in the draft request body — which is the client telling the server what something
means, the exact shape the checklist bans.

And the second fact, which is the one that actually decides it: **a reinstall would erase it.**
FR-HIS-03 calls history "the retention moat; treat it as a first-class feature, not a log",
and one of the six jobs-to-be-done in §4 of REQUIREMENTS is "What did I do last year?". A moat
that a new phone empties is not a moat. Every other first-class record in this app survives a
reinstall because it syncs; a wish that did not would be the one exception, and nobody would
discover it until the day it mattered.

So: synced, by the same mechanism, for the reason the prompt gives — "a wish history that does
not do those is the one entity in the app that behaves differently, and the next person to
touch sync will not know."

### 2.2 What that gives up

- **Last-write-wins per record is a slightly odd fit.** A wish is near-append-only: it is
  written once and then edited approximately never. LWW costs nothing here, but it means a
  wish carries an `updatedAt` that will almost always equal its `createdAt`, and a reader
  will wonder why. The schema comment will say why.
- **The push path gets a fourth entity**, which means `PushPage`, `planPages`,
  `rowsRejectedBy`, `SyncEngine`, `SyncStore`, `LocalStore`, the two DTO files, the server's
  `SyncPushSchema`, `SyncService.push` and `SyncCursor` all grow a case. That is ~8 files of
  mechanical widening and it is the single largest chunk of M5 by line count. §2.4 is how I
  keep it from also being the largest chunk by risk.
- **Message text goes to the server.** It already would have — the draft is generated there —
  but storing it means a user's words about a named person sit in Postgres. §11 already
  governs this (no PII in logs; the text is never logged, only stored), and `notes` on a
  person is the same shape of data. Worth naming rather than discovering.

### 2.3 The shape, and the one unusual thing about it

```
model Wish {
  id       String @id             // client-generated uuid, as every record here is
  userId   String
  personId String                 // FK, cascade — a deleted person's wishes go with them

  // --- identity of the occasion ---------------------------------------------------
  eventType      EventType        // BIRTHDAY, DEATH_ANNIVERSARY, ...
  occurrenceYear Int              // the calendar year of the occasion, owner's timezone
  sentiment      Sentiment        // DERIVED on the server. Never taken from a client
  eventId        String?          // soft reference. NOT a foreign key — see below

  // --- what was actually sent (FR-HIS-01) -----------------------------------------
  messageText String
  tone        WishTone            // WARM FUNNY RESPECTFUL SHORT POETIC HEARTFELT
  language    WishLanguage        // ENGLISH HINDI HINGLISH
  channel     WishChannel         // WHATSAPP SMS SHARE COPY
  source      WishSource          // AI | TEMPLATE | USER — who wrote the words
  posterId    String?             // reserved for M6, nothing writes it (as photoKey was)

  sentAt    DateTime              // when the user said yes, not when the draft was made
  createdAt DateTime
  updatedAt DateTime
  deletedAt DateTime?

  @@index([userId, updatedAt, id])          // the sync cursor
  @@index([personId, eventType, occurrenceYear])  // the no-repeat query
}
```

**`eventId` is a plain nullable column with no foreign key, and that is deliberate.** Three
reasons, in order of weight:

1. **Deleting an event must not delete the history.** The commonest repair in this app is
   "that date was wrong" — delete the event, add it again. With a cascading FK, the record of
   what you sent your mother in 2024 would vanish because you fixed her birthday in 2026. A
   fact about the past cannot be owned by a row the user is free to replace.
2. **It removes the sync ordering problem entirely.** A wish with a real FK to `event` would
   have to be in the same push page as its event *and* its person, which `planPages` does not
   currently express — it groups by person only. With no FK, a wish's only dependency is its
   person, so it packs into exactly the unit that already exists, and §2.4 stays mechanical.
3. The no-repeat query does not need it. "What did I send this person for their birthday in
   previous years" is `(personId, eventType, occurrenceYear < N)`.

The cost: a person with two `CUSTOM` events in the same year cannot be told apart by
`(personId, eventType, occurrenceYear)`. For `BIRTHDAY` and the three anniversaries that is
impossible by construction; for `CUSTOM` it is possible and the consequence is that the
no-repeat rule would see both as one history. I am accepting that, and `eventId` is kept
precisely so a later milestone can tighten it without a migration.

**`sentiment` is derived on the server** from the person's `isDeceased` and the event type, by
the same `sentimentFor()` the events module already uses. The write schema is `.strict()`, so
a client that sends `sentiment` gets a 400 naming the field — not a silent strip. This is the
rule that M1 got wrong once and it is not getting a second chance here.

### 2.4 "Per person per year" is under-specified — I am resolving it, in writing

FR-HIS-01 says wish history is "per person per year". A person can have a birthday and a
wedding anniversary in the same year, so that phrase does not identify a row. I am reading it
as **per person, per event type, per year**, which is what the schema above encodes and what
the index serves. Flagged here rather than silently chosen (checklist §B).

### 2.5 The client side

New table `wish` in `sqldelight/wishkaro/db/Wish.sq`, plus migration `6.sqm` (schema version
6 → 7), with the same `pending_sync` / `sync_blocked` / `sync_block_reason` columns every
other synced table has. Column order is load-bearing — `verifyMigrations` fails the build on
any difference, ordinals included — so the new table is declared in the order the migration
creates it, once, and never reordered.

`LocalStore` gains `observeWishes(personId)`, `recordWishLocally(...)`, `wishHistoryFor(...)`,
and the four `SyncStore` members (`pendingWishes`, `blockWish`, `clearWishPending`,
`restampWishPending`) plus a `remoteWishes` arm on `applyRemote`.

**The record is written when the user confirms the send, not when the draft is produced.**
FR-WSH-07 is explicit: the question comes *after* the hand-off. A draft that was generated and
never sent is not history, must not suppress next year's draft, and must not appear on the
person screen. Regenerate therefore does not write a row either — the previous draft is passed
back up in the request as ephemeral "do not produce this again" context (§3.4).

---

## 3. Decision 2 — the provider boundary

### 3.1 Where the key is, and where it is not

The phone calls our server. Our server calls Anthropic. The key exists in exactly two places:
`~/.secrets/wishkaro-anthropic.key`, read into `server/.env` as `ANTHROPIC_API_KEY`
(gitignored, and `.env.example` gets the variable with an empty value and a comment); and
Railway's own environment. No key in the APK, no key in git, no key in a log line, no key in
an error message that reaches a client.

`env.ts` gains `ANTHROPIC_API_KEY` (required — the process refuses to boot without it, the way
it already refuses without Firebase credentials), `ANTHROPIC_MODEL` (default
`claude-opus-5-5`), `ANTHROPIC_EFFORT` (default `low`), `USD_INR_RATE` (default `89.0`), and
the two rate-limit ceilings in §3.5.

### 3.2 The module

```
server/src/modules/wishes/
  wishes.module.ts
  wishes.controller.ts          POST /v1/wishes/draft
  wishes.schemas.ts             zod, .strict(), derived fields refused
  drafting.service.ts           orchestration: load → build → call → ledger → return
  prompt-builder.ts             PURE. person+history+tone+language → system + user
  prompt-builder.spec.ts        pure tests
  tone-rules.ts                 which tones are legal for which sentiment
  tone-rules.spec.ts            the restriction test that must go red on Funny
  llm/
    llm-provider.ts             the port: draft(request) → DraftResult
    anthropic.provider.ts       the only adapter
    pricing.ts                  USD per MTok per model, versioned
  ledger/
    ai-usage.service.ts         one row per call, always
  rate-limit/
    draft-rate-limit.service.ts Redis, per user, two windows
  live/
    draft-live.spec.ts          REAL calls. Skipped unless RUN_LIVE_AI_TESTS=1
```

**Why a port with one implementation.** Not speculative generality: the fake is how the
mechanism tests (ledger written, rate limit enforced, refusal handled, error handled) run in
CI with no key and no money. The real one is how the *prompt* tests run, and those are in
`live/`, excluded from CI by name, and run by hand against a key. That split is the direct
answer to "a test that asserts on a fake model's output tests the fake" — the fake is never
asked a question about words.

### 3.3 The API facts the prompt names, and what I am doing with each

| Fact | What the adapter does |
|---|---|
| `thinking: {type:"enabled", budget_tokens:N}` is rejected | `thinking` is **omitted entirely**. On `claude-opus-5-5` thinking is always on and adaptive; `effort` is the only control |
| `effort` defaults to `medium` | Set **explicitly** to `low` via `output_config: { effort: "low" }`. A two-sentence greeting does not need more, and this is the one knob that is mine to set rather than Dipen's |
| Forced `tool_choice` is rejected | No tools at all. The draft comes back as **structured output** — `output_config.format` with a one-field schema — so there is no "Here's a lovely birthday message:" preamble to strip |
| Assistant prefill is rejected | Not used. Shape comes from the system prompt and the output format |
| `stop_reason: "refusal"` arrives as HTTP 200 | `stop_reason` is checked **before** `content` is read, on every path. §5 |

**Prompt caching.** The request is laid out stable-first: the system prompt (role, rules,
tone-and-language instruction block) is one `system` text block with
`cache_control: {type:"ephemeral"}` on it; the person, their traits, the history and the
regenerate-avoid list go in the `messages` array *after* that breakpoint. Nothing volatile —
no timestamp, no request id, no uuid — crosses into the cached prefix. I will verify by
reading `usage.cache_read_input_tokens` across repeated calls and record the actual number in
`M5_VERIFY.md`, not assume it. A zero there is a finding, not a rounding error.

One honest caveat I expect to have to write down: the minimum cacheable prefix is
model-dependent and is between 512 and 4096 tokens. A short system prompt may simply **not
cache at all**, silently. If the measurement says zero I will say whether the prefix was too
short rather than reporting a cache that is not there.

### 3.4 What the endpoint takes and returns

```
POST /v1/wishes/draft
{
  personId, eventId?, eventType, occurrenceYear,
  tone, language,
  avoid?: string[]      // previous drafts THIS session (Regenerate), max 3, max 2000 chars
}
→ 200 { text, tone, language, model, draftedAt }
→ 400 TONE_NOT_ALLOWED      // Funny on a SOLEMN event
→ 404 PERSON_NOT_FOUND
→ 429 RATE_LIMITED          { retryAfterSeconds }
→ 503 DRAFT_UNAVAILABLE     // refusal chain exhausted, or provider error
```

The server loads the person, their traits, their events and their wish history itself. The
client sends ids and choices, never meanings — no relation string, no hobby list, no
sentiment, no "this person is deceased". `sentiment` is derived server-side and the tone is
validated against it there, so the tone restriction holds even against a client that has been
tampered with.

`occurrenceYear` is sent rather than derived because the composer can legitimately be opened
for next year's birthday from a T-7 reminder in December; the server validates it is within
±1 of the current year in the owner's timezone.

### 3.5 The cost ledger (FR-POS-10) and the rate limit (FR-WSH-08)

**The ledger.** One `AiUsage` row per model call, written in a `finally`, including when the
call refused, errored or timed out — a call that produced tokens cost money whether or not the
user got a draft.

```
model AiUsage {
  id String @id
  userId String
  feature String        // "wish_draft". M6 writes "poster", M8 "gift_ideas"
  provider String       // "anthropic"
  model String          // the model that actually served it — a fallback changes this
  outcome String        // OK | REFUSED | ERROR | TIMEOUT

  inputTokens Int
  outputTokens Int
  cacheReadTokens Int
  cacheWriteTokens Int

  costUsd    Decimal @db.Decimal(12,6)
  usdInrRate Decimal @db.Decimal(10,4)   // the rate AT THE TIME. History does not re-price
  costInr    Decimal @db.Decimal(12,4)
  pricingVersion String                  // which rate table produced costUsd

  createdAt DateTime
  @@index([userId, createdAt])
  @@index([feature, createdAt])
}
```

`usdInrRate` and `pricingVersion` are stored per row so that changing either later does not
silently rewrite what last month cost. Four token counts rather than two because cache reads
are an order of magnitude cheaper than fresh input and a ledger that cannot see the difference
cannot answer whether caching is working.

A row that cannot be written is a **bug, not an optimisation**: if the ledger write throws, the
request fails. The alternative — serve the draft and lose the row — is how a cost ledger
quietly becomes a sample.

**The rate limit.** Redis, two fixed windows per user — `WISH_DRAFT_LIMIT_HOUR` (default 15)
and `WISH_DRAFT_LIMIT_DAY` (default 50) — counted on **attempted** drafts, so a refusal still
costs a slot (it cost money). 429 carries `retryAfterSeconds` and a message the app shows
verbatim, because FR-WSH-08's limit has to be visible or it is a dead button
(definition-of-done item 8).

**It fails closed.** If Redis is unreachable the endpoint returns 503 with a distinct message
rather than serving unmetered drafts. Redis being down already means BullMQ is down and this
server is degraded; the failure mode I will not accept is "the meter broke, so the spending
became unlimited".

I will bring the measured INR cost per draft and per 100 to Dipen (definition-of-done item 7)
before any model decision is reopened. **I am not quietly picking a cheaper model.**

---

## 4. Decision 3 — the composer with no network

**Recommendation: the composer always opens, and offline it opens holding real editable text —
the cached draft for this exact occasion if there is one, otherwise a locally generated
non-AI template — banner-marked as written on the phone, with a Retry that redrafts the
moment the network returns.**

Not a spinner, not a refusal, not an empty box.

### 4.1 Why

The user got here by tapping a reminder, which means they are about to walk into a room. The
options the prompt lists, scored against that:

| Option | Verdict |
|---|---|
| Refuse to open | Fails FR-VIEW-05 outright, and strands the user at the exact moment the app exists for |
| Spinner | The same thing with worse manners |
| Empty composer | Honest, and leads somewhere, but hands the user a blank box when the whole promise was "help me say something not generic" |
| Cached draft | Right when there is one — but there is nothing to cache on the first open, which is the common case |
| **Non-AI template** | Always available, local-first like everything else in this app, testable with no network and no key, and genuinely usable |

So it is the last two, in that order: cache if we have it, template if we do not. Both land in
the same editable field and the user cannot tell the difference except from the banner.

The template family reuses the `ToneStrings` split that already exists — there is a
celebratory set and a solemn set and the solemn set contains no celebratory word, by
construction rather than by filter. The composer picks by the person's mirrored `sentiment`,
which is in SQLite and readable offline. **A remembrance template is never a birthday template
with the word "happy" removed.**

### 4.2 The same fallback serves three different failures

This is why it is worth building properly rather than as an offline special case. The local
template renders when:

1. there is no network,
2. the API refuses (§5), and
3. the API errors, times out or the rate limit is hit *and* the user asks for something anyway.

One code path, three triggers, one set of tests. Case 3 is subtle: a 429 shows the rate-limit
message **and** still leaves the user with a template they can send, because "come back in
40 minutes" is not an answer on the morning of somebody's birthday.

### 4.3 What it gives up

- **Template text is now product copy I am writing**, and it ships. It will be mediocre in the
  way templates are mediocre, which is the thing this app's whole premise is against. It is
  marked as such on screen ("Written on your phone — tap Retry for a personal draft") so the
  user knows the good version is one tap away.
- **The cached-draft store is another local table** — a small one, `wish_draft_cache`, keyed
  by `(personId, eventType, occurrenceYear, tone, language)`, capped and swept. It does **not**
  sync: it is a convenience, not a record, and the record is `wish`.
- Offline, the draft is not conditioned on history. A template cannot be, and saying so on
  screen would be noise.

---

## 5. Decision 4 — the solemn path, and the refusal

### 5.1 Two separate templates, not one with a branch

`prompt-builder.ts` selects between `CELEBRATORY_SYSTEM` and `SOLEMN_SYSTEM` **before** it
builds anything, the same way `toneFor()` picks a `ToneStrings` set. There is no conditional
inside a shared prompt, for the reason `Tone.kt` already gives: a conditional is a thing
someone can forget, a set you have to pick is a thing the compiler makes you pick.

Tone is chosen **from the person, never from the event type**. A deceased person's birthday is
a `SOLEMN` event whose `type` is still `BIRTHDAY`, and M1 already got this wrong once.

### 5.2 The tone restriction, in one place, held to one table

`SOLEMN` → `RESPECTFUL`, `SHORT`, `HEARTFELT`. `CELEBRATORY` → `WARM`, `FUNNY`, `RESPECTFUL`,
`SHORT`, `POETIC`. Six tones in total; the two sets overlap on two of them and `HEARTFELT`
exists only on the solemn side (FR-WSH-09 names it and FR-WSH-03 does not).

That rule is implemented **twice, in two languages** — the server validates it, the client
hides the chips — which is exactly the shape checklist §C says must be held to one shared
table of cases. So it gets one, in the pattern M3 established with `name-tokens.json`:
`docs/fixtures/wish-tones.json`, read by `ToneRulesTest.kt` and `tone-rules.spec.ts`. If the
two drift, one suite goes red.

**The restriction test is the one that must go red if someone adds Funny back**
(definition-of-done item 9). It asserts on the server's rejection, not on a list comparison,
so it is testing the behaviour rather than restating the data.

### 5.3 The refusal

`stop_reason` is checked before `content` on every path. Server-side fallbacks are on from the
first line — `betas: ["server-side-fallback-2026-07-01"]` with `fallbacks: "default"` — so a
decline is re-run on another model inside the same call, and `response.model` (which the
ledger records) tells us afterwards which one served it.

If the whole chain still refuses, the endpoint returns **503 `DRAFT_UNAVAILABLE`** and the app
shows the solemn local template with one quiet line above it: *"We couldn't draft this one.
Here's a simple message you can change."* No error code, no "something went wrong", no retry
spinner, and nothing that reads like the app has an opinion about the death. This is the worst
screen in the app to show a raw error on and it will not show one.

Both failures are **demonstrated, not argued** (definition-of-done item 5): the refusal by
pointing the adapter at a request shaped to trip a classifier and, if that will not reproduce
on demand, by a provider-level injection that returns a genuine refusal envelope — stated as
such in `M5_VERIFY.md`, with which one it was. The network failure with the radio off.

### 5.4 TBD-4 is Dipen's, and FR-WSH-09 ships behind it

TBD-4 (solemn copy and template art direction, reviewed by hand, not AI-only) is unanswered.
M5 builds the mechanism: the separate template, the restricted tone list, the tests, the
refusal path, the offline path. **It does not close FR-WSH-09.** Definition-of-done item 4
produces a real remembrance draft, records it verbatim in `M5_VERIFY.md`, and hands it to
Dipen to read. If the art direction comes back wrong, the fix is the system prompt text and
the template strings — not the mechanism — so rejecting the copy costs a commit, not a
milestone.

---

## 6. Three things I am raising rather than resolving

Per the prompt and checklist §B. M0 to M4 each hit at least one of these and raising it was
right every time.

### 6.1 §12 asks for a GenAI content reporting mechanism and output moderation. M5 is the first GenAI content.

`REQUIREMENTS.md` §12 carries two unchecked boxes that M5 is the first milestone to make
reachable:

- [ ] GenAI content reporting mechanism (Play GenAI policy)
- [ ] Input/output moderation on all generated content

The build prompt's "Explicitly NOT in M5" list does not mention either, and its scope section
does not include them. It also says "Build ONLY what this prompt specifies", so I am not
building them unasked.

**My recommendation:** a single "Report this draft" affordance in the composer overflow — one
screen, one `POST`, perhaps 60 lines across both sides — is cheap now and is a Play *policy*
gate rather than a feature, which makes it the kind of thing that is painful to discover during
submission. Full input/output moderation is genuinely M6's, alongside image moderation.

**Decision needed:** add the report affordance to M5, or carry both boxes to M6 with M6's
prompt naming them. Either is fine; silently doing neither is not.

### 6.2 §12 says the minor flag suppresses personalisation. FR-WSH-02 says condition the draft on hobbies.

`FR-PPL-09` derives `isMinor` server-side and §12 says "Minor flag suppresses profiling,
personalisation and affiliate tracking". FR-WSH-02 says the draft is conditioned on hobbies
and the rest of the profile. For a person flagged as a minor those two instructions point in
opposite directions.

M1 already half-answers it: `completenessSuggestions` is empty for a minor, so the app never
*asks* for a minor's traits. But traits can still arrive by import, and `notes` is always
collectable.

**My recommendation, and I will build this unless told otherwise:** for a person with
`isMinor = true` the prompt carries relation, event type, milestone and age band only — no
traits, no notes, no free text about them. The draft is still personal (it knows this is your
ten-year-old niece's birthday) and it is not profiled. One test, asserting the prompt builder
omits traits for a minor.

Raising it rather than just doing it because it is a *narrowing* of a requirement and those
are the ones that get silently lost.

### 6.3 FR-POS-10 files the cost ledger under posters. M5 is where it starts.

The requirement that mandates the ledger lives in `FR-POS` (§7.7, posters) and says "every
generation writes a cost ledger row". M5 writes rows for text, which is not a generation in
that section's sense. The build prompt is explicit that M5 is where it starts and why.

This is paperwork, not a design question, and it has a precedent: M3 amended FR-IMP-04 and M4
amended FR-VIEW-06 rather than leaving a documented thing that is not what was built. I will
add the same kind of amendment note against FR-POS-10 saying the ledger is cross-feature and
begins in M5 — unless Dipen would rather it got its own `FR-AI` id, which is arguably where it
belongs now that three milestones will write to it.

---

## 7. Implementation order

The floor goes first, for the reason M2, M3 and M4 each had one: wish history is the *input*
to this feature, not an output of it, and a composer written first would be written as though
history were optional.

| # | Step | Done when | Needs a key |
|---|---|---|---|
| 1 | **Wish history, server** — Prisma model + additive migration, derived `sentiment`, `SyncCursor` gains `wish`, `SyncPushSchema` + `SyncService.push`/`pull`, the no-repeat query | `sync-cursor.spec.ts` covers a four-entity cursor; a push/pull round trip test; an M4-shaped cursor still decodes | no |
| 2 | **Wish history, client** — `Wish.sq` + `6.sqm`, `LocalStore`, `SyncStore`, `PushPage`/`planPages`/`rowsRejectedBy`, DTOs, `SyncEngine` | `verifyMigrations` green; `planPages` tests cover a wish whose person is pending and not in the snapshot; `androidUnitTest` drives it against real in-memory SQLite, not a fake store (checklist §D) | no |
| 3 | **The endpoint skeleton** — module, zod schemas, rate limit, ledger, the fake provider, error shapes | `curl` gets a 429 after the 15th call; `AiUsage` holds a row per attempt including the refused one; all against the fake | no |
| 4 | **The real adapter** — `anthropic.provider.ts`, caching layout, `stop_reason` first, fallbacks on | **`curl` produces a real draft and a real ledger row with real token counts, before any UI exists.** Cache-read tokens measured and recorded | **yes** |
| 5 | **The prompt builder + tests** — both template families, the tone rules and the shared fixture, the minor rule, the no-repeat conditioning | Pure tests green in CI; `live/draft-live.spec.ts` asserts on real output and is skipped without a key; the solemn restriction goes red when Funny is added back | yes (for `live/`) |
| 6 | **The composer** — `Screen.WishComposer` + saved-state registration, the offline/template path, tone and language selectors, Regenerate, free editing | `NavKeyRegistrationTest` green; the composer opens with the radio off and holds editable text | partly |
| 7 | **Send targets + the loop** — WhatsApp, SMS, share sheet, copy via a new `expect/actual` `MessageSender`; "Did you send it?"; the wish row; the one enrichment nudge | A wish lands in WhatsApp's composer on the real phone; a row appears in history; exactly one nudge per interaction | no |
| 8 | **Entry points** — `wishkaro://wish/<personId>?event=<id>`, `ReminderPresenter`'s two actions, the event card, the person screen; history on the person screen (FR-PPL-07) and "Last year you sent…" in the composer (FR-HIS-04) | All three entries land on the composer; the solemn reminder still says **Send message** | no |
| 9 | **The solemn path end to end on the device**, the refusal, the network failure, the matrix of five tones × three languages, and the cost numbers | Definition-of-done items 1–3 and 5–8 observed; item 4 produced and handed over | yes |
| 10 | **`docs/M5_VERIFY.md`**, CI green, PR | Pass / PARTIAL / UNCHECKED per item, with what each partial means | — |

Steps 1–3 are buildable today. Step 4 is the gate.

### 7.1 Two scope notes

**Nothing in M5 sends anything.** FR-WSH-06 is a correctness requirement here: the send targets
are `Intent`s handed to another app, there is no code path that can transmit a message, and
there will be no "send directly" affordance even disabled. The "Did you send it?" question
exists precisely because the app genuinely does not know.

**No credit check, no paywall hook, not even a disabled one.** The build prompt rules it out
and FR-WSH-08 agrees: text drafting is free. A metered path that charges nothing is a path
nobody tests.

---

## 8. What I need from you before step 4

1. **The Anthropic API key.** At `~/.secrets/wishkaro-anthropic.key` and in Railway's env.
   Nothing below step 3 can be verified without it. This is the blocker.
2. **§6.1 — the Play GenAI boxes.** Report affordance in M5, or both carried to M6?
   (My recommendation: the report affordance now, moderation in M6.)
3. **§6.2 — the minor rule.** Confirm I should narrow the prompt for `isMinor` people, or
   say the §12 line does not mean that here.
4. **§6.3 — where the cost ledger's requirement lives.** Amendment against FR-POS-10, or a new
   `FR-AI` id?
5. **Rate-limit ceilings.** 15/hour and 50/day per user is my proposal, after measurement I
   will tell you what that costs at the ceiling. Say if you want them different.
6. **Model and effort — flagged, not asked.** I am defaulting to `claude-opus-5-5` with
   `output_config.effort: "low"` and I will bring you the measured INR per draft and per 100
   at step 9. The decision to move to Sonnet or Haiku is yours and I am not pre-empting it.
7. **TBD-4.** Still open, and that is fine — M5 builds the mechanism and ships FR-WSH-09
   behind your reading of the real output (§5.4). Say if you would rather the solemn composer
   was feature-flagged off entirely until then; I do not think it should be, because an
   unexercised path is worse than a reviewed one.

### 8.1 Already decided unless you say otherwise

- Wish history is a **synced** entity with no FK to `event` (§2).
- The composer **always opens**, with a cached draft or a local template offline (§4).
- The ledger fails the request if it cannot write a row (§3.5).
- The rate limit **fails closed** when Redis is unreachable (§3.5).
- Draft text is **never logged**, only stored (§11, no PII in logs).

---

## 9. Explicitly not in M5

Posters and image generation (M6). Gift suggestions and affiliate links (M8). Billing, credits
and the paywall (M7). Video (v1.1). Input/output moderation (M6 — see §6.1). Hindi *UI*
localisation is v1.1 and is not the same thing as a Hindi *draft*, which is in scope.

---

## 10. Test data

Every person used in a test, a fixture or an example in `M5_VERIFY.md` is **invented**
(checklist §F). This milestone adds a rule the earlier ones did not need: **no generated
message about a real person goes into a document, a fixture or a test**, because the repo is
public and a drafted wish is about somebody by name. The five-by-three matrix and the
remembrance draft in `M5_VERIFY.md` are all about invented people, and the account with 575
people on Railway is used for the no-repeat check against real history **without any of its
contents being written down**.
