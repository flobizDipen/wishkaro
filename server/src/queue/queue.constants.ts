// BullMQ rejects ':' in queue names — it is the delimiter in its own Redis keyspace.
export const QUEUE_MATERIALISE = 'occurrences-materialise';
export const QUEUE_SEND = 'reminders-send';

export const REDIS_CONNECTION = Symbol('REDIS_CONNECTION');
