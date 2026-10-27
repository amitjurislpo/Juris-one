-- S23: HR approval of the employee onboarding. A submitted onboarding waits for HR: approved (the portal opens) or sent back
-- (CHANGES_REQUESTED) with a comment and a flag per section that needs work. REOPENED of V22 is replaced by CHANGES_REQUESTED.

ALTER TABLE "employee_onboardings" DROP CONSTRAINT "employee_onboardings_status_check";
UPDATE "employee_onboardings" SET "status" = 'CHANGES_REQUESTED' WHERE "status" = 'REOPENED';
ALTER TABLE "employee_onboardings" ADD CONSTRAINT "employee_onboardings_status_check"
    CHECK ("status" IN ('NOT_REQUIRED', 'NOT_STARTED', 'IN_PROGRESS', 'SUBMITTED', 'CHANGES_REQUESTED', 'APPROVED'));

ALTER TABLE "employee_onboardings" ADD COLUMN "review_comment" TEXT;
ALTER TABLE "employee_onboardings" ADD COLUMN "reviewed_by" UUID;
ALTER TABLE "employee_onboardings" ADD COLUMN "reviewed_at" TIMESTAMPTZ(6);
ALTER TABLE "employee_onboardings" ADD COLUMN "approved_at" TIMESTAMPTZ(6);
ALTER TABLE "employee_onboardings" ADD CONSTRAINT "employee_onboardings_reviewed_by_fkey" FOREIGN KEY ("reviewed_by") REFERENCES "employees"("id") ON DELETE SET NULL ON UPDATE CASCADE;

CREATE TABLE "onboarding_review_flags" (
    "id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id(),
    "onboarding_id" UUID NOT NULL,
    "section" TEXT NOT NULL,
    "comment" TEXT NOT NULL,
    "created_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "onboarding_review_flags_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "onboarding_review_flags_section_check" CHECK ("section" IN ('PERSONAL', 'ADDRESS', 'QUALIFICATIONS', 'EMPLOYMENT', 'ACCOUNTS', 'FAMILY', 'NOMINATIONS', 'ATTACHMENTS'))
);
CREATE INDEX "onboarding_review_flags_onboarding_id_idx" ON "onboarding_review_flags"("onboarding_id");
ALTER TABLE "onboarding_review_flags" ADD CONSTRAINT "onboarding_review_flags_onboarding_id_fkey" FOREIGN KEY ("onboarding_id") REFERENCES "employee_onboardings"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "onboarding_review_flags" ADD CONSTRAINT "onboarding_review_flags_business_id_fkey" FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE;

ALTER TABLE "onboarding_review_flags" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "onboarding_review_flags" FORCE ROW LEVEL SECURITY;
CREATE POLICY "tenant_isolation" ON "onboarding_review_flags" USING (jurisone_tenant_id() IS NULL OR business_id = jurisone_tenant_id())
    WITH CHECK (jurisone_tenant_id() IS NULL OR business_id = jurisone_tenant_id());
GRANT SELECT, INSERT, UPDATE, DELETE ON "onboarding_review_flags" TO jurisone_tenant;
