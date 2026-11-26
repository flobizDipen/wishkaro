# M4 Build Prompt — Views and the Widget

> Paste the block below into a **fresh Claude Code session** opened at the repo root.
>
> M3 is merged (`75c3ea2`). `main` carries people and events CRUD, a reminder engine with a
> local alarm fallback, offline-first sync with a chunked push, and import from contacts and
> spreadsheets. M4 is the first milestone that is mostly surface — and the first one a user
> with 300 imported people will judge on sight.

---

## The prompt

```text
You are building M4 of WishKaro, a Compose Multiplatform + NestJS app. M0 to M3 are merged to
main and working: Google sign-in, people and events CRUD, deceased/solemn tone handling,
offline-first sync with a chunked outbox, a reminder ladder with local alarm fallback and
quiet hours, and import from device contacts and CSV/XLSX. Read docs/REQUIREMENTS.md for
product context, docs/M0_PLAN.md through docs/M3_PLAN.md for the decisions those milestones
locked in, and docs/MILESTONE_CHECKLIST.md, which is the standing process here. Work to that
checklist: every item in it was bought by a specific bug, and the milestone that paid for each
is named beside it. Sections C, D, E and F apply to this milestone as written; if you decide an
item does not apply, say which and why rather than passing over it. Sections G and H are
DIPEN'S, not yours — you do not review your own PR as the final word and you never merge.
Build ONLY what this prompt specifies.

## What M4 is

Making an app full of people usable. When M4 is done, this must be true on a REAL Android
phone and on a tablet or a foldable:

  Someone with 300 imported people opens WishKaro and finds what they are looking for
    -> the next birthdays first, grouped by how soon they are
    -> a month at a glance, and a year to scroll
    -> the same app on a tablet, a fold and in split-screen, without a restart
    -> and a home-screen widget that answers "whose birthday is it?" without opening anything

M3 made the 300-person case real rather than hypothetical: there are 566 invented people in
the owner's live account right now, deliberately left there (docs/M3_VERIFY.md §8). Use them.
Every list in this milestone should be looked at with that account loaded, not with a seeded
handful.

## Decide this before you write code

**Navigation is a `when` over a StateFlow on an Application-scoped singleton, and four
separate things in M4 need it not to be.**

`AppStore.screen` is a `StateFlow<Screen>` on a Koin `single`, and `App.kt` dispatches on it.
There is no back stack, no saved state, and no way to express two panes at once.

Four M4 requirements press on exactly that:

  - **FR-VIEW-06** wants two-pane list-detail on expanded widths. "List" and "detail" then
    have to be true at the same time, which one `Screen` value cannot say.
  - **FR-VIEW-07** wants fold, unfold, rotation and resize with no lost state and no restart,
    with explicit state-restoration tests. A singleton survives configuration change, but it
    survives the Activity too, and that is not the same thing.
  - **FR-WID-02** adds a second deep-link entry point beside M2's `wishkaro://person/<id>`.
    Both have to land somewhere with a sensible back stack behind them.
  - **FR-VIEW-01..04** add four top-level destinations, which usually means per-tab history.

This is not a theoretical concern in this codebase. **Two of M3's seven findings were this
exact failure**: a stale `_result` on an Application-scoped singleton bounced the user out of
import because there was no BackHandler and system back finished the Activity while the store
kept its state; and `_busy` outlived the Activity on a coroutine that never resumed. M3 fixed
both at the call site. M4 multiplies the surface.

So: keep the hand-rolled navigator and make it correct, or replace it. If you replace it,
Navigation 3 is the obvious candidate and this session has a `navigation-3` skill available —
read it before deciding, not after. Weigh it honestly: a navigation rewrite touching every
screen in the app is a large change to land in a milestone that also has a widget in it, and
"large change plus new surface" is how milestones stop being reviewable.

Whatever you choose, write it in docs/M4_PLAN.md with what you are giving up, and show me
before building on it.

