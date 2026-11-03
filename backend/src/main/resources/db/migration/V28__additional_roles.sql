-- A person can hold more than one role. "employees.role_id" stays the main role (its key decides the owner / HR rules, the
-- assignable-roles list and the sidebar grouping); this table holds the ADDITIONAL roles, whose permissions are added to the
-- main role's. Business-owned, with row-level security like every business table.
CREATE TABLE "employee_additional_roles" (
    "employee_id" UUID        NOT NULL,
    "role_id"     UUID        NOT NULL,
    "business_id" UUID        NOT NULL DEFAULT jurisone_tenant_id(),
    "assigned_by" UUID,
    "assigned_at" TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT "employee_additional_roles_pkey" PRIMARY KEY ("employee_id", "role_id"),
    CONSTRAINT "employee_additional_roles_employee_fkey" FOREIGN KEY ("employee_id") REFERENCES "employees"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "employee_additional_roles_role_fkey" FOREIGN KEY ("role_id") REFERENCES "roles"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "employee_additional_roles_assigned_by_fkey" FOREIGN KEY ("assigned_by") REFERENCES "employees"("id") ON DELETE SET NULL ON UPDATE CASCADE
);
CREATE INDEX "employee_additional_roles_role_idx" ON "employee_additional_roles"("role_id");
CREATE INDEX "employee_additional_roles_business_idx" ON "employee_additional_roles"("business_id");

-- Written outside a business (the seed, a script) the row takes the business of its employee.
CREATE FUNCTION jurisone_additional_role_business() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW."business_id" IS NULL THEN
        NEW."business_id" := (SELECT e."business_id" FROM employees e WHERE e."id" = NEW."employee_id");
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER "employee_additional_roles_business" BEFORE INSERT ON "employee_additional_roles"
    FOR EACH ROW EXECUTE FUNCTION jurisone_additional_role_business();

ALTER TABLE "employee_additional_roles" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "employee_additional_roles" FORCE ROW LEVEL SECURITY;
CREATE POLICY "tenant_isolation" ON "employee_additional_roles" USING (jurisone_tenant_id() IS NULL OR business_id = jurisone_tenant_id())
    WITH CHECK (jurisone_tenant_id() IS NULL OR business_id = jurisone_tenant_id());
GRANT SELECT, INSERT, UPDATE, DELETE ON "employee_additional_roles" TO jurisone_tenant;

-- Does this employee hold the permission through the main role or any additional role?
CREATE FUNCTION jurisone_employee_has_permission(p_employee UUID, p_permission TEXT) RETURNS BOOLEAN LANGUAGE sql STABLE AS $$
    SELECT EXISTS (
        SELECT 1 FROM role_permissions rp
        WHERE rp.permission_key = p_permission
          AND (rp.role_id = (SELECT e.role_id FROM employees e WHERE e.id = p_employee)
               OR rp.role_id IN (SELECT a.role_id FROM employee_additional_roles a WHERE a.employee_id = p_employee)))
$$;
GRANT EXECUTE ON FUNCTION jurisone_employee_has_permission(UUID, TEXT) TO jurisone_tenant;

-- The "JurisTrack manager" role template (optional; offered only to businesses that have the JurisTrack module).
INSERT INTO "role_templates" ("key", "name", "locked", "mandatory", "permissions", "assignable", "sort_order") VALUES
('juristrack_manager', 'JurisTrack manager', false, false, ARRAY['self','dir','juristrack.manager'], ARRAY[]::TEXT[], 7);

-- Every business that already has the JurisTrack module (through a plan or a grant that is not cancelled or revoked) gets the role now;
-- from here on the application adds it when a business gets the module.
DO $$
DECLARE
    b RECORD;
    new_role UUID;
BEGIN
    FOR b IN SELECT bs."id" FROM "businesses" bs WHERE NOT bs."is_platform" AND (
            EXISTS (SELECT 1 FROM "business_modules" m WHERE m."business_id" = bs."id" AND m."module" = 'JURIS_TRACK' AND m."revoked_at" IS NULL)
         OR EXISTS (SELECT 1 FROM "business_subscriptions" s JOIN "subscription_plans" p ON p."id" = s."plan_id"
                    WHERE s."business_id" = bs."id" AND s."status" <> 'CANCELLED' AND 'JURIS_TRACK' = ANY (p."modules")))
    LOOP
        IF NOT EXISTS (SELECT 1 FROM "roles" WHERE "business_id" = b."id" AND "key" = 'juristrack_manager') THEN
            new_role := gen_random_uuid();
            INSERT INTO "roles" ("id", "key", "name", "is_builtin", "is_locked", "business_id") VALUES (new_role, 'juristrack_manager', 'JurisTrack manager', true, false, b."id");
            INSERT INTO "role_permissions" ("role_id", "permission_key") VALUES (new_role, 'self'), (new_role, 'dir'), (new_role, 'juristrack.manager');
        END IF;
    END LOOP;
END $$;
