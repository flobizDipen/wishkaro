import { bumped } from './person-derivation.service';

/**
 * A re-derivation must never move a row's `updatedAt` backwards.
 *
 * `updatedAt` on a synced row is whatever the CLIENT stamped it with — that is what drives
 * last-write-wins. A device whose clock runs fast writes a timestamp in the server's future,
 * and `new Date()` at derivation time is then EARLIER than the row's own value.
 *
 * Writing it costs two separate things, and M3 paid both on a real handset two seconds ahead
 * of this deployment (docs/M3_VERIFY.md §6.3):
 *
 *  - the client's last-write-wins sees the corrected copy as older and discards it, so the
 *    server's derived values never land; and
 *  - `updatedAt` is the sync cursor's ordering key, so a row that moves backwards can fall
 *    behind a cursor that has already passed it and never be delivered again.
 *
 * The client's half of this fix is in `LocalStore.applyRemote`; this is the server's half,
 * and either one alone would have hidden the bug rather than fixed it.
 */

describe('a re-derivation timestamp', () => {
  const rowStamp = new Date('2026-11-26T06:04:02.406Z');

  it('is the server clock when the server clock is ahead — the ordinary case', () => {
    const serverNow = new Date('2026-11-26T06:05:00.000Z');
    expect(bumped(rowStamp, serverNow)).toEqual(serverNow);
  });

  it('is one millisecond past the row when the DEVICE clock is ahead', () => {
    // The observed case: the handset was two seconds ahead of the deployment.
    const serverNow = new Date('2026-11-26T06:04:00.481Z');
    expect(bumped(rowStamp, serverNow)).toEqual(new Date('2026-11-26T06:04:02.407Z'));
  });

  it('never returns a value at or before the row it is re-deriving', () => {
    // Every skew from a minute behind to a minute ahead, in steps that cross zero exactly.
    for (let skewMs = -60_000; skewMs <= 60_000; skewMs += 1_000) {
      const serverNow = new Date(rowStamp.getTime() + skewMs);
      expect(bumped(rowStamp, serverNow).getTime()).toBeGreaterThan(rowStamp.getTime());
    }
  });

  it('bumps by a whole millisecond, because the wire cannot carry less', () => {
    // Postgres stores timestamp(3) and the API formats with toISOString(). A microsecond
    // bump would be invisible on the far side and the row would come back looking equal —
    // which is the same bug with extra steps. SyncEngine.retryStamp has the same note.
    const serverNow = new Date(rowStamp.getTime() - 1);
    const result = bumped(rowStamp, serverNow);
    expect(result.toISOString()).not.toEqual(rowStamp.toISOString());
    expect(result.getTime() - rowStamp.getTime()).toBe(1);
  });
});
