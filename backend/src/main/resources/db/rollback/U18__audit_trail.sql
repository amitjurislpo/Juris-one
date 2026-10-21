-- Reverses V18__audit_trail.sql (run with psql, then delete version 18 from flyway_schema_history). Existing audit rows are kept.
DROP TRIGGER IF EXISTS "audit_log_no_truncate" ON "audit_log";
DROP TRIGGER IF EXISTS "audit_log_append_only" ON "audit_log";
DROP FUNCTION IF EXISTS jurisone_audit_log_guard();
DROP INDEX IF EXISTS "audit_log_action_idx";
DROP INDEX IF EXISTS "audit_log_business_at_desc_idx";
ALTER TABLE "audit_log"
    DROP COLUMN IF EXISTS "request_id",
    DROP COLUMN IF EXISTS "user_agent",
    DROP COLUMN IF EXISTS "duration_ms",
    DROP COLUMN IF EXISTS "status",
    DROP COLUMN IF EXISTS "path",
    DROP COLUMN IF EXISTS "method";
