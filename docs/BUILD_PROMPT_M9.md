# M9 Build Prompt — Privacy (the half that needs no Play Console)

**Read `docs/REQUIREMENTS.md` first — all of it.** §7.13 (`FR-SET`) and §12 are the scope, but
§11 governs how, and §10's hard rules still apply to anything that touches a generation. M5
missed §10 entirely because it quoted requirements by FR- id and §10's rules have none; §12 is
a **checklist of unticked boxes with no ids at all**, which is the same trap one step worse.

Then read `docs/MILESTONE_CHECKLIST.md`, and `docs/M6_VERIFY.md` §0.7 — the five-reviewer pass
that found 31 defects in code whose own suite and device run were green. The lesson it draws
about itself is the one that matters here: the author, the tests and the verification were the
same person against the same fakes.

---

## The prompt

Build M9's privacy half. Work on a branch off `main`, open one PR, and do not merge it.

Before you write any code, write `docs/M9_PLAN.md`: the four decisions below with what each one
gives up, the order you will build in, and every collision you find between this prompt and
`REQUIREMENTS.md` **raised rather than resolved**. M5 raised three and all three were real; M6
raised seven.

---

## What M9 is

Four things the app has never had, all of which Play or the DPDP Act requires before it can
ship, and none of which needs an API key or a Play Console account:

1. **Export my data** (FR-SET-03) — full JSON and CSV of people, events and history.
2. **Delete my account** (FR-SET-04) — in-app **and** from a public web URL, irreversible.
3. **Privacy policy and terms** (FR-SET-05) — reachable in-app and at a stable public URL.
4. **Retention enforced by a job** (FR-SET-06) — source photos purged after 90 days unless
   attached to a saved poster, generated media after 180, both overridable to "keep".

Plus one compliance gap that shipped three milestones ago: §12 asks for a **prominent disclosure
screen before the contacts permission**, and M3 shipped contacts import without one. It is a Play
requirement, it is cheap, and it is the kind of thing that is found by a reviewer rather than by
you.

---

## What M9 is NOT

**The launch half.** §12's other boxes — the Data Safety form, the closed test with 12+ testers
for 14 consecutive days — need a Play Console account that does not exist yet. They are calendar
time, not build time, and they are Dipen's to start in parallel. Do not block on them and do not
tick them.

Also not here: M7 (monetisation), M8 (gifts), M10 (video), M11 (tree). Nothing in this milestone
should need `OPENAI_API_KEY`.

---

## Decide this before you write code

Four. All four are Dipen's. Put each in `M9_PLAN.md` with what it costs either way and **do not
pick for him.**

### 1. Where do the policy pages and the public deletion URL live?

FR-SET-05 wants a **stable public URL** before Play submission, and FR-SET-04's deletion path is
a web page, not an app screen. This project has no website. The API runs at
`wishkaro-production-0312ab77.up.railway.app` — a generated hostname that changes if the service
is ever recreated, which is the opposite of stable, and it is the host Play would be given as the
canonical location of the privacy policy.

The options differ in what they tie together, so say so rather than guessing: serving the pages
from the existing NestJS app is no new infrastructure and ties the legal pages to the API's
uptime and to that hostname; a domain in front of Railway fixes the stability and costs money and
a DNS decision; static hosting elsewhere separates them entirely and adds a second thing to
deploy. **Whether to buy a domain is not a session's call.**

### 2. How is the export delivered?

FR-SET-03 says "delivered by email link". **This app cannot send email.** Firebase is here for
FCM and nothing else; there is no SMTP, no provider, no key, and adding one is a new external
dependency in a milestone whose whole point is that it needs none.

There is an alternative already built: the blob store mints short-lived signed URLs
(`SIGNED_URL_TTL_SECONDS`), which is how posters already leave the server. An in-app download
needs no new vendor and no new secret. It is also a weaker reading of the requirement — an email
link survives the app being uninstalled, which is exactly the moment somebody wants their data.

Raise it. If the answer is email, that is a new provider and a new key and it belongs in the plan
as such.

### 3. What does "irreversible" delete, exactly — and what survives?

This is the one with a real conflict in it, so work it through rather than deciding it.

A deletion has to reach: the Postgres rows (person, event, trait, wish, poster, generation,
asset, device, refresh token, settings), the **blob store objects** (photos and posters are files,
not rows, and a deleted row leaves its bytes), and the **FCM tokens** — a device left registered
keeps receiving pushes for an account that no longer exists, which is the most visible possible
failure of a deletion.

