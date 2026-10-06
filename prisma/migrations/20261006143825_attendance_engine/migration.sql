-- AlterEnum
ALTER TYPE "DayStatus" ADD VALUE 'NOT_TRACKED';

-- AlterTable
ALTER TABLE "attendance_days" ADD COLUMN     "label" TEXT,
ADD COLUMN     "leave_days" DECIMAL(3,1) NOT NULL DEFAULT 0,
ADD COLUMN     "lop_days" DECIMAL(3,1) NOT NULL DEFAULT 0,
ADD COLUMN     "missed_checkout" BOOLEAN NOT NULL DEFAULT false;

-- CreateTable
CREATE TABLE "job_runs" (
    "id" UUID NOT NULL,
    "name" TEXT NOT NULL,
    "key" TEXT NOT NULL,
    "ran_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "ok" BOOLEAN NOT NULL DEFAULT true,
    "detail" JSONB,

    CONSTRAINT "job_runs_pkey" PRIMARY KEY ("id")
);

-- CreateIndex
CREATE UNIQUE INDEX "job_runs_name_key_key" ON "job_runs"("name", "key");
