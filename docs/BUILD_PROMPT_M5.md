# M5 Build Prompt — Wish Sending

> Paste the block below into a **fresh Claude Code session** opened at the repo root.
>
> M4 is merged (`596b50c`). `main` carries people and events CRUD, a reminder engine with a
> local alarm fallback, offline-first sync with a chunked push, import from contacts and
> spreadsheets, four views, an adaptive layout and a home-screen widget. M5 is the first
> milestone where the app writes words a user will send to another human — and the first
> where something outside this codebase costs money every time it runs.

---

## The prompt

```text
You are building M5 of WishKaro, a Compose Multiplatform + NestJS app. M0 to M4 are merged to
main and working: Google sign-in, people and events CRUD, deceased/solemn tone handling,
offline-first sync with a chunked outbox, a reminder ladder with local alarm fallback and
quiet hours, import from device contacts and CSV/XLSX, four views on a Navigation 3 back stack
that survives process death, an adaptive two-pane layout, and a Glance widget. Read
docs/REQUIREMENTS.md for product context, docs/M0_PLAN.md through docs/M4_PLAN.md for the
decisions those milestones locked in, and docs/MILESTONE_CHECKLIST.md, which is the standing
process here. Work to that checklist: every item in it was bought by a specific bug, and the
milestone that paid for each is named beside it. Sections C, D, E and F apply to this milestone
as written; if you decide an item does not apply, say which and why rather than passing over
it. Sections G and H are DIPEN'S, not yours — you do not review your own PR as the final word
and you never merge. Build ONLY what this prompt specifies.

## What M5 is

The app saying something. When M5 is done, this must be true on a REAL Android phone:

- Tapping **Send wish** from a notification, an event card or a person screen opens a composer
  already holding a draft that reads like it was written by someone who knows the person.
- The draft is conditioned on who they are and on what was sent to them in previous years, and
  it does not repeat last year's message.
- Tone and language are the user's to change, and changing either produces a genuinely
  different message, not the same one reworded.
- For a deceased person the composer is a different composer: different prompt, different tone
  list, no Funny, no Poetic, and copy that never congratulates.
- The user edits freely, hands the message to WhatsApp, and the app asks once whether it was
  sent.
- Every draft costs a measured amount of money, and that number is in a table you can query.

## Decide this before you write code

Four things, and the first is the floor.

### 1. There is no wish history. Anywhere.

`server/prisma/schema.prisma` has eight models — User, Person, PersonTrait, Event,
EventOccurrence, Reminder, Device, RefreshToken — and none of them is a wish.
`composeApp/src/commonMain/sqldelight/wishkaro/db/` has eight tables and none of them is a
wish either. Check both before you take my word for it.

Four requirements already depend on it: FR-WSH-02 conditions the draft on "what was sent in
previous years (must not repeat)", FR-WSH-07 records it after the handoff, FR-PPL-07 shows it
on the person screen, and FR-HIS-05 hangs an enrichment nudge off it. So history is not a
feature M5 adds at the end — it is the input to the thing M5 is about, and a composer built
first will be built against a history that does not exist yet.

Decide its shape first, and decide it as a SYNCED entity or deliberately not one. Everything
that syncs in this app does the same three things: last-write-wins per record on `updatedAt`,
soft delete as a tombstone, and a monotonic per-entity cursor. A wish history that does not do
those is the one entity in the app that behaves differently, and the next person to touch sync
will not know. If you decide it should be local-only, say so and say why — that is a real
answer, and "the user's own record of what they said" is an argument for it.

### 2. This is the first external AI provider, and the repo is public.

There is no LLM client in this codebase. No `@anthropic-ai/sdk`, no key, no cost ledger, no
rate limit. M5 introduces all four, and three of them have a way of being added badly.

- **The key never reaches the client.** The phone calls our server; our server calls Anthropic.
  A key in the APK is a key anyone can extract, and this repo is public on top of that.
- **The key lives outside the repo**, the way the Firebase service account already does
  (`~/.secrets/`, and Railway's own env for the deploy). Nothing new goes in git.
- **A cost ledger row per call**: provider, model, input tokens, output tokens, INR, user,
  feature. FR-POS-10 calls this non-negotiable for posters, and M5 is where it starts, because
  wish text is the first thing in this app that costs money per use. Without it you cannot
  answer "what does a user cost per year", which is the question M7 is built on.
- **A fair-use rate limit**, per FR-WSH-08. Text drafting is free to the user; it is not free
  to us, and "free and unmetered" with no ceiling is an invitation.

Model: default to `claude-opus-5-5` and set `output_config.effort` explicitly — its default is
`medium`, and a two-sentence birthday greeting does not need more than `low`. Do NOT quietly
pick a cheaper model to save money: measure the real cost with the ledger first and bring me
the number. Sonnet 5.5 and Haiku 5.5 exist and are much cheaper per token; whether a greeting
is a job for one of them is a product decision with a quality cost attached, and it is mine.

Four API facts that will cost you a day each if you learn them from a 400:

- `thinking: {type: "enabled", budget_tokens: N}` is rejected on this model. Adaptive thinking
  or nothing, with `effort` as the control.
- Forced `tool_choice` (`any` / `tool`) is rejected. If a draft must come back as structured
  JSON, use structured outputs (`output_config.format`), not a forced tool call.
- Assistant prefill is rejected. Shape the output with the system prompt or with structured
  outputs.
- `stop_reason: "refusal"` arrives as an HTTP 200. See §4.

Prompt caching is worth having from the start and nearly free to design in: the system prompt
and the tone/language templates are stable, the person's details are not, so stable content
goes first and the volatile part goes after the last cache breakpoint. Verify it is actually
working by reading `usage.cache_read_input_tokens` rather than assuming — a zero there across
repeated calls means something in the prefix is varying.

### 3. The composer is the first screen in this app that cannot work offline.

Everything so far is local-first by construction: add a person on a plane, the sync catches up
later. A draft needs a round trip to a model. So decide, before building it, what the composer
does with no network — and the answer has to be better than a spinner, because the user opened
it by tapping a reminder, which means they are about to walk into a room.

The options are a cached draft, a non-AI template, a plain empty composer they can type into,
or refusing to open. One of those is right and I do not want to be told after it is built.
Whichever it is, FR-VIEW-05 still applies: never a blank screen that leads nowhere.

### 4. The solemn path is the one the model may refuse.

FR-WSH-09 says the solemn composer gets a separate, hand-reviewed prompt template with its own
tests, and that a tone failure there is the kind of bug that cannot ship. Two ways it fails:

The first is the one the requirement names — the model writes something warm and celebratory
about a death anniversary. That is what the separate template and the restricted tone list are
for, and that is what the tests must actually assert, on real generated output and not on a
fake.

The second is newer and is not in REQUIREMENTS.md: the API can decline. `stop_reason:
"refusal"` comes back with HTTP 200 and a category, so code that reads `content` without
checking `stop_reason` first will show the user an empty message or a crash. A remembrance
prompt about a dead relative is exactly the shape that can trip a safety classifier. Turn on
server-side fallbacks (`betas: ["server-side-fallback-2026-07-01"]` with `fallbacks:
"default"`) and decide what the user sees if a draft still cannot be produced — on the screen
where they are writing about somebody who died, which is the worst place in this app to show a
raw error.

## What already exists — do not rebuild it

| Thing | Where | Note |
|---|---|---|
| SOLEMN/CELEBRATORY tone | `domain/`, `toneFor(person)` | Every string on the person screen already goes through it. The composer joins that, it does not invent a second one |
| `Person`, `PersonEvent`, `PersonTrait` | client and server | Relation, hobbies, likes, budget band — the conditioning FR-WSH-02 asks for is mostly already on the record |
| Offline-first sync | `sync` module, `SyncPaging.kt` | Chunked push, LWW, tombstones, cursor. A new synced entity joins this; it does not get its own mechanism |
| Notification actions | `push/ReminderPresenter.kt` | All four buttons already exist and are placed by sentiment and offset. Snooze and Mark done are wired for real; **Send wish** (and, on a solemn reminder, **Send message** — M2 already uses the different label, do not regress it) currently calls `openIntent`, the same `wishkaro://person/<id>` deep link, so it lands on the person screen. Making those two land on the composer is M5's entry point, and the labels and placement are already decided |
| Navigation 3 back stack | `ui/nav/` | Add destinations to `Screen` AND to `WISHKARO_SAVED_STATE`. `NavKeyRegistrationTest` fails the build if you forget, which is deliberate |
| Two-pane opt-in | `ui/adaptive/TwoPaneScene.kt` | Opt-in by metadata. FR-VIEW-08 says the composer stays SINGLE-PANE whatever the width — so it opts in to nothing, and that is already how it works |
| `AdaptiveDialog` | `ui/adaptive/` | Use it for any confirmation. Do not pass it a scrolling Column as `text` and then add your own scroll — that crashed the app in M4 |