But `ai_usage` is a **cost ledger**, and §10 rule 2 makes a row per call non-negotiable because it
is how the product's unit economics are known. Deleting a user's ledger rows destroys financial
history; keeping them keeps a `user_id` attached to a user who asked to be erased. The usual
resolution is to anonymise rather than delete — null the user, keep the cost — and that is a
decision about what the business keeps, not a schema detail.

Say what you propose for each table, and let him choose.

### 4. How is deletion tested, given the only account is his?

The live Railway database holds the owner's own account: **566 invented people and 2 real
contacts** (`M3_VERIFY.md`). Testing account deletion end to end means deleting a real account
with real data in it, and the whole point of the feature is that it cannot be undone.

So: a second Google account used as a throwaway, or a local stack with seeded data, or a
deliberate decision to verify it only against Postgres in CI and never on the live deployment.
Each of those proves something different. Item 10 of the definition of done turns on which one he
picks.

---

## What already exists — do not rebuild it

| Thing | Where | What M9 does with it |
|---|---|---|
| Scheduled jobs | `QUEUE_MATERIALISE` (nightly), `queue.constants.ts` | The retention sweep is a repeatable BullMQ job. There is already a nightly one to copy. |
| Blob store | `modules/media/blob-store.ts`, signed URLs | Deleting objects, and possibly delivering the export. `isDurable` and `storeName` already report what kind of store is behind it. |
| Settings screen | `ui/settings/SettingsScreen.kt` (M2) | Export, delete and the policy links are rows on a screen that exists. |
| Content report | `POST /v1/ai/report` (M6) | §12's GenAI reporting box. **Already ticked** — do not rebuild it, do tick it. |
| Moderation | `modules/moderation/` (M6) | §12's input/output moderation box, on both surfaces. Already ticked. |
| Minor flag | `isMinorFor`, FR-PPL-09 | §12's "minor flag suppresses profiling" box. Verify it, do not rebuild it. |
| Soft deletes | `deletedAt` everywhere, sync tombstones | **Account deletion is NOT a soft delete.** Read how tombstones work before you write a hard one, because the sync protocol has opinions about rows vanishing. |

---

## Scope

### Export (FR-SET-03)
People, events, history. JSON **and** CSV. The CSV must round-trip into M3's importer — the
corrected-CSV path already proves a shape, so use it rather than inventing a second one.

### Deletion (FR-SET-04)
In-app, and a public web page. Irreversible, with explicit confirmation naming what goes. The web
path must work for somebody who has uninstalled the app, which means it cannot depend on an app
session.

### Policy and terms (FR-SET-05)
Reachable in-app and publicly, at whatever URL decision 1 settles on.

### Retention (FR-SET-06)
A scheduled job, not a stated intention — §12 says so in as many words. 90 days for source
photos unless attached to a saved poster, 180 for generated media, both overridable to "keep"
per item. A warning before generated media is purged.

### Prominent disclosure (§12)
Before the contacts permission, saying what is read and why, in the user's words. M3 shipped
without it.

### The checklist itself
§12's boxes are unticked in the document. Tick the ones this milestone genuinely closes, leave
the rest, and say in `M9_VERIFY.md` which were already true before you started (several are).

---

## Definition of done

1. An export produced for the 575-person account, opened, and checked to contain the people,
   events and wish history actually in it — not merely a file that downloaded.
2. Its CSV re-imported through M3's importer without a mapping change.
3. An account deleted from the app, per decision 4's method, and verified gone: no rows, **no
   blob objects**, no device token, and a push to that token no longer delivers.
4. The same deletion driven from the public web URL, for a user with no app session.
5. The retention job run against seeded data of the right ages, with the keep-override honoured
   and the 90/180 boundaries hit exactly — including a photo attached to a saved poster, which
   must survive.
6. Policy and terms reachable from inside the app and from a browser with no session.
7. The prominent disclosure shown before the contacts permission on the handset.
8. §12's checklist updated, with each tick traceable to something in `M9_VERIFY.md`.
9. Nothing in this milestone requires an API key, and `M9_VERIFY.md` says so explicitly.
10. Both targets compile, the server suite green across three timezones, CI green on the exact
    head.

