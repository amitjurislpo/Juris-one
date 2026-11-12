-- Reverses V34__on_duty_requests.sql (run with psql, then delete version 34 from flyway_schema_history).
-- Lost on purpose: every On Duty request and the on-duty share of locked attendance days (a locked On Duty day keeps its status and LOP days).
ALTER TABLE "attendance_days" DROP COLUMN IF EXISTS "on_duty_days";
DROP TABLE IF EXISTS "on_duty_requests";
