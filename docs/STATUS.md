# Where WishKaro actually stands

> **Snapshot date:** 2026-11-27, at `main` = `6ddc0a5`.
> **This is a snapshot, not a source of truth.** `REQUIREMENTS.md` defines the requirements and
> the `*_VERIFY.md` documents record what was proved. Where this file disagrees with them, they
> win. Re-derive it rather than trusting it after the next milestone.

## The one-line answer

**v1 is 8 of its 10 milestones merged.** Everything that makes the app *work* — people, events,
reminders, import, the views, the widget, wish drafting, posters, and the privacy half — is on
`main`. What is missing from v1 is **the two milestones that make it a business**: M7
(monetisation) and M8 (gift suggestions). Nothing in the product can be charged for, and the
gift half of the pitch does not exist.

The honest qualifier: a large part of what *is* merged has **never run against a real AI
provider**, because there has never been an API key. It is built, tested against deliberate
fakes, and unexercised.

---

## How this was measured, and what that is worth

Area by area, from what is actually on `main`: the Prisma schema (15 models), the server modules
(13), the client screens (14), the widget, and targeted searches for each feature named in a
requirement. It is **not** a line-by-line audit of all 130 requirements, and I have not re-run
every milestone's verification by hand.

So treat the area verdicts as reliable and the exact counts as approximate. Where a requirement
is marked NOT MET below, that is because I checked and it is absent — not because a document
said so.

---

## Flows that work end to end

These are complete paths a person can walk through, on `main`, today.

| Flow | State |
|---|---|
| **Sign in → account exists** | Google Sign-In, server exchanges the ID token, user and owner person created. Proved on the handset. |
| **Add a person and an event → get reminded** | Add, edit, multiple events per person, soft delete with a 30-day restore, duplicate detection, minor flag. Server is the scheduling source of truth, client alarms as fallback, the full T-7 / T-1 / morning-of ladder, quiet hours, digest mode, snooze, leap-day handling, and the solemn ladder that drops the T-7 gifting nudge. |
| **Import contacts or a file → people** | Contacts import with the review screen, CSV/XLSX parsed on-device, column mapping, the date formats people actually type, a per-row result report, and idempotent re-import. |
| **See what is coming** | Four views — list, calendar, timeline, people directory — with empty states that lead somewhere, adaptive layout across window sizes, fold awareness, and a Glance widget in three sizes. |
| **Compose and send a wish** | Composer with tone and language options, regenerate, free editing, hand-off to WhatsApp/SMS/share, the "did you send it?" history capture, and a separate restricted path for solemn events. **The drafting itself runs on a fake provider.** |
| **Make a poster** | Theme library, cache, `sharp` compositor at three sizes, photo upload with EXIF stripped, async job with progress, cost ledger, rate limits and spend cap, moderation on input and output, content reporting, and a separate solemn theme family. **Generation runs on a fake provider.** |
| **Delete your account (in-app)** | 14-table inventory, proved in CI, locally, and by hand over real HTTP. The cost ledger is anonymised rather than destroyed, enforced by the database. |
| **Photos get purged** | Daily retention sweep with a warning that is a *precondition* of purging, not a notification sent afterwards. |

---

## Built, but not proved

Real code, real tests, no real-world exercise. This is the category that makes a percentage
misleading.

| What | Why it is unproved |
|---|---|
| **Every AI path** — wish drafting, poster copy, poster artwork, both moderation surfaces, the cost ledger's measured INR | No API key has ever existed. All of it runs against committed fakes. Eight definition-of-done items across M5 and M6 wait on this. |
| **Poster art direction** | Every theme on `main` is a visible placeholder. Nobody has yet judged whether a poster is one anybody would want to send — which is the product. |
| **The contacts prominent-disclosure screen** | Code, copy and 7 tests done; the APK installs and launches and stops at the sign-in screen. Needs a sign-in only Dipen can do. |
| **Reminder delivery on non-Pixel handsets** | The OEM battery helper is built and has never been exercised on a Xiaomi/Oppo/Vivo/Realme device — eight milestones now. The entire product is a reminder that must fire. |
| **Push after deletion** | Every row, blob and key proved gone; nobody has watched a push fail to arrive. |
| **Instrumented tests** | 21 pass on the handset by hand. None run in CI — there is no device in CI. |

---

## Not built at all

| Area | Requirements | Milestone |
|---|---|---|
| **Monetisation** — Play Billing, server verification, credit ledger, paywall, restore | 11 | M7, not started |
| **Gift suggestions** — profile wizard, ideas, affiliate links, click logging, gift history | 12 | M8, not started |
| **AI video** | 5 | M10 (v1.1) |
| **Family tree** | 6 | M11 (v2) |

Two consequences worth stating plainly:

- **The app cannot take money.** Credits, Premium, the paywall and the margin guard are all M7.
- **"Gift" is one of the three words in the value proposition** (*Remember · Wish · Gift*) and
  none of it exists. `FR-HIS-02` (gift history) is unbuildable until M8, which also makes
  `FR-HIS-03` — history feeding every prompt — half of what it is specified to be.

---

## Requirements in a shipped area that are still NOT MET

These are the ones a percentage hides, because their milestone merged.

| Requirement | State |
|---|---|
| `FR-AUTH-01` | **Not built.** The 3-screen value carousel (Remember · Wish · Gift) does not exist — no carousel, pager or onboarding screen anywhere in the client. First launch goes straight to sign-in. |
| `FR-AUTH-08` / `FR-SET-04` | **Half.** Deletion from Settings works and is proved. Deletion from a public web URL — which the Play requirement is about — is not built. |
| `FR-SET-03` | **Half.** The export producer is built and unit-proved; no export has been produced from an account with real data, and delivery is undecided. |
| `FR-SET-05` | **Not built**, deliberately: a policy page describing a deletion that does not exist is the worst artifact to publish. |
| `FR-TREE-06` | **Half, and nobody had this on a list.** It is an explicit *v1 obligation*: the relationship edge table **and** `calendar_system` exist in the v1 schema. `calendar_system` is there. **There is no relationship edge table in the schema** — no `Relationship` model, no `PARENT_OF` / `SPOUSE_OF` / `SIBLING_OF`. Adding it in v2 means a migration on populated production data, which is exactly what the obligation existed to avoid. |

---

## The numbers, with their caveat attached

Of **130 requirements**, roughly **120 belong to v1** (video and most of the tree are later).

- **~97** are in areas a merged milestone shipped.
- **~8** of those are the NOT MET rows above.
- **~13** more are built but unexercised, nearly all waiting on an API key.
- **23** belong to M7 and M8, which have not started.

So: **about three quarters of v1 is built, roughly two thirds is built and actually proved, and
the last quarter is the half that earns money.**

A truer summary than any percentage: **the app works and cannot be sold.**

---

## If I had to name three things

1. **The Play Console gate.** Twelve testers for fourteen consecutive days before production
   access. Every milestone merged so far took less time than that gate, and it has not started.
2. **The API key.** It blocks more items than anything else, and one of them is the only
   question that matters — whether a generated wish and a generated poster are *good*.
3. **`FR-TREE-06`.** The one thing on this page that gets harder with every day of real data.
   It is a schema obligation, it was written down in v1 precisely so v2 would not need a
   migration, and it was missed. Now in `OPEN_DECISIONS.md` as D6.
