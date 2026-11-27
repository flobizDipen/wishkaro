/**
 * **The DB-backed suites share one database, so this project's tests run SERIALLY.**
 *
 * `package.json` passes `--runInBand`. The reason is in `docs/M9_VERIFY.md` §14.5, and the
 * short version is that six `.db.spec.ts` suites talk to one Postgres and one Redis, and two
 * kinds of shared state make parallel workers race:
 *
 *  - **§10 rule 6's global spend cap is a SUM over `ai_usage` for the day.** It is global by
 *    design — it bounds the bill, not a feature — so any suite writing a ledger row moves the
 *    number every other suite's cap test reads.
 *  - **Unique keys and cleanup predicates.** `theme_cache.theme_key` is UNIQUE, and a cleanup
 *    written slightly wider than what a suite seeded deletes another suite's fixtures.
 *
 * Measured rather than assumed: parallel failed **3 runs in 5** on a developer machine whose
 * database had accumulated 1,543 ledger rows; `--runInBand` passed **5 in 5**, then 5 more.
 * CI has been green throughout because it starts from an empty database every time, which
 * narrows the window — that makes this a flake that fails locally and passes in CI, which is
 * the worse way round, because it trains people to re-run rather than to look.
 *
 * **It costs nothing.** Both modes complete in 7 seconds; the suite is I/O-bound on Postgres,
 * not CPU-bound. Determinism for free.
 *
 * Per-suite database isolation would be the thorough fix and is not worth it at this size.
 */
/** @type {import('jest').Config} */
module.exports = {
  moduleFileExtensions: ['js', 'json', 'ts'],
  rootDir: '.',
  testRegex: '.*\\.spec\\.ts$',
  transform: { '^.+\\.ts$': ['ts-jest', { tsconfig: 'tsconfig.json' }] },
  collectCoverageFrom: ['src/**/*.ts'],
  coverageDirectory: './coverage',
  testEnvironment: 'node',
  moduleNameMapper: { '^@/(.*)$': '<rootDir>/src/$1' },
};
