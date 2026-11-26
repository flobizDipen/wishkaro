import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import type { EventType, PosterFormat, Sentiment, ThemeFamily } from '@prisma/client';
import {
  BANNED_ON_SOLEMN,
  DEFAULT_FORMAT,
  FORMATS,
  THEMES,
  ThemeNotAllowedError,
  assertThemeAllowed,
  defaultThemeFor,
  familyFor,
  isThemeAllowed,
  themeById,
  themesFor,
} from './theme-library';
import { NO_MILESTONE, themeKeyFor } from './theme-key';

/**
 * The fixture is read HERE and nowhere else.
 *
 * `theme-library.ts` holds the manifest as TypeScript and this suite holds it to the shared
 * table — the same arrangement `tone-rules.ts` and `name-tokens.ts` already have, where the
 * rule lives in code and only the spec reads `docs/fixtures/`.
 *
 * That split is not tidiness. The first version of `theme-library.ts` did `readFileSync` on
 * this file at module load, and the Docker image copies only `server/` — no `docs/` — so the
 * container would have thrown at boot every time, and CI's `docker build` would not have
 * caught it because it never starts the image.
 */
interface ThemeFixture {
  families: ThemeFamily[];
  family_for_sentiment: Record<Sentiment, ThemeFamily>;
  aspect_ratios: Record<PosterFormat, { ratio: string; width: number; height: number }>;
  default_format: PosterFormat;
  banned_on_solemn: string[];
  themes: {
    id: string;
    family: ThemeFamily;
    displayName: string;
    palette: string;
    motifs: string[];
  }[];
  picker_cases: { sentiment: Sentiment; themeId: string; offered: boolean; why: string }[];
  key_cases: {
    themeId: string;
    eventType: EventType;
    milestone: string | null;
    palette: string;
    aspectRatio: string;
    key: string;
  }[];
}

const THEME_FIXTURE: ThemeFixture = JSON.parse(
  readFileSync(join(__dirname, '../../../../docs/fixtures/poster-themes.json'), 'utf8'),
) as ThemeFixture;

describe('the shipped manifest matches the shared table', () => {
  it('is actually reading the table', () => {
    // Without this, a path that stopped resolving makes every fixture-driven test below pass
    // by iterating an empty list — the one failure mode a fixture-driven suite has, and the
    // reason `WishToneRulesTest` opens the same way.
    expect(THEME_FIXTURE.picker_cases.length).toBeGreaterThanOrEqual(12);
    expect(THEME_FIXTURE.themes.length).toBeGreaterThanOrEqual(6);
  });

  it('names the same themes, in the same families, with the same palettes', () => {
    expect(new Set(THEMES.map((t) => t.id))).toEqual(
      new Set(THEME_FIXTURE.themes.map((t) => t.id)),
    );
    for (const f of THEME_FIXTURE.themes) {
      const mine = themeById(f.id);
      expect(mine).toBeDefined();
      expect(mine!.family).toBe(f.family);
      expect(mine!.displayName).toBe(f.displayName);
      expect(mine!.palette).toBe(f.palette);
      expect(mine!.motifs).toEqual(f.motifs);
    }
  });

  it('agrees about the formats and the default', () => {
    expect(FORMATS).toEqual(THEME_FIXTURE.aspect_ratios);
    expect(DEFAULT_FORMAT).toBe(THEME_FIXTURE.default_format);
  });

  it('agrees about the banned lexicon and the sentiment mapping', () => {
    expect([...BANNED_ON_SOLEMN]).toEqual(THEME_FIXTURE.banned_on_solemn);
    for (const [sentiment, family] of Object.entries(THEME_FIXTURE.family_for_sentiment)) {
      expect(familyFor(sentiment as Sentiment)).toBe(family);
    }
  });
});

