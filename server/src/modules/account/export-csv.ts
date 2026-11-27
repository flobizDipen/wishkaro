/**
 * The CSV half of FR-SET-03, and the half of definition-of-done item 2 that lives on this
 * side of the round trip.
 *
 * > 2. *"Its CSV re-imported through M3's importer without a mapping change."*
 *
 * Two decisions in here are the whole of it, and both are about a reader written in a
 * different language that this file never gets to see.
 *
 * ## 1. Dates are `d MMM yyyy`, never `dd/MM/yyyy`
 *
 * `03/05/1990` is ambiguous, and M3's importer resolves it with a **user preference** —
 * FR-IMP-06's day-first/month-first toggle, which FR-SET-08 makes a setting. So an export
 * written in that form re-imports as 3 May for one user and 5 March for another, silently,
 * with no row marked failed and nothing to notice. A round trip that depends on a preference
 * is not a round trip.
 *
 * `ImportDates.fromNamedMonth` returns `ambiguous = false` unconditionally, because a named
 * month leaves nothing to be ambiguous about. That is the property this format is chosen for,
 * and `docs/fixtures/export-roundtrip.json` is where both sides agree on it.
 *
 * ## 2. `people.csv` carries BIRTHDAYS ONLY, and says so
 *
 * M3's importer hard-codes `type = BIRTHDAY` and its `ColumnMapping` has no type target, so
 * one row is one person and one birthday — always. Writing a death anniversary into a file
 * that re-imports as a birthday would reintroduce the outcome FR-EVT-05 calls "the worst
 * failure this app can have", through the front door, in the milestone about doing right by
 * the user's data.
 *
 * So: birthdays in `people.csv`, **every** event of every type in `events.csv` (which is for
 * reading, not for re-importing) and in the JSON, which is the lossless artifact. The
 * alternative readings of item 2 are raised in `docs/M9_PLAN.md` C11 rather than resolved
 * here; the one that silently converts a remembrance into a celebration is not being built in
 * the meantime.
 */

/** Three-letter English month names. The importer's `MONTH_NAMES` accepts exactly these. */
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/**
 * `3 May 1990`, or `17 Nov` when the year is unknown (FR-EVT-02).
 *
 * The year is simply absent rather than blank or zero: `ImportDates` reads a two-part date as
 * day-and-month with no year, which is the same "unknown" the record already holds. A
 * placeholder year would come back as a confident wrong age, which is the exact failure
 * FR-EVT-02 exists about.
 */
export function formatExportDate(day: number, month: number, year: number | null): string {
  const name = MONTHS[month - 1] ?? String(month);
  return year == null ? `${day} ${name}` : `${day} ${name} ${year}`;
}

/**
 * RFC 4180 quoting, matching `Csv.escape` in `composeApp` field for field.
 *
 * Deliberately the same four trigger characters and the same doubling rule as the Kotlin
 * writer, because the Kotlin READER is what consumes this and the two were written months
 * apart by different code. `export-csv.spec.ts` and `ExportRoundTripTest.kt` read one shared
 * table of cases so a drift in either is a failure in both.
 */
export function csvEscape(value: string): string {
  return /[",\n\r]/.test(value) ? `"${value.replace(/"/g, '""')}"` : value;
}

/** `\r\n` line endings, as `Csv.write` uses — Excel on Windows is the common destination. */
export function csvWrite(rows: string[][]): string {
  return rows.map((row) => row.map(csvEscape).join(',')).join('\r\n');
}

/**
 * The header `ImportStore.guessMapping` has to claim correctly with nobody touching the
 * mapping screen — which is what "without a mapping change" means.
 *
 * The guesser matches substrings in claim order (name, date, relation, phone, notes) and
 * allows each column to be claimed once. These five names are the ones that resolve to
 * 0,1,2,3,4, and `export-roundtrip.json` pins that expectation so a change to either side
 * fails rather than quietly producing a file nobody can import in one tap.
 */
export const PEOPLE_CSV_HEADER = ['Name', 'Date', 'Relation', 'Phone', 'Notes'];

export interface ExportPersonRow {
  displayName: string;
  relationToOwner: string | null;
  phone: string | null;
  notes: string | null;
  day: number;
  month: number;
  year: number | null;
}

/** One row per person-with-a-birthday, in the five columns M3's importer maps. */
export function peopleCsv(rows: ExportPersonRow[]): string {
  return csvWrite([
    PEOPLE_CSV_HEADER,
    ...rows.map((r) => [
      r.displayName,
      formatExportDate(r.day, r.month, r.year),
      r.relationToOwner ?? '',
      r.phone ?? '',
      r.notes ?? '',
    ]),
  ]);
}

export interface ExportEventRow {
  displayName: string;
  type: string;
  day: number;
  month: number;
  year: number | null;
  label: string | null;
  isMuted: boolean;
}

/**
 * Every event of every type, for reading.
 *
 * **Not re-importable, on purpose, and the header says so.** `Event type` is a column M3's
 * mapping screen cannot target, so importing this file would drop the type and turn every row
 * into a birthday. The column is here because the user asked for their data, and leaving the
 * type out of a data export to make the file look importable would be answering a DPDP access
 * request with a lie about what we hold.
 */
export function eventsCsv(rows: ExportEventRow[]): string {
  return csvWrite([
    ['Person', 'Event type', 'Date', 'Label', 'Muted'],
    ...rows.map((r) => [
      r.displayName,
      r.type,
      formatExportDate(r.day, r.month, r.year),
      r.label ?? '',
      r.isMuted ? 'yes' : 'no',
    ]),
  ]);
}

export interface ExportWishRow {
  displayName: string;
  eventType: string;
  occurrenceYear: number;
  sentiment: string;
  tone: string;
  language: string;
  channel: string;
  source: string;
  messageText: string;
  sentAt: Date;
}

/** FR-HIS-01's wish history — "what was sent, tone, language, channel". */
export function wishesCsv(rows: ExportWishRow[]): string {
  return csvWrite([
    ['Person', 'Event type', 'Year', 'Sentiment', 'Tone', 'Language', 'Channel', 'Written by', 'Sent at', 'Message'],
    ...rows.map((r) => [
      r.displayName,
      r.eventType,
      String(r.occurrenceYear),
      r.sentiment,
      r.tone,
      r.language,
      r.channel,
      r.source,
      r.sentAt.toISOString(),
      r.messageText,
    ]),
  ]);
}
