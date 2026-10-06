-- Reverse of attendance_engine
DROP TABLE "job_runs";
ALTER TABLE "attendance_days" DROP COLUMN "label", DROP COLUMN "leave_days", DROP COLUMN "lop_days", DROP COLUMN "missed_checkout";
-- Postgres can't drop an enum value: rebuild the type without NOT_TRACKED.
DELETE FROM "attendance_days" WHERE "status" = 'NOT_TRACKED';
ALTER TYPE "DayStatus" RENAME TO "DayStatus_old";
CREATE TYPE "DayStatus" AS ENUM ('PRESENT', 'HALF_DAY', 'ABSENT', 'LEAVE', 'HOLIDAY', 'WEEKLY_OFF', 'BEFORE_JOINING', 'AFTER_EXIT', 'PENDING');
ALTER TABLE "attendance_days" ALTER COLUMN "status" TYPE "DayStatus" USING ("status"::text::"DayStatus");
DROP TYPE "DayStatus_old";
