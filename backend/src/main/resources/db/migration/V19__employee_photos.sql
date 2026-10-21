-- S19: employee profile photos. The image bytes live in their own table so the many `SELECT e.*` queries on employees never
-- drag them along; employees.photo_updated_at is the cheap "has a photo / which version" marker that every list carries.

ALTER TABLE "employees" ADD COLUMN "photo_updated_at" TIMESTAMPTZ(6);

CREATE TABLE "employee_photos" (
    "employee_id"  UUID NOT NULL REFERENCES "employees"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    "business_id"  UUID NOT NULL DEFAULT jurisone_tenant_id() REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    "content_type" TEXT NOT NULL,
    "data"         BYTEA NOT NULL,
    "updated_at"   TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "employee_photos_pkey" PRIMARY KEY ("employee_id"),
    CONSTRAINT "employee_photos_type_check" CHECK ("content_type" IN ('image/jpeg', 'image/png', 'image/webp')),
    CONSTRAINT "employee_photos_size_check" CHECK (octet_length("data") BETWEEN 1 AND 409600)
);
CREATE INDEX "employee_photos_business_id_idx" ON "employee_photos"("business_id");

ALTER TABLE "employee_photos" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "employee_photos" FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "tenant_isolation" ON "employee_photos";
CREATE POLICY "tenant_isolation" ON "employee_photos" USING (jurisone_tenant_id() IS NULL OR business_id = jurisone_tenant_id())
    WITH CHECK (jurisone_tenant_id() IS NULL OR business_id = jurisone_tenant_id());
GRANT SELECT, INSERT, UPDATE, DELETE ON "employee_photos" TO jurisone_tenant;
