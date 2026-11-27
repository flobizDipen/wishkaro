import type { Sentiment, ThemeFamily, PosterFormat } from '@prisma/client';

/**
 * The pre-approved theme library (FR-POS-04) and the rule that decides which themes an
 * occasion may use (FR-POS-13).
 *
 * ## Why the library is the product and live generation is the edge
 *
 * FR-POS-04: "A library of pre-generated, hand-approved themes ships with the app so the
 * common path requires **no** live generation at all." The library, the cache and the
 * compositor are therefore not a fallback for when generation fails — they ARE the poster
 * feature, and `gpt-image-1` is the premium edge. FR-MON-03a says the same thing from the
 * money side: ₹99 less Play's 15% is ~₹84/month against ₹3–15 per cache-miss poster, so the
 * ₹0-marginal library has to be the default path in the UI.
 *
 * ## Where the manifest lives, and why it is split
 *
 * FR-POS-04 says the library "ships with the app"; §9 serves it from `GET /v1/themes`;
 * FR-POS-01(c) composites on the server. Read literally those are three different homes for
 * one thing. Split, and raised in docs/M6_PLAN.md §3.5: the MANIFEST (ids, families,
 * palettes, display names) is small, ships in the app so the picker renders and filters with
 * no network, and is mirrored here; the ARTWORK is served and cached.
 *
 * The manifest is `docs/fixtures/poster-themes.json` — **one** table read by both sides, per
 * `MILESTONE_CHECKLIST.md` §C, in the pattern `wish-tones.json` established. If the two
 * drift, one of the two suites goes red.
 *
 * ## TBD-5
 *
 * Every theme in the file today is a visible placeholder and its display name says so.
 * FR-POS-04's "hand-approved" names a hand that is not a session's, and TBD-5 — due at M4,
 * still open — is what sets `theme_cache.approved`. The mechanism is what is being built;
 * the art is Dipen's, and answering TBD-5 costs this file and the palettes rather than the
 * pipeline.
 */

export interface ThemeBackground {
  from: string;
  to: string;
}

export interface PosterTheme {
  id: string;
  family: ThemeFamily;
  displayName: string;
  palette: string;
  motifs: string[];
  background: ThemeBackground;
  textColor: string;
}

export interface FormatSpec {
  ratio: string;
  width: number;
  height: number;
}

/**
 * The shipped manifest — a MIRROR of `docs/fixtures/poster-themes.json`, not a read of it.
 *
 * **The first version of this file did `readFileSync` on the fixture at module load, and
 * that was a production crash.** The Docker image copies `prisma`, `src`, `tsconfig*` and
 * `nest-cli.json` from `server/` and nothing else — `docs/` is not in it — so the container
 * would have thrown at boot, before the first request, every time. CI would not have caught
 * it: the `docker` job runs `docker build` and never starts the image, which is exactly the
 * gap `MILESTONE_CHECKLIST.md` §E records for the Android side ("a build that cannot start
 * passes CI — M2 proved it, with 308 green tests").
 *
 * So the manifest is TypeScript, exactly as `PosterTheme.kt` is Kotlin, and
 * `theme-library.spec.ts` reads the fixture and asserts the two agree. That is the same
 * arrangement both other shared tables already have — `tone-rules.ts` and `name-tokens.ts`
 * hold their rules in code and only their SPECS read `docs/fixtures/`. The fixture is the
 * table of cases; it was never meant to be a runtime asset.
 */
export const THEMES: readonly PosterTheme[] = Object.freeze([
  {
    id: 'ph-confetti',
    family: 'CELEBRATORY',
    displayName: 'Confetti — placeholder',
    palette: 'festive',
    motifs: ['confetti', 'streamers'],
    background: { from: '#F9A826', to: '#EF5DA8' },
    textColor: '#1A1A1A',
  },
  {
    id: 'ph-sunrise',
    family: 'CELEBRATORY',
    displayName: 'Sunrise — placeholder',
    palette: 'warm',
    motifs: ['sun', 'gradient'],
    background: { from: '#FFD166', to: '#EF476F' },
    textColor: '#1A1A1A',
  },
  {
    id: 'ph-bloom',
    family: 'CELEBRATORY',
    displayName: 'Bloom — placeholder',
    palette: 'floral',
    motifs: ['flowers', 'petals'],
    background: { from: '#A8E6CF', to: '#3D84A8' },
    textColor: '#0B2B33',
  },
  {
    id: 'ph-gold',
    family: 'CELEBRATORY',
    displayName: 'Gold — placeholder',
    palette: 'gold',
    motifs: ['foil', 'rays'],
    background: { from: '#F7E7A1', to: '#B8860B' },
    textColor: '#2B2000',
  },

  // The solemn family. Three separate entries in a SEPARATE family — not celebratory themes
  // with the motifs taken out. `domain/Tone.kt` took the same decision for words and said
  // why: a conditional is a thing someone can forget, and a set you have to pick is a thing
  // the compiler makes you pick. A remembrance template is never a birthday template with
  // the balloons removed.
  {
    id: 'ph-stillwater',
    family: 'SOLEMN',
    displayName: 'Still water — placeholder',
    palette: 'muted-blue',
    motifs: ['water', 'horizon'],
    background: { from: '#5C7A89', to: '#2E4057' },
    textColor: '#F2F4F5',
  },
  {
    id: 'ph-dusk',
    family: 'SOLEMN',
    displayName: 'Dusk — placeholder',
    palette: 'muted-grey',
    motifs: ['sky', 'dusk'],
    background: { from: '#8D99AE', to: '#2B2D42' },
    textColor: '#F2F4F5',
  },
  {
    id: 'ph-lamp',
    family: 'SOLEMN',
    displayName: 'Lamp — placeholder',
    palette: 'muted-amber',
    motifs: ['diya', 'flame'],
    background: { from: '#7A6652', to: '#2C2418' },
    textColor: '#F5EFE6',
  },
] satisfies PosterTheme[]);

