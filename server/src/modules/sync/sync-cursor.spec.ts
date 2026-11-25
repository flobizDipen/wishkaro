import { advance, cursorWhere, decodeCursor, EMPTY_CURSOR, encodeCursor } from './sync-cursor';

describe('sync cursor', () => {
  it('round-trips', () => {
    const cursor = {
      person: { updatedAt: '2026-11-24T10:00:00.000Z', id: 'aaa' },
      event: { updatedAt: '2026-11-24T11:00:00.000Z', id: 'bbb' },
      trait: { updatedAt: '2026-11-24T12:00:00.000Z', id: 'ccc' },
    };
    expect(decodeCursor(encodeCursor(cursor))).toEqual(cursor);
  });

  /**
   * M1 added a third entity. A cursor minted by an M0 client has no `trait` key at all, and
   * must still decode — with trait null, so that client pulls every trait it has never seen
   * rather than being handed an error or skipping them forever.
   */
  it('accepts an M0 cursor that predates traits', () => {
    const m0 = Buffer.from(
      JSON.stringify({
        person: { updatedAt: '2026-11-24T10:00:00.000Z', id: 'aaa' },
        event: { updatedAt: '2026-11-24T11:00:00.000Z', id: 'bbb' },
      }),
    ).toString('base64url');

    const decoded = decodeCursor(m0);
    expect(decoded.person?.id).toBe('aaa');
    expect(decoded.event?.id).toBe('bbb');
    expect(decoded.trait).toBeNull();
  });

  it('treats a missing cursor as a full resync', () => {
    expect(decodeCursor(undefined)).toEqual(EMPTY_CURSOR);
  });

  it('falls back to a full resync rather than failing on a corrupt cursor', () => {
    expect(decodeCursor('not-base64-at-all!!')).toEqual(EMPTY_CURSOR);
    expect(decodeCursor(Buffer.from('{"person":5}').toString('base64url'))).toEqual(EMPTY_CURSOR);
  });

  it('rejects a cursor whose timestamp is not a date', () => {
    const bad = Buffer.from(JSON.stringify({ person: { updatedAt: 'soon', id: 'x' } })).toString(
      'base64url',
    );
    expect(decodeCursor(bad).person).toBeNull();
  });

  describe('cursorWhere — the (updated_at, id) tuple', () => {
    it('is empty for a first sync, so everything is returned', () => {
      expect(cursorWhere(null)).toEqual({});
    });

    it('breaks ties on id, so same-millisecond writes are neither repeated nor skipped', () => {
      const where = cursorWhere({ updatedAt: '2026-11-24T10:00:00.000Z', id: 'm' }) as {
        OR: [{ updatedAt: { gt: Date } }, { updatedAt: Date; id: { gt: string } }];
      };
      expect(where.OR).toHaveLength(2);
      expect(where.OR[0].updatedAt.gt.toISOString()).toBe('2026-11-24T10:00:00.000Z');
      expect(where.OR[1].id.gt).toBe('m');
    });
  });

  describe('advance', () => {
    it('keeps the previous cursor when a page is empty', () => {
      const previous = { updatedAt: '2026-11-24T10:00:00.000Z', id: 'a' };
      expect(advance(previous, [])).toBe(previous);
    });

    it('moves to the last row of the page', () => {
      const rows = [
        { id: 'a', updatedAt: new Date('2026-11-24T10:00:00.000Z') },
        { id: 'b', updatedAt: new Date('2026-11-24T10:00:01.000Z') },
      ];
      expect(advance(null, rows)).toEqual({ updatedAt: '2026-11-24T10:00:01.000Z', id: 'b' });
    });
  });

  it('advances the three entities independently, so none can starve the others', () => {
    // People filled a page; events and traits did not. Their cursors must not jump forward.
    const cursor = decodeCursor(
      encodeCursor({
        person: advance(null, [{ id: 'p9', updatedAt: new Date('2026-11-24T12:00:00.000Z') }]),
        event: advance(null, []),
        trait: advance(null, []),
      }),
    );
    expect(cursor.person?.id).toBe('p9');
    expect(cursor.event).toBeNull();
    expect(cursor.trait).toBeNull();
  });
});
