# M6 Verification — AI Posters

> Branch `feat/m6-ai-posters`, cut from `main` at `52e842a`.
>
> **Read §1 before the table.** Three kinds of claim are kept apart here, because M5 was
> right to separate them and because rounding one up is how a milestone closes an item it
> did not finish:
>
> - **PROVED** — I made it happen and watched the result.
> - **READ-VERIFIED** — the code is right as far as reading and types go, and nothing has
>   exercised it. A type is a claim about a shape, not a recording of one.
> - **BLOCKED** — not proved at all, and not partially proved either.

---

## 0. Incidents

### 0.1 The headline ran off both edges of every poster

The first poster I rendered and actually looked at said **"mbering Rohan Deshp"**.

`Layout.textWidth` was declared in all three layouts in `compositor.ts` and **read by
nothing**. The headline was drawn at a fixed `font-size` with `text-anchor="middle"`, so any
name longer than the canvas ran off both sides. At 101px — the no-photo path, which sets the
headline 30% larger — "Remembering Rohan Deshpande" is about 1,450px wide on a 1080px
canvas.

**Why the suite was green.** `compositor.spec.ts` has 476 lines and asserts geometry: the
photo lands in its box, the canvas comes out at the exact format size, the text bands do not
overlap the photo. Not one assertion said the words were inside the picture. The milestone's
central output is an image and nothing was looking at it.

Found by writing the PNG to disk and opening it, which is what the build prompt means by
"verify by doing, not by reading". Indian names are long: "Remembering Venkataraman
Subramanian" is not an edge case, and the format most users will see is the one that
overflows first.

**Fix.** `text-fitting.ts` wraps on whole words first and shrinks only when a single word
cannot be broken — two lines at 78px reads better than one at 38px on a phone at arm's
length.

**And the fix was wrong once, which the new test caught immediately.** The first version let
a line stay slightly wide once it hit a minimum font size, on the reasoning that "a headline
at 30% is not a headline". The pixel-measuring spec failed on *Happy Anniversary,
Priyadarshini Raghunathan* — a name, not a pathological input. Running off the canvas is
strictly worse than being small, so the floor now truncates with an ellipsis.

**A second bug the same measurement found.** LINK (1200×630) is the only landscape format,
so its photo sits beside the text and its text box is a 590px column starting at x=530. With
no photo there is no column — a 1.3× headline set there ran off the right edge while the
whole left half of the canvas sat empty. The typographic template now centres on the canvas.

**The test does not trust the estimate.** `sharp` gives no text metrics, so the width is
estimated; the spec therefore renders the text layer alone and measures the **drawn ink**
with `sharp.trim()`. If the estimate is wrong, the tests fail. 42 cases: three formats × two
photo paths × six long names, Devanagari included.

### 0.2 A privacy assertion that nearly passed for the wrong reason

`photo.db.spec.ts` asserts that EXIF GPS coordinates do not survive the upload. The first
version checked `exif.toString('latin1')` for the substring `GPSLatitude` — and **EXIF tag
names are not stored as text.** The block is binary, keyed by numeric tag, so the
precondition would never have matched and the test would have been green while proving
nothing about the input.

It now builds the same image twice, with and without a GPS IFD, and asserts the first one's
EXIF block is strictly longer. That is what actually shows the coordinates were in there to
begin with.

This is the shape `MILESTONE_CHECKLIST.md` warns about under green revert-checks: a test
that passes because the input never had the thing whose absence is being asserted.

### 0.3 The handset, and what it took to reach it

The Pixel 9a (`4B201JEBF03493`, Android 17) was behind a secure keyguard that no session can
open, and the app was signed out of the local server. Dipen unlocked the phone and tapped
"Continue with Google" himself; authenticating as somebody is not a thing a session does on
their behalf, and the alternative — a debug-only dev-login button — was offered and not
taken, so it is **not** in this branch.