/** FR-POS-05's three formats, at their exact sizes. Mirrored; the spec holds them to it. */
export const FORMATS: Readonly<Record<PosterFormat, FormatSpec>> = Object.freeze({
  STATUS: { ratio: '9:16', width: 1080, height: 1920 },
  SQUARE: { ratio: '1:1', width: 1080, height: 1080 },
  LINK: { ratio: '1.91:1', width: 1200, height: 630 },
});

/** FR-POS-05: "User picks; default is status." */
export const DEFAULT_FORMAT: PosterFormat = 'STATUS';

/**
 * FR-POS-13's forbidden motifs: "muted palette, no balloons, confetti, cake or party motifs".
 * Mirrored from the fixture's `banned_on_solemn`, which is what the spec asserts against.
 */
export const BANNED_ON_SOLEMN: readonly string[] = Object.freeze([
  'balloon',
  'balloons',
  'confetti',
  'cake',
  'party',
  'streamer',
  'streamers',
  'firework',
  'fireworks',
  'celebrate',
  'celebration',
  'congratulations',
  'congrats',
  'cheers',
  'hurray',
  'festive',
]);

/** One-to-one today, and written down rather than inlined — see `familyFor`. */
const FAMILY_FOR_SENTIMENT: Readonly<Record<Sentiment, ThemeFamily>> = Object.freeze({
  CELEBRATORY: 'CELEBRATORY',
  SOLEMN: 'SOLEMN',
});

const BY_ID = new Map(THEMES.map((t) => [t.id, t]));

/**
 * Which family an occasion's sentiment may use (FR-POS-13).
 *
 * One line, in one place, and it is written down rather than inlined because this is the
 * single most dangerous mapping in the milestone. A sentiment is a fact about an occasion
 * and a family is a property of a picture; they happen to be one-to-one, and two call sites
 * each deciding that for themselves is how they stop being.
 *
 * **The input is the sentiment the SERVER derived, by `sentimentFor()`, after the derivation
 * has run.** Never `person.isDeceased` read directly — M5 shipped two bugs of exactly that
 * shape in one milestone (`454ea75`, `cb5919d`), both "derived state read one step too
 * early", and M6's version of that failure is a picture of balloons on a remembrance.
 */
export function familyFor(sentiment: Sentiment): ThemeFamily {
  return FAMILY_FOR_SENTIMENT[sentiment];
}

/**
 * The themes an occasion may use. **A filter on the way in, not a sort.**
 *
 * FR-POS-13: "Celebratory themes are filtered **out of the picker** for those events" — not
 * ranked lower. The difference is the whole requirement: a ranked-lower celebratory theme is
 * still reachable by scrolling, and this is a product where the failure mode is somebody
 * sending a cake to a grieving family.
 *
 * Returns a new array each call rather than a cached one per family, because a caller that
 * sorted the result in place would quietly reorder the library for everybody.
 */
export function themesFor(sentiment: Sentiment): PosterTheme[] {
  const family = familyFor(sentiment);
  return THEMES.filter((t) => t.family === family);
}

/**
 * Whether this exact theme may be used for this occasion.
 *
 * The server's half of the rule. The client filters the picker so the user never sees a
 * forbidden theme; this is what holds when the request did not come from that picker. Both
 * halves are necessary and `MILESTONE_CHECKLIST.md` §C is why they are held to one table.
 */
export function isThemeAllowed(sentiment: Sentiment, themeId: string): boolean {
  const theme = BY_ID.get(themeId);
  if (!theme) return false;
  return theme.family === familyFor(sentiment);
}

export function themeById(themeId: string): PosterTheme | undefined {
  return BY_ID.get(themeId);
}

/**
 * The default theme for an occasion.
 *
 * Deliberately the FIRST of the occasion's own family rather than a named constant, so that
 * there is no way to express a default that is not in the family the occasion is allowed.
 * A constant would be a second place for the solemn rule to be got wrong.
 */
export function defaultThemeFor(sentiment: Sentiment): PosterTheme {
  const allowed = themesFor(sentiment);
  if (allowed.length === 0) {
    // Unreachable with the shipped manifest, and the fixture asserts both families are
    // non-empty. Throwing rather than falling back to THEMES[0]: a fallback here would be a
    // path that hands a celebratory theme to a remembrance when the manifest is edited
    // wrongly, which is the exact failure the family split exists to make impossible.
    throw new Error(`No themes in the ${familyFor(sentiment)} family — check poster-themes.json`);
  }
  return allowed[0];
}

export class ThemeNotAllowedError extends Error {
  constructor(sentiment: Sentiment, themeId: string) {
    super(`Theme "${themeId}" is not available for a ${sentiment} occasion`);
    this.name = 'ThemeNotAllowedError';
  }
}

/**
 * Refuse rather than coerce.
 *
 * Silently swapping a forbidden theme for an allowed one would hide a client bug for ever,
 * and the client in question is the one that decides what somebody sends to a grieving
 * family. `assertToneAllowed` took the same decision in M5 and for the same reason.
 */
export function assertThemeAllowed(sentiment: Sentiment, themeId: string): void {
  if (!isThemeAllowed(sentiment, themeId)) {
    throw new ThemeNotAllowedError(sentiment, themeId);
  }
}
