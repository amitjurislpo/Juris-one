-- Reverses V26__attendance_regularization.sql (run with psql, then delete version 26 from flyway_schema_history).
-- Lost on purpose: the Old/New values, reasons, attachments' database rows (files stay on disk) and decision remarks.
-- Duplicate pending requests that V26 cancelled stay cancelled. Finance gets "appr.reg" back.
DROP INDEX IF EXISTS "regularization_requests_work_date_idx";
DROP INDEX IF EXISTS "regularization_requests_one_pending_per_day";
ALTER TABLE "regularization_requests"
    DROP COLUMN IF EXISTS "updated_at", DROP COLUMN IF EXISTS "attachment_path", DROP COLUMN IF EXISTS "attachment_bytes",
    DROP COLUMN IF EXISTS "attachment_mime", DROP COLUMN IF EXISTS "attachment_name", DROP COLUMN IF EXISTS "created_by",
    DROP COLUMN IF EXISTS "decision_note", DROP COLUMN IF EXISTS "new_status", DROP COLUMN IF EXISTS "new_minutes",
    DROP COLUMN IF EXISTS "old_status", DROP COLUMN IF EXISTS "old_minutes", DROP COLUMN IF EXISTS "old_check_out",
    DROP COLUMN IF EXISTS "old_check_in", DROP COLUMN IF EXISTS "description", DROP COLUMN IF EXISTS "reason_id";
DROP TABLE IF EXISTS "regularization_reasons";
UPDATE "role_templates" SET "permissions" = array_append("permissions", 'appr.reg') WHERE "key" IN ('fm', 'fe') AND NOT ('appr.reg' = ANY("permissions"));
INSERT INTO "role_permissions" ("role_id", "permission_key") SELECT "id", 'appr.reg' FROM "roles" WHERE "key" IN ('fm', 'fe') AND "is_builtin" ON CONFLICT DO NOTHING;
