import { applyQuietHours, isQuietHour, localDateKey } from './quiet-hours';

const IST = 'Asia/Kolkata';
const NONE = { quietHoursStart: null, quietHoursEnd: null };
const OVERNIGHT = { quietHoursStart: 22, quietHoursEnd: 7 };
const DAYTIME = { quietHoursStart: 9, quietHoursEnd: 17 };

/** 09:00 IST is 03:30 UTC. Every instant below is written as the UTC the server stores. */
const ist = (iso: string) => new Date(iso);

describe('isQuietHour — the midnight-spanning case', () => {
  /**
   * `start <= h < end` is right for 09:00–17:00 and silently wrong for 22:00–07:00, where it
   * matches nothing at all — so a user with overnight quiet hours gets woken at 3am by an app
   * that believes it is being quiet. This is the whole reason the function exists.
   */
  it('wraps around midnight', () => {
    const quiet = [22, 23, 0, 1, 2, 3, 4, 5, 6];
    const loud = [7, 8, 9, 12, 17, 20, 21];
    for (const h of quiet) expect(isQuietHour(h, 22, 7)).toBe(true);
    for (const h of loud) expect(isQuietHour(h, 22, 7)).toBe(false);
  });

  it('still handles a window that does not wrap', () => {
    for (const h of [9, 10, 16]) expect(isQuietHour(h, 9, 17)).toBe(true);
    for (const h of [8, 17, 18, 0]) expect(isQuietHour(h, 9, 17)).toBe(false);
  });

  it('is inclusive at the start and exclusive at the end', () => {
    expect(isQuietHour(22, 22, 7)).toBe(true);
    // Exclusive at the end is what makes "defer to the end of the window" terminate rather
    // than defer again by a day.
    expect(isQuietHour(7, 22, 7)).toBe(false);
  });

  it('treats a zero-width window as no window', () => {
    for (let h = 0; h <= 23; h += 1) expect(isQuietHour(h, 22, 22)).toBe(false);
  });
});

describe('applyQuietHours (FR-REM-09)', () => {
  it('leaves a reminder alone when there is no window', () => {
    const due = ist('2026-12-02T03:30:00.000Z');
    expect(applyQuietHours(due, IST, NONE)).toEqual(due);
  });

  /**
   * The case docs/BUILD_PROMPT_M2.md names as the one to reason about first: Asia/Kolkata,
   * quiet hours 22:00–07:00, a reminder due at 09:00. It is OUTSIDE the window and must not
   * move. An implementation that gets the wrap wrong either defers it to 07:00 — delivering
   * every morning reminder an hour early, for ever — or fails to notice 03:00 at all.
   */
  it('does not move a 09:00 reminder under a 22:00–07:00 window', () => {
    const nineAm = ist('2026-12-02T03:30:00.000Z');
    expect(applyQuietHours(nineAm, IST, OVERNIGHT)).toEqual(nineAm);
  });

  it('defers a late-evening reminder to 07:00 the next morning', () => {
    // 23:30 IST on 2 Dec is 18:00Z on 2 Dec. 07:00 IST on 3 Dec is 01:30Z on 3 Dec.
    const lateNight = ist('2026-12-02T18:00:00.000Z');
    expect(applyQuietHours(lateNight, IST, OVERNIGHT)).toEqual(
      ist('2026-12-03T01:30:00.000Z'),
    );
  });

  it('defers an early-morning reminder to 07:00 the SAME day', () => {
    // 03:00 IST on 3 Dec is 21:30Z on 2 Dec. The window's end is 07:00 on the 3rd, not the 4th.
    const beforeDawn = ist('2026-12-02T21:30:00.000Z');
    expect(applyQuietHours(beforeDawn, IST, OVERNIGHT)).toEqual(
      ist('2026-12-03T01:30:00.000Z'),
    );
  });

  it('defers a reminder due exactly at the start of the window', () => {
    // 22:00 IST on 2 Dec is 16:30Z. Start is inclusive, so it moves to 07:00 on the 3rd.
    expect(applyQuietHours(ist('2026-12-02T16:30:00.000Z'), IST, OVERNIGHT)).toEqual(
      ist('2026-12-03T01:30:00.000Z'),
    );
  });

  it('leaves a reminder due exactly at the end of the window alone', () => {
    // 07:00 IST on 3 Dec is 01:30Z. End is exclusive, so it fires then.
    const atEnd = ist('2026-12-03T01:30:00.000Z');
    expect(applyQuietHours(atEnd, IST, OVERNIGHT)).toEqual(atEnd);
  });

  it('works for a daytime window too', () => {
    // 12:00 IST is 06:30Z; 17:00 IST is 11:30Z, same day.
    expect(applyQuietHours(ist('2026-12-02T06:30:00.000Z'), IST, DAYTIME)).toEqual(
      ist('2026-12-02T11:30:00.000Z'),
    );
  });

  /** Applying it twice must not move the reminder a second time, or the materialiser drifts. */
  it('is idempotent', () => {
    const once = applyQuietHours(ist('2026-12-02T18:00:00.000Z'), IST, OVERNIGHT);
    expect(applyQuietHours(once, IST, OVERNIGHT)).toEqual(once);
  });

  /**
   * The window belongs to the user's day, so the same UTC instant is quiet or not depending
   * on where they are standing. 18:00Z is 23:30 in Kolkata and 13:00 in New York.
   */
  it('is evaluated in the user’s zone, not UTC', () => {
    const instant = ist('2026-12-02T18:00:00.000Z');
    expect(applyQuietHours(instant, IST, OVERNIGHT)).not.toEqual(instant);
    expect(applyQuietHours(instant, 'America/New_York', OVERNIGHT)).toEqual(instant);
  });
});

describe('localDateKey', () => {
  it('uses the user’s calendar day, not the UTC one', () => {
    // 2 Dec 23:30 IST is still 2 December to the user, though it is 2 Dec 18:00 in UTC.
    expect(localDateKey(ist('2026-12-02T18:00:00.000Z'), IST)).toBe('2026-12-02');
    // 3 Dec 03:00 IST is 2 Dec 21:30 UTC — a different UTC date, the user's next day.
    expect(localDateKey(ist('2026-12-02T21:30:00.000Z'), IST)).toBe('2026-12-03');
  });
});
