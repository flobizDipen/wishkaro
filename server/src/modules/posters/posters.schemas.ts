import { z } from 'zod';
import { UuidSchema } from '../sync/sync.schemas';

/**
 * What a client may ask for (FR-POS-01, FR-POS-05).
 *
 * `.strict()`, like every other write schema here, and the notable thing is again what is
 * ABSENT: no `sentiment`, no `family`, no copy, no "this person is deceased". The client
 * sends ids and a format; the server looks up what they mean.
 *
 * That is not tidiness. `sentiment` decides which theme family is legal, and a client that
 * could assert it could ask for confetti on a remembrance — which REQUIREMENTS.md §11 files
 * under correctness rather than content, and which is the failure this whole milestone is
 * written around.
 *
 * **No headline either.** FR-POS-01(a) makes the words the server's job, and a
 * user-supplied headline would be unmoderated text rendered at 72px onto an image carrying
 * our attribution. If that is wanted later it is a feature with a moderation path, not a
 * field.
 */
export const RequestPosterSchema = z
  .object({
    personId: UuidSchema,

    /** Soft, like the composer's: the poster screen can be opened for a person. */
    eventId: UuidSchema.nullable().optional(),
    eventType: z.enum([
      'BIRTHDAY',
      'WEDDING_ANNIVERSARY',
      'ENGAGEMENT_ANNIVERSARY',
      'DEATH_ANNIVERSARY',
      'CUSTOM',
    ]),
    /** Bounded to ±1 of the server's year in the service, as drafting already does. */
    occurrenceYear: z.number().int().min(1900).max(2200),

    /**
     * The theme the user picked, or absent for the occasion's default.
     *
     * Validated against the occasion's family server-side. A tampered request carrying a
     * celebratory id for a solemn occasion gets a 400, not a poster — FR-POS-13's filter
     * holds on the way in as well as in the picker.
     */
    themeId: z.string().trim().min(1).max(64).nullable().optional(),

    /** FR-POS-05: "User picks; default is status." */
    format: z.enum(['STATUS', 'SQUARE', 'LINK']).default('STATUS'),
  })
  .strict();

export type RequestPosterRequest = z.infer<typeof RequestPosterSchema>;

export interface RequestPosterResponse {
  generationId: string;
  status: string;
}

export interface GenerationStatusResponse {
  generationId: string;
  status: string;
  cacheHit: boolean;
  degraded: boolean;
  moderation: string;
  latencyMs: number | null;
  errorCode: string | null;
  poster: PosterSummary | null;
}

export interface PosterSummary {
  id: string;
  personId: string;
  themeId: string;
  family: string;
  sentiment: string;
  eventType: string;
  format: string;
  attributed: boolean;
  createdAt: string;
  /** Short-lived and signed (§11). Null when the bytes have gone. */
  url: string | null;
  urlExpiresAt: string | null;
}