Anything that cannot be proved is **BLOCKED**, named, and not rounded up. M5 and M6 both did this
well; keep the standard.

---

## Engineering rules

- **Deletion is a hard delete, and the sync protocol has to survive it.** Rows vanishing is not
  the same as rows tombstoned, and four entities have cursors that assume monotonic `updatedAt`.
  Think about what a client with a stale cursor sees after its account is gone.
- **The bytes matter as much as the rows.** A deletion that leaves photographs in a blob store is
  not a deletion; it is a database change.
- **Nothing in a log, ever** (§11). Not an export path, not a deletion confirmation, not a
  filename. A user's data is PII and a deletion is the worst moment to write some down.
- **The public deletion page is unauthenticated by definition.** Whatever proves identity there is
  the single most attackable surface this app has. Say in the plan how it works before building
  it, because "a link we email" and "sign in with Google again" are very different threat models.
- **A test must ask the layer that holds the rule** — M5 shipped two vacuous tests.
- **The revert-check is not a formality.** A green revert-check is a signal to investigate; M5
  had three and each was a real gap.
- **A comment that makes a performance claim carries the measurement or does not make the claim.**
- The repo is public: no key, no `.env`, no service account, ever.
- Test data is invented people. The 575-person account is M3's seeded set plus two real contacts;
  do not put a real person's data into a fixture or a document.

---

## How to work

- Branch off `main`, one PR, do not merge it.
- `docs/M9_PLAN.md` before code. Raise collisions; do not resolve them quietly.
- Commit in coherent steps with real messages, ending with
  `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.
- Verify by doing. M6's two most valuable bugs were only reachable on a handset.
- `docs/M9_VERIFY.md` separates **proved**, **read-verified** and **blocked**. Three different
  claims.
- Do not push `main` and do not merge; Dipen handles both.

---

## Why retention goes first

Because a clock is already running on it, and nothing else in this milestone has one.

M6 began storing **photographs of real people** — uploaded, EXIF-stripped, composited — and
nothing purges anything. Every photo and every generated poster accumulates on a personal Railway
deployment, indefinitely, for a project whose repository is public. FR-SET-06 is the only thing
that stops it, §12 insists it be a job rather than a sentence in a policy, and every day it is not
built the pile is larger.

Export and deletion are requirements. Retention is a requirement with interest accruing.

---

## The thing most likely to go wrong

**A deletion that reports success and leaves something behind.**

It has three favourite hiding places in this codebase, and all three exist today:

1. **Blob objects.** Photos and posters are files keyed by id. Delete the row and the bytes stay,
   with no row left to find them by — undiscoverable *and* undeleted, which is the worst
   combination.
2. **FCM device tokens.** A token outlives the account unless something removes it. The failure is
   visible and awful: a reminder about somebody's late father, pushed to a phone whose owner
   deleted their account last week.
3. **The ledger.** `ai_usage` rows carry `user_id` and §10 rule 2 says they must exist. Whatever
   decision 3 settles on, the code must do what the decision says and `M9_VERIFY.md` must state
   it, because "we deleted everything" and "we kept the costs" are both defensible and only one of
   them can be what the policy page claims.

So: enumerate every table and every store that holds something belonging to a user, write the list
in the plan before writing the deletion, and make the test assert on the *absence* of each one
rather than on the endpoint returning 204.

---

## Before you run this

Dipen must answer, or knowingly defer:

1. **Where the policy pages and the public deletion URL live** — and whether that means buying a
   domain.
2. **How the export is delivered** — email (a new vendor and a new key) or an in-app signed URL
   (nothing new, weaker reading of FR-SET-03).
3. **What deletion erases and what it anonymises**, table by table, with the `ai_usage` conflict
   named.
4. **How deletion is tested**, given the only live account is his own with two real contacts in
   it.

Decisions 1 and 2 may add work that is not in this prompt. Say so in the plan rather than
absorbing it quietly — M6 absorbed an entire photo subsystem and was right to, but it named it.

---

## After M9

M7, once the Play Developer API exists and TBD-1 can be answered against a real bill rather than a
published rate card. The launch half of M9 — Data Safety, the 14-day closed test with 12+ testers
— runs on Dipen's calendar, not on a session's, and §12's hard external gate says to start it
early. It is the longest-lead item in the whole project and nothing in a repository can shorten
it.
