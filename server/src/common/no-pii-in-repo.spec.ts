import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';

/**
 * **No real person's email address is committed to this public repository.**
 *
 * `MILESTONE_CHECKLIST.md` §F has said since M3 that fixtures carry invented people and never
 * real contacts. M5 §0.5 extended it to screenshots. `docs/M9_VERIFY.md` §14 extended it to
 * export files, and §14.1 to verification documents. Every one of those extensions was bought
 * the same way: by somebody nearly committing the thing.
 *
 * Four written rules and one found leak later — a real address sat in `docs/M0_PREREQUISITES.md`
 * from M0 until M9 — the lesson the checklist itself draws applies: *"where a mistake has a
 * mechanical signature, write the grep or the test instead."* A rule on a list did not stop
 * M6 shipping the same `${'$'}` bug twice more after it was listed. This is the grep.
 *
 * ## What it looks for, and what it deliberately does not
 *
 * Addresses at the consumer mail hosts a real person's account lives on, plus the owner's work
 * domain. **Not** a general email regex: `.env.example`, fixtures and specs are full of
 * deliberately fake addresses, and a check that flagged those would be turned off within a week
 * — which is the real failure mode for a test like this one.
 *
 * `example.invalid`, `example.test`, `example.com` and `wishkaro.local` are reserved by RFC 2606
 * and RFC 6761 precisely so that they can be used this way. They are what a fixture should use,
 * and they are not matched below.
 *
 * ## Scope, stated honestly
 *
 * This checks the **working tree**, not history. Removing an address does not unpublish it —
 * git history is permanent and public repositories are scraped, exactly as with a leaked
 * secret, and unlike a secret an address cannot be rotated. So this stops the next one from
 * being added; it cannot undo one already pushed.
 */

const REPO_ROOT = resolve(__dirname, '../../..');

/**
 * Hosts a real person's personal account lives on, and this project owner's work domain.
 *
 * Narrow on purpose. Widening it to "any address anywhere" would make the suite fail on its own
 * fixtures, and a test that cries wolf is a test that gets deleted.
 */
const REAL_ADDRESS = String.raw`[A-Za-z0-9._%+-]+@(gmail|yahoo|outlook|hotmail|icloud|proton|protonmail)\.(com|me)|[A-Za-z0-9._%+-]+@flobiz\.in`;

/** Files that are allowed to mention the pattern because they are ABOUT the pattern. */
const ALLOWED = [
  'server/src/common/no-pii-in-repo.spec.ts',
];

function grepTrackedFiles(pattern: string): string[] {
  try {
    /**
     * `--untracked`, which matters more than it looks.
     *
     * Plain `git grep` searches TRACKED files only, so a brand-new document carrying an
     * address — exactly the case this exists for, since a new verification document is how
     * M9 nearly did it — would not be searched at all until after it was committed, which is
     * one push too late.
     */
    const out = execFileSync('git', ['grep', '-nIE', '--untracked', pattern, '--', '.'], {
      cwd: REPO_ROOT,
      encoding: 'utf8',
    });
    return out.split('\n').filter((line) => line.trim() !== '');
  } catch (error) {
    // `git grep` exits 1 when it finds nothing, which is the passing case here.
    const status = (error as { status?: number }).status;
    if (status === 1) return [];
    throw error;
  }
}

describe('the public-repo rule, as a grep rather than a reminder', () => {
  it('runs against a real checkout', () => {
    /**
     * Without this, a `git grep` that failed for any reason — wrong cwd, no git, a bad
     * pattern — would make the test below pass by finding nothing. That is the vacuous-green
     * shape this project has shipped twice and now checks for by habit.
     *
     * Its first version grepped for the name of a constant that existed only in THIS file,
     * which was untracked at the time, so the sanity check itself found nothing and failed.
     * It now looks for a string that is unarguably in the committed tree.
     */
    expect(grepTrackedFiles('WishKaro').length).toBeGreaterThan(10);
  });

  it('contains no real personal or work email address', () => {
    const hits = grepTrackedFiles(REAL_ADDRESS).filter(
      (line) => !ALLOWED.some((allowed) => line.startsWith(`${allowed}:`)),
    );

    /**
     * The failure message names the file and line and **does not repeat the address**, which
     * is the whole point: a test that printed what it found would put the address into CI logs
     * and into the terminal of everybody who ran the suite.
     */
    const where = hits.map((line) => line.split(':').slice(0, 2).join(':'));
    expect(where).toEqual([]);
  });

  it('leaves the reserved example domains alone, so fixtures can use them', () => {
    // RFC 2606 and RFC 6761 reserve these. If this ever fails, the pattern above has been
    // widened into something that will be switched off rather than fixed.
    for (const safe of [
      'nobody@example.invalid',
      'someone@example.test',
      'test@example.com',
      'dev@wishkaro.local',
    ]) {
      expect(new RegExp(REAL_ADDRESS).test(safe)).toBe(false);
    }
  });
});
