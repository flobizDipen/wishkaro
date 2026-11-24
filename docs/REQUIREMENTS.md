# WishKaro — Product Requirements

> **Status:** v0.2 — all open decisions resolved, build-ready
> **Date:** 2026-11-24
> **Owner:** Dipen Jansari (solo / personal project)
> **Package id:** `in.wishkaro.app` (Android `applicationId` + iOS bundle id)
> **Repo:** single **public** GitHub monorepo — `github.com/flobizDipen/wishkaro`.
> ⚠️ Because the repo is public, **any secret that is ever committed is compromised the moment it
> is pushed** and must be rotated, not merely deleted — git history is permanent and public repos
> are scraped continuously by credential bots. See the secrets rules in §11.

**How to read this document.** Every requirement has a stable id (`FR-AUTH-01`). Open questions
have ids too (`OD-1`). During the alignment pass we walk module by module; when a module is agreed
it gets marked `AGREED` in its heading. Only agreed modules go into a build prompt.

---

## 1. Product summary

WishKaro is an India-first Android app (Compose Multiplatform, iOS later) that makes sure you never
miss a birthday, anniversary or remembrance day for the people you care about — and then helps you
*actually do something about it*: send a personalised wish, generate a custom poster or video, and
find a gift worth giving.

The product is three layers stacked on one another:

1. **Remember** — a reliable, low-friction event store (manual entry + contacts/CSV import) with
   notifications that genuinely arrive on Indian Android hardware.
2. **Act** — one tap from the notification to a drafted wish on WhatsApp, an AI-designed poster, or
   a short video.
3. **Give** — AI gift ideas derived from what you know about the person, deep-linked to partner
   stores with affiliate attribution.

Revenue: freemium subscription + consumable AI credits (Google Play Billing) and affiliate
commission on gifts (outside Play Billing — physical goods are exempt).

---

## 2. Goals and non-goals

### Goals

| # | Goal | How we'll know |
|---|---|---|
| G1 | A reminder never silently fails | Notification delivery rate measurable and > 98% on a test matrix including Xiaomi/Oppo/Vivo/Realme |
| G2 | Adding a person takes under 10 seconds | Median time-to-first-person from cold start; 3 required fields only |
| G3 | The app is useful on day 1, before any AI | Core free tier is fully functional with zero paid features |
| G4 | Marginal cost per active user stays near zero | AI metered by credits; generated art cached and reused |
| G5 | iOS is weeks of work, not months | ≥ 85% shared Kotlin; platform code confined to named `expect/actual` modules |

### Non-goals (explicitly not building)

- A social network — no feeds, no in-app wish walls, no friend graph, no public profiles.
- A genealogy product — no GEDCOM, no unlimited generations, no multi-marriage lineages.
- A merchant — we never take payment for a physical gift, hold inventory, or handle returns.
- A chat app — we hand off to WhatsApp/SMS, we don't carry messages.
- Generative video of real people — see §7.8.

---

## 3. Scope ladder

### v1 — the shipped product

Onboarding + Google auth · people & events (birthday / wedding, engagement & death anniversary) · contacts import ·
CSV/Excel import · reminder engine (push + local fallback) · list, calendar and timeline views ·
home-screen widget · rich notification actions · one-tap wish send via WhatsApp with AI-drafted
message · AI poster generation · gift idea suggestions with affiliate deep links · wish & gift
history · progressive gift profiling · Premium subscription + credit packs · privacy, export and
deletion.

### v1.1 — fast follows

AI video (motion-graphics slideshow) · Hindi UI localisation · referral.

### v2 — deferred by decision

**Family tree** (ego-centric graph UI, 3 generations) · invite-to-claim deep links · tithi/lunar
calendar dates · Google Sheets live sync · Google People API import · iOS release.

### Never (unless the thesis changes)

In-app checkout · multi-writer shared trees · full genealogy · ads on records flagged as minors.

> **OD-1 — Family tree removal.** The tree is out of v1, agreed. **Recommendation:** keep
> `relation_to_owner` as a field on every person *and* keep the `relationship` edge table in the
> schema from day one, but ship no tree UI. Reason: relation already drives gift suggestions,
> wish tone and notification copy. Keeping the edge table costs ~nothing now and
> makes v2 pure UI work. **Open:** confirm, or strip edges entirely and keep only the flat field.
>
> **OD-2 — Invite-to-claim.** This loop was attached to the tree and is your only organic growth
> mechanism. With the tree gone it has no home in v1. **Recommendation:** drop it from v1 entirely
> rather than half-build it; revisit with the tree in v2. **Open:** confirm.

---

## 4. Users and jobs

**Primary user — "the designated rememberer".** 25–45, urban/semi-urban India, Android, the person
in the family or friend group who is expected to remember. Currently coping with a mix of Google
Calendar entries, a WhatsApp note-to-self, and their mother's memory.

Jobs to be done:

| Job | Trigger | Today's workaround | Our answer |
|---|---|---|---|
| "Don't let me forget" | Ongoing dread | Calendar entries that get muted | Reliable multi-channel reminder ladder |
| "Tell me in time to act" | 1 week out | Nothing | 7-day lead notification framed around gifting |
| "Help me say something not generic" | Morning of | Copy-pasted forward | AI wish in the right tone and language |
| "Make it feel personal" | Close relations | Canva, 20 minutes | AI poster with their photo, 15 seconds |
| "What do I even get them?" | 1 week out | Ask someone, panic-buy | Gift ideas from a stored profile |
| "What did I do last year?" | Every year | Memory | Wish & gift history per person |

**Secondary user — "the recipient".** Never installs the app in v1. Receives a WhatsApp message
with a poster. Their only relevance in v1 is as a growth surface: the poster carries subtle
attribution (see `OD-9`).

---

## 5. Locked decisions

Agreed during scoping. Changing one of these invalidates downstream requirements, so flag it loudly.

