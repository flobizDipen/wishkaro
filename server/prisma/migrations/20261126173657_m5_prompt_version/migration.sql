-- AlterTable
ALTER TABLE "ai_usage" ADD COLUMN     "prompt_version" TEXT NOT NULL DEFAULT 'unversioned';
