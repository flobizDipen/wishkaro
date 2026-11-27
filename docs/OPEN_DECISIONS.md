# Open decisions and unfinished work

> **Status:** one list, kept deliberately, so that work parked "until the end" is parked
> somewhere rather than remembered.
> **Date opened:** 2026-11-27, after M9 merged unfinished.
> **Owner of every row in §A and §B:** Dipen. Nothing here is a Claude session's to decide.

Two milestones have now merged carrying items their own definition of done calls NOT MET, and
each recorded its own in its own `*_VERIFY.md`. That is the right place for the detail and the
wrong place for the list: nobody reads six verification documents to find out what is
outstanding. This file is the index. The verification documents stay authoritative — where they
disagree with this file, they win and this file is stale.

**What this file is not.** It is not a backlog of features, and it is not a bug list. Everything
here is either a decision only Dipen can make, an external gate no amount of building clears, or
work a milestone explicitly declined to do and named rather than dropped.

---

## A. Decisions that block merged requirements

These block requirements that are **already on `main`** in a state the spec does not consider
met. While they stay open, `main` delivers less than its presence suggests.

| # | Decision | Blocks | What it costs to answer |
|---|---|---|---|
| **A1** | **Where the policy pages and the public deletion URL live** — and whether that means buying a domain. The Railway hostname is generated, not stable, so it cannot be the answer. | M9 items 4 and 6; §12 box 1; the text of the policy page; FR-SET-04's web half and FR-SET-05 entirely | Money and DNS propagation. **Needed before Play submission regardless**, which is why it is first. |
| **A2** | **How the export is delivered.** FR-SET-03 says email. Email means a new vendor and a new key, and **makes M9 definition-of-done item 9 false**, since M9's whole premise was that it needs no API key. An in-app signed URL adds nothing new and is a weaker reading of FR-SET-03. | M9 items 1 and 2; §12 box 5 | Nothing but the decision. The signed-URL path is already built; the honest cost is recording FR-SET-03 as partially met rather than quietly redefining it. |
| **A3** | **TBD-5 — visual identity**: palette, type, icon, poster template art direction. Open since **M4**. | Three M6 items. Every theme on `main` is a visible placeholder, so **nobody has yet judged whether a poster is one anybody would want to send** — which is the product. | Taste and time, or money for a designer. |
| **A4** | **TBD-4 / M5 definition-of-done item 4** — a real remembrance draft, recorded verbatim and **read by Dipen**. Neither done nor deferred: M6 did not touch it, M9 did not touch it. | The solemn path's only human check. FR-EVT-05 exists to prevent a tone failure no test can see. | Ten minutes, once B1 exists. |

---

## B. External gates

No decision clears these. They cost money, an account, or calendar time.

| # | Gate | Blocks | Clock |
|---|---|---|---|
| **B1** | **An AI provider key** (`OPENAI_API_KEY`, and whatever M8 needs). | **Four M5 items and four M6 items.** TBD-1 (verify live pricing, finalise credit economics) and TBD-3 (re-check the credit allowance against the measured cache-hit rate) cannot start. §10 rule 7's moderation is structurally built on both surfaces and **exercised on neither**. | None of its own, but it blocks more items than anything else here. |
| **B2** | **Play Console account** — TBD-8, marked *Now*. | Everything about launch. | **The real one.** Closed testing requires **12 testers for 14 consecutive days** before production access. Every milestone merged so far took less time than that gate. Starting it late does not make it shorter. |
| **B3** | **TBD-2** — choose and register with an affiliate network (`OD-7`). | M8. Deferred by decision; recorded here so it is not rediscovered as a surprise. | Post-launch. |
| **B4** | **TBD-6** — licensed music library for video. | M10. | v1.1. |

---

## C. Defaults taken, each reversible in one line

M9 took these rather than stall, and named each one. They are live on `main`. Listed so that
reversing one is a decision rather than an archaeology exercise.

| # | Taken | Reversible by |
|---|---|---|
| C2 | `asset.retention` + `asset.purge_warned_at` — FR-SET-06's warning clause had nowhere to live | a migration |
| C3 | `poster.source_asset_id`, backfilled by the pipeline's own rule | a migration |
| C8 | One primary-key lookup in the auth guard, once deletion made it load-bearing. **The latency is unmeasured**, and the comment makes no claim about it | the Redis denylist option |
| C11 | `people.csv` is birthdays only | changing the header and teaching the importer a type column |

---

## D. Carried forward, owned by nobody

Each was named by the milestone that could not close it, then named again by the next. That is
the pattern worth watching: an item that appears in three consecutive close-outs is not being
carried forward, it is being avoided.

| # | Item | Milestones carrying it |
|---|---|---|
| **D1** | **OEM battery helper** unexercised on a non-Pixel device. The whole product is a reminder that must fire; on many Indian handsets it will not, and that has never been tested on one. | **Eight** |
| **D2** | **Mobile-data leg** of the Railway deploy, unproven — only ever reached over Wi-Fi. | Several |
| **D3** | **Instrumented tests do not run in CI** — no device in CI. 21 pass on the handset when somebody runs them by hand, and every device-only bug M6 found was found that way. | Since M4 |
| **D4** | **FR-POS-09's cache-hit target** is unmeasured and plausibly missed. The 85 ms figure in `M6_VERIFY` describes a code path the merged code no longer has. | M6 |
| **D5** | **`docs/fixtures/name-tokens.json` contains a name nobody has verified is invented**, while the file's own header **asserts** every person in it is invented, and three milestones read that file. Recorded in `M9_VERIFY` §15. Dipen has seen this and left it; it stays listed because the header makes a claim the repository cannot currently support. | M9 |
| **D6** | **`FR-TREE-06`'s schema obligation is half met.** It requires the relationship edge table **and** `calendar_system` to exist in the **v1** schema. `calendar_system` is there; **there is no relationship edge table** — no `Relationship` model, no `PARENT_OF` / `SPOUSE_OF` / `SIBLING_OF` anywhere in `schema.prisma`. The obligation was written into v1 precisely so that v2 would not need a migration on populated production data. Every day of real users makes it more expensive. Found 2026-11-27 while writing `STATUS.md`; no milestone had it on a list. | None — it was missed |

---

## E. Deliberately not in this file

One outstanding security item is **not written here, and should not be**. This repository is
public, and a public file describing where a credential lives on a developer's machine is a map
for anyone who reaches that machine. It is in the session record and Dipen has been told
directly. The rule generalises: **an open TODO about a secret belongs wherever the secret is,
and this repository is not that place.**

---

## How to use this file

- A row leaves by being **answered or done**, not by being reworded. Strike it through with the
  date and what was decided, as `M9_VERIFY` §16 does — a struck row is evidence, while a deleted
  row is a file that always looks short.
- When a milestone closes, it adds what it could not close, and strikes what it closed.
- §A and §B are Dipen's. A session that finds itself about to answer one of these should stop and
  ask instead — the lesson `MILESTONE_CHECKLIST.md` already records twice.