## Scope

### Composer (FR-WSH-01..06)

Entry from a notification action, an event card and the person screen. A draft on open, tone
and language selectors that re-draft, Regenerate, free editing, and the four send targets —
WhatsApp first, then SMS, share sheet, copy.

Sending is never automatic (FR-WSH-06). The app prepares a message and hands it over; the user
presses send in their own app. Nothing in M5 sends anything on anybody's behalf, and no code
path should be able to.

### Solemn composer (FR-WSH-09)

A separate template family, Respectful · Short · Heartfelt, no Funny, no Poetic. Its own tests.
See §4 above.

### Wish history (FR-WSH-07, FR-PPL-07, FR-HIS-05)

"Did you send it?" once, dismissible. History on the person screen. One enrichment nudge after
a send, never more than one per interaction.

### Server (FR-WSH-02, FR-WSH-08, FR-POS-10)

The drafting endpoint, the conditioning, the no-repeat rule against previous years, the rate
limit, and the cost ledger.

## Explicitly NOT in M5

Posters and any image generation (M6). Gift suggestions and affiliate links (M8). Billing,
credits and the paywall (M7). Video (v1.1). Do not build a credit check "ready for M7" — FR-WSH-08
says text drafting is free, and a metered path that charges nothing is a path nobody tests.