**Second, smaller decision: Glance and Compose Multiplatform.** Glance is Android-only and
androidx-only. The widget is `androidMain` by definition — but decide where the line sits
between it and the shared code it renders, so the widget reads the same upcoming-events
query the list does rather than growing a second one. Two implementations of "what is coming
up" is the duplication this project has refused three times.

## What already exists — do not rebuild it

  - `BirthdayMath.nextOccurrence` already handles 29 February, year-unknown dates and
    timezones. Every countdown in this milestone goes through it.
  - `PeopleListScreen` exists with search, filter and an empty state that now leads to
    import. It does NOT group by Today / This week / This month / Later — FR-VIEW-01 is new.
  - The deep link `wishkaro://person/<id>` is already handled in `MainActivity` and already
    arrives from notifications. The widget joins that path rather than inventing one.
  - Notification actions exist with the right copy and the right ABSENCES — a SOLEMN reminder
    offers no Gift ideas and no Snooze (FR-REM-11). **Send wish and Gift ideas deliberately
    open the person and draft nothing**; that is M5 and M8. M4 does not change that, and must
    not quietly start.
  - `person.completeness_score` is server-derived and already mirrored locally, for
    FR-VIEW-04's completeness indicators. The client never computes it.
  - WorkManager is already wired for the daily reminder upkeep. FR-WID-03's midnight refresh
    is a second job on existing infrastructure, not new plumbing.

## Also still carried over

  - **The OEM battery helper has never run on a non-Pixel device.** Unchecked across M2 and
    M3. Close it or leave it honest.
  - **The mobile-data leg of the Railway deploy is still unproven.** Same rule.
  - **Two review findings are open on `main`** unless a follow-up PR has landed by the time
    you read this — check. A row the server refuses is set aside with no way for the user to
    learn of it (`LocalStore.observeBlockedCount` and `blockedRows` have no callers), and
    import row numbers do not match the user's file when a row is blank or absent. If they are
    still open, say so and do not build over them.
  - **The dedup rule's precision at scale** (`M3_VERIFY.md` §6.2) was flagged as worth
    deciding before M4. It is a product decision, not yours to take alone — raise it.
  - **The name-token table never runs against Kotlin/Native**: CI compiles the iOS target but
    runs no tests on it.

## Scope

### Views (FR-VIEW-01..05)

  - **List**, the default: upcoming events grouped Today / This week / This month / Later,
    with countdowns.
  - **Calendar**: a month grid with event dots; tapping a day shows that day's events.
  - **Timeline**: a scrollable year.
  - **People**: the alphabetical directory, with completeness indicators.
  - An empty state for every one of them that leads to import or add. Never a blank screen.

### Adaptive layout (FR-VIEW-06..09)

  - Compact → single pane with bottom navigation. Medium → navigation rail. Expanded →
    rail plus two-pane list-detail, for People↔detail and Events↔detail (OD-15).
  - Dialogs become side sheets and bottom sheets become dialogs on expanded widths. The
    composer and generation flows stay single-pane whatever the width — they are M5 and M6,
    so this is a rule to establish, not a screen to build.
  - Fold-aware: in book posture the hinge separates panes rather than being drawn over.
  - `resizeableActivity=true`, no orientation locks, full landscape and split-screen.
  - **State survives fold, unfold, rotation, resize and multi-window, with tests.** FR-VIEW-07
    calls this the part that actually breaks. Treat it as the milestone's hardest claim.

### Widget (FR-WID-01..03, FR-VIEW-10)

  - Glance, three sizes, including sizes that suit a tablet home screen.
  - Today and this week's events, with a count and names.
  - Tapping an entry deep-links to that person; tapping the header opens the app.
  - Updates on data change and at least daily at midnight local.

## Explicitly NOT in M4

Any AI, wish drafting or WhatsApp handoff (M5). Posters or photo upload (M6). Billing (M7).
Gift suggestions (M8). Export or account deletion (M9). Family tree (v2). Hindi localisation
(v1.1). No new server endpoints — M4 is a client milestone, and if you find yourself adding
one, stop and say why.

Send wish and Gift ideas still open the person. Do not start M5 inside M4.

## Definition of done

