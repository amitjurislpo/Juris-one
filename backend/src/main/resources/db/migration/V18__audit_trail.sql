-- Complete audit trail: every write request (and every sensitive read) is recorded with who, when (to the microsecond), how it ended
-- and how long it took; page views are recorded too. The log is append-only.
ALTER TABLE "audit_log"
    ADD COLUMN "method"      TEXT,
    ADD COLUMN "path"        TEXT,
    ADD COLUMN "status"      INTEGER,
    ADD COLUMN "duration_ms" INTEGER,
    ADD COLUMN "user_agent"  TEXT,
    ADD COLUMN "request_id"  TEXT;

CREATE INDEX IF NOT EXISTS "audit_log_business_at_desc_idx" ON "audit_log"("business_id", "at" DESC);
CREATE INDEX IF NOT EXISTS "audit_log_action_idx" ON "audit_log"("action", "at" DESC);

-- Rows are never changed or removed. The only allowed updates are the two that keep references tidy:
--   * a row written outside a business request gets its actor's business (business_id NULL -> a value), and
--   * deleting an employee clears the reference to them (actor_id -> NULL, an ON DELETE SET NULL foreign key).
CREATE OR REPLACE FUNCTION jurisone_audit_log_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        IF to_jsonb(NEW) = to_jsonb(OLD) THEN
            RETURN NEW; -- nothing changes
        END IF;
        IF (to_jsonb(NEW) - 'business_id') = (to_jsonb(OLD) - 'business_id') AND OLD.business_id IS NULL THEN
            RETURN NEW;
        END IF;
        IF (to_jsonb(NEW) - 'actor_id') = (to_jsonb(OLD) - 'actor_id') AND NEW.actor_id IS NULL THEN
            RETURN NEW;
        END IF;
    END IF;
    RAISE EXCEPTION 'audit_log rows are a permanent record and can''t be changed or deleted' USING ERRCODE = 'insufficient_privilege';
END $$;

DROP TRIGGER IF EXISTS "audit_log_append_only" ON "audit_log";
CREATE TRIGGER "audit_log_append_only" BEFORE UPDATE OR DELETE ON "audit_log" FOR EACH ROW EXECUTE FUNCTION jurisone_audit_log_guard();
DROP TRIGGER IF EXISTS "audit_log_no_truncate" ON "audit_log";
CREATE TRIGGER "audit_log_no_truncate" BEFORE TRUNCATE ON "audit_log" FOR EACH STATEMENT EXECUTE FUNCTION jurisone_audit_log_guard();
