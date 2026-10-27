-- S22: employee onboarding wizard. HR creates the employee; the employee signs in and fills a six-step wizard
-- (personal, previous employment, accounts & statutory, family, nominations, attachments) and submits it.
-- Replaces the pre-hire candidate flow of V7 (onboardings / onboarding_documents are dropped; dev data is disposable).
-- Identity numbers (bank account, PAN, Aadhaar, UAN) are stored encrypted (PiiCrypto) with only the last four characters in clear.

DROP TABLE IF EXISTS "onboarding_documents";
DROP TABLE IF EXISTS "onboardings";
DELETE FROM "settings" WHERE "key" = 'onboarding.policy';

CREATE TABLE "employee_onboardings" (
    "id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id(),
    "employee_id" UUID NOT NULL,
    "status" TEXT NOT NULL DEFAULT 'NOT_STARTED',
    "current_step" INTEGER NOT NULL DEFAULT 1,
    "step_status" JSONB NOT NULL DEFAULT '{}'::jsonb,
    "submitted_at" TIMESTAMPTZ(6),
    "created_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "employee_onboardings_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "employee_onboardings_status_check" CHECK ("status" IN ('NOT_REQUIRED', 'NOT_STARTED', 'IN_PROGRESS', 'SUBMITTED', 'REOPENED')),
    CONSTRAINT "employee_onboardings_step_check" CHECK ("current_step" BETWEEN 1 AND 6)
);
CREATE UNIQUE INDEX "employee_onboardings_employee_id_key" ON "employee_onboardings"("employee_id");
CREATE INDEX "employee_onboardings_business_id_idx" ON "employee_onboardings"("business_id", "status");
ALTER TABLE "employee_onboardings" ADD CONSTRAINT "employee_onboardings_business_id_fkey" FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "employee_onboardings" ADD CONSTRAINT "employee_onboardings_employee_id_fkey" FOREIGN KEY ("employee_id") REFERENCES "employees"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- Every employee that exists today does not go through the wizard.
INSERT INTO "employee_onboardings" ("id", "business_id", "employee_id", "status")
SELECT gen_random_uuid(), "business_id", "id", 'NOT_REQUIRED' FROM "employees";

CREATE TABLE "onboarding_personal" (
    "onboarding_id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id(),
    "blood_group" TEXT,
    "personal_email" TEXT,
    "father_name" TEXT,
    "marital_status" TEXT,
    "marriage_date" DATE,
    "spouse_name" TEXT,
    "country_of_origin" TEXT,
    "nationality" TEXT,
    "is_international_employee" BOOLEAN,
    "is_physically_challenged" BOOLEAN,
    "disability_type" TEXT,
    "gender" TEXT,
    "date_of_birth" DATE,
    "height_cm" NUMERIC(5, 1),
    "weight_kg" NUMERIC(5, 1),
    "identification_mark" TEXT,
    "religion" TEXT,
    "caste" TEXT,
    "hobby" TEXT,
    CONSTRAINT "onboarding_personal_pkey" PRIMARY KEY ("onboarding_id")
);

CREATE TABLE "onboarding_addresses" (
    "id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id(),
    "onboarding_id" UUID NOT NULL,
    "kind" TEXT NOT NULL,
    "line1" TEXT, "line2" TEXT, "line3" TEXT, "city" TEXT, "district" TEXT, "state" TEXT, "pin" TEXT, "country" TEXT, "phone" TEXT,
    "is_same_as_permanent" BOOLEAN NOT NULL DEFAULT false,
    CONSTRAINT "onboarding_addresses_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "onboarding_addresses_kind_check" CHECK ("kind" IN ('PERMANENT', 'PRESENT')),
    CONSTRAINT "onboarding_addresses_unique" UNIQUE ("onboarding_id", "kind")
);

CREATE TABLE "onboarding_emergency_contacts" (
    "onboarding_id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id(),
    "name" TEXT, "mobile" TEXT, "relationship" TEXT,
    CONSTRAINT "onboarding_emergency_contacts_pkey" PRIMARY KEY ("onboarding_id")
);

CREATE TABLE "onboarding_education" (
    "id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id(),
    "onboarding_id" UUID NOT NULL,
    "position" INTEGER NOT NULL,
    "qualification" TEXT NOT NULL, "from_year" INTEGER NOT NULL, "to_year" INTEGER NOT NULL, "institute" TEXT NOT NULL, "grade_or_percentage" TEXT NOT NULL,
    CONSTRAINT "onboarding_education_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "onboarding_courses" (
    "id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id(),
    "onboarding_id" UUID NOT NULL,
    "position" INTEGER NOT NULL,
    "course_name" TEXT, "institute" TEXT, "from_year" INTEGER, "to_year" INTEGER,
    CONSTRAINT "onboarding_courses_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "onboarding_employments" (
    "id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id(),
    "onboarding_id" UUID NOT NULL,
    "position" INTEGER NOT NULL,
    "company_name" TEXT NOT NULL,
    "designation" TEXT,
    "last_drawn_salary_paise" BIGINT,
    "from_date" DATE NOT NULL,
    "to_date" DATE NOT NULL,
    "reason_for_leaving" TEXT,
    "company_address" TEXT,
    CONSTRAINT "onboarding_employments_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "onboarding_bank" (
    "onboarding_id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id(),
    "country" TEXT,
    "account_number_enc" TEXT,
    "account_number_last4" TEXT,
    "ifsc" TEXT, "bank_name" TEXT, "branch" TEXT, "branch_address" TEXT, "name_as_per_bank" TEXT, "account_type" TEXT,
    CONSTRAINT "onboarding_bank_pkey" PRIMARY KEY ("onboarding_id")
);

