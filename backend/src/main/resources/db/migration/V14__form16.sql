-- S14: Form 16 (India), per financial year and employee. A published Form 16 is an immutable snapshot: the JSON it was
-- built from (with its SHA-256) and the PDF rendered from it. Written to be re-runnable (IF NOT EXISTS / DROP ... IF EXISTS).

CREATE TABLE IF NOT EXISTS "form16_forms" (
    "id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id(),
    "fy" TEXT NOT NULL,                       -- "2026-27"
    "employee_id" UUID NOT NULL,
    "employee_code" TEXT NOT NULL,            -- copied: the record must read the same later
    "employee_name" TEXT NOT NULL,
    "snapshot" TEXT NOT NULL,                 -- the exact JSON text that was hashed (the PAN is only inside it encrypted)
    "snapshot_sha256" TEXT NOT NULL,
    "pdf" BYTEA NOT NULL,
    "pdf_sha256" TEXT NOT NULL,
    "published_by" UUID,
    "published_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "form16_forms_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "form16_forms_fy_check" CHECK ("fy" ~ '^[0-9]{4}-[0-9]{2}$'),
    CONSTRAINT "form16_forms_business_id_fkey" FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT "form16_forms_employee_id_fkey" FOREIGN KEY ("employee_id") REFERENCES "employees"("id") ON DELETE RESTRICT ON UPDATE RESTRICT
);
CREATE UNIQUE INDEX IF NOT EXISTS "form16_forms_business_fy_employee_key" ON "form16_forms"("business_id", "fy", "employee_id");
CREATE INDEX IF NOT EXISTS "form16_forms_employee_id_idx" ON "form16_forms"("employee_id");

-- never updated or deleted (jurisone_append_only comes from V5)
DROP TRIGGER IF EXISTS "form16_forms_append_only" ON "form16_forms";
CREATE TRIGGER "form16_forms_append_only" BEFORE UPDATE OR DELETE ON "form16_forms" FOR EACH ROW EXECUTE FUNCTION jurisone_append_only();
DROP TRIGGER IF EXISTS "form16_forms_no_truncate" ON "form16_forms";
CREATE TRIGGER "form16_forms_no_truncate" BEFORE TRUNCATE ON "form16_forms" FOR EACH STATEMENT EXECUTE FUNCTION jurisone_append_only();

ALTER TABLE "form16_forms" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "form16_forms" FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "tenant_isolation" ON "form16_forms";
CREATE POLICY "tenant_isolation" ON "form16_forms" USING (jurisone_tenant_id() IS NULL OR business_id = jurisone_tenant_id())
    WITH CHECK (jurisone_tenant_id() IS NULL OR business_id = jurisone_tenant_id());
GRANT SELECT, INSERT ON "form16_forms" TO jurisone_tenant;
