-- S35: shift roster. Shifts become per business (the three seeded ones stay as read-only platform defaults, business_id NULL),
-- and a new tenant table holds date-range shift assignments per employee. The shift of an employee on a date is the assignment
-- that covers it, else employees.shift_id (kept as the fallback; nothing is copied or deleted, so nothing can shadow it).
-- New permission shifts.manage for the Owner and HR Manager (templates and the roles of existing businesses).

-- 1. Shifts belong to a business or (NULL) to the platform.
ALTER TABLE "shifts" ADD COLUMN "business_id" UUID DEFAULT jurisone_tenant_id() REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE;
-- The three existing rows are platform defaults.
UPDATE "shifts" SET "business_id" = NULL;
ALTER TABLE "shifts" ADD COLUMN "created_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP;
ALTER TABLE "shifts" ADD CONSTRAINT "shifts_times_check" CHECK ("start_local" ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$' AND "end_local" ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$');
ALTER TABLE "shifts" ADD CONSTRAINT "shifts_grace_check" CHECK ("grace_minutes" BETWEEN 0 AND 240);

-- Codes are unique inside one business (and among the platform defaults).
DROP INDEX "shifts_code_key";
CREATE UNIQUE INDEX "shifts_business_code_key" ON "shifts"(COALESCE("business_id", '00000000-0000-0000-0000-000000000000'::uuid), "code");

ALTER TABLE "shifts" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "shifts" FORCE ROW LEVEL SECURITY;
-- Everyone reads the platform defaults and their own business's shifts; a business writes only its own rows.
CREATE POLICY "shifts_read" ON "shifts" FOR SELECT USING (jurisone_tenant_id() IS NULL OR "business_id" IS NULL OR "business_id" = jurisone_tenant_id());
CREATE POLICY "shifts_write" ON "shifts" FOR ALL USING (jurisone_tenant_id() IS NULL OR "business_id" = jurisone_tenant_id())
    WITH CHECK (jurisone_tenant_id() IS NULL OR "business_id" = jurisone_tenant_id());

-- 2. The roster: one row = one employee on one shift from a date to a date (NULL = open ended).
CREATE TABLE "shift_assignments" (
    "id"           UUID NOT NULL,
    "business_id"  UUID NOT NULL DEFAULT jurisone_tenant_id() REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    "employee_id"  UUID NOT NULL REFERENCES "employees"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    "shift_id"     UUID NOT NULL REFERENCES "shifts"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    "from_date"    DATE NOT NULL,
    "to_date"      DATE,
    "created_by"   UUID REFERENCES "employees"("id") ON DELETE SET NULL ON UPDATE CASCADE,
    "created_at"   TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "shift_assignments_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "shift_assignments_dates_check" CHECK ("to_date" IS NULL OR "to_date" >= "from_date")
);
-- Ranges of one employee must not overlap: the service checks it under a lock on the employee row (an exclusion constraint would need btree_gist).
CREATE INDEX "shift_assignments_employee_idx" ON "shift_assignments"("employee_id", "from_date");

ALTER TABLE "shift_assignments" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "shift_assignments" FORCE ROW LEVEL SECURITY;
CREATE POLICY "tenant_isolation" ON "shift_assignments" USING (jurisone_tenant_id() IS NULL OR business_id = jurisone_tenant_id())
    WITH CHECK (jurisone_tenant_id() IS NULL OR business_id = jurisone_tenant_id());
GRANT SELECT, INSERT, UPDATE, DELETE ON "shift_assignments" TO jurisone_tenant;

-- 3. The permission: Owner and HR Manager (templates, and the roles of businesses that already exist).
UPDATE "role_templates" SET "permissions" = array_append("permissions", 'shifts.manage')
WHERE "key" IN ('owner', 'hrm') AND NOT ('shifts.manage' = ANY ("permissions"));
INSERT INTO "role_permissions" ("role_id", "permission_key")
SELECT "id", 'shifts.manage' FROM "roles" WHERE "business_id" IS NOT NULL AND "key" IN ('owner', 'hrm')
ON CONFLICT DO NOTHING;
