import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { nameTokens, tokensOverlap } from './name-tokens';

/**
 * Half of the shared table. The other half is NameTokensTest.kt, reading THIS FILE's fixture.
 *
 * The rule exists twice, in two languages, because import dedup runs on the phone offline
 * and create-time dedup runs here. MILESTONE_CHECKLIST.md §C is explicit about the price:
 * a rule implemented twice is held to one shared table of cases. The table is the fixture,
 * not a copy of it — if the two implementations ever drift again, one of these two suites
 * goes red.
 *
 * Both implementations were wrong before M3, in different ways and on different scripts.
 * See docs/M3_PLAN.md §3.
 */

interface Fixture {
  cases: { name: string; tokens: string[]; why: string }[];
  duplicate_pairs: { a: string; b: string; match: boolean; why: string }[];
}

const fixture: Fixture = JSON.parse(
  readFileSync(join(__dirname, '../../../../docs/fixtures/name-tokens.json'), 'utf8'),
) as Fixture;

describe('nameTokens — the shared table of cases', () => {
  it('has a table to read', () => {
    // A path typo would otherwise make this whole suite silently vacuous.
    expect(fixture.cases.length).toBeGreaterThan(15);
    expect(fixture.duplicate_pairs.length).toBeGreaterThan(5);
  });

  it.each(fixture.cases)('$name -> $why', ({ name, tokens }) => {
    expect([...nameTokens(name)].sort()).toEqual([...tokens].sort());
  });
});

describe('tokensOverlap — the shared table of pairs', () => {
  it.each(fixture.duplicate_pairs)('$a vs $b -> $why', ({ a, b, match }) => {
    expect(tokensOverlap(nameTokens(a), nameTokens(b))).toBe(match);
  });
});

describe('the specific failures this rule was fixed for', () => {
  it('does not shatter a Devanagari name at its vowel signs', () => {
    // The old Kotlin rule gave [जयदत, सर] here: \p{L} excludes combining marks, and
    // Devanagari builds words out of them.
    expect([...nameTokens('जयदत्त जांसरी')]).toEqual(['जयदत्त', 'जांसरी']);
  });

  it('does not reduce a Gujarati name to nothing', () => {
    // The old rule here was `[^a-z0-9ऀ-ॿ]` — ASCII plus the Devanagari block. A Gujarati
    // name tokenised to the empty set, and findDuplicates returns [] on an empty set, so
    // every Gujarati person silently had NO duplicate detection at all.
    const tokens = nameTokens('જયદત્ત જાંસરી');
    expect(tokens.size).toBe(2);
  });

  it('keeps accented Latin whole, so both sides derive the same tokens', () => {
    // The old rule gave [jos, lvarez]; the client gave [josé, álvarez]. The two sides
    // could therefore disagree about whether two records were the same human.
    expect([...nameTokens('José Álvarez')]).toEqual(['josé', 'álvarez']);
  });
});
