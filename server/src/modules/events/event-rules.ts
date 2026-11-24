import type { EventType, Sentiment } from '@prisma/client';

/**
 * Sentiment is DERIVED from the event type and is never accepted from a client (FR-EVT-01a).
 *
 * Letting a caller choose it would make "happy birthday" on a death anniversary a one-field
 * mistake away — REQUIREMENTS.md §7.3 calls that the worst failure this app can have, and
 * asks for it to be impossible by construction rather than by a copy tweak.
 */
export function sentimentFor(type: EventType): Sentiment {
  return type === 'DEATH_ANNIVERSARY' ? 'SOLEMN' : 'CELEBRATORY';
}

/** The event types M0 is allowed to create. The enum carries the full v1 set regardless. */
export const M0_EVENT_TYPES: readonly EventType[] = ['BIRTHDAY'];
