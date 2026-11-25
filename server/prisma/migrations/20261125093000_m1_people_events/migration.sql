-- CreateEnum
CREATE TYPE "TraitKind" AS ENUM ('HOBBY', 'LIKE', 'DISLIKE', 'BUDGET_BAND');

-- CreateEnum
CREATE TYPE "TraitSource" AS ENUM ('USER', 'IMPORT', 'INFERRED');

-- AlterTable
ALTER TABLE "event" ADD COLUMN     "is_muted" BOOLEAN NOT NULL DEFAULT false,
ADD COLUMN     "label" TEXT;

-- AlterTable
ALTER TABLE "event_occurrence" ADD COLUMN     "milestone_label" TEXT;

-- AlterTable
ALTER TABLE "person" ADD COLUMN     "completeness_score" INTEGER NOT NULL DEFAULT 0,
ADD COLUMN     "email" TEXT,
ADD COLUMN     "gender" TEXT,
ADD COLUMN     "is_deceased" BOOLEAN NOT NULL DEFAULT false,
ADD COLUMN     "is_minor" BOOLEAN NOT NULL DEFAULT false,
ADD COLUMN     "notes" TEXT,
ADD COLUMN     "phone" TEXT,
ADD COLUMN     "photo_key" TEXT;

-- CreateTable
CREATE TABLE "person_trait" (
    "id" TEXT NOT NULL,
    "person_id" TEXT NOT NULL,
    "user_id" TEXT NOT NULL,
    "kind" "TraitKind" NOT NULL,
    "value" TEXT NOT NULL,
    "source" "TraitSource" NOT NULL DEFAULT 'USER',
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted_at" TIMESTAMP(3),

    CONSTRAINT "person_trait_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "person_trait_user_id_updated_at_id_idx" ON "person_trait"("user_id", "updated_at", "id");

-- CreateIndex
CREATE INDEX "person_trait_person_id_idx" ON "person_trait"("person_id");

-- AddForeignKey
ALTER TABLE "person_trait" ADD CONSTRAINT "person_trait_person_id_fkey" FOREIGN KEY ("person_id") REFERENCES "person"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "person_trait" ADD CONSTRAINT "person_trait_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "user"("id") ON DELETE CASCADE ON UPDATE CASCADE;
