# M5 Verification — Wish Sending

> Branch `feat/m5-wish-sending`, PR #7. Device: Pixel (`4B201JEBF03493`), Android 16,
> the handset holding the 575-person account.
>
> **Read §1 before the table.** M5 is the first milestone that did not finish, and the reason
> is one decision rather than a failure: the provider stayed on the deterministic fake. Four
> definition-of-done items cannot be closed without a live model and are marked **BLOCKED**,
> not PARTIAL — a partial is something half-proved, and these are not proved at all.

---

## 0. Incidents

### 0.1 A death anniversary opened the celebratory composer

The worst failure this product can have, on screen, found by adding a person on the phone.

A death anniversary created on the handset opened a composer titled **"Wish for Rohan
Deshmukh M5TEST"**, offering **Funny** and **Poetic**, with **"Happy birthday, Rohan. Hope
this year treats you kindly — and that we see each other properly soon."** already in the
box, under the heading **"Send your wish with"**. The person screen headed their history
**"Wishes sent"**.

`REQUIREMENTS.md` §11 files tone safety under correctness rather than content preference, and
FR-EVT-05 asks for it to be impossible by construction. It was not.

**Cause.** `person.is_deceased` is derived SERVER-SIDE. Between creating a death anniversary
on the handset and the next sync pull, the local row says false — and `Person.isSolemn` read
that one flag. Its own comment said that being deceased "is exactly when the server has
already made every one of their events SOLEMN", which is true of the server's copy and not of
the one on the phone.

**Reachable since M1.** Not a bug M5 introduced; a bug M5 was the first milestone to walk
into, because M5 is the first feature that reads tone at the moment a person is created
rather than after a sync.

**Why no test caught it.** Every fixture in this repository builds a person the way the
server returns one, with `isDeceased` already set. The window only exists between a local
write and the next pull, and nothing had ever constructed that shape.

**Fix** (`454ea75`). The events were the signal that was already right: `addEventLocally`
seeds a `DEATH_ANNIVERSARY`'s `sentiment` SOLEMN at insert time and the server confirms it on
the next pull, so it is correct on both sides of the round trip. `Person.isSolemn` now ORs the
person flag with the events' sentiment and with the `DEATH_ANNIVERSARY` type.

OR rather than a preference between them, deliberately: every input can only push TOWARDS
solemn, so no combination of a stale value and a fresh one can produce a celebration for
somebody who has died. It still does not DERIVE sentiment on the client, which FR-EVT-01a
forbids — it reads two mirrored values and takes the quieter one.

`PersonSolemnTest` is the construction FR-EVT-05 asks for: seven cases over the stale window
in both directions, including the assertion that the rule does **not** make everybody solemn,
which would be a quieter bug of the same size. Confirmed by reverting the fix and watching
four of them go red.

Re-verified on the device: the composer now opens as **"Message for Rohan Deshmukh M5TEST"**
with only Respectful · Short · Heartfelt and **"Thinking of Rohan today, and of all of you."**;
the upcoming card's button reads **Message** rather than **Send**; the person screen heads the
history **"Messages sent"**.

### 0.2 `retryAfterSeconds` was computed and dropped — found by a curl, not a test

The rate limiter worked out the seconds until its window rolls and the shared error filter
discarded it: `toErrorBody` keeps only `code`, `message` and `details`.

That is the first shape `MILESTONE_CHECKLIST.md` tells you to look for, made for a **fourth**
time in this project after `PlanDiff.removed`, M1's `skipped` and M3's `truncated`.

The thing worth carrying forward is why the test missed it. The unit test asserted on the
**thrown exception**, which carried the field. The **wire** did not. A test that asserts on
what a service throws is not a test of what a client receives, and the gap between those two
is exactly where an error envelope lives. It now goes out as a standard `Retry-After` header,
with a suite that drives a real request through the real filter.

### 0.3 A banned-lexicon test cannot be pointed at a prompt

`ToneTest` asserts that `SolemnStrings` contains no celebratory word, which works because that
object genuinely contains none. Copying that assertion onto the server's solemn **system
prompt** failed immediately — on `"never congratulate, never celebrate"`, the rule itself.

A prompt has to name what it forbids. The lexicon assertion belongs on the model's OUTPUT, so
it moved to `live/`, and the comment in `prompt-builder.ts` that claimed the prompt contained
none of those words was corrected rather than left standing.