/**
 * Half of the shared table. The other half is `PosterThemesTest.kt`, reading THE SAME FILE.
 *
 * `MILESTONE_CHECKLIST.md` §C: a rule implemented twice in two languages is held to one table
 * of cases. The server REFUSES a theme outside the occasion's family; the client FILTERS the
 * picker so one is never shown. Neither half can be dropped, and if they drift one of these
 * two suites goes red.
 *
 * `REQUIREMENTS.md` §11 files the rule under correctness rather than content preference:
 * "automated tests assert that no SOLEMN event can ever produce celebratory copy, **a
 * celebratory template**, a gift suggestion or a T-7 gifting reminder."
 */
describe('the poster theme library — the shared table of cases', () => {
  describe('the picker, case by case from the fixture', () => {
    for (const c of THEME_FIXTURE.picker_cases) {
      const verb = c.offered ? 'offers' : 'does NOT offer';
      it(`${verb} ${c.themeId} on a ${c.sentiment} occasion — ${c.why}`, () => {
        // Asserted through the function a caller actually uses, not against a list literal.
        // M5 §5.4: a test that restates the data is a test of the data.
        expect(isThemeAllowed(c.sentiment, c.themeId)).toBe(c.offered);
        expect(themesFor(c.sentiment).some((t) => t.id === c.themeId)).toBe(c.offered);
      });
    }
  });

  /**
   * Definition-of-done item 2, as a single assertion: "no celebratory theme reachable in the
   * picker".
   *
   * Deliberately NOT a restatement of the `offered: false` rows above. Those name seven
   * themes; this one holds for a theme somebody adds tomorrow without touching the cases
   * table, which is the shape that actually ships a bug.
   */
  it('NO celebratory theme is reachable for a solemn occasion', () => {
    const offered = themesFor('SOLEMN');

    expect(offered.length).toBeGreaterThan(0);
    expect(offered.every((t) => t.family === 'SOLEMN')).toBe(true);
    for (const theme of THEMES.filter((t) => t.family === 'CELEBRATORY')) {
      expect(isThemeAllowed('SOLEMN', theme.id)).toBe(false);
    }
  });

  /**
   * FR-POS-13's lexicon, on the DATA.
   *
   * M5 §0.3 is why this is here rather than on a prompt: a prompt has to NAME what it
   * forbids, so "never use confetti" fails its own check and the assertion is worthless. A
   * motif list is a list of things that get drawn, which is the right surface — these are
   * closer to M5's `WishTemplatesTest`, which could assert on template strings because those
   * are the words a recipient reads.
   */
  it('no solemn theme names a celebratory motif anywhere in its data', () => {
    for (const theme of THEMES.filter((t) => t.family === 'SOLEMN')) {
      const haystack = [theme.id, theme.displayName, theme.palette, ...theme.motifs]
        .join(' ')
        .toLowerCase();
      for (const banned of BANNED_ON_SOLEMN) {
        expect(haystack).not.toContain(banned);
      }
    }
  });

  it('the banned list is the one FR-POS-13 actually writes down', () => {
    // FR-POS-13: "muted palette, no balloons, confetti, cake or party motifs". If somebody
    // trims the list, this says which four the requirement names by name.
    for (const word of ['balloon', 'confetti', 'cake', 'party']) {
      expect(BANNED_ON_SOLEMN).toContain(word);
    }
  });

  it('both families are non-empty, so neither picker can be blank', () => {
    // `defaultThemeFor` throws on an empty family rather than falling back across the
    // boundary. An empty solemn family would be an empty picker, which FR-VIEW-05 forbids
    // and which would push somebody towards the celebratory tab.
    expect(themesFor('CELEBRATORY').length).toBeGreaterThan(0);
    expect(themesFor('SOLEMN').length).toBeGreaterThan(0);
  });

  it('every theme belongs to a declared family', () => {
    for (const theme of THEMES) {
      expect(THEME_FIXTURE.families).toContain(theme.family);
    }
  });

  it('theme ids are unique', () => {
    // They are the cache key's first component and a database column. A duplicate would make
    // two different pictures share one cache entry.
    expect(new Set(THEMES.map((t) => t.id)).size).toBe(THEMES.length);
  });

  describe('defaults', () => {
    it.each<[Sentiment]>([['CELEBRATORY'], ['SOLEMN']])(
      'the default theme for a %s occasion is in that occasion’s own family',
      (sentiment) => {
        expect(defaultThemeFor(sentiment).family).toBe(familyFor(sentiment));
      },
    );

    it('the default format is the WhatsApp status size (FR-POS-05)', () => {
      expect(DEFAULT_FORMAT).toBe('STATUS');
      expect(FORMATS.STATUS).toEqual({ ratio: '9:16', width: 1080, height: 1920 });
    });

    it('all three FR-POS-05 formats are present at their exact sizes', () => {
      expect(FORMATS.SQUARE).toEqual({ ratio: '1:1', width: 1080, height: 1080 });
      expect(FORMATS.LINK).toEqual({ ratio: '1.91:1', width: 1200, height: 630 });
    });
  });

  describe('refusing rather than coercing', () => {
    it('throws ThemeNotAllowedError for a celebratory theme on a solemn occasion', () => {
      // Refused, not swapped. A silent swap would hide the bug in whatever sent it — and the
      // sender here is the thing that decides what reaches a grieving family.
      expect(() => assertThemeAllowed('SOLEMN', 'ph-confetti')).toThrow(ThemeNotAllowedError);
    });

    it('throws for a theme that does not exist at all', () => {
      expect(() => assertThemeAllowed('CELEBRATORY', 'no-such-theme')).toThrow(
        ThemeNotAllowedError,
      );
    });

    it('allows a theme of the occasion’s own family', () => {
      expect(() => assertThemeAllowed('SOLEMN', 'ph-stillwater')).not.toThrow();
    });
  });
});

