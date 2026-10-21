-- Multi-business (SAAS_PLAN S2). One database, one row per business; every business-owned row carries business_id
-- and PostgreSQL row-level security keeps businesses apart:
--   * the API sets app.business_id (and switches to the jurisone_tenant role) for every request of a business user;
--   * sign-in, the super admin, jobs and the seed run with app.business_id unset ("system mode") and see every row;
--   * business_id defaults to app.business_id, so inserts made inside a business are tagged automatically.

-- ── businesses ──────────────────────────────────────────────────────────────
CREATE TABLE "businesses" (
    "id" UUID NOT NULL,
    "name" TEXT NOT NULL,
    "legal_name" TEXT,
    "code" TEXT NOT NULL,                    -- URL-safe slug, unique
    "employee_prefix" TEXT NOT NULL,         -- employee IDs: <prefix>-001
    "gstin" TEXT,
    "address" TEXT,
    "contact_email" TEXT,
    "contact_phone" TEXT,
    "time_zone" TEXT NOT NULL DEFAULT 'Asia/Kolkata',
    "status" TEXT NOT NULL DEFAULT 'ACTIVE',
    "created_by" UUID,
    "created_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "businesses_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "businesses_status_check" CHECK ("status" IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT "businesses_prefix_check" CHECK ("employee_prefix" ~ '^[A-Z]{2,5}$')
);
CREATE UNIQUE INDEX "businesses_code_key" ON "businesses"("code");

-- The business of the current request, or NULL in system mode.
CREATE FUNCTION jurisone_tenant_id() RETURNS UUID LANGUAGE sql STABLE AS $$
    SELECT NULLIF(current_setting('app.business_id', true), '')::uuid
$$;

-- Work emails are the sign-in, so they must be unique across every business. This check looks past row-level security
-- (its own app.business_id is empty) but only answers yes / no.
CREATE FUNCTION jurisone_email_in_use(p_email TEXT, p_except UUID) RETURNS BOOLEAN LANGUAGE sql STABLE SET app.business_id = '' AS $$
    SELECT EXISTS (SELECT 1 FROM employees WHERE email = p_email AND (p_except IS NULL OR id <> p_except))
$$;

-- ── business_id on business-owned tables ────────────────────────────────────
ALTER TABLE "employees"    ADD COLUMN "business_id" UUID DEFAULT jurisone_tenant_id();   -- NULL only for platform users (super admin)
ALTER TABLE "roles"        ADD COLUMN "business_id" UUID DEFAULT jurisone_tenant_id();   -- NULL = platform role
ALTER TABLE "departments"  ADD COLUMN "business_id" UUID DEFAULT jurisone_tenant_id();
ALTER TABLE "holidays"     ADD COLUMN "business_id" UUID DEFAULT jurisone_tenant_id();
ALTER TABLE "settings"     ADD COLUMN "business_id" UUID DEFAULT jurisone_tenant_id();
ALTER TABLE "payroll_runs" ADD COLUMN "business_id" UUID DEFAULT jurisone_tenant_id();
ALTER TABLE "audit_log"    ADD COLUMN "business_id" UUID DEFAULT jurisone_tenant_id();   -- NULL = platform event
ALTER TABLE "job_runs"     ADD COLUMN "business_id" UUID DEFAULT jurisone_tenant_id();   -- NULL = platform job

-- Existing data (a database from before this release) becomes one business, so nothing is lost.
DO $$
DECLARE legacy UUID;
BEGIN
    IF EXISTS (SELECT 1 FROM "employees" e LEFT JOIN "users" u ON u.employee_id = e.id WHERE NOT COALESCE(u.is_super_admin, false)) THEN
        legacy := gen_random_uuid();
        INSERT INTO "businesses" ("id", "name", "code", "employee_prefix") VALUES (legacy, 'Existing company', 'existing-company', 'JC');
        UPDATE "employees" e SET "business_id" = legacy
            WHERE NOT EXISTS (SELECT 1 FROM "users" u WHERE u.employee_id = e.id AND u.is_super_admin);
        UPDATE "roles" SET "business_id" = legacy;
        UPDATE "departments" SET "business_id" = legacy;
        UPDATE "holidays" SET "business_id" = legacy;
        UPDATE "settings" SET "business_id" = legacy;
        UPDATE "payroll_runs" SET "business_id" = legacy;
        UPDATE "audit_log" SET "business_id" = legacy;
        UPDATE "job_runs" SET "business_id" = legacy WHERE "name" <> 'seed';
    ELSE
        DELETE FROM "settings";   -- settings are per business from now on
    END IF;
END $$;

ALTER TABLE "departments"  ALTER COLUMN "business_id" SET NOT NULL;
ALTER TABLE "holidays"     ALTER COLUMN "business_id" SET NOT NULL;
ALTER TABLE "settings"     ALTER COLUMN "business_id" SET NOT NULL;
ALTER TABLE "payroll_runs" ALTER COLUMN "business_id" SET NOT NULL;

ALTER TABLE "employees"    ADD CONSTRAINT "employees_business_id_fkey"    FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "roles"        ADD CONSTRAINT "roles_business_id_fkey"        FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "departments"  ADD CONSTRAINT "departments_business_id_fkey"  FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "holidays"     ADD CONSTRAINT "holidays_business_id_fkey"     FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "settings"     ADD CONSTRAINT "settings_business_id_fkey"     FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "payroll_runs" ADD CONSTRAINT "payroll_runs_business_id_fkey" FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE "audit_log"    ADD CONSTRAINT "audit_log_business_id_fkey"    FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE SET NULL ON UPDATE CASCADE;
ALTER TABLE "job_runs"     ADD CONSTRAINT "job_runs_business_id_fkey"     FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE;

CREATE INDEX "employees_business_id_idx" ON "employees"("business_id");
CREATE INDEX "audit_log_business_id_at_idx" ON "audit_log"("business_id", "at");

-- ── uniqueness is per business now (work email stays unique platform-wide: it is the sign-in) ──
DROP INDEX "departments_name_key";
CREATE UNIQUE INDEX "departments_business_id_name_key" ON "departments"("business_id", "name");
DROP INDEX "roles_key_key";
DROP INDEX "roles_name_key";
CREATE UNIQUE INDEX "roles_business_id_key_key" ON "roles"("business_id", "key") NULLS NOT DISTINCT;
CREATE UNIQUE INDEX "roles_business_id_name_key" ON "roles"("business_id", "name") NULLS NOT DISTINCT;
DROP INDEX "employees_code_key";
CREATE UNIQUE INDEX "employees_business_id_code_key" ON "employees"("business_id", "code") NULLS NOT DISTINCT;
DROP INDEX "holidays_date_region_key";
CREATE UNIQUE INDEX "holidays_business_id_date_region_key" ON "holidays"("business_id", "date", "region");
DROP INDEX "payroll_runs_month_key";
CREATE UNIQUE INDEX "payroll_runs_business_id_month_key" ON "payroll_runs"("business_id", "month");
ALTER TABLE "settings" DROP CONSTRAINT "settings_pkey";
ALTER TABLE "settings" ADD CONSTRAINT "settings_pkey" PRIMARY KEY ("business_id", "key");
DROP INDEX "job_runs_name_key_key";
CREATE UNIQUE INDEX "job_runs_business_id_name_key_key" ON "job_runs"("business_id", "name", "key") NULLS NOT DISTINCT;

-- ── row-level security ──────────────────────────────────────────────────────
-- Tables with business_id: visible / writable only inside the current business (or everything in system mode).
DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['businesses', 'employees', 'roles', 'departments', 'holidays', 'settings', 'payroll_runs', 'audit_log', 'job_runs'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
    END LOOP;
END $$;

CREATE POLICY "tenant_isolation" ON "businesses" USING (jurisone_tenant_id() IS NULL OR "id" = jurisone_tenant_id())
    WITH CHECK (jurisone_tenant_id() IS NULL OR "id" = jurisone_tenant_id());
DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['employees', 'roles', 'departments', 'holidays', 'settings', 'payroll_runs', 'audit_log', 'job_runs'] LOOP
        EXECUTE format('CREATE POLICY "tenant_isolation" ON %I USING (jurisone_tenant_id() IS NULL OR business_id = jurisone_tenant_id()) '
                    || 'WITH CHECK (jurisone_tenant_id() IS NULL OR business_id = jurisone_tenant_id())', t);
    END LOOP;
END $$;

-- Rows that belong to an employee follow that employee's business (the subquery is itself filtered by employees' policy).
DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['users', 'punches', 'attendance_days', 'leave_requests', 'leave_ledger', 'regularization_requests', 'salary_structures',
                             'payslips', 'payroll_adjustments', 'declaration_items', 'tax_profiles', 'notifications'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY "tenant_isolation" ON %I USING (jurisone_tenant_id() IS NULL OR EXISTS (SELECT 1 FROM employees e WHERE e.id = %I.employee_id)) '
                    || 'WITH CHECK (jurisone_tenant_id() IS NULL OR EXISTS (SELECT 1 FROM employees e WHERE e.id = %I.employee_id))', t, t, t);
    END LOOP;
END $$;

-- Role children follow their role.
DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['role_permissions', 'role_assignable_roles'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY "tenant_isolation" ON %I USING (jurisone_tenant_id() IS NULL OR EXISTS (SELECT 1 FROM roles r WHERE r.id = %I.role_id)) '
                    || 'WITH CHECK (jurisone_tenant_id() IS NULL OR EXISTS (SELECT 1 FROM roles r WHERE r.id = %I.role_id))', t, t, t);
    END LOOP;
END $$;

-- ── the role business requests run as ───────────────────────────────────────
-- PostgreSQL superusers (like the docker-compose user) skip row-level security, so business requests switch to this
-- plain role (SET ROLE) and switch back (RESET ROLE) afterwards. Creating it needs CREATEROLE once.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'jurisone_tenant') THEN
        CREATE ROLE jurisone_tenant NOLOGIN;
    END IF;
END $$;
DO $$
BEGIN
    EXECUTE format('GRANT jurisone_tenant TO %I', current_user);
EXCEPTION WHEN duplicate_object OR invalid_grant_operation THEN NULL;  -- already a member / superuser
END $$;
GRANT USAGE ON SCHEMA public TO jurisone_tenant;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO jurisone_tenant;
GRANT EXECUTE ON FUNCTION jurisone_tenant_id() TO jurisone_tenant;
GRANT EXECUTE ON FUNCTION jurisone_email_in_use(TEXT, UUID) TO jurisone_tenant;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO jurisone_tenant;