| # | Topic | Decision |
|---|---|---|
| D1 | Ambition | Full product, no deadline. Personal project, solo developer |
| D2 | Market | India-first (INR, WhatsApp as share surface) |
| D3 | Family tree | **Out of v1**, deferred to v2 |
| D4 | Relationship model (v2) | Ego-centric, ~3 generations, real edges, deceased flag; no divorce/adoption |
| D5 | Auth | Google Sign-In, mandatory at launch |
| D6 | Event types | Birthday (year optional); wedding, engagement and **death** anniversaries, milestone-aware. **No festivals** |
| D7 | Reminder delivery | Server-scheduled FCM push + on-device alarm fallback, de-duplicated |
| D8 | Reminder ladder | 7 days before, 1 day before, morning-of (user configurable) |
| D9 | Import | Device contacts (`READ_CONTACTS`) + CSV/Excel upload with column mapping |
| D10 | Monetisation | Freemium → Premium subscription → consumable AI credits, via Play Billing |
| D11 | AI output | AI designs template + copy; **real photo composited, never generated** |
| D12 | Video | Server-rendered motion-graphics slideshow, not a generative video model |
| D13 | Gifting | AI generates gift *ideas* → affiliate-tagged partner search/category deep links |
| D14 | Gift profiling | Progressive + just-in-time wizard + visible completeness score |
| D15 | Cross-platform | Shared CMP UI + shared logic; `expect/actual` platform shims; Android ships first |
| D16 | Backend | NestJS + PostgreSQL + Redis/BullMQ + Cloudflare R2, Docker, Railway/Fly |
| D17 | Core loop | WhatsApp wish send · widget + rich notifications · wish/gift history · calendar + timeline |
| D18 | Compliance | Privacy-by-design baseline from day one |
| D19 | Repo | Single **public** GitHub monorepo (`flobizDipen/wishkaro`) — decided 2026-11-24, overriding the earlier private-repo requirement |
| D20 | Text AI | Claude (Haiku for volume, Sonnet for reasoning), strict JSON output |
| D21 | Image AI | **OpenAI `gpt-image-1`**, server-side only, behind an `ImageProvider` interface |
| D22 | Build order | M0 walking skeleton → vertical feature slices |
| D23 | Sync | Offline-first local DB, last-write-wins per field, soft deletes, sync cursor |
| D24 | Name | WishKaro / `in.wishkaro.app` |

---

## 6. Decision register — all resolved

Every item below was accepted on 2026-11-24. Kept in the doc rather than deleted, because the
*reasoning* is what you'll want when one of these is questioned in three months.

| # | Question | Decision |
|---|---|---|
| OD-1 | Keep relationship edge table with no tree UI? | Yes — keep edges, ship no UI |
| OD-2 | Invite-to-claim in v1? | No — defer with the tree |
| OD-3 | Free-tier limits: cap people/events, or only meter AI? | Unlimited people/events; meter AI only |
| OD-4 | Premium price point | **₹99/month, ₹799/year** (₹67/mo effective, 33% saving) |
| OD-5 | Credit economics | ⚠️ **Revised down for ₹99.** ₹99 minus Play's 15% leaves ~₹84/month. A cache-**miss** poster costs roughly ₹3–15, so a 30-credit allowance is underwater before a single video. Recommend **10 credits/month** included, 1 credit = 1 custom poster, 5 = 1 video, packs of 10/50/150 — and lean hard on the free pre-approved theme library (₹0 marginal) as the default path. Revisit once the real cache-hit rate is measured |
| OD-6 | Enable Play User Choice Billing (Razorpay/UPI alongside Play)? | Not in v1 — adds work for 4 points of fee |
| OD-7 | Affiliate network | **Deferred — not deciding now.** Build the gift surface partner-agnostic: links are table-driven server-side, so a network can be slotted in later without an app update |
| OD-8 | Languages at launch | English UI only; AI wishes in English / Hindi / Hinglish |
| OD-9 | Watermark/attribution on free posters? | Yes, subtle — removed by Premium. Free growth, clean upsell |
| OD-10 | Analytics stack | PostHog (free tier) + Firebase Crashlytics |
| OD-11 | Does a person need a photo for a poster? | No — fall back to an illustrated/typographic template |
| OD-12 | Death anniversary: Gregorian only, or tithi too? | **Gregorian only — DECIDED.** Accepted known gap: families who observe punyatithi by the Hindu calendar will see a date that drifts from their observance. Mitigation proposed in `OD-16` |
| OD-13 | Minimum Android API level | **26 — DECIDED.** Plus foldable + tablet adaptive layouts in scope (see FR-VIEW-06..09) |
| OD-14 | **When a person has a death anniversary, what happens to their birthday?** | Offer an opt-in "remembrance birthday" — gentle notification, no gift surface, no celebration posters |
| OD-15 | Which screens get the two-pane treatment on large screens? | People↔detail and Events↔detail; dialogs become side sheets. Composer and generation stay single-pane |
| OD-16 | Per-occurrence date override — move a single year's occurrence ("this year we observe on 14 Oct") without changing the base event | **Yes.** Cheap to build (one nullable `override_date` on `event_occurrence`) and it absorbs most of the tithi gap from `OD-12` — families who compute the Hindu date themselves can just set it. Also covers "we're celebrating on the weekend" |

---

## 7. Functional requirements

### 7.1 Onboarding and authentication — `FR-AUTH`

| id | Requirement |
|---|---|
| FR-AUTH-01 | First launch shows a 3-screen value carousel (Remember · Wish · Gift), skippable. |
| FR-AUTH-02 | Sign-in is **mandatory** before the main app. Google Sign-In only (Credential Manager / One Tap). |
| FR-AUTH-03 | Backend exchanges the Google ID token for a WishKaro session: short-lived JWT access token + long-lived rotating refresh token stored in platform secure storage. |
| FR-AUTH-04 | On first sign-in the server creates a `user` and an owner `person` record representing the user themselves (needed for relationship context and "your own birthday"). |
| FR-AUTH-05 | Post-sign-in setup asks, in order: your own date of birth (optional, skippable) → notification permission (Android 13+ runtime) → exact-alarm permission rationale → "add your first 3 people" (contacts import offered here). |
| FR-AUTH-06 | Contacts permission is requested **only** behind a prominent-disclosure screen stating what is read, why, and that it is not uploaded in bulk. Declining must leave the app fully usable. |
| FR-AUTH-07 | Sign-out clears local DB and tokens after an explicit confirm. |
| FR-AUTH-08 | Account deletion is reachable in ≤ 3 taps from Settings, and also from a public web URL (Play requirement). Deletion is irreversible, confirmed by typing, and purges all media from R2 within 30 days. |

