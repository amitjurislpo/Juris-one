-- Reverses V3__businesses_and_tenancy.sql (run with psql as the schema owner, then delete version 3 from
-- flyway_schema_history). Only safe while a single business exists: per-business duplicates (same department name,
-- employee code, payroll month in two businesses) would violate the restored global unique indexes.
DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['businesses', 'employees', 'roles', 'departments', 'holidays', 'settings', 'payroll_runs', 'audit_log', 'job_runs',
                             'users', 'punches', 'attendance_days', 'leave_requests', 'leave_ledger', 'regularization_requests', 'salary_structures',
                             'payslips', 'payroll_adjustments', 'declaration_items', 'tax_profiles', 'notifications', 'role_permissions', 'role_assignable_roles'] LOOP
        EXECUTE format('DROP POLICY IF EXISTS "tenant_isolation" ON %I', t);
        EXECUTE format('ALTER TABLE %I NO FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I DISABLE ROW LEVEL SECURITY', t);
    END LOOP;
END $$;

DROP INDEX IF EXISTS "job_runs_business_id_name_key_key";
CREATE UNIQUE INDEX "job_runs_name_key_key" ON "job_runs"("name", "key");
ALTER TABLE "settings" DROP CONSTRAINT "settings_pkey";
ALTER TABLE "settings" ADD CONSTRAINT "settings_pkey" PRIMARY KEY ("key");
DROP INDEX IF EXISTS "payroll_runs_business_id_month_key";
CREATE UNIQUE INDEX "payroll_runs_month_key" ON "payroll_runs"("month");
DROP INDEX IF EXISTS "holidays_business_id_date_region_key";
CREATE UNIQUE INDEX "holidays_date_region_key" ON "holidays"("date", "region");
DROP INDEX IF EXISTS "employees_business_id_code_key";
CREATE UNIQUE INDEX "employees_code_key" ON "employees"("code");
DROP INDEX IF EXISTS "roles_business_id_key_key";
DROP INDEX IF EXISTS "roles_business_id_name_key";
CREATE UNIQUE INDEX "roles_key_key" ON "roles"("key");
CREATE UNIQUE INDEX "roles_name_key" ON "roles"("name");
DROP INDEX IF EXISTS "departments_business_id_name_key";
CREATE UNIQUE INDEX "departments_name_key" ON "departments"("name");
DROP INDEX IF EXISTS "employees_business_id_idx";
DROP INDEX IF EXISTS "audit_log_business_id_at_idx";

ALTER TABLE "employees"    DROP COLUMN "business_id";
ALTER TABLE "roles"        DROP COLUMN "business_id";
ALTER TABLE "departments"  DROP COLUMN "business_id";
ALTER TABLE "holidays"     DROP COLUMN "business_id";
ALTER TABLE "settings"     DROP COLUMN "business_id";
ALTER TABLE "payroll_runs" DROP COLUMN "business_id";
ALTER TABLE "audit_log"    DROP COLUMN "business_id";
ALTER TABLE "job_runs"     DROP COLUMN "business_id";

DROP TABLE "businesses";
DROP FUNCTION jurisone_email_in_use(TEXT, UUID);
DROP FUNCTION jurisone_tenant_id();
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM jurisone_tenant;
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE SELECT, INSERT, UPDATE, DELETE ON TABLES FROM jurisone_tenant;
REVOKE USAGE ON SCHEMA public FROM jurisone_tenant;
-- The role itself is left in place (roles are cluster-wide); drop it with DROP ROLE jurisone_tenant if nothing else uses it.
