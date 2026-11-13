-- Reverses V39__remote_work_half_day.sql (run with psql, then delete version 39 from flyway_schema_history).
-- Lost on purpose: which half of a day was remote. A half-day remote request becomes a full remote day after the rollback; reject or
-- withdraw half-day requests first if that is not wanted.
ALTER TABLE "remote_work_requests" DROP CONSTRAINT IF EXISTS "remote_work_requests_half_is_one_day_check";
ALTER TABLE "remote_work_requests" DROP CONSTRAINT IF EXISTS "remote_work_requests_part_check";
ALTER TABLE "remote_work_requests" DROP COLUMN IF EXISTS "part";
