// BullMQ rejects ':' in queue names — it is the delimiter in its own Redis keyspace.
export const QUEUE_MATERIALISE = 'occurrences-materialise';
export const QUEUE_SEND = 'reminders-send';
/** M6. Discrete jobs added on demand, not a scheduler — see `poster.queue.ts`. */
export const QUEUE_POSTER = 'posters-generate';

export const REDIS_CONNECTION = Symbol('REDIS_CONNECTION');
