import type { EventType } from '@prisma/client';

/**
 * `sentimentFor` moved to ./derived.ts in M1, where it gained a second input.
 *
 * It is NOT re-exported from here on purpose. Every call site had to be revisited when the
 * rule changed from "derived from type" to "derived from type and whether the person is
 * deceased", and a compatibility re-export would have let one of them keep the old meaning
 * silently. See docs/M1_PLAN.md §2.1.
 */

/**
 * The event types the API will create. M0 allowed only BIRTHDAY; M1 opens all five
 * (FR-EVT-01). The Prisma enum has carried the full set since M0, so this is a validation
 * change rather than a migration.
 *
 * `as const satisfies` rather than a type annotation, so `z.enum(ALLOWED_EVENT_TYPES)` infers
 * the literal union instead of widening to `string`, and so removing a value from the Prisma
 * enum becomes a compile error here rather than a silently broken API.
 */
export const ALLOWED_EVENT_TYPES = [
  'BIRTHDAY',
  'WEDDING_ANNIVERSARY',
  'ENGAGEMENT_ANNIVERSARY',
  'DEATH_ANNIVERSARY',
  'CUSTOM',
] as const satisfies readonly EventType[];