Three things were checked before asking, so the tap was not wasted: the installed APK really
did carry `http://127.0.0.1:3000`, `adb reverse tcp:3000 tcp:3000` was up, and the app's
`default_web_client_id` matched the server's `GOOGLE_CLIENT_ID` exactly. The sign-in matched
a user this local database already held from M0 testing rather than creating one.

**One thing to know for next time.** Reinstalling the APK mid-session signed the app out
again: the refresh token had already rotated across several app restarts, so
`renewAccessToken` cleared the session, which is the documented behaviour and not a bug. It
cost a second sign-in, and the remaining device work was therefore done through instrumented
tests on the handset rather than by tapping through the app a third time — §2 says which
items that affects.

### 0.4 Every share failed, and the failure was silent

The first time Share was tapped on the handset, nothing happened. No crash, no message, no
sheet.

`AndroidMessageSender` had the FileProvider authority written as an **over-escaped Kotlin
interpolation**, so the string passed to `FileProvider.getUriForFile` was the literal
`${'$'}{appContext.packageName}.posters` rather than `in.wishkaro.app.posters`. Every call
threw `IllegalArgumentException: Couldn't find meta-data for provider with authority …`,
which `shareImage` caught and turned into `false` — and `false` means "the other app could
not be opened", which is exactly what the screen then said. **Indistinguishable, from the
user's side, from WhatsApp not being installed.**

Nothing could have caught it but a device. There is no compile-time link between the Kotlin
string and `android:authorities` in the manifest; a JVM test has no merged manifest and no
`ContentProvider` registry, so the only assertion it could make is that two strings this
file wrote agree with each other — the tautology that let it through.

`PosterSharingTest` now asserts on the handset that the authority resolves to a declared,
non-exported, URI-granting provider; that staging returns a `content://` URI another app can
read; and that the **real** `AndroidMessageSender.shareImage` opens a chooser. Reverting the
escape turns two of the five red.

### 0.5 The studio opened with no controls on screen

At STATUS (9:16) the preview was `fillMaxWidth().aspectRatio(ratio)`, making it 1.78× as
tall as it is wide — taller than the viewport on a 20:9 phone. Opening the poster studio
showed the title, the person's name and a full screen of empty gradient. The theme picker,
the size chips and the "Make the poster" button were all below the fold and nothing on
screen suggested they were there.

`PosterPickerFilterTest` did not catch it, and correctly so: `assertExists` passes for a
node that is composed but off-screen. That is the right behaviour for that assertion and the
wrong thing to have relied on. The preview is now capped at 380dp and fitted inside both
bounds, because a 9:16 poster is height-bound and a 1.91:1 one is width-bound and either
rule alone overflows the other case.

---

## 1. The honest summary

The milestone the build prompt asked for is the cheap one, and the cheap one works.

**FR-POS-04's argument held.** The library, the cache and the compositor are the product;
live generation is the edge. Every poster in this verification was produced with
`IMAGE_PROVIDER=fake`, which is to say **no model was called at all**, and the output is a
real poster rather than a placeholder for one. The key that is missing blocks the premium
path and nothing on the common path.

**Definition-of-done item 5 is the one that matters and it is measured.** A second request
for the same key came back `cacheHit=true` in 85ms, wrote no `ai_usage` row, consumed no
Redis rate-limit slot and incremented `hit_count`. That is the number TBD-3, §13's health
metric and FR-MON-03a are all waiting on, and it is the one only M6 can produce.

**The device half ran, and it found two more bugs.** Dipen unlocked the Pixel 9a and signed
in; the whole path then worked from the handset — the solemn picker offering no celebratory
theme for a person whose `is_deceased` is still false, a photo chosen in the app's own
picker and composited unmodified, a report reaching the server, and the share sheet opening
with `poster.png` attached. Getting there cost a FileProvider authority that made every
share fail silently (§0.4) and a preview that hid every control below the fold (§0.5).

