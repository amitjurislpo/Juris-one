-- S5: company policies, immutable published versions and the legal acknowledgement record.

-- A policy and its working draft. Publishing copies the draft into policy_versions (version + 1).
CREATE TABLE "policies" (
    "id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id(),
    "title" TEXT NOT NULL,
    "body" TEXT NOT NULL DEFAULT '',
    "file_path" TEXT,                        -- attached PDF (files are never overwritten: a new upload gets a new path)
    "file_name" TEXT,
    "file_sha256" TEXT,
    "requires_ack" BOOLEAN NOT NULL DEFAULT true,
    "status" TEXT NOT NULL DEFAULT 'DRAFT',
    "version" INTEGER NOT NULL DEFAULT 0,    -- latest published version (0 = never published)
    "created_by" UUID,
    "created_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "published_at" TIMESTAMPTZ(6),
    "archived_at" TIMESTAMPTZ(6),

    CONSTRAINT "policies_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "policies_status_check" CHECK ("status" IN ('DRAFT', 'PUBLISHED', 'ARCHIVED'))
);
CREATE INDEX "policies_business_id_idx" ON "policies"("business_id");
ALTER TABLE "policies" ADD CONSTRAINT "policies_business_id_fkey" FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- What was published, exactly as published. Never updated or deleted (trigger below).
CREATE TABLE "policy_versions" (
    "id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id(),
    "policy_id" UUID NOT NULL,
    "version" INTEGER NOT NULL,
    "title" TEXT NOT NULL,
    "body" TEXT NOT NULL,
    "body_sha256" TEXT NOT NULL,
    "file_path" TEXT,
    "file_name" TEXT,
    "file_sha256" TEXT,
    "requires_ack" BOOLEAN NOT NULL,
    "published_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "published_by" UUID,

    CONSTRAINT "policy_versions_pkey" PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX "policy_versions_policy_id_version_key" ON "policy_versions"("policy_id", "version");
CREATE INDEX "policy_versions_business_id_idx" ON "policy_versions"("business_id");
ALTER TABLE "policy_versions" ADD CONSTRAINT "policy_versions_policy_id_fkey" FOREIGN KEY ("policy_id") REFERENCES "policies"("id") ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE "policy_versions" ADD CONSTRAINT "policy_versions_business_id_fkey" FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE RESTRICT ON UPDATE RESTRICT;

-- The legal record: who accepted which version, with a snapshot of both. Append-only (trigger below).
CREATE TABLE "policy_acknowledgements" (
    "id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id(),
    "policy_id" UUID NOT NULL,
    "policy_version_id" UUID NOT NULL,
    "version" INTEGER NOT NULL,
    "policy_title" TEXT NOT NULL,
    "body_sha256" TEXT NOT NULL,
    "file_sha256" TEXT,
    "employee_id" UUID NOT NULL,
    "employee_code" TEXT NOT NULL,
    "employee_name" TEXT NOT NULL,
    "employee_email" TEXT NOT NULL,
    "acknowledged_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "ip" TEXT,
    "user_agent" TEXT,

    CONSTRAINT "policy_acknowledgements_pkey" PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX "policy_acknowledgements_version_employee_key" ON "policy_acknowledgements"("policy_version_id", "employee_id");
CREATE INDEX "policy_acknowledgements_business_id_idx" ON "policy_acknowledgements"("business_id");
CREATE INDEX "policy_acknowledgements_employee_id_idx" ON "policy_acknowledgements"("employee_id");
ALTER TABLE "policy_acknowledgements" ADD CONSTRAINT "policy_acknowledgements_version_fkey" FOREIGN KEY ("policy_version_id") REFERENCES "policy_versions"("id") ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE "policy_acknowledgements" ADD CONSTRAINT "policy_acknowledgements_employee_id_fkey" FOREIGN KEY ("employee_id") REFERENCES "employees"("id") ON DELETE RESTRICT ON UPDATE RESTRICT;
ALTER TABLE "policy_acknowledgements" ADD CONSTRAINT "policy_acknowledgements_business_id_fkey" FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE RESTRICT ON UPDATE RESTRICT;

CREATE FUNCTION jurisone_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% rows are a permanent record and can''t be changed or deleted', TG_TABLE_NAME USING ERRCODE = 'insufficient_privilege';
END $$;
CREATE TRIGGER "policy_versions_append_only" BEFORE UPDATE OR DELETE ON "policy_versions" FOR EACH ROW EXECUTE FUNCTION jurisone_append_only();
CREATE TRIGGER "policy_acknowledgements_append_only" BEFORE UPDATE OR DELETE ON "policy_acknowledgements" FOR EACH ROW EXECUTE FUNCTION jurisone_append_only();
CREATE TRIGGER "policy_versions_no_truncate" BEFORE TRUNCATE ON "policy_versions" FOR EACH STATEMENT EXECUTE FUNCTION jurisone_append_only();
CREATE TRIGGER "policy_acknowledgements_no_truncate" BEFORE TRUNCATE ON "policy_acknowledgements" FOR EACH STATEMENT EXECUTE FUNCTION jurisone_append_only();

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['policies', 'policy_versions', 'policy_acknowledgements'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY "tenant_isolation" ON %I USING (jurisone_tenant_id() IS NULL OR business_id = jurisone_tenant_id()) '
                    || 'WITH CHECK (jurisone_tenant_id() IS NULL OR business_id = jurisone_tenant_id())', t);
    END LOOP;
END $$;
GRANT SELECT, INSERT, UPDATE, DELETE ON "policies" TO jurisone_tenant;
GRANT SELECT, INSERT ON "policy_versions", "policy_acknowledgements" TO jurisone_tenant;

-- the new permission: owners and HR managers of existing businesses get it
INSERT INTO "role_permissions" ("role_id", "permission_key")
SELECT "id", 'policies.manage' FROM "roles" WHERE "business_id" IS NOT NULL AND "key" IN ('owner', 'hrm')
ON CONFLICT DO NOTHING;
