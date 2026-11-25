-- AlterEnum
ALTER TYPE "ReminderOffset" ADD VALUE 'DIGEST';

-- AlterTable
ALTER TABLE "person" ADD COLUMN     "reminder_hour_override" INTEGER;

-- AlterTable
ALTER TABLE "reminder" ADD COLUMN     "delivered_at" TIMESTAMP(3),
ADD COLUMN     "delivery_outcome" TEXT;

-- AlterTable
ALTER TABLE "user" ADD COLUMN     "digest_enabled" BOOLEAN NOT NULL DEFAULT false,
ADD COLUMN     "digest_hour" INTEGER NOT NULL DEFAULT 9,
ADD COLUMN     "ladder_d0" BOOLEAN NOT NULL DEFAULT true,
ADD COLUMN     "ladder_d1" BOOLEAN NOT NULL DEFAULT true,
ADD COLUMN     "ladder_d7" BOOLEAN NOT NULL DEFAULT true,
ADD COLUMN     "quiet_hours_end" INTEGER,
ADD COLUMN     "quiet_hours_start" INTEGER,
ADD COLUMN     "reminder_hour" INTEGER NOT NULL DEFAULT 9,
ADD COLUMN     "settings_updated_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- ---------------------------------------------------------------------------------------
-- Rewrite the dedupe keys of reminders that have not gone out yet.
--
-- Up to M1 a key was `${occurrence.id}:D0`. The phone has never seen an occurrence id, so it
-- could not produce that string, so the local alarm M2 adds would have carried a different
-- key from the server's push and the user would have been notified twice for one birthday.
-- The new format is `${event_id}:${occurrence_year}:${offset}`, which both sides can compute
-- from data they already hold. See docs/M2_PLAN.md §1 and src/modules/reminders/dedupe-key.ts.
--
-- WHICH ROWS MOVE, and why the others must not:
--
--   PENDING    — has not been shown to anyone. Safe, and the point of the exercise.
--   SUPPRESSED — revivePending() can return it to PENDING, so it has to move too or it would
--                come back carrying a key the client can no longer match.
--   SENT / FAILED / DELIVERED — history. The phone has already claimed those keys in its
--                `seen_reminder` table; rewriting them would un-claim notifications the user
--                has already seen and let a retry show them again.
--   event_occurrence_id IS NULL — self-test rows (`test:${user}:${ms}`). Not event-derived,
--                no local alarm, nothing to agree with.
--
-- This cannot produce a double. The only way the phone could show a rewritten reminder twice
-- is if it had already claimed the OLD key, which would mean it had already been shown, which
-- would mean the row was not PENDING, which would mean it is not rewritten.
--
-- No guard clause is needed against collisions: the rewrite is injective because
-- event_occurrence is UNIQUE on (event_id, occurrence_year) and dedupe_key was already UNIQUE,
-- so there is at most one reminder per (occurrence, offset). If that ever stopped being true
-- the UNIQUE index raises here and the whole migration rolls back, which is the correct
-- outcome — far better than a silently merged pair of reminders.
UPDATE "reminder" AS r
SET "dedupe_key" = o."event_id" || ':' || o."occurrence_year"::text || ':' || r."offset"::text
FROM "event_occurrence" AS o
WHERE r."event_occurrence_id" = o."id"
  AND r."status" IN ('PENDING', 'SUPPRESSED');