## Definition of done

1. A wish drafted, edited and handed to WhatsApp on a real phone, with a screenshot of the
   WhatsApp composer holding the text.
2. The same person, same event, next year: the draft does not repeat what the history holds.
   Proven with a seeded history row, not by eye.
3. Tone and language each change the output. Five tones × three languages, with the outputs
   recorded, so a reviewer can see they differ.
4. A death anniversary drafts a remembrance message. The output recorded verbatim, read by
   Dipen before merge, per TBD-4 — this is the one definition-of-done item that is not yours
   to close.
5. A refusal and a network failure are both handled on the solemn path, demonstrated, not
   argued.
6. The composer's offline behaviour, whatever §3 decided, shown with the radio off.
7. The cost ledger holds one row per draft, and you can state the INR cost of a single draft
   and of 100.
8. The rate limit returns something the app shows the user, rather than a dead button.
9. Unit tests for the no-repeat rule, the tone restriction on solemn events, and the prompt
   builder. The solemn restriction must be red if someone adds Funny back.
10. CI green, and docs/M5_VERIFY.md written to the honest standard — Pass, PARTIAL and what
    each partial means, the way M3_VERIFY and M4_VERIFY do it.

## Engineering rules

Everything from M0 to M4 still applies, and docs/MILESTONE_CHECKLIST.md is the list. In
addition:

- The repo is PUBLIC. No keys, no real people, no real phone numbers in anything committed —
  and now also no generated message about a real person in a doc.
- The API key is server-side only and lives outside the repo.
- Every model call writes a ledger row. A call that cannot write one is a bug, not an
  optimisation.
- Tone comes from the person, never from the event type. A deceased person's birthday is a
  SOLEMN event whose type is still BIRTHDAY, and M1 already got this wrong once.
- A test that asserts on a fake model's output tests the fake. Where the thing under test is
  the prompt, the test has to see real output.
- Conventional commits, small and reviewable. Branch off main, PR at the end. main is
  merge-only and merging is Dipen's call, not yours.

## How to work

1. Write docs/M5_PLAN.md — the history decision first, then the provider boundary, then the
   offline answer, then your implementation order. Show it to me before writing code.
2. Wish history, client and server, with its sync behaviour settled. Everything else sits on it.
3. The drafting endpoint with the ledger and the rate limit, provable with a curl before any
   UI exists.
4. The prompt builder and its tests, including the solemn restriction.
5. The composer, then the send targets, then the "Did you send it?" loop.
6. The solemn path, end to end, on a device.
7. Stop and tell me when it works. Do not continue into M6.

If any requirement here conflicts with docs/REQUIREMENTS.md, stop and ask — do not silently
pick one. M0 to M4 each hit at least one and raising it was the right call every time.
```

---

## Before you run this

| Check | Why |
|---|---|
| PR #6 merged (`596b50c`), `main` green | M5 branches from it |
| An Anthropic API key, in `~/.secrets/` and in Railway's env | M5 cannot be built without one, and it must never be in git |
| A real WhatsApp install on the test phone | Item 1 cannot be checked against a stub, and the intent handoff is the half most likely to be wrong |
| TBD-4 answered, or accepted as open | Item 4 is Dipen's to close. If the art direction is not decided, M5 can still build the mechanism — but it ships behind that review, and the plan should say so |
| The account with 575 people still intact | Item 2 needs real history against a real person, and deleting the account is a deliberate act |
| A non-Pixel phone, if one has appeared | M2's OEM battery helper is now unchecked across four milestones |

## Why history goes first

M2 put delivery reporting first so everything after it was measurable. M3 put the outbox first
because it was the floor everything else stood on. M4's floor was navigation. M5's is wish
history, for the same reason each of those was: it is the input to the feature, not an output
of it. FR-WSH-02's "must not repeat" cannot be built, tested or even demonstrated against a
table that does not exist, and a composer written first will be written as though history were
optional — which is exactly how it will behave a year later, when the second birthday comes
round and nobody is watching.

## The thing most likely to go wrong

The words. Every milestone so far failed in ways a test or a device could catch: a stale flag,
a dropped row, a window one month too narrow. M5 can pass every test in this document and still
produce a message that is subtly wrong for the person it is about — too familiar for a
colleague, too cheerful for a widow, in Hinglish for someone who reads neither.

That is why item 4 is not closeable by the session that writes it, and why FR-WSH-09 asks for a
hand-reviewed template rather than a tested one. The tests can prove Funny is unavailable on a
death anniversary. They cannot prove the remembrance message is one a grieving person would
want to send, and nothing in the checklist will catch it, because the checklist is a list of
bugs and this would not be one.

## After M5

M6 is AI posters: the theme library, the cache, `gpt-image-1` backgrounds, `sharp` compositing,
the credit ledger and moderation. It is the first milestone with a real marginal cost per use
and the first that needs TBD-1 and TBD-3 answered. M5's cost ledger is what makes M6's pricing
a measurement instead of a guess. Write its prompt in this same shape.
