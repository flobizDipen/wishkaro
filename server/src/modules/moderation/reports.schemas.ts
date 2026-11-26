import { z } from 'zod';
import { UuidSchema } from '../sync/sync.schemas';

/**
 * "Report this content" (FR-POS-12, FR-SET-07, §12's Play GenAI box).
 *
 * Play's GenAI policy requires an in-app reporting mechanism for generated output. §9 has
 * listed `POST /v1/ai/report` since v1 and nothing has ever written to it — which is the
 * kind of gap that is cheap now and expensive during a submission review.
 *
 * `.strict()`, like every other write schema in this app.
 */
export const ReportContentSchema = z
  .object({
    /**
     * Which surface. The same vocabulary as `ai_usage.feature`, so a report can be lined up
     * against what the thing it is about cost.
     */
    feature: z.enum(['poster', 'wish_draft']),

    /**
     * What is being reported. A poster has an id; a draft the user never sent has no row at
     * all, so it is identified by its generation if there was one and by the text otherwise.
     * Exactly one of the three has to be present — see the refinement below.
     */
    posterId: UuidSchema.nullable().optional(),
    generationId: UuidSchema.nullable().optional(),

    /**
     * The reported text, for a wish draft.
     *
     * Sent by the client rather than looked up, because FR-WSH-07 writes history only after
     * the hand-off: a draft the user rejected as offensive is precisely the one that was
     * never saved, so without this the report would say that something was wrong and not
     * what. Capped at a draft's own ceiling.
     */
    reportedText: z.string().trim().min(1).max(2000).nullable().optional(),

    /** A short category from a fixed client-side list. */
    reason: z.enum([
      'OFFENSIVE',
      'INACCURATE',
      'INSENSITIVE',
      'WRONG_TONE',
      'LOOKS_WRONG',
      'OTHER',
    ]),

    /** The user's own words. Never logged — §11. */
    detail: z.string().trim().max(1000).nullable().optional(),
  })
  .strict()
  .refine((v) => v.posterId != null || v.generationId != null || v.reportedText != null, {
    message: 'A report must name a poster, a generation or the text it is about',
    path: ['posterId'],
  });

export type ReportContentRequest = z.infer<typeof ReportContentSchema>;