**What is not done.** Nothing has reached a live model: no image has been generated, no
classifier has returned a verdict, and no INR figure in this document is a measurement. TBD-5
is open, so every theme is a visible placeholder and nobody has judged whether a poster is
one anybody would want to send — which is the whole of what items 2 and 4 still want.

**§10 rule 7 is structurally met and not actually exercised.** The port, the deterministic
input rules, the stored verdict, the block path and the report endpoint all exist and are
tested, across **both** surfaces — posters and the wish drafting path that shipped in M5.
A classifier has never been called. Those are two different claims and this document does
not merge them.

---

## 2. Definition of done, item by item

| # | Item | Verdict | Evidence |
|---|---|---|---|
| 1 | A celebratory poster, composited with a real photo, sent from a real handset | **PROVED, short of the last tap** | End to end on the Pixel 9a: a photo chosen in the app's own system picker (no permission prompt), uploaded as 1200×1600 JPEG with `photo_key` written, composited upright into the 760px box with the head preserved, headline wrapped beneath it, attribution at the foot — I looked at the PNG. Share then opened the system sheet showing **"Sharing 1 file · poster.png"** with WhatsApp among the targets. **It stops at the sheet by design**: FR-WSH-06 says the app never sends, and every route into WhatsApp's composer targets a real number or a picker of real contacts (`MILESTONE_CHECKLIST.md` §E). The last tap is Dipen's. Getting here required fixing §0.4 |
| 2 | The same for a **solemn** occasion, no celebratory theme reachable, hand-checked by Dipen | **PARTIAL / BLOCKED** | **The filter: PROVED five ways**, all with `is_deceased` still false on the server. On the handset: the person screen read "Send a message" and **"Make a remembrance poster"**, the studio was titled "Remembrance poster", and the picker's view tree held exactly `ph-stillwater`, `ph-dusk`, `ph-lamp` and **no celebratory node**; generating gave `family=SOLEMN, sentiment=SOLEMN` in 207ms. Asha, on the same handset, got exactly the four celebratory swatches and none of the solemn — so the gate is not answering SOLEMN to everything. Plus the server's 400 `THEME_NOT_ALLOWED`, the DB-backed pipeline test and the instrumented picker test. **The hand-check: BLOCKED on TBD-5** — the themes are placeholders and nobody has judged the art |
| 3 | All three formats produced and inspected at full size | **PROVED** | 1080×1920, 1080×1080 and 1200×630 produced through the API and read back off disk at those exact dimensions. I opened the STATUS and LINK ones; §0.1 is what that found |
| 4 | The no-photo path produces something good enough to send | **PARTIAL / BLOCKED** | **Mechanism: PROVED.** The typographic template centres, wraps, keeps a rule above the headline and carries the attribution — and after §0.1 it is legible, which it was not. **"Good enough": BLOCKED on TBD-5.** That sentence is a judgement and the artwork is a placeholder gradient by design |
| 5 | A cache **hit** measured: no model call, no ledger row, no rate-limit slot | **PROVED** | By curl: miss 136ms, hit 85ms, `cacheHit=true`, `ai_usage` count unchanged at 0, no `poster:*` key in Redis, `hit_count` 0→1. And by a DB-backed test with the provider reporting `isLive`, where the miss **does** write a row and **does** take a slot, so the hit's zero means something |
| 6 | A cache **miss** measured end to end, p95 under 15s | **BLOCKED** | A miss *is* a live generation and there is no key. The library miss (render + store + composite) is 136ms, which is a measurement of the cheap path, not of the one the target is about |
| 7 | A ledger row per generation with a non-zero **measured** INR, and image pricing in `pricing.ts` | **PARTIAL / BLOCKED** | **Pricing: PROVED present**, per image and per `(model, quality, size)`. **One row per call: PROVED** — including for a REFUSED image, where the call happened and produced nothing. **Non-zero measured: BLOCKED.** The fake is priced at ₹0 and the published rates in `pricing.ts` are unverified; TBD-1 is open. Arithmetic from a rate table is not a measurement, and M5 was right to refuse to call one the other |
| 8 | The global spend cap observed degrading a poster request to cached themes | **PROVED** | Ledger seeded to ₹250 against a ₹200 cap; the request came back `SUCCEEDED`, `degraded=true`, with a poster, no `ai_usage` row and no rate-limit slot consumed — being capped is our budget, not the user's allowance |
| 9 | Moderation on both surfaces, and "Report this content" reaching the server | **PARTIAL / BLOCKED** | **The report: PROVED from the app.** Tapped "Report this content" on the solemn poster on the handset, chose "Insensitive for this occasion", and a `content_report` row landed on the server — `feature=poster`, `reason=INSENSITIVE`, tied to the poster id, `detail` null and nothing logged. **Mechanism: PROVED.** A blocked generation comes back `BLOCKED` (not `FAILED`), stores `moderation=BLOCKED` and writes no poster; input moderation is wired into the wish draft path too. **A live verdict: BLOCKED** — the classifier needs the same missing key |
| 10 | Both targets compile, the server suite green across three timezones, CI green on the exact head | **PROVED locally; CI on the head** | `compileDebugKotlinAndroid` and `compileKotlinIosSimulatorArm64` both green; `assembleDebug` green; **21 instrumented tests green on the Pixel 9a itself** (M4's 13 included — CI still runs none); the 595-test server suite green in UTC, Asia/Kolkata and Pacific/Kiritimati |

**Items 1, 3, 5, 8, 9's report half and 10 are proved. Four turn on the missing key and two
on TBD-5.** Item 5 is the one the milestone exists to produce; item 1 is the one that needed
a real handset, and it needed one — two of this milestone's four bugs were only reachable
there.

---

## 3. What was done, in order

1. **Server, by curl, against a local Postgres and Redis.** Seeded two invented people —
   one living, one with a death anniversary row and `is_deceased` deliberately left false.
2. `GET /v1/themes` for each. The living one got four celebratory themes; the stale one got
   `sentiment: SOLEMN` and three solemn themes, **while their person row still said alive**.
3. `POST /v1/ai/poster` with `ph-confetti` for the stale person → **400 THEME_NOT_ALLOWED**.
4. A legitimate solemn request → `SUCCEEDED`, `cacheHit=false`, 136ms, theme `ph-stillwater`,
   family `SOLEMN`.
5. The same request again → `cacheHit=true`, 85ms. Counted `ai_usage` rows (0, unchanged) and
   scanned Redis for `poster:*` (none). This is item 5.
6. All three formats, read off disk: 1080×1920, 1080×1080, 1200×630.
7. **Opened the PNGs.** §0.1.
8. Fixed the fitting, re-rendered, opened them again. "Remembering / Rohan Deshpande" on two
   centred lines, muted palette, no celebratory motif, attribution at the foot.
9. Uploaded a generated portrait and composited it. The photo lands upright in its box with
   the top of the head kept; the red stripe at the foot of the source is cropped away, which
   is `fit: cover` behaving.
10. **Emulator.** Installed, launched, reached the sign-in screen. Went no further — see §4.3.
11. Ran the instrumented picker test on the emulator, then reverted the filter to read
    `is_deceased` directly and watched it go red.

### 3.1 On the Pixel 9a, after Dipen unlocked it and signed in

12. Seeded the same two invented people under the signed-in account, leaving Rohan's
    `is_deceased` **false** and his birthday's stored sentiment **CELEBRATORY** — the state
    a sync push leaves before `recomputeMany` runs. Confirmed in the database first, so the
    rest of the run is inside the window rather than near it.
13. Opened Rohan from People. The screen read **"Send a message"** and **"Make a
    remembrance poster"**.
14. Opened the studio: titled **"Remembrance poster"**, previewing *Still water*. Scrolled
    — and found §0.5, the controls all below the fold.
15. Dumped the view tree: exactly `ph-stillwater`, `ph-dusk`, `ph-lamp`. **No celebratory
    node.** Generated: `SOLEMN`/`SOLEMN`, 207ms, attributed.
16. Tapped **Report this content → "Insensitive for this occasion"**. A `content_report`
    row arrived on the server, tied to the poster id.
17. Opened Asha: **"Send a wish"**, **"Make a poster"**, exactly the four celebratory
    swatches and no solemn one.
18. **Add a photo** → the system photo picker opened with **no permission prompt**. Chose a
    synthetic image pushed to the gallery for the purpose — never one of Dipen's — and
    confirmed by its `date_modified` that the tile tapped was that one. It uploaded as
    1200×1600 JPEG and `photo_key` was written.
19. Generated: `CELEBRATORY`/`CELEBRATORY`, 166ms, `ph-confetti`, 1080×1920. Pulled the PNG
    and looked at it: photo upright and unmodified, head preserved, headline wrapped on two
    lines beneath it, attribution at the foot.
20. Tapped **Share** — and nothing happened. That is §0.4. Fixed it, pinned it with
    `PosterSharingTest`, reinstalled, and the sheet opened: **"Sharing 1 file · poster.png"**
    with WhatsApp among the targets. Dismissed without choosing a target.
21. Ran the whole instrumented suite on the handset: **21 tests, all green.**

---

## 4. What could not be proved, and why

### 4.1 No image has ever been generated

`OPENAI_API_KEY` is empty. `IMAGE_PROVIDER=fake` is the committed default and the module
logs a warning at boot saying so in as many words. `OpenAiImageProvider` is written against
the installed SDK's own types — `tsc` rejects a field that does not exist on `ImagesResponse`
— and has **never made a request**. Items 6 and 7's measured half are BLOCKED on this.

### 4.2 No classifier has ever been called

Same key. `MODERATION_PROVIDER=fake` returns `UNCHECKED`, not `ALLOWED`, which is the whole
point: one word's difference would have made every `generation` row read as approved by
something that was never called. The module logs at boot that rule 7 is structurally met and
not exercised.

### 4.3 One stretch of the journey was driven by a test rather than by tapping

Everything in §3.1 up to and including the share failure was done by tapping through the
app. After the fix, reinstalling signed the app out again (§0.3), so the **repaired** share
was proved by `PosterSharingTest.theRealSenderOpensAChooser` — which drives the production
`AndroidMessageSender.shareImage`, on the handset, and left the chooser on screen where it
was photographed and dismissed.

What that leaves: the studio → Share button wiring was exercised once, with the broken
build. The repaired path was exercised through the same sender a layer below the button.
`PosterPickerFilterTest` covers that the button exists and is enabled when bytes are
present. Nobody has tapped the repaired Share button in the app itself.

### 4.4 Nothing was handed to WhatsApp, and nothing was sent

M5's constraint is inherited exactly: every route to WhatsApp's composer targets a real
number or opens a picker of real contacts, and a blind tap near a send button in somebody's
real chat is the incident class `MILESTONE_CHECKLIST.md` §E exists to prevent. The share
sheet was opened and dismissed without choosing a target — and its direct-share row carries
real contacts, which is also why no screenshot of it exists anywhere (§9).

So "sent" means "handed over and stopped", exactly as in M5. The last tap is Dipen's.

### 4.5 The cache was measured against a store that survives a restart, not a deploy

`BLOB_STORE=local` writes under `server/.storage/`, which is gitignored. Railway's filesystem
is ephemeral, so **every poster and every cached theme is lost on redeploy** — which turns
FR-POS-03's "generated once, served thousands of times" into "generated once per deploy".
That is a hole in the deployment, not in the code, and `MediaModule` logs it at boot. The
85ms hit is real; it has not been shown to survive a deploy, because no deploy has happened.
R2 credentials are item 6 in `M6_PLAN.md` §9.

### 4.6 Still carried forward, named rather than quietly dropped

M6 touches none of these and closes none of them.

- **The OEM battery helper is unexercised on a non-Pixel device** — now **seven** milestones.
- **The mobile-data leg of the Railway deploy is unproven** (the SIM will not register).
- **M4's tablet hardware, three empty states and two widget sizes** are still open. The
  instrumented suite now runs locally on an emulator (16 tests, all green) but **CI still
  does not run it**.
- **M5's definition-of-done item 4 — a real remembrance draft, read by Dipen for TBD-4 — is
  not done and not deferred.** It stays M5's open item until a model writes a solemn draft
  and a person reads it. **M6 did not touch it and does not close it.**
- **M5's item 1 is still PARTIAL**: the hand-off was proved into Google Messages, not
  WhatsApp.

---

## 5. Tests

| Suite | Count | Where |
|---|---|---|
| Server | 595 | 37 suites, including 3 DB-backed against real Postgres and Redis. Green in **UTC, Asia/Kolkata and Pacific/Kiritimati** (UTC+14, where "today" is tomorrow in UTC) |
| Android unit | green | `:composeApp:testDebugUnitTest` |
| Android instrumented | 21 | **Pixel 9a, Android 17** (and 16 of them on an emulator earlier). **Not in CI** |
| iOS | compiles | `:composeApp:compileKotlinIosSimulatorArm64` |

### 5.1 Every fix was confirmed by reverting it

The build prompt is explicit that a green revert-check is a signal to investigate, not a
formality — M5 had three and each was a real gap.

| Fix | Reverted by | Result |
|---|---|---|
| The deceased predicate (`isDeceasedConsidering`) | returning `personIsDeceased` alone | Stale-window test red, **plus two existing suites** |
| Text fitting | drawing the headline at the nominal size | **25 of 42** red |
| The theme cache | `lookup()` returning null | 2 red |
| The global spend cap | skipping `isCapped()` | 1 red |
| The client picker filter | `posterThemesFor` reading `person.isDeceased` | Instrumented test red **on a device** |
| The FileProvider authority (§0.4) | restoring the over-escaped interpolation | 2 of 5 red **on the handset** |

**Two of these first produced a compile error rather than a failing test**, which is not a
revert-check — it proves the code is referenced, not that anything asserts on its behaviour.
Both were redone so the function still type-checked and only its behaviour changed. That
distinction is the difference between a check and a formality.

### 5.2 A test that would have passed for the wrong reason

§0.2. The EXIF precondition never matched the thing it claimed to match.

---

## 6. Bugs found in earlier milestones' code

**None in merged code. Four in this branch's own work, all found before anybody else saw
them** — which is the outcome the "verify by doing" rule is for. Two were only reachable on
a device:

| # | Bug | Found by |
|---|---|---|
| §0.1 | The headline ran off both edges of every poster | Opening a PNG |
| §0.2 | A privacy assertion that could not have failed | Writing the assertion carefully |
| §0.4 | Every share failed silently — FileProvider authority | **Tapping Share on the handset** |
| §0.5 | The studio opened with every control below the fold | **Opening the studio on the handset** |

Both device bugs were invisible to 595 server tests, a green Android unit suite and 16
instrumented tests on an emulator. §0.4 in particular cannot be caught off-device at all.

The four existing `person.isDeceased || marksPersonDeceased(…)` expressions in
`PersonDerivationService`, `SyncService` and `EventsService` were correct. They were
consolidated into one function because M6 needed a fifth, and five copies of the most
dangerous predicate in this product is not a thing to add.

---

## 7. Decisions this milestone took

Each is argued where it lives; `M6_PLAN.md` §9.1 listed them and none changed.

1. **A poster is not a fifth synced entity.** It is server-owned, immutable and never edited,
   so last-write-wins on it is ceremony. The reference already syncs on `wish.poster_id`.
2. **`Generation` is a second table beside `AiUsage`.** Different cardinalities; collapsing
   them makes either the row count or the hit rate a lie.
3. **`theme_cache` is never swept; `asset` carries `expires_at`** for M9. Product art and a
   user's media are different things with different lifetimes.
4. **The blob store is a port with a local default** and an R2 adapter that throws by name
   rather than being stubbed.
5. **Step (a) goes through the existing `LlmProvider`**, extended with `draftPosterCopy`,
   not a second text seam.
6. **Moderation covers both surfaces.** Posters and the wish drafting path that shipped.
7. **A cache hit consumes no rate-limit slot**, which is why the limiter is consumed inside
   the pipeline on the live branch rather than at the controller.
8. **The photo path was absorbed**, unscoped — `M6_PLAN.md` §3.1 raised it as C1 and
   definition-of-done item 1 cannot be met without it. The minimum only.

---

## 8. What Dipen still owes, and what each blocks

Unchanged from `M6_PLAN.md` §9. Restated with what the work has since shown.

1. **TBD-5 — visual identity and poster art direction.** Blocks items 2's hand-check, 4's
   judgement and 1's product judgement. Everything mechanical is done behind it: answering
   it costs `theme-library.ts`, `PosterTheme.kt`, `poster-themes.json` and the layout table
   in `compositor.ts`, not the pipeline.
2. **The `OPENAI_API_KEY` for `gpt-image-1`.** Blocks items 6, 7's measured half and 9's
   live half. ~~and item 1~~ — it does not; item 1 ran entirely off the library, which is
   FR-POS-04 working.
3. **Moderation scope.** Built for both surfaces, as the plan said it would be unless told
   otherwise. §10 rule 7 is now structurally met on both and exercised on neither.
4. **Poster rate-limit ceilings, and whether ₹200/day stands.** Shipped at 5/hour and 20/day
   as proposed. At §10's own "₹1–15 per image", 20 a day for one user is up to ₹300 — more
   than the entire global cap. The two numbers were sized against different cost shapes.
5. **C1 — the photo path.** Absorbed, minimum only. Say if that was the wrong call.
6. **C2 — storage.** R2 credentials, or accept that posters do not survive a redeploy (§4.5).
7. **C3 — `Generation` as a second table**, and the §8 amendment note that goes with it.
8. **C4 — FR-POS-01 still names Claude.** Same correction D20 got.
9. **C5 — the theme library's split home.** Manifest in the app, artwork from the server.
10. ~~The handset's keyguard~~ — **done.** Unlocked and signed in; §3.1 is what that
    bought, including the two bugs at §0.4 and §0.5. The one thing left on the device is
    the final tap into WhatsApp, which is yours by the same rule M5 settled on.
11. **A debug-only dev-login button?** Offered during this run and not taken, so it is not
    in the branch. M2, M4 and M6 have each now lost verification time to "no Google account
    / signed out, stops at sign-in", and a reinstall costs a fresh sign-in every time
    (§0.3). Worth a decision before M7 rather than during it.

---

## 9. Test data

Every person in a test, a fixture or an example above is **invented**
(`MILESTONE_CHECKLIST.md` §F). Compositor fixtures are solid-colour and gradient PNGs
generated by the test itself — not only a privacy measure but what makes "the photo is
unmodified" an exact comparison rather than an eyeball.

Two screenshots taken during §3.1 were **deleted rather than kept**: the system photo
picker, whose grid is Dipen's own camera roll, and the share sheet, whose direct-share row
names real contacts. Neither is in the repository and neither is in the scratch directory
any more. The synthetic image pushed to the device's gallery for the picker test was
removed from the device afterwards.

**No composited poster carrying a real face has entered this repository in any form**, and
none ever should (`M6_PLAN.md` §1.2). The posters described in §3 carry invented names and a
generated gradient in place of a photograph; they were written to a scratch directory outside
the repo and are not committed. What was on the screen is described in words above, as M5 §0.5
had to for a screenshot it had to delete.