CREATE TABLE "onboarding_statutory" (
    "onboarding_id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id(),
    "previous_pf_number" TEXT,
    "uan_enc" TEXT, "uan_last4" TEXT,
    "pan_enc" TEXT, "pan_last4" TEXT, "name_per_pan" TEXT,
    "aadhaar_enc" TEXT, "aadhaar_last4" TEXT, "name_per_aadhaar" TEXT,
    "passport_number" TEXT, "name_per_passport" TEXT, "passport_expiry" DATE,
    CONSTRAINT "onboarding_statutory_pkey" PRIMARY KEY ("onboarding_id")
);

CREATE TABLE "onboarding_family_members" (
    "id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id(),
    "onboarding_id" UUID NOT NULL,
    "position" INTEGER NOT NULL,
    "name" TEXT NOT NULL, "relationship" TEXT NOT NULL, "date_of_birth" DATE NOT NULL, "gender" TEXT NOT NULL, "blood_group" TEXT NOT NULL, "nationality" TEXT NOT NULL,
    "is_minor" BOOLEAN NOT NULL DEFAULT false,
    "has_mental_illness" BOOLEAN NOT NULL DEFAULT false,
    "guardian_name" TEXT, "guardian_relationship" TEXT, "guardian_line1" TEXT, "guardian_line2" TEXT, "guardian_line3" TEXT,
    "guardian_city" TEXT, "guardian_state" TEXT, "guardian_country" TEXT, "guardian_pin" TEXT, "guardian_mobile" TEXT, "guardian_email" TEXT,
    CONSTRAINT "onboarding_family_members_pkey" PRIMARY KEY ("id")
);

CREATE TABLE "onboarding_nominations" (
    "id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id(),
    "onboarding_id" UUID NOT NULL,
    "scheme" TEXT NOT NULL,
    "position" INTEGER NOT NULL,
    "family_member_id" UUID NOT NULL,
    "percentage" INTEGER,
    CONSTRAINT "onboarding_nominations_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "onboarding_nominations_scheme_check" CHECK ("scheme" IN ('EPF', 'EPS', 'ESI', 'GRATUITY')),
    CONSTRAINT "onboarding_nominations_percentage_check" CHECK ("percentage" IS NULL OR "percentage" BETWEEN 1 AND 100)
);

CREATE TABLE "onboarding_attachments" (
    "id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id(),
    "onboarding_id" UUID NOT NULL,
    "slot_key" TEXT NOT NULL,
    "file_name" TEXT NOT NULL,
    "mime" TEXT NOT NULL,
    "size_bytes" BIGINT NOT NULL,
    "sha256" TEXT NOT NULL,
    "storage_path" TEXT NOT NULL,                -- relative to jurisone.storage-dir
    "uploaded_at" TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT "onboarding_attachments_pkey" PRIMARY KEY ("id")
);

ALTER TABLE "onboarding_nominations" ADD CONSTRAINT "onboarding_nominations_family_member_id_fkey" FOREIGN KEY ("family_member_id") REFERENCES "onboarding_family_members"("id") ON DELETE CASCADE ON UPDATE CASCADE;

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['onboarding_personal', 'onboarding_addresses', 'onboarding_emergency_contacts', 'onboarding_education', 'onboarding_courses',
                             'onboarding_employments', 'onboarding_bank', 'onboarding_statutory', 'onboarding_family_members', 'onboarding_nominations', 'onboarding_attachments'] LOOP
        EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY ("onboarding_id") REFERENCES "employee_onboardings"("id") ON DELETE CASCADE ON UPDATE CASCADE', t, t || '_onboarding_id_fkey');
        EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE', t, t || '_business_id_fkey');
        EXECUTE format('CREATE INDEX %I ON %I("onboarding_id")', t || '_onboarding_id_idx', t);
    END LOOP;
    FOREACH t IN ARRAY ARRAY['employee_onboardings', 'onboarding_personal', 'onboarding_addresses', 'onboarding_emergency_contacts', 'onboarding_education', 'onboarding_courses',
                             'onboarding_employments', 'onboarding_bank', 'onboarding_statutory', 'onboarding_family_members', 'onboarding_nominations', 'onboarding_attachments'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY "tenant_isolation" ON %I USING (jurisone_tenant_id() IS NULL OR business_id = jurisone_tenant_id()) '
                    || 'WITH CHECK (jurisone_tenant_id() IS NULL OR business_id = jurisone_tenant_id())', t);
        EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON %I TO jurisone_tenant', t);
    END LOOP;
END $$;