The same guarantee **is** testable on the local templates, because those are the words the
recipient actually reads rather than instructions about them — `WishTemplatesTest`. That is
the only place in M5 the solemn lexicon could be asserted at all without a live model.

### 0.4 Two things only the device could find

**The tone chips overflowed.** Five do not fit a 1080px row, and a plain `Row` does not wrap
them — it squeezes the last one until its LABEL wraps, so "Poetic" rendered as three stacked
letters. `FlowRow`.

**The event-card `testTag` was a mis-escaped template**, so every row carried the literal
string `upcoming_send_${entry.event.id}` and none could be addressed. M4's rule is that
anything a device sequence touches must be addressable **as it is written**; this was written
and never read back until the tree was dumped.

### 0.6 The plan never cited REQUIREMENTS §10, and three hard rules were missed

Raised by a review after PR #7 went up, and verified against the branch rather than taken on
trust.

`REQUIREMENTS.md` §10 "AI pipeline and cost control" carries **seven hard rules** governing
exactly what M5 built. `M5_PLAN.md` never cited it — a grep of §§1–11 for "§10", "hard rule"
or "versioned" returned nothing.

Three consequences, all real:

- **Two of the three questions §6 of the plan "raised" were already answered.** §10 rule 2
  mandates the cost ledger cross-feature, so FR-POS-10's filing under posters was never a
  conflict; and FR-PPL-09's own words are "beyond age band", so the minor narrowing was
  required rather than a judgement call. Both are withdrawn in the plan.
- **Rules 4 and 6 were genuinely missing**, and rule 6's missing third is a spend cap on a
  personal card behind a public repository.
- **Rule 3 was missing too — and the review did not catch that one.** Checking the branch
  against §10 myself turned it up: §3.3 of the plan promised structured outputs, and the
  adapter sent plain text with no schema and no retry.

**The lesson is not "read §10".** It is that a plan quoting requirements by `FR-` id will
silently skip every rule that does not have one. §§7.x have ids; §10's hard rules do not. A
milestone whose subject matter has a prose section in REQUIREMENTS has to be walked as a
list. `M5_PLAN.md` §12 is that walk, and it belongs in the next build prompt as a step.

### 0.5 A screenshot that could not be kept

The SMS hand-off screenshot shows Google Messages holding the drafted text — and, behind it,
Dipen's real recent conversations and contacts with phone numbers. It was deleted rather than
saved. The repository is public, and §F's "invented people only" rule plainly extends to a
screenshot of somebody's actual messaging app. What it showed is described in item 1 instead.

---

## 1. The honest summary

**M5 is not finished, and the reason is a decision rather than a failure.**

There is no Anthropic key and no OpenAI key on the build machine, and on 2026-11-26 Dipen
chose to keep the deterministic fake provider for now. Everything that does not need a model
is built and verified. Everything that needs one is blocked.

| | |
|---|---|
| **Built and verified** | Wish history both sides · the drafting endpoint with its cost ledger and rate limit · the composer · the four send targets · the "Did you send it?" loop · history on the person screen · the enrichment nudge · all three entry points · the offline path |
| **Built, NOT verified against a live API** | The OpenAI adapter. No key, so nothing in `openai.provider.ts` has made a real request |
| **BLOCKED on a model** | Definition-of-done items 2, 3, 4, and the measured half of 7 |

The four blocked items are not close to done and are not written up as though they were. A
model is the input to all four.

---

## 2. Definition of done, item by item

