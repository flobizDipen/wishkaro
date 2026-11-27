import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { csvWrite, formatExportDate, peopleCsv, PEOPLE_CSV_HEADER, type ExportPersonRow } from './export-csv';

/**
 * The WRITER half of definition-of-done item 2, against the shared table of cases.
 *
 * `composeApp/src/commonTest/.../ExportRoundTripTest.kt` is the reader half and reads the same
 * file. `MILESTONE_CHECKLIST.md`, bought by M3: *"a rule implemented twice in two languages is
 * held to one shared table of cases"* — and this is that shape exactly, a writer in TypeScript
 * and a reader in Kotlin, where each side's own suite stays green while the two disagree.
 *
 * The fixture is also where the *reasoning* lives, because it is the thing both sides read.
 */

interface Fixture {
  header: string[];
  expectedMapping: Record<string, number>;
  cases: {
    why: string;
    person: ExportPersonRow;
    row: string[];
    reimported: { name: string; day: number; month: number; year: number | null; relation: string };
  }[];
}

const fixture = JSON.parse(
  readFileSync(join(__dirname, '../../../../docs/fixtures/export-roundtrip.json'), 'utf8'),
) as Fixture;

describe('the export CSV writes what M3\'s importer reads', () => {
  it('uses the header the fixture pins', () => {
    expect(PEOPLE_CSV_HEADER).toEqual(fixture.header);
  });

  it.each(fixture.cases.map((c) => [c.why, c] as const))('%s', (_why, testCase) => {
    const written = peopleCsv([testCase.person]);
    const expected = csvWrite([fixture.header, testCase.row]);

    expect(written).toBe(expected);
  });

  /**
   * The decision this file exists for, asserted directly rather than only implied by the
   * cases above.
   *
   * `03/05/1990` is resolved by a USER PREFERENCE on the other side (FR-IMP-06's toggle,
   * FR-SET-08's setting), so an export in that form re-imports as 3 May for one user and
   * 5 March for another, silently. A named month is resolved by `fromNamedMonth`, which
   * returns `ambiguous = false` unconditionally.
   */
  it('never writes a date that the dd/MM toggle could resolve two ways', () => {
    for (const { person } of fixture.cases) {
      const written = formatExportDate(person.day, person.month, person.year);

      // A month NAME is present, which is what makes it unambiguous. Asserted as "contains a
      // letter" rather than as a regex over month names, so adding a format that is also
      // unambiguous (an ISO date, say) does not fail for being different.
      expect(written).toMatch(/[A-Za-z]/);
      expect(written).not.toMatch(/^\d+[/\-.]\d+/);
    }
  });

  it('writes a year-unknown birthday with no year rather than a placeholder', () => {
    // FR-EVT-02. A sentinel year comes back as a confident wrong age, which is the exact
    // failure that requirement exists about — and `ImportDates.SENTINEL_YEARS` exists because
    // other products do it.
    expect(formatExportDate(17, 11, null)).toBe('17 Nov');
    expect(formatExportDate(17, 11, null)).not.toMatch(/\d{4}/);
  });

  it('quotes exactly where the Kotlin writer quotes, and doubles quotes the same way', () => {
    // The four trigger characters, one row, so a drift in either escape rule is one failure
    // rather than a scattering.
    expect(csvWrite([['plain', 'has,comma', 'has"quote', 'has\nnewline']])).toBe(
      'plain,"has,comma","has""quote","has\nnewline"',
    );
  });

  it('writes CRLF line endings', () => {
    expect(csvWrite([['a'], ['b']])).toBe('a\r\nb');
  });
});

describe('what people.csv deliberately cannot carry', () => {
  /**
   * `docs/M9_PLAN.md` C11, as an assertion.
   *
   * M3's importer hard-codes `type = BIRTHDAY` and `ColumnMapping` has no type target. A
   * death anniversary written into a file that re-imports as a birthday would reintroduce the
   * outcome FR-EVT-05 calls "the worst failure this app can have ... impossible by
   * construction, not by a copy tweak" — through the front door, in the milestone about doing
   * right by the user's data.
   *
   * So the header has five columns and no type, and this test is what stops a sixth being
   * added without the importer learning to read it.
   */
  it('has no event-type column, because the importer could not read one', () => {
    expect(PEOPLE_CSV_HEADER.map((h) => h.toLowerCase())).not.toContain('type');
    expect(PEOPLE_CSV_HEADER.map((h) => h.toLowerCase())).not.toContain('event type');
    expect(PEOPLE_CSV_HEADER).toHaveLength(5);
  });
});
