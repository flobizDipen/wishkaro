-- CreateEnum
CREATE TYPE "EventType" AS ENUM ('BIRTHDAY', 'WEDDING_ANNIVERSARY', 'ENGAGEMENT_ANNIVERSARY', 'DEATH_ANNIVERSARY', 'CUSTOM');

-- CreateEnum
CREATE TYPE "Sentiment" AS ENUM ('CELEBRATORY', 'SOLEMN');

-- CreateEnum
CREATE TYPE "CalendarSystem" AS ENUM ('GREGORIAN');

-- CreateEnum
CREATE TYPE "ReminderOffset" AS ENUM ('D7', 'D1', 'D0');

-- CreateEnum
CREATE TYPE "ReminderChannel" AS ENUM ('FCM', 'LOCAL');

-- CreateEnum
CREATE TYPE "ReminderStatus" AS ENUM ('PENDING', 'SENT', 'DELIVERED', 'FAILED', 'SUPPRESSED');

-- CreateEnum
CREATE TYPE "DevicePlatform" AS ENUM ('ANDROID', 'IOS');

-- CreateTable
CREATE TABLE "user" (
    "id" TEXT NOT NULL,
    "google_sub" TEXT NOT NULL,
    "email" TEXT NOT NULL,
    "name" TEXT,
    "photo_url" TEXT,
    "locale" TEXT,
    "timezone" TEXT NOT NULL DEFAULT 'Asia/Kolkata',
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted_at" TIMESTAMP(3),

    CONSTRAINT "user_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "person" (
    "id" TEXT NOT NULL,
    "user_id" TEXT NOT NULL,
    "display_name" TEXT NOT NULL,
    "relation_to_owner" TEXT,
    "is_self" BOOLEAN NOT NULL DEFAULT false,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted_at" TIMESTAMP(3),

    CONSTRAINT "person_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "event" (
    "id" TEXT NOT NULL,
    "person_id" TEXT NOT NULL,
    "user_id" TEXT NOT NULL,
    "type" "EventType" NOT NULL,
    "sentiment" "Sentiment" NOT NULL,
    "day" INTEGER NOT NULL,
    "month" INTEGER NOT NULL,
    "year" INTEGER,
    "calendar_system" "CalendarSystem" NOT NULL DEFAULT 'GREGORIAN',
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted_at" TIMESTAMP(3),

    CONSTRAINT "event_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "event_occurrence" (
    "id" TEXT NOT NULL,
    "event_id" TEXT NOT NULL,
    "occurrence_year" INTEGER NOT NULL,
    "occurs_on" DATE NOT NULL,
    "override_date" DATE,
    "computed_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "event_occurrence_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "reminder" (
    "id" TEXT NOT NULL,
    "event_occurrence_id" TEXT,
    "user_id" TEXT NOT NULL,
    "offset" "ReminderOffset" NOT NULL,
    "scheduled_at_utc" TIMESTAMP(3) NOT NULL,
    "channel" "ReminderChannel" NOT NULL DEFAULT 'FCM',
    "status" "ReminderStatus" NOT NULL DEFAULT 'PENDING',
    "dedupe_key" TEXT NOT NULL,
    "sent_at" TIMESTAMP(3),
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "reminder_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "device" (
    "id" TEXT NOT NULL,
    "user_id" TEXT NOT NULL,
    "fcm_token" TEXT NOT NULL,
    "platform" "DevicePlatform" NOT NULL,
    "timezone" TEXT,
    "last_seen_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "device_pkey" PRIMARY KEY ("id")
);

-- CreateTable
CREATE TABLE "refresh_token" (
    "id" TEXT NOT NULL,
    "user_id" TEXT NOT NULL,
    "token_hash" TEXT NOT NULL,
    "expires_at" TIMESTAMP(3) NOT NULL,
    "revoked_at" TIMESTAMP(3),
    "replaced_by_id" TEXT,
    "created_at" TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "refresh_token_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "user_google_sub_key" ON "user"("google_sub");

-- CreateIndex
CREATE INDEX "user_email_idx" ON "user"("email");

-- CreateIndex
CREATE INDEX "person_user_id_updated_at_id_idx" ON "person"("user_id", "updated_at", "id");

-- CreateIndex
CREATE INDEX "event_user_id_updated_at_id_idx" ON "event"("user_id", "updated_at", "id");

-- CreateIndex
CREATE INDEX "event_person_id_idx" ON "event"("person_id");

-- CreateIndex
CREATE INDEX "event_occurrence_occurs_on_idx" ON "event_occurrence"("occurs_on");

-- CreateIndex
CREATE UNIQUE INDEX "event_occurrence_event_id_occurrence_year_key" ON "event_occurrence"("event_id", "occurrence_year");

-- CreateIndex
CREATE UNIQUE INDEX "reminder_dedupe_key_key" ON "reminder"("dedupe_key");

-- CreateIndex
CREATE INDEX "reminder_scheduled_at_utc_status_idx" ON "reminder"("scheduled_at_utc", "status");

-- CreateIndex
CREATE INDEX "reminder_user_id_idx" ON "reminder"("user_id");

-- CreateIndex
CREATE UNIQUE INDEX "device_fcm_token_key" ON "device"("fcm_token");

-- CreateIndex
CREATE INDEX "device_user_id_idx" ON "device"("user_id");

-- CreateIndex
CREATE UNIQUE INDEX "refresh_token_token_hash_key" ON "refresh_token"("token_hash");

-- CreateIndex
CREATE INDEX "refresh_token_user_id_idx" ON "refresh_token"("user_id");

-- CreateIndex
CREATE INDEX "refresh_token_expires_at_idx" ON "refresh_token"("expires_at");

-- AddForeignKey
ALTER TABLE "person" ADD CONSTRAINT "person_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "user"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "event" ADD CONSTRAINT "event_person_id_fkey" FOREIGN KEY ("person_id") REFERENCES "person"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "event" ADD CONSTRAINT "event_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "user"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "event_occurrence" ADD CONSTRAINT "event_occurrence_event_id_fkey" FOREIGN KEY ("event_id") REFERENCES "event"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "reminder" ADD CONSTRAINT "reminder_event_occurrence_id_fkey" FOREIGN KEY ("event_occurrence_id") REFERENCES "event_occurrence"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "reminder" ADD CONSTRAINT "reminder_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "user"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "device" ADD CONSTRAINT "device_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "user"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- AddForeignKey
ALTER TABLE "refresh_token" ADD CONSTRAINT "refresh_token_user_id_fkey" FOREIGN KEY ("user_id") REFERENCES "user"("id") ON DELETE CASCADE ON UPDATE CASCADE;
