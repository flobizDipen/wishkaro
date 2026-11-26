import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join } from 'node:path';

/**
 * **Nothing the Docker image ships may read a file the Docker image does not contain.**
 *
 * This exists because M6 wrote that bug and nothing else would have caught it.
 * `theme-library.ts` originally did `readFileSync('docs/fixtures/poster-themes.json')` at
 * module load. Locally it worked, `npm test` was green, `npm run build` was green, and
 * `docker build` in CI was green — because the `docker` job builds the image and never
 * starts it. The container would have thrown at boot, before the first request, on every
 * deploy.
 *
 * `server/Dockerfile` copies `package*.json`, `prisma`, `tsconfig*.json`, `nest-cli.json`
 * and `src`. `docs/` is not in the image and neither is anything else above `server/`.
 *
 * `MILESTONE_CHECKLIST.md` §E records the same shape on the Android side — "a build that
 * cannot start passes CI — M2 proved it, with 308 green tests". This is the server's version
 * of that lesson, as a test rather than a note.
 *
 * The shared tables of cases (`docs/fixtures/`) are for SPECS. Feature code mirrors them and
 * the spec asserts the mirror — which is what `tone-rules.ts` and `name-tokens.ts` already
 * do, and what `theme-library.ts` now does.
 */
describe('shipped server code reads nothing from outside the image', () => {
  const SRC = join(__dirname, '../..');

  /** Every `.ts` under `src/` that is not a test. These are the files that get compiled. */
  function shippedFiles(dir: string, found: string[] = []): string[] {
    for (const entry of readdirSync(dir)) {
      const path = join(dir, entry);
      if (statSync(path).isDirectory()) {
        shippedFiles(path, found);
      } else if (entry.endsWith('.ts') && !entry.endsWith('.spec.ts')) {
        found.push(path);
      }
    }
    return found;
  }

  const files = shippedFiles(SRC);

  it('finds the source tree', () => {
    // The guard against this whole suite passing because the walk returned nothing.
    expect(files.length).toBeGreaterThan(40);
  });

  it('no non-spec file reaches above server/ for a file at runtime', () => {
    const offenders: string[] = [];

    for (const file of files) {
      const source = readFileSync(file, 'utf8');
      // Strip comments before looking. Every one of these modules TALKS about
      // `docs/fixtures/` in its doc comment — correctly, since that is where the shared
      // table lives — and a test that could not tell a comment from code would either fail
      // on all of them or be switched off.
      const code = source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/(^|[^:])\/\/.*$/gm, '$1');

      if (/\bdocs\/fixtures\b/.test(code)) {
        offenders.push(`${file}: references docs/fixtures in code`);
      }
      // `..` escaping server/ in a path join. Three levels up from `src/modules/x/` is
      // `server/`; four is outside the image.
      if (/['"`](?:\.\.\/){4,}/.test(code)) {
        offenders.push(`${file}: a path escapes server/`);
      }
    }

    expect(offenders).toEqual([]);
  });
});
