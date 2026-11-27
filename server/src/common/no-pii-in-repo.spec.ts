import { execFileSync } from 'node:child_process';
import { resolve } from 'node:path';

/**
 * **No real person's email address is committed to this public repository.**
 *
 * The check itself is `tools/check-no-pii.sh`, and this spec **calls it** rather than
 * reimplementing it. That is the point of the arrangement: there is exactly one definition of
 * what counts as a real address, and a developer running the server suite exercises the same
 * code CI does.
 *
 * ## Why the check does not live here
 *
 * It did, for about an hour, and a peer session found the hole. This spec runs in the server
 * suite; `.github/workflows/server.yml` is path-filtered to `server/**`,
 * `.github/workflows/server.yml` and `docs/fixtures/**` — and **the leak it was written for was
 * in `docs/M0_PREREQUISITES.md`**. A pull request that adds an address to `docs/` and touches
 * nothing under `server/` never runs this suite, so the guard would have stayed silent on
 * exactly the case that motivated it.
 *
 * `.github/workflows/privacy.yml` now runs the script with **no path filter at all**. This spec
 * remains because the server suite is what a developer runs locally, and a check nobody runs
 * until they push is a check that finds things late.
 *
 * ## Why four written rules did not prevent the leak it guards
 *
 * `MILESTONE_CHECKLIST.md` §F has said since M3 that fixtures carry invented people; M5 §0.5
 * extended it to screenshots; `docs/M9_VERIFY.md` §14 to export files; §14.1 to verification
 * documents. A real address still sat in `docs/` across four milestones. The checklist's own
 * conclusion applies — *where a mistake has a mechanical signature, write the grep instead.*
 */

const REPO_ROOT = resolve(__dirname, '../../..');
const SCRIPT = 'tools/check-no-pii.sh';

interface Run {
  status: number;
  stdout: string;
  stderr: string;
}

function runCheck(args: string[] = []): Run {
  const result = execFileSync('bash', [SCRIPT, ...args], {
    cwd: REPO_ROOT,
    encoding: 'utf8',
    // The script exits 1 on a hit, which is a result rather than a crash.
    stdio: ['ignore', 'pipe', 'pipe'],
  });
  return { status: 0, stdout: result, stderr: '' };
}

function runCheckAllowingFailure(): Run {
  try {
    return runCheck();
  } catch (error) {
    const e = error as { status?: number; stdout?: string; stderr?: string };
    return { status: e.status ?? -1, stdout: e.stdout ?? '', stderr: e.stderr ?? '' };
  }
}

describe('the public-repo rule, as a grep rather than a reminder', () => {
  it('the script exists and is executable from the repo root', () => {
    // Without this, a missing or unrunnable script would make the check below "pass" by
    // throwing something the catch turned into a result — the vacuous-green shape this project
    // has shipped twice and now checks for by habit.
    const pattern = runCheck(['--pattern']).stdout.trim();
    expect(pattern.length).toBeGreaterThan(20);
    expect(pattern).toContain('@');
  });

  it('the working tree contains no real personal or work email address', () => {
    const result = runCheckAllowingFailure();

    // On failure the script prints file:line to stderr and never the match, so surfacing its
    // output here cannot leak the address into a CI log either.
    expect({ status: result.status, where: result.stderr.trim() }).toEqual({
      status: 0,
      where: '',
    });
  });

  it('leaves the reserved example domains alone, so fixtures can use them', () => {
    // RFC 2606 and RFC 6761 reserve these. If this ever fails, the pattern has been widened
    // into something that will be switched off rather than fixed — which is the real failure
    // mode for a check like this one.
    const pattern = new RegExp(runCheck(['--pattern']).stdout.trim());

    for (const safe of [
      'nobody@example.invalid',
      'someone@example.test',
      'test@example.com',
      'dev@wishkaro.local',
      'spender@example.invalid',
    ]) {
      expect(pattern.test(safe)).toBe(false);
    }
  });

  it('still matches the shapes it is for', () => {
    // The other half of the previous test. A pattern narrowed until it matches nothing would
    // pass every test above and guard nothing at all.
    const pattern = new RegExp(runCheck(['--pattern']).stdout.trim());

    for (const real of ['someone.real@gmail.com', 'a.person@yahoo.com', 'someone@flobiz.in']) {
      expect(pattern.test(real)).toBe(true);
    }
  });
});
