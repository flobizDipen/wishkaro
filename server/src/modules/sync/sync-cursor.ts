/**
 * The sync cursor.
 *
 * Ordering is the tuple (updated_at, id), not updated_at alone: two records written in the
 * same millisecond are otherwise indistinguishable, and a cursor that cannot separate them
 * either repeats a record forever or skips one. The id breaks the tie.
 *
 * People and events advance independently, so a page limit on one entity can never cause the
 * other to skip rows. The whole thing is opaque base64 to the client — the shape can change
 * without an app update.
 */

export interface EntityCursor {
  updatedAt: string;
  id: string;
}

export interface SyncCursor {
  person: EntityCursor | null;
  event: EntityCursor | null;
  /**
   * Added in M1. A cursor minted by an M0 client has no `trait` key, so it decodes to null
   * and that client gets a full trait resync on its next pull — which is exactly right, since
   * it has never seen any. Extending the shape rather than collapsing the three into one
   * ordering keeps a page limit on people from stalling events or traits.
   */
  trait: EntityCursor | null;
  /**
   * Added in M5, by exactly the same argument traits were added in M1: a cursor minted by an
   * M0–M4 client has no `wish` key, decodes to null, and that client gets a full wish resync
   * on its next pull. It has never seen one, so a full resync IS the correct delta.
   *
   * A fourth independent ordering rather than a fourth thing jammed into one: a page limit
   * reached on people must not be able to stall wishes, and a user with one busy entity must
   * not starve the others.
   */
  wish: EntityCursor | null;
}

export const EMPTY_CURSOR: SyncCursor = { person: null, event: null, trait: null, wish: null };

export function encodeCursor(cursor: SyncCursor): string {
  return Buffer.from(JSON.stringify(cursor), 'utf8').toString('base64url');
}

/** Unparseable cursors fall back to a full resync rather than failing the request. */
export function decodeCursor(encoded: string | undefined): SyncCursor {
  if (!encoded) return EMPTY_CURSOR;
  try {
    const parsed: unknown = JSON.parse(Buffer.from(encoded, 'base64url').toString('utf8'));
    if (!parsed || typeof parsed !== 'object') return EMPTY_CURSOR;
    const o = parsed as Record<string, unknown>;
    return {
      person: readEntity(o.person),
      event: readEntity(o.event),
      trait: readEntity(o.trait),
      wish: readEntity(o.wish),
    };
  } catch {
    return EMPTY_CURSOR;
  }
}

function readEntity(value: unknown): EntityCursor | null {
  if (!value || typeof value !== 'object') return null;
  const o = value as Record<string, unknown>;
  if (typeof o.updatedAt !== 'string' || typeof o.id !== 'string') return null;
  if (Number.isNaN(Date.parse(o.updatedAt))) return null;
  return { updatedAt: o.updatedAt, id: o.id };
}

/** `WHERE (updated_at, id) > (cursor.updatedAt, cursor.id)`, expressed for Prisma. */
export function cursorWhere(cursor: EntityCursor | null): object {
  if (!cursor) return {};
  const at = new Date(cursor.updatedAt);
  return {
    OR: [{ updatedAt: { gt: at } }, { updatedAt: at, id: { gt: cursor.id } }],
  };
}

export function advance(
  previous: EntityCursor | null,
  rows: { id: string; updatedAt: Date }[],
): EntityCursor | null {
  const last = rows.at(-1);
  if (!last) return previous;
  return { updatedAt: last.updatedAt.toISOString(), id: last.id };
}