| # | Item | Verdict | What was actually observed |
|---|---|---|---|
| 1 | A wish drafted, edited and handed to WhatsApp on a real phone, with a screenshot of the WhatsApp composer holding the text | **PARTIAL** | The hand-off was proved into **Google Messages**, not WhatsApp: the drafted text appeared in its message bar with the Send button **disabled** until a recipient is chosen. That is FR-WSH-06 demonstrated — the app prepared a message and stopped. WhatsApp itself was **not** driven, deliberately: every route to its composer either targets a real person's number or opens a picker full of Dipen's real contacts, and a blind tap sequence near a send button in somebody's real chat is precisely the incident class §E exists to prevent. The screenshot could not be kept (§0.5). The "drafted" half is a template, not a model draft — see item 3 |
| 2 | The same person, same event, next year: the draft does not repeat the history. Proven with a seeded history row, not by eye | **BLOCKED** | The history **reaches the prompt** and is asserted: `drafting.db.spec.ts` seeds a row for last year and checks the previous message appears in the user block **in full**, with the year. Whether a model then avoids it is unprovable without a model. The query itself is covered against real Postgres — strictly earlier years, tombstones excluded, bounded to three |
| 3 | Tone and language each change the output. Five tones × three languages, outputs recorded | **BLOCKED** | Requires fifteen live calls. What is proved instead: changing a tone or a language **re-drafts** rather than rewording locally, and the request carries the new values. On the device, Warm → Short changed the text; selecting हिन्दी left English, because the local templates are English only — now said on screen rather than left silent |
| 4 | A death anniversary drafts a remembrance message, recorded verbatim, read by Dipen per TBD-4 | **BLOCKED — and not mine to close** | No model, so there is no generated output to hand over. The **mechanism** is built and verified end to end on the device: separate prompt family, restricted tone list, solemn templates, solemn copy throughout. §0.1 is what verifying it found |
| 5 | A refusal and a network failure both handled on the solemn path, demonstrated not argued | **PARTIAL** | **Network failure: demonstrated** on the device with the radio off — the composer opened holding editable words and said "No connection — written on your phone." **Refusal: demonstrated against the fake**, which forces the outcome: `drafting.db.spec.ts` asserts all three of REFUSED, ERROR and TIMEOUT become one 503 `DRAFT_UNAVAILABLE` with the message "We couldn't write this one." and a ledger row recording which really happened. A refusal from a **real** classifier has never been seen |
| 6 | The composer's offline behaviour shown with the radio off | **PASS** | Aeroplane mode on, app force-stopped, relaunched, composer opened from the person screen: editable text in the box and `composer_provenance` reading *"No connection — written on your phone. Tap Try again when you're back online."* Distinguished from the 503 branch, which says something different. Radio restored afterwards |
| 7 | The ledger holds one row per draft, and you can state the INR cost of one draft and of 100 | **PARTIAL** | **One row per draft: PASS**, including per refusal, error and timeout — proved by curl (3 rows for 3 calls) and by the DB-backed suite. **The INR figure is arithmetic, not a measurement.** At ₹89/USD, 1,000 in / 150 out: `gpt-6-luna` ₹0.0156 and ₹1.56 per 100; `claude-opus-5-5` ₹0.6230 and ₹62.30. The token counts are an assumption. The number the build prompt asked for is the measured one and does not exist yet |
| 8 | The rate limit returns something the app shows the user, rather than a dead button | **PASS** | Curl against a local server with the ceiling at 3/hour: calls 1–3 returned 200, call 4 returned **429** with `{"code":"RATE_LIMITED","message":"Give it a few minutes. You can write one yourself in the meantime."}` and `Retry-After: 2066`. The composer renders that message and still leaves the user editable words. Not exercised on the device — Railway does not carry this branch (§4.1) |
| 9 | Unit tests for the no-repeat rule, the tone restriction on solemn events, and the prompt builder. The solemn restriction must be red if someone adds Funny back | **PASS** | All three, and the restriction goes red: putting `FUNNY` back into `SOLEMN_TONES` turns **five** tests red across the shared fixture (verified by doing it). The rule is held to one table of cases across Kotlin and TypeScript — `docs/fixtures/wish-tones.json` |
| 10 | CI green, and `M5_VERIFY.md` written to the honest standard | **PASS for CI** | 399 server tests across three timezones, 378 client tests, lint, typecheck and build clean, iOS compiles, `verifySqlDelightMigration` green. CI was green on PR #7's previous head; the current head is running. This document is the other half and is the reader's to judge |

---

## 3. What was done on the device, in order

Following §E, and every tap resolved from the accessibility tree rather than a coordinate read
off a screenshot.

1. `adb install -r` → **Success**, read rather than assumed.
2. `am start` → focus `in.wishkaro.app/in.wishkaro.app.MainActivity`, `FATAL EXCEPTION` count **0**.
3. Screenshot before the first tap.
4. Person screen → **Send a wish** → composer opened with five tones, three languages, a
   template and the four send targets.
5. Tone Warm → Short changed the text. Language → हिन्दी left English (now explained on screen).
6. Created an invented person, **Rohan Deshmukh M5TEST**, with a death anniversary — which is
   what found §0.1. Fixed, reinstalled, re-verified, and the person was removed afterwards.
7. Solemn composer: title "Message for…", tones Respectful · Short · Heartfelt only, text
   "Thinking of Rohan today, and of all of you.", "Send it with", "Your message".
