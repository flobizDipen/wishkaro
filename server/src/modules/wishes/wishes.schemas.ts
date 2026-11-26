import { z } from 'zod';
import { UuidSchema } from '../sync/sync.schemas';

/**
 * What a client may ask for (FR-WSH-01..04).
 *
 * `.strict()`, like every other write schema here. The notable thing is what is ABSENT: no
 * relation, no hobbies, no notes, no `sentiment`, no "this person is deceased". The client
 * sends ids and choices; the server looks up what they mean.
 *
 * That is not tidiness. `sentiment` decides which prompt family runs and which tones are
 * legal, so a client that could assert it could ask for a Funny draft about a death
 * anniversary — which REQUIREMENTS.md §11 files under correctness rather than content.
 */
export const DraftWishSchema = z
  .object({
    personId: UuidSchema,

    /**
     * The occasion. `eventId` is optional and soft — the composer can legitimately be opened
     * for a person rather than for a specific row — so the TYPE and the YEAR are what
     * identify it, matching the wish table's own key.
     */
    eventId: UuidSchema.nullable().optional(),
    eventType: z.enum([
      'BIRTHDAY',
      'WEDDING_ANNIVERSARY',
      'ENGAGEMENT_ANNIVERSARY',
      'DEATH_ANNIVERSARY',
      'CUSTOM',
    ]),
    /**
     * Sent rather than derived, because the composer is legitimately opened for NEXT year's
     * birthday from a T-7 reminder in December. Bounded to ±1 of the server's current year
     * in `drafting.service.ts`, where the owner's timezone is known.
     */
    occurrenceYear: z.number().int().min(1900).max(2200),

    tone: z.enum(['WARM', 'FUNNY', 'RESPECTFUL', 'SHORT', 'POETIC', 'HEARTFELT']),
    language: z.enum(['ENGLISH', 'HINDI', 'HINGLISH']),

    /**
     * Regenerate (FR-WSH-04): drafts produced in this sitting that the user turned down.
     *
     * Ephemeral — nothing here is stored. A draft that was never sent is not history, must
     * not suppress next year's draft and must not appear on the person screen, so this is
     * the one piece of wish text that reaches the server without a row behind it.
     *
     * Capped at three. Every entry is input tokens on a request that is already paying for
     * the history block, and a user who has pressed Regenerate eleven times does not want
     * eleven constraints — they want a different kind of message.
     */
    avoid: z.array(z.string().trim().min(1).max(2000)).max(3).default([]),
  })
  .strict();

export type DraftWishRequest = z.infer<typeof DraftWishSchema>;

/**
 * What comes back.
 *
 * `source` is on the response rather than inferred by the client: the server knows whether a
 * model actually served this, and the client writing `AI` into the history for something a
 * template produced would be the app lying in its own records.
 */
export interface DraftWishResponse {
  text: string;
  tone: string;
  language: string;
  source: 'AI' | 'TEMPLATE';
  /** The model that served it, or null when no model did. For debugging, not for display. */
  model: string | null;
  draftedAt: string;
}