### 7.2 People — `FR-PPL`

| id | Requirement |
|---|---|
| FR-PPL-01 | Adding a person requires exactly three inputs: **name**, **event date**, **relation**. Everything else is optional. |
| FR-PPL-02 | `relation` is a chip picker with a curated Indian-context list (Mother, Father, Brother, Sister, Spouse, Son, Daughter, Grandparent, Uncle, Aunt, Cousin, Friend, Best Friend, Colleague, Boss, Neighbour, Other) plus free text. |
| FR-PPL-03 | A person may have multiple events (birthday + anniversary), and multiple people may share one event (a couple's anniversary). |
| FR-PPL-04 | Optional profile fields, all progressively collected: photo, gender, phone, email, hobbies/interests (chips), likes, dislikes, budget band, notes, deceased flag. |
| FR-PPL-05 | A visible **profile completeness score** (0–100) with a one-tap "improve" affordance. Score weights fields by their value to gift suggestion quality. |
| FR-PPL-06 | Duplicate detection on create and on import: fuzzy name match + same date → "Is this the same person?" merge prompt. |
| FR-PPL-07 | Person detail screen shows: next event with countdown, age/years (if year known), profile, wish history, gift history, and action buttons (Wish, Poster, Gift ideas). |
| FR-PPL-08 | Soft delete with a 30-day restore window; archived people stop generating reminders immediately. |
| FR-PPL-09 | **Minor flag**: if a known birth year implies age < 18, the record is auto-flagged. Flagged records are excluded from any profiling, recommendation personalisation beyond age band, and all ad/affiliate tracking. |
| FR-PPL-10 | Search and filter across people by name, relation, month, and completeness. |

### 7.3 Events — `FR-EVT`

| id | Requirement |
|---|---|
| FR-EVT-01 | Event types in v1: `BIRTHDAY`, `WEDDING_ANNIVERSARY`, `ENGAGEMENT_ANNIVERSARY`, `DEATH_ANNIVERSARY`, `CUSTOM`. **No festivals** — festivals are the same date for everybody, so they are a poor reminder and were only ever a gifting surface. |
| FR-EVT-01a | Every event type carries a `sentiment` of `CELEBRATORY` or `SOLEMN`. `DEATH_ANNIVERSARY` is `SOLEMN`; everything else is `CELEBRATORY`. **This flag gates tone, templates, notification copy and the entire gift surface** — it is not cosmetic, and every downstream feature must branch on it. |
| FR-EVT-02 | Birthday supports **year unknown**. When unknown: no age shown, no age-based gift logic, no "turning 30" copy. This must be handled everywhere, not just the UI. |
| FR-EVT-03 | Wedding and engagement anniversaries derive a milestone label (1st, 10th, 25th Silver, 50th Golden). Milestones raise the event's priority and unlock premium template sets. Death anniversaries derive a plain year count ("12 years") and **never** a celebratory milestone name. |
| FR-EVT-04 | **Death anniversary behaviour.** Creating one sets `is_deceased` on the person. The app then: suppresses the gift surface entirely (`FR-GFT-11`), restricts tones to remembrance (`FR-WSH-09`), uses the solemn template set only (`FR-POS-13`), and drops the T-7 gifting reminder (`FR-REM-11`). Copy never uses "celebrate", "wish", "congrats" or emoji for these events. |
| FR-EVT-05 | **Deceased person's birthday.** When a person is marked deceased, their birthday reminder is disabled by default and the user is offered an opt-in "remembrance birthday" that behaves as `SOLEMN` (`OD-14`). Silently continuing to send "Happy Birthday!" for someone who has died is the worst failure this app can have — it must be impossible by construction, not by a copy tweak. |
| FR-EVT-06 | `CUSTOM` events allow an arbitrary recurring annual date with a user-supplied label. |
| FR-EVT-07 | Schema reserves a `calendar_system` column (`GREGORIAN` default) so lunar/tithi dates can be added later without migration. v1 only ever writes `GREGORIAN` (`OD-12` decided). Punyatithi/shraddh is traditionally observed by the Hindu calendar, so this is a **known and accepted** gap, not an oversight — the column exists so tithi can be added later without a migration. |
| FR-EVT-08 | Every event stores the owner's timezone at creation; reminders fire in the owner's *current* timezone, and a timezone change reschedules pending reminders. |
| FR-EVT-09 | Leap-day (29 Feb) birthdays notify on 28 Feb in non-leap years. |
| FR-EVT-10 | **Per-occurrence date override** (`OD-16`). A single year's occurrence can be moved to a different date without altering the base event; subsequent years revert to the base date. Surfaces as "Observing on a different date this year?" on the event. Absorbs most of the tithi gap from `OD-12` and covers "celebrating on the weekend". |

### 7.4 Import — `FR-IMP`

| id | Requirement |
|---|---|
| FR-IMP-01 | **Contacts import**: reads display name, photo URI, phone, and the contact's birthday field from the device. Runs entirely on-device; only the records the user explicitly selects are persisted or synced. |
| FR-IMP-02 | Contacts import shows a review screen: contacts *with* a birthday pre-selected and sorted first; contacts without one available but unselected. Never bulk-imports silently. |
| FR-IMP-03 | Each imported row needs a relation before it saves; a bulk "set relation for selected" action is required or the flow is unusable at scale. |
| FR-IMP-04 | **File import**: accepts `.csv`, `.xlsx`. Parsed client-side where possible; large files go to the server. |
| FR-IMP-05 | File import shows a **column-mapping screen** (source column → Name / Date / Relation / Phone / Notes) with a live 5-row preview. |
| FR-IMP-06 | Date parsing tolerates the formats people actually type: `dd/MM/yyyy`, `dd-MM-yy`, `MM/dd/yyyy`, `d MMM yyyy`, `yyyy-MM-dd`, and day-month with no year. Ambiguous dd/MM vs MM/dd is resolved by an explicit user toggle, defaulted to dd/MM for India. |
| FR-IMP-07 | Import produces a result report: imported / skipped / failed with per-row reasons, and failures are downloadable as a corrected CSV. |
| FR-IMP-08 | Import is idempotent — re-importing the same file does not create duplicates (match on name + date). |

### 7.5 Reminders and notifications — `FR-REM`

| id | Requirement |
|---|---|
| FR-REM-01 | The **server is the scheduling source of truth**. A nightly job materialises the next occurrence of every event per user and enqueues FCM sends. |
| FR-REM-02 | The client **also** schedules a local alarm per upcoming event as a fallback. Both paths carry the same `notification_id`; the client de-duplicates so the user never sees a double. |
| FR-REM-03 | Default ladder: **T-7 days** (framed around gifting), **T-1 day**, **morning-of** (default 09:00 local). All three individually toggleable; the delivery hour is user-configurable globally and overridable per person. |
| FR-REM-04 | Notification actions: **Send wish**, **Gift ideas**, **Snooze 1h**, **Mark done**. T-7 leads with Gift ideas; morning-of leads with Send wish. |
| FR-REM-05 | A dedicated Android notification channel per ladder step, so users can tune them separately. |
| FR-REM-06 | Onboarding and Settings include an OEM battery-optimisation helper: detects Xiaomi/Oppo/Vivo/Realme/Samsung and deep-links to the correct autostart/battery settings screen with instructions. This is the single highest-impact reliability feature in the app. |
| FR-REM-07 | A self-test: "Send me a test reminder in 2 minutes" in Settings, so the user can verify delivery themselves. |
| FR-REM-08 | Daily digest option: one notification at a chosen hour listing everything happening today, instead of per-event notifications. |
| FR-REM-09 | Quiet hours — no notifications in a user-defined window; deferred to the window's end. |
| FR-REM-10 | If FCM delivery fails or the device has been offline > 48h, the local alarm must still fire. Delivery outcome is reported back to the server for the G1 metric. |
| FR-REM-11 | **Solemn ladder.** `SOLEMN` events use T-1 and morning-of only — no T-7 gifting nudge. Notification copy is gentle, carries no emoji, and offers only **Send message** and **Mark done**; the Gift ideas action is absent. |

### 7.6 Wish sending — `FR-WSH`

| id | Requirement |
|---|---|
| FR-WSH-01 | From a notification, event card or person screen, **Send wish** opens a composer pre-filled with an AI-drafted message. |
| FR-WSH-02 | The draft is conditioned on: relation, event type, milestone, age band (if known), hobbies, tone selector, language selector, and **what was sent in previous years** (must not repeat). |
| FR-WSH-03 | Tone options: Warm · Funny · Respectful · Short · Poetic. Language options: English · Hindi · Hinglish. |
| FR-WSH-04 | "Regenerate" produces a different draft; the user can always edit freely before sending. |
| FR-WSH-05 | Send targets: WhatsApp (primary, via intent with pre-filled text and optional image), SMS, generic share sheet, copy to clipboard. |
| FR-WSH-06 | Sending is **never automatic**. The app does not send on the user's behalf; it hands a prepared message to the user's own messaging app for them to press send. |
| FR-WSH-07 | After handing off, the app asks "Did you send it?" to record wish history (one tap, dismissible). |
| FR-WSH-08 | Text drafting is free and unmetered within a fair-use rate limit (it is cheap); only image/video generation consumes credits. |
| FR-WSH-09 | **Solemn composer.** For `SOLEMN` events the composer uses a separate, restricted prompt template; tone options reduce to Respectful · Short · Heartfelt, language options are unchanged, and Funny/Poetic are unavailable. The draft is framed as remembrance, never congratulation. This prompt is reviewed by hand and covered by its own tests — a tone failure here is the kind of bug that gets an app uninstalled and screenshotted. |

### 7.7 AI posters — `FR-POS`

| id | Requirement |
|---|---|
| FR-POS-01 | The poster pipeline is: **(a)** Claude picks/writes the copy and selects a theme; **(b)** `gpt-image-1` generates the *background artwork* only; **(c)** the server composites the person's **unmodified** photo, the copy, and frame elements over that background using `sharp`. |
| FR-POS-02 | The person's face is **never** passed through a generative model in the default path. This guarantees likeness fidelity and avoids personality-rights exposure. |
| FR-POS-03 | Generated background artwork is **cached and reused**. Cache key = (theme, event type, milestone, palette, aspect ratio). A popular theme is generated once and served thousands of times, driving marginal cost toward zero. |
| FR-POS-04 | A library of pre-generated, hand-approved themes ships with the app so the common path requires **no** live generation at all. Live generation is the premium/custom path. |
| FR-POS-05 | Output formats: 1080×1920 (WhatsApp status), 1080×1080 (square), 1200×630 (link preview). User picks; default is status. |
| FR-POS-06 | No photo available → typographic/illustrated template, still high quality (`OD-11`). |
| FR-POS-07 | Optional **"AI blend"** variant (clearly labelled, extra credits) uses `gpt-image-1`'s image-edit endpoint with a mask to stylise the photo itself. Opt-in only, with a warning that the face may change. |
| FR-POS-08 | Free tier posters carry subtle WishKaro attribution; Premium removes it (`OD-9`). |
| FR-POS-09 | Generation is asynchronous via BullMQ with a visible progress state; target p95 under 15 seconds for a cache miss, under 2 seconds for a cache hit. |
| FR-POS-10 | Every generation writes a cost ledger row (provider, model, tokens/images, INR cost, user, feature). Non-negotiable — without it you cannot price the product. |
| FR-POS-11 | Per-user and global rate limits and a **daily spend cap** that degrades to cached themes when breached. |
| FR-POS-12 | Input and output moderation, plus an in-app "Report this content" action on every generated asset (Play GenAI policy requirement). |
| FR-POS-13 | **Remembrance template set.** `SOLEMN` events may only use solemn themes — muted palette, no balloons, confetti, cake or party motifs. Celebratory themes are filtered out of the picker for those events, and the generation prompt is built from a separate template family. |

### 7.8 AI video — `FR-VID` *(v1.1)*

| id | Requirement |
|---|---|
| FR-VID-01 | Video is a **server-rendered motion-graphics slideshow**: the user's photos, Ken Burns motion, animated text, transitions, and a licensed music bed. No generative video model. |
| FR-VID-02 | Render pipeline: `ffmpeg` filter graphs (preferred — no browser dependency, low memory) or Remotion if richer motion proves necessary. Decision deferred to the milestone. |
| FR-VID-03 | 2–6 photos, 15–30 seconds, 1080×1920, H.264, under 10 MB so WhatsApp will not re-compress it to mush. |
| FR-VID-04 | Claude supplies the scene copy; theme and music are chosen from a curated licensed set. **Music licensing must be cleared before launch** — this is a real legal exposure, not a detail. |
| FR-VID-05 | Costs credits (5× a poster). Rendering is queued with push notification on completion; the user does not wait on screen. |

### 7.9 Gift suggestions — `FR-GFT`

| id | Requirement |
|---|---|
| FR-GFT-01 | Suggestions are generated by Claude from a structured profile: relation, age band (if known), gender (if known), hobbies/interests, likes, dislikes, budget band, occasion, milestone, **gifts already given in previous years**, and a server-curated **seasonal trends** block. |
| FR-GFT-02 | Output is strict JSON: 4–6 ideas, each with `title`, `why_this_person` (one line tying it to their profile), `price_band`, `category`, and `search_terms`. |
| FR-GFT-03 | Each idea renders as a card. Tapping it opens an **affiliate-tagged search or category URL** at a partner (IGP, FNP, Amazon, Flipkart, Winni) in a Custom Tab. No SKU catalog is maintained in v1. |
| FR-GFT-04 | Partner selection per category is table-driven server-side, so partners and tags can change without an app update. |
| FR-GFT-05 | If the profile is too sparse for a good answer, the app runs the **just-in-time profile wizard** (≤ 20 seconds, chip-based) before generating, rather than returning generic ideas. |
| FR-GFT-06 | "Seasonal trends" is a hand-curated server-side table refreshed periodically (`OD-14` pattern). **No Google Trends scraping dependency.** |
| FR-GFT-07 | User can mark an idea as **"I gave this"**, which writes gift history and permanently excludes it for that person. |
| FR-GFT-08 | Affiliate disclosure is shown clearly on the gift surface — legally required and cheap to do honestly. |
| FR-GFT-09 | Click-outs are logged (person, idea, partner, timestamp) for conversion analysis. Records flagged as minors are excluded from click tracking (`FR-PPL-09`). |
| FR-GFT-10 | Gift suggestions are free but rate-limited; they drive affiliate revenue, so metering them would be self-defeating. |
| FR-GFT-11 | **The gift surface does not exist for `SOLEMN` events.** No cards, no entry point, no notification action, no upsell. Not hidden behind a setting — absent. |
| FR-GFT-12 | Partner links are **table-driven server-side** and currently carry no affiliate tag (`OD-7` deferred). The schema, click logging and disclosure copy are all built now so a network can be switched on later with a server config change and no app update. |

### 7.10 History and enrichment — `FR-HIS`

| id | Requirement |
|---|---|
| FR-HIS-01 | Wish history: per person per year — what was sent, tone, language, channel, and the poster used. |
| FR-HIS-02 | Gift history: per person per year — what was given, approximate spend, source. |
| FR-HIS-03 | History is fed back into every AI prompt so the app visibly improves each year. This is the retention moat; treat it as a first-class feature, not a log. |
| FR-HIS-04 | "Last year you sent…" surfaces in the composer and on the person screen. |
| FR-HIS-05 | Enrichment nudges: after a wish is sent, offer one quick profile question. Never more than one per interaction. |

### 7.11 Widget and views — `FR-WID` / `FR-VIEW`

| id | Requirement |
|---|---|
| FR-WID-01 | Glance home-screen widget in three sizes showing today / this week's events with count and names. |
| FR-WID-02 | Tapping an entry deep-links to that person; tapping the header opens the app. |
| FR-WID-03 | Widget updates on data change and at least daily at midnight local. |
| FR-VIEW-01 | **List** (default): upcoming events grouped Today / This week / This month / Later, with countdown. |
| FR-VIEW-02 | **Calendar**: month grid with event dots, tap a day for its events. |
| FR-VIEW-03 | **Timeline**: scrollable year view. |
| FR-VIEW-04 | **People**: alphabetical directory with completeness indicators. |
| FR-VIEW-05 | Empty states for every view that lead to import or add, never a blank screen. |
| FR-VIEW-06 | **Adaptive layout.** Every screen branches on `WindowSizeClass`. Compact → single pane with bottom nav. Medium → navigation rail. Expanded → navigation rail + **two-pane list-detail** (People↔detail, Events↔detail) via Material 3 Adaptive (`OD-15`). |
| FR-VIEW-07 | **Foldables.** Layout is fold-aware: in book posture the hinge is treated as a pane separator rather than being drawn over. The app must survive fold/unfold, rotation and window resize **without losing state or restarting** — this is the part that actually breaks, so it needs explicit state-restoration tests. |
| FR-VIEW-08 | On expanded widths, dialogs become side sheets and bottom sheets become dialogs. The composer, poster preview and generation flows stay single-pane regardless of width. |
| FR-VIEW-09 | `resizeableActivity=true`, no orientation locks anywhere, full landscape and multi-window/split-screen support. Target Play's large-screen quality tier: no letterboxing, no stretched phone UI. |
| FR-VIEW-10 | The Glance widget declares sizes appropriate to tablet home screens as well as phones. |

### 7.12 Monetisation — `FR-MON`

| id | Requirement |
|---|---|
| FR-MON-01 | **Free**: unlimited people and events, all reminders, all views, widget, import, AI wish text, gift suggestions, pre-made poster templates with attribution. |
| FR-MON-02 | **Premium** — **₹99/month or ₹799/year**: no attribution, premium template sets, custom live-generated posters, **10 credits/month**, priority render queue, early access. |
| FR-MON-03 | **Credits** (consumable): custom AI poster = 1, AI video = 5, AI blend = 3. Packs of 10 / 50 / 150. Premium's 10 monthly credits expire at period end; purchased pack credits never expire (`OD-5`, `FR-MON-06`). |
| FR-MON-03a | ⚠️ **Margin guard.** ₹99 less Play's 15% is ~₹84/month against a cache-miss cost of ~₹3–15 per poster. The free pre-approved theme library (₹0 marginal) must therefore be the *default* path in the UI, with live generation positioned as the deliberate premium choice. Revisit the allowance once M6 reports a real cache-hit rate. |
| FR-MON-04 | All digital purchases go through **Google Play Billing**. Credits are consumable products; Premium is a subscription with base plans monthly and annual. |
| FR-MON-05 | Purchases are **server-verified** against the Play Developer API before credits are granted. Never trust the client. |
| FR-MON-06 | Credit ledger is append-only (grant, consume, refund, expiry) with a derived balance. Credits from packs never expire; monthly Premium credits expire at period end. |
| FR-MON-07 | Generation is debited **only on success**; a failed render refunds automatically. |
| FR-MON-08 | Paywall is contextual (shown at the moment of value, not on launch) and always states the price in INR including taxes. |
| FR-MON-09 | Affiliate revenue flows entirely outside Play Billing (physical goods exemption). |
| FR-MON-10 | Restore purchases, and graceful handling of grace period / account hold / pause states. |

### 7.13 Settings, privacy, account — `FR-SET`

| id | Requirement |
|---|---|
| FR-SET-01 | Notification settings: ladder toggles, delivery hour, quiet hours, digest mode, per-channel control. |
| FR-SET-02 | Battery-optimisation helper (`FR-REM-06`) permanently accessible. |
| FR-SET-03 | **Export my data** — full JSON + CSV of people, events, history, delivered by email link. |
| FR-SET-04 | **Delete my account** — in-app and via public web URL, irreversible, with explicit confirmation. |
| FR-SET-05 | Privacy policy and terms, reachable in-app and publicly. |
| FR-SET-06 | Data retention: uploaded source photos purged after 90 days unless attached to a saved poster; generated media after 180 days with a warning; both user-overridable to "keep". |
| FR-SET-07 | Content report action on all generated output. |
| FR-SET-08 | Theme (light/dark/system), language, and the dd/MM vs MM/dd preference. |

### 7.14 Family tree — `FR-TREE` *(v2, documented so the schema is right now)*

| id | Requirement |
|---|---|
| FR-TREE-01 | Ego-centric graph, ~3 generations up and down, edges: `PARENT_OF`, `SPOUSE_OF`, `SIBLING_OF`. |
| FR-TREE-02 | Derived relationship labels inferred by traversal (mother's sister → Aunt). |
| FR-TREE-03 | Pan/zoom Compose Canvas rendering with collapse/expand by branch. |
| FR-TREE-04 | Deceased flag renders differently and suppresses birthday reminders unless the user opts into remembrance. |
| FR-TREE-05 | Invite-to-claim via signed deep-link token (`OD-2`). |
| FR-TREE-06 | **v1 obligation:** the `relationship` edge table and `calendar_system` column exist in the v1 schema so none of this needs a migration later. |

---

## 8. Data model (v1)

```
user              id, google_sub, email, name, photo_url, locale, timezone,
                  created_at, deleted_at

person            id, user_id (owner), display_name, photo_key, gender,
                  phone, email, relation_to_owner, is_self, is_deceased,
                  is_minor (derived), notes, completeness_score,
                  created_at, updated_at, deleted_at

person_trait      id, person_id, kind (HOBBY|LIKE|DISLIKE|BUDGET_BAND|...),
                  value, source (USER|IMPORT|INFERRED), created_at

event             id, person_id, user_id,
                  type (BIRTHDAY|WEDDING_ANNIVERSARY|
                        ENGAGEMENT_ANNIVERSARY|DEATH_ANNIVERSARY|CUSTOM),
                  sentiment (CELEBRATORY|SOLEMN),   -- derived from type
                  label, day, month, year (nullable),
                  calendar_system (default GREGORIAN),
                  is_muted, created_at, updated_at, deleted_at

event_occurrence  id, event_id, occurs_on (date), override_date (nullable),
                  milestone_label, computed_at     -- materialised per year
                  -- effective date = COALESCE(override_date, occurs_on)

reminder          id, event_occurrence_id, user_id, offset (D7|D1|D0),
                  scheduled_at_utc, channel (FCM|LOCAL),
                  status (PENDING|SENT|DELIVERED|FAILED|SUPPRESSED),
                  dedupe_key, sent_at

wish_log          id, person_id, event_occurrence_id, channel, tone,
                  language, message_text, asset_id, sent_at

gift_log          id, person_id, event_occurrence_id, title, category,
                  price_band, partner, clicked_at, marked_given_at

asset             id, user_id, person_id, kind (POSTER|VIDEO|SOURCE_PHOTO),
                  r2_key, width, height, bytes, theme_id,
                  generation_id, expires_at, created_at

generation        id, user_id, feature, provider, model, prompt_hash,
                  status, cost_inr, latency_ms, cache_hit, created_at

theme_cache       id, theme_key (unique), r2_key, event_type, palette,
                  aspect_ratio, approved, hit_count, created_at

credit_ledger     id, user_id, delta, reason (PURCHASE|GRANT|CONSUME|
                  REFUND|EXPIRY), ref_id, balance_after, created_at

subscription      id, user_id, play_purchase_token, product_id, state,
                  current_period_end, created_at, updated_at

relationship      id, from_person_id, to_person_id,
                  type (PARENT_OF|SPOUSE_OF|SIBLING_OF)   -- v2 UI, v1 schema

device            id, user_id, fcm_token, platform, app_version,
                  timezone, last_seen_at
```

**Indexing notes.** `event_occurrence(occurs_on, user_id)` drives the nightly scheduler.
`reminder(scheduled_at_utc, status)` drives the send loop. `theme_cache(theme_key)` must be unique
or caching silently fails.

---

## 9. API surface (v1)

```
POST   /v1/auth/google                 exchange Google ID token → session
POST   /v1/auth/refresh
POST   /v1/auth/logout
DELETE /v1/account                     irreversible deletion
GET    /v1/account/export

GET    /v1/sync?cursor=                delta pull (people, events, history)
POST   /v1/sync                        delta push (batched upserts + tombstones)

GET    /v1/people            POST /v1/people
GET    /v1/people/:id        PATCH /v1/people/:id      DELETE /v1/people/:id
POST   /v1/people/:id/traits
POST   /v1/people/import/file          multipart CSV/XLSX → job
GET    /v1/import/:jobId

GET    /v1/events/upcoming?from=&to=
POST   /v1/events            PATCH /v1/events/:id      DELETE /v1/events/:id

POST   /v1/devices                     register FCM token
POST   /v1/reminders/test
POST   /v1/reminders/:id/ack           delivery telemetry

POST   /v1/ai/wish                     → { drafts[] }
POST   /v1/ai/poster                   → { generationId }  (async)
GET    /v1/ai/generation/:id
POST   /v1/ai/gift-ideas               → { ideas[] }
POST   /v1/ai/report                   content report

GET    /v1/themes                      pre-approved template library
POST   /v1/wishes                      log a sent wish
POST   /v1/gifts/click                 log affiliate click-out
POST   /v1/gifts/given

GET    /v1/billing/products
POST   /v1/billing/verify              Play purchase token verification
GET    /v1/billing/credits
POST   /v1/billing/webhook             Play RTDN
```

---

## 10. AI pipeline and cost control

**Providers.** Claude for all text and structured reasoning (Haiku for high-volume wish drafting,
Sonnet for gift reasoning). OpenAI `gpt-image-1` for background artwork and the optional AI-blend
edit path. Both sit behind `LlmProvider` / `ImageProvider` interfaces so either can be swapped
without touching feature code.

**Hard rules.**

1. No model is ever called from the client. No API key ever ships in the app.
2. Every call is wrapped in a `generation` record with measured INR cost.
3. Structured outputs only — JSON schema enforced and validated server-side; a malformed response is
   retried once, then falls back to a template.
4. Prompts are versioned files in the repo, not string literals scattered through services.
5. Background artwork is cached by theme key and reused indefinitely.
6. Per-user rate limits, a global daily spend cap, and automatic degradation to the pre-approved
   theme library when the cap is hit.
7. Input and output moderation on every generation, with user reporting.

**Cost model to validate during the AI milestone** (`TBD-1` — verify against live pricing):

| Item | Rough unit cost |
|---|---|
| Wish draft (Haiku, short) | fractions of a paisa |
| Gift ideas (Sonnet, structured) | ~₹0.3–1 |
| Background art, `gpt-image-1`, cache **miss** | ~₹1–15 depending on quality tier |
| Background art, cache **hit** | ~₹0 (R2 egress is free) |
| Poster composite (`sharp`, own CPU) | ~₹0 |
| Video render (ffmpeg, own CPU) | CPU seconds only |

The entire economic thesis rests on the cache-hit rate. Instrument it from day one.

---

## 11. Non-functional requirements

| Area | Requirement |
|---|---|
| Offline | Full read access offline. Add/edit offline, queued and synced. Only AI and gifting require network. |
| Sync | Last-write-wins per field on `updated_at`, soft deletes as tombstones, monotonic cursor. Single-owner data, so conflicts are rare by construction. |
| Performance | Cold start to list < 2s on a mid-range device. List scroll 60fps at 1,000 people. |
| Reliability | Reminder delivery > 98% on the OEM test matrix (G1). |
| Security | TLS only, certificate pinning on release builds, tokens in EncryptedSharedPreferences / Keychain, R2 objects served via short-lived signed URLs, no PII in logs or analytics. |
| Secrets (public repo) | No key, token, keystore or service-account file is ever committed — see `.gitignore`. Enable GitHub **secret scanning + push protection** on the repo. Keys live in `.env` locally and in the host's secret store in CI/deploy. A leaked key must be **rotated at the provider**, since deleting the commit does not unpublish it. The `gpt-image-1` and Anthropic keys are the highest-value targets: both bill directly to your card. |
| Accessibility | TalkBack labels on all interactive elements, minimum 4.5:1 contrast, dynamic type support. |
| Large screens | `minSdk 26`. Adaptive layouts across compact/medium/expanded, fold-aware, no orientation locks, no letterboxing, state preserved across fold/rotate/resize. Verified on a foldable and a 10" tablet before every release. |
| Tone safety | Automated tests assert that no `SOLEMN` event can ever produce celebratory copy, a celebratory template, a gift suggestion or a T-7 gifting reminder. Treated as a correctness requirement, not a content preference. |
| Observability | Structured JSON logs, Sentry on both client and server, a `/health` endpoint, queue depth and job-failure alerting. |
| Testing | Unit tests on date maths, recurrence, timezone and milestone logic (highest bug density by far); integration tests on sync and billing verification; one end-to-end smoke covering sign-in → add → reminder. |

---

## 12. Privacy and compliance checklist

- [ ] Privacy policy and terms published at a stable public URL before Play submission.
- [ ] Play **Data Safety** form completed and truthful.
- [ ] Prominent disclosure screen before the contacts permission.
- [ ] In-app **and** public web account-deletion path (Play requirement).
- [ ] Data export (DPDP access right).
- [ ] Retention limits enforced by a scheduled job, not just stated in policy.
- [ ] Minor flag suppresses profiling, personalisation and affiliate tracking.
- [ ] No behavioural advertising anywhere in the app.
- [ ] GenAI content reporting mechanism (Play GenAI policy).
- [ ] Input/output moderation on all generated content.
- [ ] Affiliate relationship disclosed on the gift surface.
- [ ] Music licensing cleared before any video ships.
- [ ] Breach response note in `/docs`.

> ⚠️ **Hard external gate:** a personal Google Play developer account created after Nov 2023 must
> run a **closed test with 12+ testers for 14 consecutive days** before it can apply for production
> access. This is calendar time independent of the build. Create the account and recruit testers
> early.

---

## 13. Analytics (`OD-10`)

Funnel: `app_open` → `signin_complete` → `person_added` → `import_completed` →
`notification_delivered` → `notification_opened` → `wish_sent` → `poster_generated` →
`gift_idea_viewed` → `gift_clicked` → `paywall_shown` → `purchase_completed`.

North-star metric: **wishes sent per active user per month.** It is the only event that proves the
product did its job.

Health metrics: notification delivery rate by OEM · theme cache-hit rate · INR cost per active user ·
D1/D7/D30 retention · profile completeness distribution.

No PII in any analytics payload. Minor-flagged records are excluded from all behavioural events.

---

## 14. Tech stack

**Client** — Kotlin Multiplatform + Compose Multiplatform · Android first, iOS target kept
compiling · Koin (DI) · Ktor client · SQLDelight · kotlinx.serialization · kotlinx.datetime ·
Coil · Glance (widget) · Play Billing 7+ · Firebase Messaging · WorkManager + AlarmManager ·
Material 3 Adaptive + `WindowSizeClass` (foldable/tablet) · `minSdk 26`.

`expect/actual` boundary: notifications, alarms, contacts, secure storage, billing, share intents,
photo picker, deep links.

**Server** — NestJS (TypeScript) · PostgreSQL + Prisma · Redis + BullMQ · Cloudflare R2 ·
`sharp` (compositing) · `ffmpeg` (video, v1.1) · Firebase Admin (FCM) · Google Play Developer API ·
Anthropic SDK · OpenAI SDK · Zod validation · Pino logging.

**Infra** — Docker + docker-compose for local · Railway or Fly.io for staging/prod ·
GitHub Actions with path filters · Sentry · PostHog.

---

## 15. Repo structure

```
wishkaro/                        (public GitHub repo)
├── composeApp/
│   ├── commonMain/              ui, domain, data, di
│   ├── androidMain/             actuals, widget, billing, contacts
│   └── iosMain/                 actuals (kept compiling, not shipped in v1)
├── server/
│   └── src/modules/             auth, people, events, reminders, import,
│                                ai, billing, gifts, media, sync
├── contracts/                   OpenAPI spec → generated Kotlin + TS clients
├── infra/                       docker, compose, deploy, migrations
├── docs/                        REQUIREMENTS.md, ADRs, prompts, rename checklist
├── .github/workflows/
└── CLAUDE.md                    conventions for Claude Code
```

---

## 16. Milestones

| M | Name | Definition of done |
|---|---|---|
| **M0** | Walking skeleton | Google sign-in → add one person → syncs to Postgres → server schedules FCM → notification lands on a **real phone**. Deployed to Railway, CI green. Nothing else. |
| M1 | People & events | Full CRUD, relations, multiple events, year-unknown handling, list view, offline sync |
| M2 | Reminder engine | Ladder, local fallback, de-dup, quiet hours, OEM helper, self-test, digest |
| M3 | Import | Contacts + CSV/XLSX, mapping, dedup, result report |
| M4 | Views & widget | Calendar, timeline, Glance widget, notification actions |
| M5 | Wish sending | AI drafts, tone/language, WhatsApp handoff, wish history |
| M6 | AI posters | Theme library, cache, `gpt-image-1` backgrounds, `sharp` compositing, cost ledger, moderation |
| M7 | Monetisation | Play Billing, server verification, credit ledger, paywall |
| M8 | Gift suggestions | Profile wizard, Claude ideas, affiliate deep links, click logging, gift history |
| M9 | Privacy & launch | Export, deletion, retention jobs, policy pages, Data Safety, closed test |
| M10 | AI video (v1.1) | ffmpeg pipeline, music licensing, queue + completion push |
| M11 | Family tree (v2) | Graph UI, inference, invite-to-claim |

Each milestone ends with a **working, installable app**. No milestone is "backend only".

---

## 17. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Notification unreliability on Indian OEMs | Kills the core promise | Dual delivery, OEM helper, self-test, measured delivery rate |
| Play's 12-tester / 14-day gate | Blocks launch by weeks | Start the developer account and tester recruitment now |
| AI cost per user exceeds revenue | Unprofitable | Credits, caching, spend caps, cost ledger from day one |
| Affiliate conversion is very low | Revenue line evaporates | Cheap to build (no catalog); treat as upside, not the plan |
| Scope paralysis from "no deadline" | Never ships | M0 skeleton, vertical slices, installable app at every milestone |
| Music licensing for video | Legal exposure | Clear licences before v1.1 ships; no YouTube-sourced audio |
| Solo-developer bandwidth | Everything | Ruthless v1 scope; tree already cut |

---

## 18. TBD register

| id | Item | Needed by |
|---|---|---|
| TBD-1 | Verify live `gpt-image-1` and Claude pricing; finalise credit economics | M6 |
| TBD-2 | Choose and register with an affiliate network (`OD-7`, deferred by decision) | Post-launch |
| TBD-3 | Re-check credit allowance against the measured cache-hit rate | After M6 |
| TBD-4 | Solemn copy and template art direction — review by hand, do not ship AI-only | M5 |
| TBD-5 | Visual identity: palette, type, icon, poster template art direction | M4 |
| TBD-6 | Licensed music library for video | M10 |
| TBD-7 | Hosting choice: Railway vs Fly.io | M0 |
| TBD-8 | Play Console account type and tester recruitment | Now |

---

*End of draft. Nothing in this document is final until its module is marked `AGREED`.*
