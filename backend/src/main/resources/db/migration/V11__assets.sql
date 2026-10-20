-- S11: asset tracking (laptops, phones, ...). Idempotent: nothing existing is dropped or recreated.

CREATE TABLE IF NOT EXISTS "assets" (
    "id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id() REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    "tag" TEXT NOT NULL,
    "type" TEXT NOT NULL DEFAULT 'OTHER',
    "make" TEXT,
    "model" TEXT,
    "serial" TEXT,
    "purchase_date" DATE,
    "status" TEXT NOT NULL DEFAULT 'AVAILABLE',
    "notes" TEXT,
    "created_by" UUID,
    "created_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "assets_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "assets_type_check" CHECK ("type" IN ('LAPTOP', 'DESKTOP', 'PHONE', 'TABLET', 'MONITOR', 'ACCESSORY', 'OTHER')),
    CONSTRAINT "assets_status_check" CHECK ("status" IN ('AVAILABLE', 'ASSIGNED', 'IN_REPAIR', 'RETIRED'))
);
CREATE UNIQUE INDEX IF NOT EXISTS "assets_business_tag_key" ON "assets"("business_id", lower("tag"));
CREATE INDEX IF NOT EXISTS "assets_business_id_idx" ON "assets"("business_id", "status");

-- One row per time an asset is held by an employee; returned_on IS NULL = still held.
CREATE TABLE IF NOT EXISTS "asset_assignments" (
    "id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id() REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    "asset_id" UUID NOT NULL REFERENCES "assets"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    "employee_id" UUID NOT NULL REFERENCES "employees"("id") ON DELETE RESTRICT ON UPDATE RESTRICT,
    "assigned_on" DATE NOT NULL,
    "assigned_by" UUID,
    "assign_notes" TEXT,
    "returned_on" DATE,
    "returned_by" UUID,
    "return_condition" TEXT,
    "return_notes" TEXT,
    "created_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "asset_assignments_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "asset_assignments_condition_check" CHECK ("return_condition" IS NULL OR "return_condition" IN ('GOOD', 'FAIR', 'DAMAGED', 'LOST'))
);
CREATE UNIQUE INDEX IF NOT EXISTS "asset_assignments_open_key" ON "asset_assignments"("asset_id") WHERE "returned_on" IS NULL;
CREATE INDEX IF NOT EXISTS "asset_assignments_employee_idx" ON "asset_assignments"("employee_id");
CREATE INDEX IF NOT EXISTS "asset_assignments_business_id_idx" ON "asset_assignments"("business_id");

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['assets', 'asset_assignments'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS "tenant_isolation" ON %I', t);
        EXECUTE format('CREATE POLICY "tenant_isolation" ON %I USING (jurisone_tenant_id() IS NULL OR business_id = jurisone_tenant_id()) '
                    || 'WITH CHECK (jurisone_tenant_id() IS NULL OR business_id = jurisone_tenant_id())', t);
    END LOOP;
END $$;
GRANT SELECT, INSERT, UPDATE, DELETE ON "assets", "asset_assignments" TO jurisone_tenant;

-- the new permission: owners and HR managers of existing businesses get it
INSERT INTO "role_permissions" ("role_id", "permission_key")
SELECT "id", 'assets.manage' FROM "roles" WHERE "business_id" IS NOT NULL AND "key" IN ('owner', 'hrm')
ON CONFLICT DO NOTHING;