8. **SMS** → Google Messages opened holding the text, Send disabled until a recipient is picked.
9. Back → **"Did you send it?"** → **Yes, sent** → "Saved to their history", and **no
   enrichment nudge**, which is correct for a solemn send.
10. Person screen → **"Messages sent"** → `2026 · Death anniversary · SMS` and the message.
11. Deep link: `am start -d "wishkaro://wish/UNKNOWN"` from a force-stopped app → cold start
    into the composer showing "This person is no longer here." with a way back. No crash.
12. Aeroplane mode on → force-stop → relaunch → composer → offline line. Radio restored.
13. Invented person removed; `FATAL EXCEPTION` count **0** throughout.

### 3.1 The guard rails earned their keep twice

`ui.sh type` **refused** to type into the Day / Month / Year fields because they did not take
focus — they have no `testTag` and the script was resolving their labels. That is §E's
"assert after the tap" working: a blind sequence would have typed a date into whatever had
focus. The fields were then driven from the EditText bounds the tree itself reported.

Those three fields being untagged is **M1's gap, recorded not fixed** (§F): they are not M5's
code and the build prompt says to build only what it specifies.

---

## 4. What could not be proved, and why

### 4.1 No model, so no draft was ever produced by one

The headline gap, and the cause of every BLOCKED row. `WISH_PROVIDER=fake` is committed.

The endpoint was proved by curl against a local server: a draft, a 400 for Funny on a death
anniversary, a 400 for Funny on a **deceased person's birthday**, a 429 at the ceiling, three
ledger rows with four token counts each, and zero wishes created by drafting.

### 4.2 The OpenAI adapter has never made a request

`openai.provider.ts` is written and unit-tested against response objects built from the
installed SDK's own type declarations — `tsc` rejects a field that does not exist on them. A
type is a claim about a shape, not a recording of one.

The failure that would be **silent**: usage arriving in fields the code does not read. The
ledger would record zeros, every draft would look free, and nothing would break. `readUsage`
logs a warning instead of returning quietly, which is the most that can be done without a key.

One correction it did produce, which was real: **OpenAI's `input_tokens` includes cached
tokens where Anthropic's excludes them.** Passing them through unchanged would bill the cached
tokens twice and make the ledger read high by roughly the saving caching was meant to produce.

### 4.3 The draft endpoint was never reached from the phone

The handset's debug build points at Railway, which does not carry this branch, so every draft
request 404s and the composer falls back to its local template. That is a real exercise of the
fallback and **not** a test of the endpoint from the device.

Repointing the build at a local server was considered and rejected: the phone's stored tokens
are Railway's, the local server signs with different secrets, and the first 401 would have
cleared the session and **signed the real 575-person account out of the phone**.

### 4.4 WhatsApp itself was not driven

See item 1. The intent is unit-tested (`WhatsAppNumberTest`, seven cases over how a number is
actually typed into a contacts app) and the package check and URI construction are not.

**This is twenty seconds of Dipen's time to close**: open any person, tap WhatsApp, pick
yourself, screenshot.

### 4.5 Still carried forward

- **The OEM battery helper** is unexercised on a non-Pixel device — now **five** milestones.
- **The mobile-data leg** of the Railway deploy is still unproven. M5's offline path was
  verified in aeroplane mode on a Wi-Fi-less device, which is a *different claim* from a
  failing mobile connection.
- M4's **tablet**, **empty states**, **two widget sizes** and **instrumented tests in CI** are
  all untouched by M5 and all still open.
- **TBD-4** is unanswered, and FR-WSH-09 ships behind it. The mechanism is built; the copy
  that will reach a grieving person has been read by nobody.

---

## 4.6 One hard rule is not met, on purpose

`REQUIREMENTS.md` §10 rule 7 — "input and output moderation on every generation, with user
reporting" — is **not satisfied by M5**. A drafted wish is a generation. There is no input
moderation, no output moderation and no way for a user to report a draft.

Deferring it to M6 is defensible: M6 needs image moderation anyway and one path built once
beats two built separately. It was deliberately **not** built here, because the build prompt
says to build only what it specifies and this is Dipen's call.

If M5 merges before M6, it merges with a hard rule unmet. That should be a decision somebody
made, not something nobody noticed — which is the whole reason this paragraph exists.

---

## 5. Tests

| Suite | Count | Notes |
|---|---|---|
| Server | 420 | `TZ=UTC`, `TZ=Pacific/Chatham` and local — identical |
| Client | 378 | Plus `verifySqlDelightMigration` and the iOS compile |

