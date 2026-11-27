-- M9 — FR-SET-06 (retention) and the one link FR-SET-06 needs in order to be expressible.
--
-- Additive only. No column is altered, no data is removed, and the one UPDATE below fills a
-- column that did not exist a moment ago (MILESTONE_CHECKLIST.md §C: "nothing destructive in
-- a migration. Real data exists on Railway and on the phone").

-- CreateEnum
CREATE TYPE "AssetRetention" AS ENUM ('AUTO', 'KEEP');

-- AlterTable
ALTER TABLE "asset" ADD COLUMN     "retention" "AssetRetention" NOT NULL DEFAULT 'AUTO',
ADD COLUMN     "purge_warned_at" TIMESTAMP(3),
ADD COLUMN     "purged_at" TIMESTAMP(3);

-- AlterTable
ALTER TABLE "poster" ADD COLUMN     "source_asset_id" TEXT;

-- CreateIndex
CREATE INDEX "asset_purged_at_expires_at_idx" ON "asset"("purged_at", "expires_at");

-- CreateIndex
CREATE INDEX "poster_source_asset_id_idx" ON "poster"("source_asset_id");

-- AddForeignKey
ALTER TABLE "poster" ADD CONSTRAINT "poster_source_asset_id_fkey" FOREIGN KEY ("source_asset_id") REFERENCES "asset"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- Backfill: which SOURCE_PHOTO each existing poster was composited from.
--
-- FR-SET-06 protects a source photo that is "attached to a saved poster", and until this
-- column existed nothing recorded the attachment: `poster.asset_id` points at the OUTPUT png.
-- Without a backfill the sweep would have to guess for every poster M6 made, and a guess at
-- sweep time is a guess that runs for ever.
--
-- The rule below is the pipeline's own rule, run backwards: `PosterPipelineService.photoFor`
-- takes the person's most recent non-tombstoned SOURCE_PHOTO, and `PhotoService.upload`
-- tombstones the previous photo at the moment it is replaced — so "the most recent photo
-- created at or before the poster" is the row the pipeline saw. Deterministic, and it runs
-- once rather than on every sweep.
--
-- Where a person had no photo at that time the subquery is NULL, which is exactly right: a
-- typographic poster (FR-POS-06 / OD-11) has no source photo and protects nothing.
UPDATE "poster" p
SET "source_asset_id" = (
  SELECT a."id"
  FROM "asset" a
  WHERE a."person_id" = p."person_id"
    AND a."user_id"   = p."user_id"
    AND a."kind"      = 'SOURCE_PHOTO'
    AND a."created_at" <= p."created_at"
  ORDER BY a."created_at" DESC, a."id" DESC
  LIMIT 1
)
WHERE p."source_asset_id" IS NULL;
