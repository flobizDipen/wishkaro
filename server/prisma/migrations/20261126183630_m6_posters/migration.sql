-- CreateEnum
CREATE TYPE "AssetKind" AS ENUM ('POSTER', 'SOURCE_PHOTO');

-- CreateEnum
CREATE TYPE "PosterFormat" AS ENUM ('STATUS', 'SQUARE', 'LINK');

-- CreateEnum
CREATE TYPE "ThemeFamily" AS ENUM ('CELEBRATORY', 'SOLEMN');

-- CreateEnum
CREATE TYPE "GenerationStatus" AS ENUM ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'BLOCKED');

-- CreateEnum
CREATE TYPE "ModerationVerdict" AS ENUM ('UNCHECKED', 'ALLOWED', 'BLOCKED');

-- AlterTable
ALTER TABLE "ai_usage" ADD COLUMN     "generation_id" TEXT;

-- CreateTable
CREATE TABLE "asset" (
    "id" TEXT NOT NULL,
    "user_id" TEXT NOT NULL,
    "person_id" TEXT,
    "kind" "AssetKind" NOT NULL,
    "blob_key" TEXT NOT NULL,
    "content_type" TEXT NOT NULL,
    "bytes" INTEGER NOT NULL,
    "width" INTEGER,
    "height" INTEGER,
    "expires_at" TIMESTAMP(3),
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted_at" TIMESTAMP(3),

    CONSTRAINT "asset_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "theme_cache" (
    "id" TEXT NOT NULL,
    "theme_key" TEXT NOT NULL,
    "theme_id" TEXT NOT NULL,
    "family" "ThemeFamily" NOT NULL,
    "blob_key" TEXT NOT NULL,
    "event_type" "EventType" NOT NULL,
    "milestone" TEXT,
    "palette" TEXT NOT NULL,
    "aspect_ratio" TEXT NOT NULL,
    "approved" BOOLEAN NOT NULL DEFAULT false,
    "hit_count" INTEGER NOT NULL DEFAULT 0,
    "provider" TEXT,
    "model" TEXT,
    "prompt_version" TEXT,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "theme_cache_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "generation" (
    "id" TEXT NOT NULL,
    "user_id" TEXT NOT NULL,
    "feature" TEXT NOT NULL,
    "person_id" TEXT,
    "status" "GenerationStatus" NOT NULL DEFAULT 'QUEUED',
    "cache_hit" BOOLEAN NOT NULL DEFAULT false,
    "degraded" BOOLEAN NOT NULL DEFAULT false,
    "moderation" "ModerationVerdict" NOT NULL DEFAULT 'UNCHECKED',
    "prompt_hash" TEXT,
    "prompt_version" TEXT,
    "latency_ms" INTEGER,
    "cost_inr" DECIMAL(12,4) NOT NULL DEFAULT 0,
    "error_code" TEXT,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "completed_at" TIMESTAMP(3),

    CONSTRAINT "generation_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "poster" (
    "id" TEXT NOT NULL,
    "user_id" TEXT NOT NULL,
    "person_id" TEXT NOT NULL,
    "generation_id" TEXT,
    "theme_id" TEXT NOT NULL,
    "family" "ThemeFamily" NOT NULL,
    "sentiment" "Sentiment" NOT NULL,
    "event_type" "EventType" NOT NULL,
    "format" "PosterFormat" NOT NULL,
    "asset_id" TEXT NOT NULL,
    "attributed" BOOLEAN NOT NULL DEFAULT true,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted_at" TIMESTAMP(3),

    CONSTRAINT "poster_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "content_report" (
    "id" TEXT NOT NULL,
    "user_id" TEXT NOT NULL,
    "feature" TEXT NOT NULL,
    "poster_id" TEXT,
    "generation_id" TEXT,
    "reason" TEXT NOT NULL,
    "detail" TEXT,
    "reported_text" TEXT,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "content_report_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "asset_user_id_created_at_idx" ON "asset"("user_id", "created_at");

-- CreateIndex
CREATE INDEX "asset_person_id_idx" ON "asset"("person_id");

-- CreateIndex
CREATE INDEX "asset_expires_at_idx" ON "asset"("expires_at");

-- CreateIndex
CREATE UNIQUE INDEX "theme_cache_theme_key_key" ON "theme_cache"("theme_key");

-- CreateIndex
CREATE INDEX "theme_cache_family_event_type_approved_idx" ON "theme_cache"("family", "event_type", "approved");

-- CreateIndex
CREATE INDEX "generation_user_id_created_at_idx" ON "generation"("user_id", "created_at");

-- CreateIndex
CREATE INDEX "generation_feature_cache_hit_created_at_idx" ON "generation"("feature", "cache_hit", "created_at");

-- CreateIndex
CREATE INDEX "poster_user_id_created_at_idx" ON "poster"("user_id", "created_at");

-- CreateIndex
CREATE INDEX "poster_person_id_created_at_idx" ON "poster"("person_id", "created_at");

-- CreateIndex
CREATE INDEX "poster_sentiment_family_idx" ON "poster"("sentiment", "family");

-- CreateIndex
CREATE INDEX "content_report_created_at_idx" ON "content_report"("created_at");

-- CreateIndex
CREATE INDEX "content_report_user_id_created_at_idx" ON "content_report"("user_id", "created_at");

-- CreateIndex
CREATE INDEX "ai_usage_generation_id_idx" ON "ai_usage"("generation_id");

-- AddForeignKey
ALTER TABLE "ai_usage" ADD CONSTRAINT "ai_usage_generation_id_fkey" FOREIGN KEY ("generation_id") REFERENCES "generation"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "asset" ADD CONSTRAINT "asset_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "user"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "asset" ADD CONSTRAINT "asset_person_id_fkey" FOREIGN KEY ("person_id") REFERENCES "person"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "generation" ADD CONSTRAINT "generation_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "user"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "poster" ADD CONSTRAINT "poster_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "user"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "poster" ADD CONSTRAINT "poster_person_id_fkey" FOREIGN KEY ("person_id") REFERENCES "person"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "poster" ADD CONSTRAINT "poster_asset_id_fkey" FOREIGN KEY ("asset_id") REFERENCES "asset"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "poster" ADD CONSTRAINT "poster_generation_id_fkey" FOREIGN KEY ("generation_id") REFERENCES "generation"("id") ON DELETE SET NULL ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "content_report" ADD CONSTRAINT "content_report_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "user"("id") ON DELETE CASCADE ON UPDATE CASCADE;
