-- S15: HR letters. Editable per-business templates with {{merge fields}}, and the immutable record of every letter issued
-- (rendered text, SHA-256, PDF, who issued it and when) with a per-business reference number HRL/<FY>/<nnnn>.
-- Written to be re-runnable (IF NOT EXISTS / DROP ... IF EXISTS / ON CONFLICT DO NOTHING).

CREATE TABLE IF NOT EXISTS "letter_templates" (
    "id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id(),
    "type" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "subject" TEXT NOT NULL,
    "body" TEXT NOT NULL,
    "needs_salary" BOOLEAN NOT NULL DEFAULT false,   -- derived from the text: it uses a salary field, so only struct.manage may issue it
    "active" BOOLEAN NOT NULL DEFAULT true,
    "version" INTEGER NOT NULL DEFAULT 1,
    "created_by" UUID,
    "created_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_by" UUID,
    "updated_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "letter_templates_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "letter_templates_type_check" CHECK ("type" IN ('OFFER', 'APPOINTMENT', 'EXPERIENCE', 'RELIEVING', 'EMPLOYMENT_VERIFICATION')),
    CONSTRAINT "letter_templates_business_id_fkey" FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS "letter_templates_business_name_key" ON "letter_templates"("business_id", "name");
CREATE INDEX IF NOT EXISTS "letter_templates_business_type_idx" ON "letter_templates"("business_id", "type");

-- last reference number used, per business and financial year
CREATE TABLE IF NOT EXISTS "letter_sequences" (
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id(),
    "fy" TEXT NOT NULL,
    "last_no" INTEGER NOT NULL DEFAULT 0,

    CONSTRAINT "letter_sequences_pkey" PRIMARY KEY ("business_id", "fy"),
    CONSTRAINT "letter_sequences_business_id_fkey" FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE
);

CREATE TABLE IF NOT EXISTS "issued_letters" (
    "id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id(),
    "reference_no" TEXT NOT NULL,             -- HRL/2026-27/0001
    "template_id" UUID,
    "template_name" TEXT NOT NULL,
    "template_version" INTEGER NOT NULL,
    "letter_type" TEXT NOT NULL,
    "needs_salary" BOOLEAN NOT NULL DEFAULT false,
    "employee_id" UUID NOT NULL,
    "employee_code" TEXT NOT NULL,
    "employee_name" TEXT NOT NULL,
    "subject" TEXT NOT NULL,
    "body" TEXT NOT NULL,                     -- the rendered text, exactly as issued
    "body_sha256" TEXT NOT NULL,
    "pdf" BYTEA NOT NULL,
    "pdf_sha256" TEXT NOT NULL,
    "issued_by" UUID,
    "issued_by_name" TEXT NOT NULL,
    "issued_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "issued_letters_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "issued_letters_business_id_fkey" FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE RESTRICT ON UPDATE RESTRICT,
    CONSTRAINT "issued_letters_employee_id_fkey" FOREIGN KEY ("employee_id") REFERENCES "employees"("id") ON DELETE RESTRICT ON UPDATE RESTRICT
);
CREATE UNIQUE INDEX IF NOT EXISTS "issued_letters_business_reference_key" ON "issued_letters"("business_id", "reference_no");
CREATE INDEX IF NOT EXISTS "issued_letters_employee_id_idx" ON "issued_letters"("employee_id", "issued_at");

-- issued letters are never changed or removed (jurisone_append_only comes from V5)
DROP TRIGGER IF EXISTS "issued_letters_append_only" ON "issued_letters";
CREATE TRIGGER "issued_letters_append_only" BEFORE UPDATE OR DELETE ON "issued_letters" FOR EACH ROW EXECUTE FUNCTION jurisone_append_only();
DROP TRIGGER IF EXISTS "issued_letters_no_truncate" ON "issued_letters";
CREATE TRIGGER "issued_letters_no_truncate" BEFORE TRUNCATE ON "issued_letters" FOR EACH STATEMENT EXECUTE FUNCTION jurisone_append_only();

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['letter_templates', 'letter_sequences', 'issued_letters'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS "tenant_isolation" ON %I', t);
        EXECUTE format('CREATE POLICY "tenant_isolation" ON %I USING (jurisone_tenant_id() IS NULL OR business_id = jurisone_tenant_id()) '
                    || 'WITH CHECK (jurisone_tenant_id() IS NULL OR business_id = jurisone_tenant_id())', t);
    END LOOP;
END $$;
GRANT SELECT, INSERT, UPDATE ON "letter_templates" TO jurisone_tenant;
GRANT SELECT, INSERT, UPDATE ON "letter_sequences" TO jurisone_tenant;
GRANT SELECT, INSERT ON "issued_letters" TO jurisone_tenant;

-- The default templates of a business, one per letter type. Safe to call again: existing names are left alone, so edits
-- HR made are kept. The offer and appointment letters carry the CTC, so they are flagged needs_salary (struct.manage only).
CREATE OR REPLACE FUNCTION jurisone_seed_letter_templates(p_business UUID) RETURNS void LANGUAGE plpgsql AS $fn$
DECLARE
    sign TEXT := E'Yours sincerely,\nFor {{business.name}}\n\nAuthorised signatory';
BEGIN
    INSERT INTO letter_templates (id, business_id, type, name, subject, body, needs_salary) VALUES
    (gen_random_uuid(), p_business, 'OFFER', 'Offer letter', 'Offer of employment: {{designation}}',
     E'Dear {{employee.name}},\n\nWe are pleased to offer you the position of {{designation}} in the {{department}} department at {{business.name}}, with effect from {{joiningDate}}.\n\n'
     || E'Your annual cost to company (CTC) will be {{ctc}} (Rupees {{ctcInWords}} only), as set out in your salary structure. This offer is subject to satisfactory verification of your documents and references.\n\n'
     || E'Please confirm your acceptance by signing and returning a copy of this letter.\n\nWe look forward to welcoming you.\n\n' || sign, true),
    (gen_random_uuid(), p_business, 'APPOINTMENT', 'Appointment letter', 'Letter of appointment: {{designation}}',
     E'Dear {{employee.name}},\n\nWith reference to your acceptance of our offer, we are pleased to appoint you as {{designation}} in the {{department}} department of {{business.name}} with effect from {{joiningDate}}. Your employee ID is {{employee.code}}.\n\n'
     || E'Your annual cost to company (CTC) is {{ctc}} (Rupees {{ctcInWords}} only). Your appointment is governed by the company policies in force from time to time, which you will be asked to read and accept on JurisOne.\n\n'
     || E'We wish you a long and successful association with us.\n\n' || sign, true),
    (gen_random_uuid(), p_business, 'EXPERIENCE', 'Experience letter', 'Experience certificate',
     E'TO WHOMSOEVER IT MAY CONCERN\n\nThis is to certify that {{employee.name}} (employee ID {{employee.code}}) was employed with {{business.name}} as {{designation}} in the {{department}} department from {{joiningDate}} to {{lastWorkingDate}}.\n\n'
     || E'During this period we found the conduct and performance of {{employee.name}} satisfactory. We wish them every success in the future.\n\n' || sign, false),
    (gen_random_uuid(), p_business, 'RELIEVING', 'Relieving letter', 'Relieving letter',
     E'Dear {{employee.name}},\n\nThis is to confirm that your resignation from the position of {{designation}} at {{business.name}} has been accepted and that you stand relieved from your duties at the close of working hours on {{lastWorkingDate}}.\n\n'
     || E'You joined us on {{joiningDate}}. We thank you for your contribution and wish you all the best.\n\n' || sign, false),
    (gen_random_uuid(), p_business, 'EMPLOYMENT_VERIFICATION', 'Employment verification letter', 'Employment verification',
     E'TO WHOMSOEVER IT MAY CONCERN\n\nThis is to confirm that {{employee.name}} (employee ID {{employee.code}}) is employed with {{business.name}} as {{designation}} in the {{department}} department, and has been with us since {{joiningDate}}.\n\n'
     || E'This letter is issued at the employee''s request, on {{today}}, for the purpose of verifying employment. It does not state any compensation.\n\n' || sign, false)
    ON CONFLICT (business_id, name) DO NOTHING;
END $fn$;

SELECT jurisone_seed_letter_templates("id") FROM "businesses";

-- the new permission: owners and HR managers of existing businesses get it
INSERT INTO "role_permissions" ("role_id", "permission_key")
SELECT "id", 'letters.manage' FROM "roles" WHERE "business_id" IS NOT NULL AND "key" IN ('owner', 'hrm')
ON CONFLICT DO NOTHING;
