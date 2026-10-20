-- Reverses V6__notification_box.sql (run with psql, then delete version 6 from flyway_schema_history).
DROP INDEX IF EXISTS "notifications_employee_unread_idx";
ALTER TABLE "notifications" DROP COLUMN IF EXISTS "ref_id";
ALTER TABLE "notifications" DROP COLUMN IF EXISTS "kind";
