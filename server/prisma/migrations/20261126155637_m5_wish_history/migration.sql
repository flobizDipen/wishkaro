-- CreateEnum
CREATE TYPE "WishTone" AS ENUM ('WARM', 'FUNNY', 'RESPECTFUL', 'SHORT', 'POETIC', 'HEARTFELT');

-- CreateEnum
CREATE TYPE "WishLanguage" AS ENUM ('ENGLISH', 'HINDI', 'HINGLISH');

-- CreateEnum
CREATE TYPE "WishChannel" AS ENUM ('WHATSAPP', 'SMS', 'SHARE', 'COPY');

-- CreateEnum
CREATE TYPE "WishSource" AS ENUM ('AI', 'TEMPLATE', 'USER');

-- CreateTable
CREATE TABLE "wish" (
    "id" TEXT NOT NULL,
    "user_id" TEXT NOT NULL,
    "person_id" TEXT NOT NULL,
    "event_type" "EventType" NOT NULL,
    "occurrence_year" INTEGER NOT NULL,
    "sentiment" "Sentiment" NOT NULL,
    "event_id" TEXT,
    "message_text" TEXT NOT NULL,
    "tone" "WishTone" NOT NULL,
    "language" "WishLanguage" NOT NULL,
    "channel" "WishChannel" NOT NULL,
    "source" "WishSource" NOT NULL,
    "poster_id" TEXT,
    "sent_at" TIMESTAMP(3) NOT NULL,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted_at" TIMESTAMP(3),

    CONSTRAINT "wish_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "ai_usage" (
    "id" TEXT NOT NULL,
    "user_id" TEXT NOT NULL,
    "feature" TEXT NOT NULL,
    "provider" TEXT NOT NULL,
    "model" TEXT NOT NULL,
    "outcome" TEXT NOT NULL,
    "input_tokens" INTEGER NOT NULL,
    "output_tokens" INTEGER NOT NULL,
    "cache_read_tokens" INTEGER NOT NULL,
    "cache_write_tokens" INTEGER NOT NULL,
    "cost_usd" DECIMAL(12,6) NOT NULL,
    "usd_inr_rate" DECIMAL(10,4) NOT NULL,
    "cost_inr" DECIMAL(12,4) NOT NULL,
    "pricing_version" TEXT NOT NULL,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "ai_usage_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE INDEX "wish_user_id_updated_at_id_idx" ON "wish"("user_id", "updated_at", "id");

-- CreateIndex
CREATE INDEX "wish_person_id_event_type_occurrence_year_idx" ON "wish"("person_id", "event_type", "occurrence_year");

-- CreateIndex
CREATE INDEX "ai_usage_user_id_created_at_idx" ON "ai_usage"("user_id", "created_at");

-- CreateIndex
CREATE INDEX "ai_usage_feature_created_at_idx" ON "ai_usage"("feature", "created_at");

-- AddForeignKey
ALTER TABLE "wish" ADD CONSTRAINT "wish_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "user"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "wish" ADD CONSTRAINT "wish_person_id_fkey" FOREIGN KEY ("person_id") REFERENCES "person"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "ai_usage" ADD CONSTRAINT "ai_usage_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "user"("id") ON DELETE CASCADE ON UPDATE CASCADE;
