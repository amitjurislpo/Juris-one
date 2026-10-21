-- S7: onboarding by link. HR invites a candidate on their personal email; the candidate fills a form and uploads
-- documents; HR verifies, then adds the employee (portal access off until HR turns it on).

CREATE TABLE "onboardings" (
    "id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id(),
    "candidate_name" TEXT NOT NULL,
    "personal_email" TEXT NOT NULL,
    -- the planned job (HR can change it until the employee is added)
    "designation" TEXT,
    "department_id" UUID,
    "role_id" UUID,
    "manager_id" UUID,
    "joining_date" DATE,
    "location" TEXT NOT NULL DEFAULT 'IN_IST',
    "required_docs" TEXT[] NOT NULL DEFAULT ARRAY['ID_PROOF', 'ADDRESS_PROOF', 'PAN', 'AADHAAR', 'SALARY_SLIP']::TEXT[],
    -- the one-time link (only its SHA-256 is stored)
    "token_hash" TEXT,
    "token_expires_at" TIMESTAMPTZ(6),
    "status" TEXT NOT NULL DEFAULT 'INVITED',
    "form_enc" TEXT,                          -- the candidate's answers, encrypted JSON (PII)
    "hr_comment" TEXT,                        -- what to change, when sent back
    "submitted_at" TIMESTAMPTZ(6),
    "verified_by" UUID,
    "verified_at" TIMESTAMPTZ(6),
    "employee_id" UUID,                       -- once added
    "created_by" UUID,
    "created_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "onboardings_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "onboardings_status_check" CHECK ("status" IN ('INVITED', 'IN_PROGRESS', 'SUBMITTED', 'CHANGES_REQUESTED', 'VERIFIED', 'ADDED', 'CANCELLED'))
);
CREATE UNIQUE INDEX "onboardings_token_hash_key" ON "onboardings"("token_hash");
CREATE INDEX "onboardings_business_id_idx" ON "onboardings"("business_id", "status");
ALTER TABLE "onboardings" ADD CONSTRAINT "onboardings_business_id_fkey" FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "onboardings" ADD CONSTRAINT "onboardings_employee_id_fkey" FOREIGN KEY ("employee_id") REFERENCES "employees"("id") ON DELETE SET NULL ON UPDATE CASCADE;

CREATE TABLE "onboarding_documents" (
    "id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id(),
    "onboarding_id" UUID NOT NULL,
    "kind" TEXT NOT NULL,
    "file_path" TEXT NOT NULL,
    "file_name" TEXT NOT NULL,
    "mime" TEXT NOT NULL,
    "size_bytes" INTEGER NOT NULL,
    "sha256" TEXT NOT NULL,
    "status" TEXT NOT NULL DEFAULT 'PENDING',
    "comment" TEXT,
    "uploaded_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "verified_by" UUID,
    "verified_at" TIMESTAMPTZ(6),
    "employee_id" UUID,                       -- set when the employee is added: the document is on their record

    CONSTRAINT "onboarding_documents_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "onboarding_documents_status_check" CHECK ("status" IN ('PENDING', 'VERIFIED', 'REJECTED')),
    CONSTRAINT "onboarding_documents_kind_check" CHECK ("kind" IN ('ID_PROOF', 'ADDRESS_PROOF', 'PAN', 'AADHAAR', 'SALARY_SLIP', 'EDUCATION', 'RELIEVING_LETTER', 'PHOTO', 'OTHER'))
);
CREATE INDEX "onboarding_documents_onboarding_id_idx" ON "onboarding_documents"("onboarding_id");
CREATE INDEX "onboarding_documents_employee_id_idx" ON "onboarding_documents"("employee_id");
ALTER TABLE "onboarding_documents" ADD CONSTRAINT "onboarding_documents_onboarding_id_fkey" FOREIGN KEY ("onboarding_id") REFERENCES "onboardings"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "onboarding_documents" ADD CONSTRAINT "onboarding_documents_business_id_fkey" FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "onboarding_documents" ADD CONSTRAINT "onboarding_documents_employee_id_fkey" FOREIGN KEY ("employee_id") REFERENCES "employees"("id") ON DELETE SET NULL ON UPDATE CASCADE;

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['onboardings', 'onboarding_documents'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY "tenant_isolation" ON %I USING (jurisone_tenant_id() IS NULL OR business_id = jurisone_tenant_id()) '
                    || 'WITH CHECK (jurisone_tenant_id() IS NULL OR business_id = jurisone_tenant_id())', t);
    END LOOP;
END $$;
GRANT SELECT, INSERT, UPDATE, DELETE ON "onboardings", "onboarding_documents" TO jurisone_tenant;
