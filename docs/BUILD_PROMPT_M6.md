# M6 Build Prompt — AI Posters

**Read `docs/REQUIREMENTS.md` first — all of it, not the poster section.** §10 governs what you
are building more directly than §7.7 does, and its hard rules do not carry FR- ids. M5 missed §10
entirely because it quoted requirements by id and §10's rules have none. That is now the first
item in `MILESTONE_CHECKLIST.md` §A. Do not repeat it.

Then read, in this order: `docs/MILESTONE_CHECKLIST.md` (the standing rules, including the ten
things M5 added), `docs/M5_PLAN.md` and `docs/M5_VERIFY.md` (the provider port, the ledger, the
spend cap and the rate limiter are all M5's, and you are extending them rather than writing them),
and `docs/M4_PLAN.md` §2.1 if you touch navigation.

---

## The prompt

Build M6: AI posters. Work on a branch off `main`, open one PR, and do not merge it.

Before you write any code, write `docs/M6_PLAN.md`: the decisions below with what each one gives
up, the order you will build in, and every collision you find between this prompt and
`REQUIREMENTS.md` **raised rather than resolved**. M5 did this and three of its raised collisions
were real. Raising one costs a paragraph; resolving one quietly costs a milestone.

---

## What M6 is

A poster is a picture the user sends instead of, or alongside, the message M5 writes. The pipeline
is fixed by FR-POS-01 and is three steps, in this order:

1. **Copy and theme** — a text model picks or writes the words and selects a theme.
2. **Background artwork only** — `gpt-image-1` generates a background. Nothing else.
3. **Compositing** — the server lays the person's **unmodified** photo, the copy and the frame
   elements over that background with `sharp`.

The thing to understand before you plan: **step 2 is the step this milestone mostly avoids.**
FR-POS-04 ships a library of pre-generated, hand-approved themes so the common path makes no live
call at all, and FR-POS-03 caches what is generated on the key `(theme, event type, milestone,
palette, aspect ratio)` so a popular theme is generated once and served thousands of times. Live
generation is the premium path. A milestone that treats live generation as the main path has built
the expensive version of the product and the one that cannot work offline.

---

## Decide this before you write code

Four blockers. Three are Dipen's and one is yours. Put each in `M6_PLAN.md` with what it costs
either way, and **do not pick for him on the first three.**

### 1. TBD-5 is unanswered, and M6 is the milestone it blocks

`REQUIREMENTS.md` §13 lists TBD-5 — "visual identity: palette, type, icon, poster template art
direction" — as due at **M4**. It is still open. M4 and M5 did not need it. M6 is almost entirely
it.

FR-POS-04 asks for themes that are **hand-approved**. FR-POS-13 asks for a **separate solemn
family** — muted palette, no balloons, no confetti, no cake, no party motifs — and for celebratory
themes to be *filtered out of the picker* for those events. Neither is a thing a session can
decide. "Hand-approved" names a hand, and it is not yours.

This is a harder blocker than the missing key was for M5, and it is worth being plain about why.
M5 could build every mechanism against a deterministic fake, because its output was text and the
rules about text — no "congratulate" on a death anniversary, no repeat of last year — are
checkable by a test. **A poster's output is a picture.** No fake tells you whether it is beautiful,
and no test tells you whether it is tasteful on a remembrance. Something has to be looked at by a
person.

What you can do without an answer: the cache, the compositor, the formats, the picker, the job
queue, the ledger rows, the moderation path, and the whole solemn/celebratory *separation* —
everything except what the themes actually look like. Build against a small set of obviously
placeholder themes that are visibly placeholders, so nobody mistakes them for art direction.

### 2. There is no image key, and images are billed per image

`OPENAI_API_KEY` is in `server/.env.example` from M5 and is empty. `gpt-image-1` is billed **per
image**, not per token, so the cost shape is nothing like M5's: a user pressing "try another" four
times is four whole images, and a retry loop with a bug in it is a bill rather than a log line.

Three things follow, and the first two are already built:

- **The global daily spend cap exists.** `SpendCapService`, `AI_DAILY_SPEND_CAP_INR`, summed from
  the ledger across **every feature** and checked before the per-user limit. That is FR-POS-11's
  cap half, done. Do not build a second one. Do add image cost to `pricing.ts`, or every poster
  row reads ₹0 and the cap cannot see them — which would be worse than having no cap, because it
  would look like one.
- **The per-user limiter exists.** `DraftRateLimitService` — fixed windows in Redis, counted on
  attempts rather than successes, fails closed. Posters need their own ceilings, not their own
  limiter.
- **A cache hit must not touch either.** FR-POS-03's whole economic argument is that the common
  path costs nothing. If a cache hit consumes a rate-limit slot, the cheap path is rationed like
  the expensive one.

### 3. Moderation is M6's to build, and M5 owes a debt you are inheriting

`REQUIREMENTS.md` §10 rule 7: *"Input and output moderation on every generation, with user
reporting."* FR-POS-12 says the same for posters and adds the in-app **"Report this content"**
action that Play's GenAI policy requires.

M5 did not build it. It is recorded as a knowingly unmet hard rule in `M5_VERIFY.md` §4.6 and in
`M5_PLAN.md` §12, with the *argument* for deferring it to M6 — that M6 needs an image moderation
path anyway, and one path built once beats two built twice.

**The argument was made. The decision was not.** M5 asked Dipen twice and has no answer, so do not
open this milestone believing moderation was handed to you. It was *offered* to you. Until he says
otherwise, rule 7 is an open hard rule against shipped code, and the first thing `M6_PLAN.md`
should do about it is ask again rather than assume.

So M6's moderation covers **two surfaces, not one**: posters, and the wish drafting path that
already shipped. If you build it for posters only, rule 7 stays unmet for the feature that is
already in users' hands, and the debt silently moves to M7. Say in the plan which surfaces you are
covering.

### 4. Is a poster a fifth synced entity?

Yours to decide, with reasons in the plan. The sync engine has four cursor entities and adding one
is a known, mechanical shape — M5's wish was the fourth and `sync-cursor.ts` explains why a
missing key in an old client's cursor is the correct full resync.

But a poster is not shaped like the other four. It is a **file**, generated server-side, that the
user does not edit offline; the record the client cares about may be a reference and a URL rather
than a row it owns. `Wish` already has a `poster_id` column reserved for exactly this and nothing
writes it yet. Last-write-wins on a binary nobody edits may be ceremony rather than sync.

Consider what the client actually needs offline: the poster it already made, to send again. That
may be a cache, not a cursor.

---

## What already exists — do not rebuild it

M5 built most of the scaffolding you need. Read it before you write anything that looks like it.

| Thing | Where | What M6 does with it |
|---|---|---|
| Cost ledger | `modules/wishes/ledger/ai-usage.service.ts`, `AiUsage` | Write `poster` rows. It is feature-tagged on purpose. Fatal on write failure — keep that. |
| Global spend cap | `modules/wishes/ledger/spend-cap.service.ts` | Already cross-feature. Add image pricing so posters are visible to it. |
| Per-user limiter | `modules/wishes/rate-limit/draft-rate-limit.service.ts` | Copy the shape, new ceilings. Fixed windows, fails closed. |
| Provider port | `modules/wishes/llm/llm-provider.ts` + `fake` + `openai` | §10 already names `ImageProvider` for `gpt-image-1`. Same shape: a fake that is the committed default, a live adapter that refuses to boot without its key. |
| Structured outputs | `modules/wishes/llm/openai.provider.ts` | `strict: true`, validated our side, **one** retry on a malformed body only — not on a refusal, not on a timeout, not on a truncation. Both attempts' tokens on the ledger row. |
| Prompt versioning | `PROMPT_VERSION` in `prompt-builder.ts` | Theme and copy prompts get their own. A hand-review that cannot be traced to a revision stops meaning anything the first time the prompt is edited. |
| Solemn derivation | `events/derived.ts` `sentimentFor`, `Person.isSolemn` | **Read the warning below.** Do not write a third implementation. |
| BullMQ | already running the reminder queue | FR-POS-09's async generation and its progress state. |
| Wish history | `Wish`, `wish.poster_id` | The column is reserved and unwritten. M6 is what writes it. |

---

## Scope

### Theme library and cache (FR-POS-03, FR-POS-04)
A library that ships with the app, a `theme_cache` table whose `theme_key` is **unique** —
§8's indexing note says caching silently fails otherwise — and the key is
`(theme, event type, milestone, palette, aspect ratio)`.

### Compositing (FR-POS-01, FR-POS-02, FR-POS-05, FR-POS-06)
`sharp`, server-side. The photo is **unmodified**. Three output formats — 1080×1920, 1080×1080,
1200×630 — with status the default. No photo is a first-class case, not an error: a typographic or
illustrated template that is still good (OD-11).

### Solemn set (FR-POS-13)
A separate template family, built from a separate prompt family, with celebratory themes filtered
**out of the picker** rather than merely ranked lower.

### Moderation and reporting (FR-POS-12, §10 rule 7)
Input and output, on every generation, across posters **and** wish drafts. An in-app "Report this
content" on every generated asset.

### Async generation (FR-POS-09)
BullMQ, a visible progress state, p95 under 15s on a cache miss and under 2s on a hit.

### Ledger and limits (FR-POS-10, FR-POS-11)
A row per generation with measured INR. Per-user and global limits, degrading to cached themes
when breached.

### Attribution (FR-POS-08)
Free-tier posters carry subtle WishKaro attribution. Premium removes it — but M7 is what knows who
is premium, so build the switch and default it to attributed.

---

## Explicitly NOT in M6

- **Credits, paywall, Play Billing.** M7. Where M6 needs to know "is this user premium", it asks
  one function that returns false.
- **The AI-blend path (FR-POS-07).** It costs extra credits, which M7 owns, and it is the one path
  that puts a face through a generative model. It is opt-in, it needs a warning, and it should not
  be built in the same milestone that establishes the rule it is the exception to.
- **Gift suggestions (M8), video (M10), family tree (M11).**
- **Anything that makes the common path call a model.** If the library and the cache are working,
  most sends generate nothing.

---

## Definition of done

1. A celebratory poster, composited with a real photo, sent from a real handset.
2. The same for a **solemn** occasion, from the solemn family, with no celebratory theme reachable
   in the picker. Hand-checked by Dipen, not only by a test.
3. All three formats produced and inspected at full size.
4. The no-photo path produces something good enough to send.
5. A cache **hit** measured: second request for the same key makes no model call, writes no ledger
   row for a generation that did not happen, and consumes no rate-limit slot.
6. A cache **miss** measured end to end, p95 under 15s.
7. A ledger row per generation with a non-zero measured INR, and image pricing in `pricing.ts`.
8. The global spend cap observed degrading a poster request to cached themes.
9. Moderation demonstrated on both surfaces — a poster and a wish draft — and "Report this
   content" reaching the server from the app.
10. Both targets compile, the server suite is green across three timezones, and CI is green on the
    exact head.

Items 1–6 and 9 need looking at a screen. Items blocked on a missing key or on TBD-5 are
**BLOCKED**, not partial — say so in `M6_VERIFY.md` and do not round them up.

---

## Engineering rules

- **No model is ever called from the client. No key ever ships in the app.** (§10 rule 1.)
- **The face never goes through a generative model** in the default path (FR-POS-02). This is a
  likeness and personality-rights rule, not an aesthetic one. There is no configuration that turns
  it off; the one exception is FR-POS-07, which is not in M6.
- **One implementation of "is this solemn".** It is `sentimentFor()` on the server and
  `Person.isSolemn` on the client, and both are mirrored, never re-derived. M5 shipped two bugs in
  this exact area; see below.
- **Every model call writes a ledger row.** A call that cannot write one is a bug, not an
  optimisation.
- **A rule implemented twice is held to one shared table of cases** in `docs/fixtures/`, read by
  both suites. `docs/fixtures/wish-tones.json` is the pattern. CI now runs on changes to that
  directory — M5 fixed that path filter.
- **A test must ask the layer that holds the rule.** M5 wrote two vacuous tests; one called a
  service directly and never ran the validation pipe it was testing.
- **The revert-check is not a formality.** When reverting a fix does not turn a test red, find out
  why before moving on. M5 had three green revert-checks and each one was a real gap.
- **A comment that makes a performance claim carries the measurement or does not make the claim.**
- **No generated content about a real person** goes into a document, a fixture or a test. The repo
  is public. This now includes images.
- The repo is public: no key, no `.env`, no service account, ever.

---

## How to work

- Branch off `main`, one PR, do not merge it.
- `docs/M6_PLAN.md` before code. Raise collisions; do not resolve them quietly.
- Commit in coherent steps with real messages. End each with
  `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.
- Verify by doing, not by reading. M5's two most valuable findings both came from a handset, and
  one of them had been reachable since M1 with a full test suite passing over it.
- `docs/M6_VERIFY.md` records what is **proved**, what is **read-verified**, and what is
  **blocked**. Those are three different claims and M5 was right to separate them.
- When you are blocked on Dipen, say so and keep going on what is not blocked.

---

## Why the theme library goes first

The same argument that put wish history before the composer in M5.

FR-POS-04 says the common path makes no live call. So the library, the cache and the compositor
are not a fallback for when generation fails — they **are** the product, and live generation is the
premium edge. Build them first and three things follow: the milestone's main path needs no API key,
the expensive path has something to degrade *to* when the cap bites, and the economics in FR-POS-03
become measurable instead of asserted.

Build it the other way around and you will have a beautiful live pipeline, no cache to prove, and
every definition-of-done item blocked behind a key.

---

## The thing most likely to go wrong

**A celebratory theme on a death anniversary.**

This is not a guess. M5 shipped **two** bugs in exactly this area, in one milestone:

- `454ea75` — the composer offered **Funny** and **Poetic** and prefilled "Happy birthday, Rohan"
  for somebody who had died. `person.is_deceased` is derived server-side, so between creating a
  death anniversary on the handset and the next sync pull the local row says false, and
  `Person.isSolemn` read that one flag. Reachable since M1. No test caught it because every fixture
  built a person the way the server returns one, with the flag already set.
- `cb5919d` — the server derived a wish's sentiment from `person.isDeceased` **before**
  `recomputeMany` had set it, so a batch carrying a new death anniversary and a birthday wish for
  the same person stored the wish as CELEBRATORY, permanently, and the next pull pushed that wrong
  answer down over the phone's correct one.

Both are the same shape: **derived state read one step too early.** M6 reads that same flag to pick
a theme family, and its failure mode is a picture of balloons on a remembrance — worse than a word,
because a picture is what gets sent and seen.

So: read the sentiment the server derived, after the derivation has run, from the one
implementation that already exists. Do not add a third. Do not read `is_deceased` directly. And
build at least one test that constructs the stale window on purpose — a person whose death
anniversary and whose poster request arrive together — because no fixture in this repo builds that
shape on its own, which is precisely why it survived twice.

---

## Before you run this

Dipen must answer, or knowingly defer:

1. **TBD-5** — visual identity and poster art direction. Blocks what the themes look like, not
   whether the pipeline works.
2. **The OpenAI key** for `gpt-image-1`, in `~/.secrets/` and Railway's env, never in git.
3. **Moderation scope** — both surfaces in M6, or posters now and wishes in M7 with rule 7
   recorded as unmet for a second milestone.
4. **Poster rate-limit ceilings** and whether the ₹200 daily cap stands once images are billed per
   image rather than per token.

Items 1 and 2 block different things. Say which of the ten definition-of-done items each one
blocks before you start, so the blocked set is known on day one rather than discovered on day six.

### Two things M5 left open that M6 must not absorb silently

Neither of these is M6 scope. Both are listed here because the way an open item disappears is by
being quietly inherited, and `MILESTONE_CHECKLIST.md` §F exists to stop exactly that.

- **§10 rule 7's deferral is undecided**, as above. Not "handed to M6" — offered, twice, with no
  answer.
- **M5's definition-of-done item 4 is not done, and it is not deferred either.** It is TBD-4's: a
  real remembrance draft, recorded verbatim and read by Dipen. There is no output to hand over
  because there was never a key, so nothing was produced to review. M6 does not inherit this and
  must not quietly close it; it stays M5's open item until a model writes a solemn draft and a
  person reads it.

---

## After M6

M7 is monetisation, and it is the first milestone that can answer TBD-3 — re-check the credit
allowance against the **measured** cache-hit rate, which is a number only M6 can produce. Leave it
where M7 can find it.