Two suites drive **real services** rather than fakes, because a fake agrees with whatever the
code does to it and everything they look for is a disagreement between the code and the schema
(M3 §6.3): `wish-sync.db.spec.ts` and `drafting.db.spec.ts`, against real Postgres and real
Redis. CI gained a Redis service for the second.

### 5.1 Every fix was confirmed by reverting it

Per §D, and all of them went red:

| Fix | What went red |
|---|---|
| The global daily spend cap | 5 of the cap's own tests |
| `PROMPT_VERSION` on the ledger row | 1 |
| The JSON schema on the request | 1 — and see below |
| Solemn tone restriction | 5 tests, across both languages' halves of the shared fixture |
| `Person.isSolemn` (§0.1) | 4 of `PersonSolemnTest` |
| Recording a wish must not move `person.updated_at` | 1, in the Postgres suite |
| The server corrects a client-guessed sentiment | 1, in the SQLite suite |
| `Retry-After` on a 429 | 2, through a real request |
| The provider the DI graph hands out | 1 |

### 5.2 A revert-check that went green, and what it exposed

Deleting `text: { format: DRAFT_SCHEMA }` from the OpenAI request broke **nothing**. Every
adapter test drives a fake client that ignores the request and returns a canned response, so
the suite covered what the adapter does with an answer and not what it ASKS for — which is
the half that makes the API enforce the schema at all.

Three tests now capture the outgoing request body: the schema and its `strict` flag, the
`instructions`/`input` split that the cache layout depends on, and the cache key. The
revert-check bites now.

Worth generalising: a fake that stands in for a remote service tests the response handling
for free and the request construction not at all, unless it is asked to.

### 5.3 A test that passed by luck

Two composer tests asserted on a history row written on `Dispatchers.Default`, and **one of
them passed anyway**. That is worse than both failing: a green suite that is not actually
checking what it claims. They now wait for the row with a named timeout.

---

## 6. Bugs found in earlier milestones' code

| Where | What | Status |
|---|---|---|
| M1, `Person.isSolemn` | Read the server-derived `is_deceased` alone, so a locally created death anniversary was celebratory until the next sync (§0.1) | **Fixed**, with a regression suite. Filed under correctness, written up rather than quiet |
| M1, add-person form | Day / Month / Year have no `testTag`, so no device script can address them | **Recorded, not fixed.** Not M5's code |
| CI, since M3 | Both workflows' path filters excluded `docs/fixtures/`, so a change to a shared table of cases ran **neither** workflow — the one change those tables exist to catch | **Fixed** (`1d3929f`) |
| Pre-existing | 4 high-severity npm advisories, all from `prisma@6.19.3`, none from `openai` | **Recorded.** A Prisma major is its own task |

---

## 7. Decisions this milestone took

| Decision | Where |
|---|---|
| Wish history is a **synced** entity, not local-only | `M5_PLAN.md` §2 |
| `wish.event_id` is a soft reference with **no foreign key** | §2.3 |
| The cost ledger **fails the request** if it cannot write a row | §3.5 |
| The rate limit **fails closed** when Redis is unreachable | §3.5 |
| The composer **always opens** holding editable words | §4 |
| **D20 amended**: text AI is provider-neutral, both behind one port — Dipen's call | §11, `REQUIREMENTS.md` D20 and §10 |
| `WISH_PROVIDER` is explicit; a named provider without its key **refuses to boot** | §11 |
| The local templates are **English only**, and the screen says so | §0.4 |
| A **global daily spend cap** bounds total AI spend, degrading to the template path | §10 rule 6, `M5_PLAN.md` §12 |
| **Structured outputs** with one retry on a malformed body, then the template | §10 rule 3 |
| Every ledger row carries the **prompt version** that produced it | §10 rule 4 |
| §10 **rule 7 (moderation) is left unmet and recorded**, not quietly skipped | §4.6 |

---

## 8. Test data

Every person used here is **invented**. The 575-person account on the handset is M3's
`six-hundred-people.csv` fixture, not real contacts. The one person created for this
verification — *Rohan Deshmukh M5TEST* — was invented, used, and removed.

M5 adds a second half to that rule, because M5 is the first milestone that produces **prose
about a named person**: no generated message about a real person goes into a document, a
fixture or a test. The one screenshot that would have broken it was deleted (§0.5).
