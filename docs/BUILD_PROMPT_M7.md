# M7 Build Prompt — Identity and first run

> **This replaces the old M7 (Monetisation).** Monetisation moved to v2 by Dipen's decision on
> 2026-11-27, together with every AI provider call. See `REQUIREMENTS.md` §3 and §16.

## The prompt

Give a fresh Claude Code session this file, with `cwd` = `/Users/dipenjansari/wishkaro`.
That path matters: `composeApp/google-services.json` is gitignored and exists only there.

---

## Why this milestone exists at all

Nine milestones built features against `MaterialTheme()` called with **no arguments**, under a
comment in `Theme.kt` reading *"Design arrives with the features that need it, not in the
skeleton."* It never arrived. That is TBD-5, open since M4.

The state of `main` today:

- Every one of the 14 screens renders in Material 3's **baseline purple**.
- There is **no dark theme**. Not light-first — light-only. `isSystemInDarkTheme` appears nowhere.
- There is **no splash screen**. No `core-splashscreen`, no `windowSplashScreenBackground`.
  A cold start shows a blank window.
- There is **no custom font**. Zero font files in the repo.
- The launcher icon background is `#FF6750A4` — Material's default purple, never changed.
- `FR-AUTH-01`'s three-screen value carousel **does not exist**. First launch goes to sign-in.
- There are exactly **three** shared components: `Fields.kt`, `NavGlyphs.kt`, `WishKaroTopBar.kt`.

And the reason this is a tractable milestone rather than a rewrite: across **81 composables**
there are **208 reads** of `MaterialTheme.colorScheme` / `MaterialTheme.typography` and exactly
**2 hardcoded colours**, both in `PosterScreen.kt`. The screens were built correctly against the
theme. Replacing the theme propagates to all of them.

**With AI and monetisation deferred to v2, the UI is the product.** v1 is a reminder app with
curated wishes, posters and gift ideas. Nothing in it is novel. The only thing that can make it
worth keeping on a home screen is how it feels.

---

## The direction, decided — do not reopen it

Dipen chose **"Warm paper"** on 2026-11-27, over "Soft modern" and "Festive gradient".
This answers **TBD-5** and **A3** in `OPEN_DECISIONS.md`.

| Token | Light | Dark |
|---|---|---|
| ground | `#FDFBF7` ivory | `#15131C` |
| ink | `#15131C` | `#FDFBF7` |
| accent | `#C8742A` saffron | `#E09A52` |
| solemn accent | `#8A8578` desaturated | `#8A8578` |

- **Type.** `Fraunces` for names, dates and headings. `Inter` for UI, body and labels.
  Display sizes are large: **the date is the hero, not the chrome.**
- **Shape.** 12dp cards, 4dp chips, soft rather than pill-round.
- **Motion.** Slow and settled. ~300ms ease-out. Not springy, not bouncy.

**The reason this direction won, and the constraint it imposes:** the same list holds a
five-year-old's birthday and a death anniversary. Warm paper is the only direction where the
second one is **not a redesign** — the solemn path is the same layout with the accent
desaturated. Build it so that is literally true (see Scope item 3). If you find yourself writing
a second set of screens for solemn events, you have taken a wrong turn.

The exact hex values above are a starting point, not scripture. You may adjust them to meet the
contrast rule below. You may **not** change the direction.

---

## What M7 is NOT

- **No new features.** M7 closes `FR-AUTH-01` and nothing else. Every other requirement it
  touches, it touches only in appearance.
- **No AI.** No provider calls, no API key, no `OPENAI_API_KEY` in any `.env`.
- **No monetisation.** No Play Billing, no paywall, no credits. That is v2.
- **No gift catalog, no wish templates, no poster templates.** That is M8, and it needs the
  identity M7 establishes. Resist it.
- **No server changes.** This milestone is `composeApp/` and `tools/`. If you believe you need a
  server change, stop and say why instead.
- **No new screens** except the onboarding carousel.

---

## Scope

### 1. Design tokens

`composeApp/src/commonMain/kotlin/in/wishkaro/app/ui/theme/` gains:

- `Color.kt` — a full `lightColorScheme` and `darkColorScheme`, every Material 3 role filled
  deliberately rather than derived. Do not use dynamic colour; the identity is the point.
- `Type.kt` — the full `Typography` scale, Fraunces on display/headline/title, Inter on
  body/label.
- `Shape.kt` — the `Shapes` set.
- `Motion.kt` — named durations and easings, so "settled" is a token and not a number retyped
  in 40 places.

`Theme.kt` applies all four and honours `isSystemInDarkTheme()`.

### 2. Fonts, bundled

Fraunces and Inter go in `composeApp/src/commonMain/composeResources/font/` as files in the
repository. **Not** `google-fonts` downloadable fonts: the app must render identically in
airplane mode and on first launch with no network. Both are OFL — **vendor the licence file**
alongside them. A public repository shipping fonts without their licence is a real problem, not
a formality.

### 3. Solemn as a theme variant, not a branch

Provide a composition-local (`LocalSolemn` or equivalent) that swaps the accent to the
desaturated value. A solemn event's screens should differ from a celebratory one's **only** by
what that local returns. Prove it: one screenshot pair of the same screen, same layout, one
token different.