describe('the cache key — FR-POS-03, composed in one place', () => {
  for (const c of THEME_FIXTURE.key_cases) {
    it(`${c.themeId} / ${c.eventType} / ${c.milestone ?? 'no milestone'} → ${c.key}`, () => {
      expect(
        themeKeyFor({
          themeId: c.themeId,
          eventType: c.eventType,
          milestone: c.milestone,
          palette: c.palette,
          aspectRatio: c.aspectRatio,
        }),
      ).toBe(c.key);
    });
  }

  it('an absent milestone is a placeholder, never an empty component', () => {
    // FR-EVT-02 makes "year unknown" first-class, so no milestone is the COMMON case. An
    // empty component would let two different occasions collide on a trimmed key.
    const key = themeKeyFor({
      themeId: 'ph-confetti',
      eventType: 'BIRTHDAY',
      milestone: null,
      palette: 'festive',
      aspectRatio: '9:16',
    });
    expect(key).toContain(`|${NO_MILESTONE}|`);
    expect(key).not.toContain('||');
    expect(key).not.toContain('undefined');
  });

  it('every component changes the key', () => {
    // If one did not, two different pictures would share one cache entry and §8's unique
    // constraint would be protecting the wrong string.
    const base = {
      themeId: 'ph-confetti',
      eventType: 'BIRTHDAY' as const,
      milestone: null,
      palette: 'festive',
      aspectRatio: '9:16',
    };
    const keys = new Set([
      themeKeyFor(base),
      themeKeyFor({ ...base, themeId: 'ph-sunrise' }),
      themeKeyFor({ ...base, eventType: 'WEDDING_ANNIVERSARY' }),
      themeKeyFor({ ...base, milestone: '25th Silver' }),
      themeKeyFor({ ...base, palette: 'warm' }),
      themeKeyFor({ ...base, aspectRatio: '1:1' }),
    ]);
    expect(keys.size).toBe(6);
  });

  it('keys on the aspect RATIO, so a new pixel size reuses artwork of the same shape', () => {
    // FR-POS-03 says "aspect ratio" and means it: 9:16 artwork fits 720×1280 as well as
    // 1080×1920. Keying on the format NAME would make a future variant a cache miss against
    // artwork that fits it perfectly.
    const a = themeKeyFor({
      themeId: 'ph-confetti',
      eventType: 'BIRTHDAY',
      milestone: null,
      palette: 'festive',
      aspectRatio: '9:16',
    });
    expect(a).toContain('9:16');
    expect(a).not.toContain('STATUS');
  });
});
