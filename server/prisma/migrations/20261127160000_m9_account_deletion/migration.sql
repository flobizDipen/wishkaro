-- M9 — FR-SET-04 (account deletion), and the one collision in it.
--
-- DPDP's erasure right says a deleted user's rows must not identify them. REQUIREMENTS.md §10
-- rule 2 and FR-POS-10 say every model call has a cost row and that this is "non-negotiable,
-- without it you cannot price the product". Both cannot hold while `user_id` is NOT NULL with
-- ON DELETE CASCADE — and that is what the schema has said since M5, so the first user.delete()
-- anybody wrote would have destroyed the ledger silently, with nobody having decided it.
--
-- Dipen's decision, taken after being shown the conflict: ANONYMISE, do not erase. These three
-- tables hold what a call cost and what was reported — not one character of a person's own
-- data. With the id nulled, no row identifies anybody and the financial history survives.
--
-- Additive and non-destructive: dropping NOT NULL widens what the column accepts and relaxing
-- the FK action changes nothing about existing rows (MILESTONE_CHECKLIST.md §C — real data
-- exists on Railway and on the phone).

-- DropForeignKey
ALTER TABLE "ai_usage" DROP CONSTRAINT "ai_usage_user_id_fkey";

-- DropForeignKey
ALTER TABLE "generation" DROP CONSTRAINT "generation_user_id_fkey";

-- DropForeignKey
ALTER TABLE "content_report" DROP CONSTRAINT "content_report_user_id_fkey";

-- AlterTable
ALTER TABLE "ai_usage" ALTER COLUMN "user_id" DROP NOT NULL;

-- AlterTable
ALTER TABLE "generation" ALTER COLUMN "user_id" DROP NOT NULL;

-- AlterTable
ALTER TABLE "content_report" ALTER COLUMN "user_id" DROP NOT NULL;

-- AddForeignKey
--
-- SET NULL rather than CASCADE. The database is what enforces this, not the application: a
-- deletion written in TypeScript that forgot to anonymise first would otherwise take the
-- ledger with it, and §10 rule 2 is too important to be guaranteed only by a service method
-- remembering to run in the right order.
ALTER TABLE "ai_usage" ADD CONSTRAINT "ai_usage_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "user"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "generation" ADD CONSTRAINT "generation_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "user"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "content_report" ADD CONSTRAINT "content_report_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "user"("id") ON DELETE SET NULL ON UPDATE CASCADE;