1. All four views work on a real phone against the account with 566 people in it, and the
   list is usable at that size — scrolling, searching and filtering without visible jank.
2. Every view's empty state leads somewhere.
3. Two-pane list-detail on a tablet or a large foldable, with the rail, and dialogs as side
   sheets.
4. Fold and unfold, rotate, and enter split-screen on a real device: no restart, no lost
   state, including mid-edit state in a form.
5. State restoration has automated tests, not only a device walkthrough.
6. The widget in all three sizes on a real home screen, showing real data.
7. Tapping a widget entry opens that person; tapping the header opens the app.
8. The widget updates after a data change, and after midnight.
9. Unit tests for the grouping boundaries (Today / This week / This month / Later across a
   month end and a year end), the calendar's month grid, and whatever navigation state the
   decision above produces.
10. CI green. docs/M4_VERIFY.md with an honest row per item, in the shape M3_VERIFY.md §9
    uses, including anything you could not prove and why.

## Engineering rules

Everything from M0 to M3 still applies, and docs/MILESTONE_CHECKLIST.md is the list. In
addition:

- The repo is PUBLIC. No secrets, and no real people in anything committed.
- Derived values stay server-derived. A view never computes a completeness score.
- One implementation of "what is coming up", shared by the list and the widget.
- A one-shot navigation needs a one-shot signal. The checklist says this because M3 paid for
  it; M4 is where it gets tested properly.
- Screenshots on a real device before claiming a layout works. The device procedure in
  §E of the checklist applies to every on-device sequence, and its newest item is that a
  locked phone looks exactly like a working one to `am start`.
- Conventional commits, small and reviewable. Branch off main, PR at the end. main is
  merge-only and merging is Dipen's call, not yours.

## How to work

1. Write docs/M4_PLAN.md — the navigation decision first, then the Glance boundary, then your
   implementation order. Show it to me before writing code.
2. Check whether the two open M3 findings have landed. Say what you find.
3. Whatever the navigation decision produces, with its state-restoration tests. Everything
   else sits on it.
4. The list, with the grouping and its boundary tests, against the 566-person account.
5. Calendar and timeline.
6. Adaptive layout, then fold and resize on a real device.
7. The widget.
8. Stop and tell me when it works. Do not continue into M5.

If any requirement here conflicts with docs/REQUIREMENTS.md, stop and ask — do not silently
pick one. M0 to M3 each hit at least one and raising it was the right call every time.
```

---

## Before you run this

| Check | Why |
|---|---|
| PR #4 merged, `main` green, and the M3 follow-up landed | M4 branches from it and should not build over known defects |
| A tablet or a foldable, real or emulated | Items 3 and 4 cannot be checked on a phone, and an emulated fold is acceptable where a real one is not available — say which was used |
| The account with 566 people still intact | It is the only honest test of item 1, and deleting it is a deliberate act, not a tidy-up |
| A non-Pixel phone, if one has appeared | M2's item 7 is unchecked across two milestones now |

## Why navigation goes first

M2 put delivery reporting first so everything after it was measurable. M3 put the outbox
first because it was the floor everything else stood on. M4's floor is navigation: four new
destinations, two panes, two deep-link entry points and a requirement to survive being folded
in half all rest on it, and a navigation decision taken late is a navigation decision that
gets worked around rather than made.

## The thing most likely to go wrong

State that outlives the screen. M3 hit this twice in one milestone — a stale result that
bounced the user out of import, and a busy flag that locked the picker shut — and both came
from UI state living on an Application-scoped singleton while the Activity came and went. M4
adds rotation, folding, resizing, split-screen and two deep links, which is five more ways for
the Activity to go away and come back. The checklist item that came out of M3 is the one to
hold onto: **a one-shot navigation needs a one-shot signal, not a persistent value.**

## After M4

M5 is wish sending: AI drafts, tone and language, WhatsApp handoff, and wish history. It is
the first milestone where the app writes something a user will send to another human, and
where the SOLEMN/CELEBRATORY distinction stops being about which buttons appear and starts
being about what the words say. Write its prompt in this same shape.
