import {
  dedupeKeyFor,
  digestDedupeKey,
  isEventDedupeKey,
  testDedupeKey,
} from './dedupe-key';

/**
 * The key is the only thing standing between the user and two notifications for one
 * birthday, so it gets tested as a contract rather than as a string builder: what it
 * guarantees, and what it is specifically NOT allowed to depend on.
 */
describe('dedupeKeyFor', () => {
  const EVENT = '0b6c4ca3-2173-4cc6-8aaf-909036de4029';

  it('is `${eventId}:${occurrenceYear}:${offset}`', () => {
    expect(dedupeKeyFor(EVENT, 2027, 'D0')).toBe(`${EVENT}:2027:D0`);
    expect(dedupeKeyFor(EVENT, 2027, 'D1')).toBe(`${EVENT}:2027:D1`);
    expect(dedupeKeyFor(EVENT, 2027, 'D7')).toBe(`${EVENT}:2027:D7`);
  });

  it('gives each rung of the ladder a distinct key', () => {
    const keys = new Set([
      dedupeKeyFor(EVENT, 2027, 'D7'),
      dedupeKeyFor(EVENT, 2027, 'D1'),
      dedupeKeyFor(EVENT, 2027, 'D0'),
    ]);
    expect(keys.size).toBe(3);
  });

  it('separates the same event in consecutive years', () => {
    expect(dedupeKeyFor(EVENT, 2027, 'D0')).not.toBe(dedupeKeyFor(EVENT, 2028, 'D0'));
  });

  it('separates different events in the same year', () => {
    const other = 'e73582ba-2759-419e-af2c-f0d50e1573b2';
    expect(dedupeKeyFor(EVENT, 2027, 'D0')).not.toBe(dedupeKeyFor(other, 2027, 'D0'));
  });

  /**
   * The point of the whole format. If the key moved when the date moved, a phone that had
   * not synced since the edit would fire the old key while the server pushed the new one —
   * two notifications for one birthday, which is the failure FR-REM-02 forbids.
   *
   * There is no date argument to pass, so this asserts the only thing that can be asserted:
   * the function's inputs do not include one, and therefore the key cannot depend on it.
   */
  it('takes no date argument, so no date edit can move it', () => {
    expect(dedupeKeyFor.length).toBe(3);
    // 29 Feb 2028 observed on 28 Feb 2029 (FR-EVT-09), a day edit, and an override_date
    // (FR-EVT-10) all leave (eventId, occurrenceYear, offset) untouched — so they all
    // produce this one string.
    expect(dedupeKeyFor(EVENT, 2029, 'D0')).toBe(`${EVENT}:2029:D0`);
  });

  it('refuses a non-integer year rather than minting `…:2027.5:D0`', () => {
    expect(() => dedupeKeyFor(EVENT, 2027.5, 'D0')).toThrow(TypeError);
    expect(() => dedupeKeyFor(EVENT, Number.NaN, 'D0')).toThrow(TypeError);
  });

  /**
   * The migration in 20261125124605_m2_reminder_engine rewrites every PENDING and SUPPRESSED
   * row to this format, relying on the rewrite being injective. It is injective because
   * event_occurrence is UNIQUE on (event_id, occurrence_year); this asserts the key function
   * does not undo that by collapsing distinct triples.
   */
  it('is injective over (event, year, offset)', () => {
    const events = [EVENT, 'e73582ba-2759-419e-af2c-f0d50e1573b2'];
    const years = [2026, 2027, 2028];
    const offsets = ['D7', 'D1', 'D0'] as const;

    const keys = new Set<string>();
    for (const e of events) {
      for (const y of years) {
        for (const o of offsets) keys.add(dedupeKeyFor(e, y, o));
      }
    }
    expect(keys.size).toBe(events.length * years.length * offsets.length);
  });
});

describe('the keys that are deliberately NOT event keys', () => {
  it('keeps a self-test distinct from every real reminder', () => {
    const key = testDedupeKey('01a211dc-d75e-7522-af96-320cab7386ea', new Date(1_795_610_216_000));
    expect(key).toBe('test:01a211dc-d75e-7522-af96-320cab7386ea:1795610216000');
    expect(isEventDedupeKey(key)).toBe(false);
  });

  it('gives a user exactly one digest per local day', () => {
    const a = digestDedupeKey('u1', '2026-12-02');
    const b = digestDedupeKey('u1', '2026-12-02');
    const c = digestDedupeKey('u1', '2026-12-03');
    expect(a).toBe(b);
    expect(a).not.toBe(c);
    expect(isEventDedupeKey(a)).toBe(false);
  });

  it('treats an event key as an event key', () => {
    expect(isEventDedupeKey(dedupeKeyFor('e', 2027, 'D0'))).toBe(true);
  });

  /**
   * The old `${occurrence.id}:D0` rows that the migration deliberately leaves behind — SENT,
   * FAILED — must not be mistaken for anything. They have two segments where a new key has
   * three, so they cannot collide with a rewritten row.
   */
  it('cannot collide with the M0/M1 keys the migration leaves in place', () => {
    const legacy = '01a2129d-b371-7ec0-8876-c760bab0ccfe:D0';
    expect(legacy.split(':')).toHaveLength(2);
    expect(dedupeKeyFor('01a2129d-b371-7ec0-8876-c760bab0ccfe', 2027, 'D0').split(':')).toHaveLength(
      3,
    );
  });
});
