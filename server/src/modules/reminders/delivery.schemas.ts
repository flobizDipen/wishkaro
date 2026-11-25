import { z } from 'zod';

/**
 * What the client tells us happened to a reminder (FR-REM-10).
 *
 * This exists so the G1 reliability target — delivery above 98% on the OEM matrix — is a
 * number someone can query rather than a claim in a document. It is built before the
 * features it judges, deliberately: every other assertion in M2 is unverifiable without it.
 *
 * The outcomes are what the DEVICE can honestly observe, not what we would like to know.
 * The phone cannot tell us "FCM never delivered this" — silence is the only evidence of
 * that, and silence is measured by the absence of a row, not by a report.
 */
export const DELIVERY_OUTCOMES = [
  /** A notification was drawn. The one that counts towards G1. */
  'SHOWN',
  /** Claimed already — the other delivery path got there first. Healthy, not a failure. */
  'SUPPRESSED_DUPLICATE',
  /** Held back by quiet hours and re-armed for the window's end (FR-REM-09). */
  'SUPPRESSED_QUIET',
  /** Rolled into the day's digest instead (FR-REM-08). */
  'SUPPRESSED_DIGEST',
  /** Arrived, but POST_NOTIFICATIONS is not granted so nothing could be drawn. */
  'BLOCKED_NO_PERMISSION',
] as const;

export type DeliveryOutcome = (typeof DELIVERY_OUTCOMES)[number];

/** Which path actually got there. See the `channel` note on Reminder in schema.prisma. */
export const DELIVERY_CHANNELS = ['FCM', 'LOCAL'] as const;

const DeliveryReportSchema = z
  .object({
    dedupeKey: z.string().min(1).max(200),
    outcome: z.enum(DELIVERY_OUTCOMES),
    channel: z.enum(DELIVERY_CHANNELS),
    /** When the device acted on it — NOT when it got round to telling us. */
    occurredAtUtc: z.string().datetime({ offset: true }),
  })
  .strict();

/**
 * Batched, because the moment that matters is frequently the moment the device has no
 * network. Reports queue on the phone and drain on the next successful sync, so a report may
 * arrive days after the notification it describes — hence `occurredAtUtc` rather than
 * trusting the server clock at receipt.
 */
export const DeliveryReportBatchSchema = z
  .object({
    reports: z.array(DeliveryReportSchema).min(1).max(200),
  })
  .strict();

export type DeliveryReport = z.infer<typeof DeliveryReportSchema>;
export type DeliveryReportBatch = z.infer<typeof DeliveryReportBatchSchema>;