M5 already built the solemn ladder and a restricted wish path. You are theming it, not
redesigning it. Read `M5_VERIFY.md` before touching it.

### 4. Splash

`androidx.core:core-splashscreen` (minSdk is 26, so the compat library is required). Icon on the
ivory ground, handing off to the first Compose frame **without a white flash**. The flash is the
entire reason this item exists — verify it with a screen recording of a cold start, not by
looking at it once.

### 5. Launcher icon

Replace `#FF6750A4`. A real adaptive icon: background, foreground, and a **monochrome layer** so
Android 13+ themed icons work. Verify on an actual launcher, both themed and not.

### 6. `FR-AUTH-01` — the onboarding carousel

Three screens, **Remember · Wish · Gift**, skippable, shown once and persisted across restart
and across process death. This closes a requirement that has been NOT MET since M0 and that
nobody noticed until 2026-11-27.

A note on honesty: "Gift" has no feature behind it until M8, and "Wish" is template-backed from
M8 onward. Write carousel copy that is true of the app being shipped. Do not promise AI.

### 7. The restyle pass

Mostly free via tokens, but walk all 14 screens and fix what tokens cannot:

- The 2 hardcoded colours in `PosterScreen.kt`.
- Empty states — `M4_VERIFY` says they "lead somewhere"; make them look like they do.
- Type hierarchy. The date should dominate. Right now everything is `bodyMedium`.
- Edge-to-edge insets. There is an `edge-to-edge` skill available; use it, and check that no
  list, button or field ends up under a system bar.

### 8. A real component set

Three shared components for 14 screens is why it looks generic. Add what the screens actually
repeat — avatar with initials fallback, date badge, event card, section header, empty state,
button pair, loading placeholder. **Derive the list from the screens**, not from this paragraph.

### 9. Dark theme

All 14 screens, the widget, and the onboarding carousel. No unreadable surface, no
white-on-white card, no icon that disappears.

---

## The contrast rule, which is not optional

Saffron on ivory is **close to the legibility threshold**. A rough calculation puts `#C8742A`
on `#FDFBF7` near **3.5:1** — enough for large text (3:1) and UI components, **not enough for
body text (4.5:1)**.

So:

- **The accent is for large text, icons, and fills with ink on top. Never for body text.**
- Measure **every** foreground/background pair you ship, in both themes, and record the numbers
  in `M7_VERIFY.md` as a table. Do not report "checked" — report figures.
- Where a pair fails, darken the token. The direction survives a darker saffron; it does not
  survive being unreadable.

Do not trust the figure above. Compute it.

---

## Definition of done

Each of these is checkable by someone who did not write it. Report each as **met** or
**NOT MET** — `M9_VERIFY.md` §16 is the model, and M9's lesson is that "partial" is a word that
hides things.

1. `Theme.kt` applies colour, type, shape and motion, and honours `isSystemInDarkTheme()`.
2. Screenshots of **all 14 screens** in **both** themes, in `M7_VERIFY.md`.
3. Fonts render from the bundle with **airplane mode on**, on the handset. Licence files committed.
4. `FR-AUTH-01`: three screens, skippable, shown once, surviving restart **and process death**,
   verified on the handset.
5. Cold start shows **no white flash** — verified by screen recording, attached or described frame
   by frame.
6. Launcher icon replaced, monochrome layer present, verified on a launcher in both themed modes.
7. **Zero hardcoded colours outside `ui/theme/`**, enforced by `tools/check-no-raw-colors.sh`
   behind an **unfiltered** workflow. Model it on `tools/check-no-pii.sh` — M9's lesson was that
   a guard inside a path-filtered workflow is blind to the case that motivated it.
8. A contrast table with measured numbers for every pair, both themes.
9. The solemn variant proved to be one token, with the screenshot pair.
10. The **21 instrumented tests still pass** on the handset, and the unit suites stay green.

If an item cannot be met, say so and say why. Merging with items NOT MET is Dipen's call to make,
not yours to avoid by rewording.

---

## Engineering rules

- **The repository is public.** No secrets, no real names, no real photographs, no real email
  addresses — in code, in fixtures, in screenshots, or in `M7_VERIFY.md`. Screenshots will contain
  people: use invented ones. Run `tools/check-no-pii.sh` **before** pushing, never after.
- AGP stays on 8.x. Do not upgrade it as part of a design milestone.
- Do not touch `server/`.
- Do not answer anything in `OPEN_DECISIONS.md` §A or §B. Those are Dipen's. Ask instead —
  `MILESTONE_CHECKLIST.md` records that lesson twice already.
- Keep commits readable. One concern per commit.

## How to work

Write `docs/M7_PLAN.md` first, with the component inventory derived from the screens and the
token set you intend to ship. Get it right before writing Compose. Then build. Then write
`docs/M7_VERIFY.md` with the evidence, item by item.

Branch `feat/m7-identity`, PR into `main`.

## After M7

M8 — **Curated content**: wish templates, real poster templates, and a gift idea catalog, each
landing as a third `curated` implementation behind the existing `LlmProvider` / `ImageProvider`
interfaces. M8 needs M7's identity to design poster templates against, which is why it is second.
