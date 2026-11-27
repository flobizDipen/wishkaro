// BullMQ rejects ':' in queue names — it is the delimiter in its own Redis keyspace.
export const QUEUE_MATERIALISE = 'occurrences-materialise';
export const QUEUE_SEND = 'reminders-send';
/** M6. Discrete jobs added on demand, not a scheduler — see `poster.queue.ts`. */
export const QUEUE_POSTER = 'posters-generate';
/**
 * M9. FR-SET-06's retention sweep, and §12's "enforced by a scheduled job, not just stated in
 * policy". Repeatable like the two above, not on-demand like the poster queue.
 */
export const QUEUE_RETENTION = 'retention-sweep';

export const REDIS_CONNECTION = Symbol('REDIS_CONNECTION');
