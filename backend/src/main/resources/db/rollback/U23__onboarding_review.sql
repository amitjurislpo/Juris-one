-- Reverses V23__onboarding_review.sql (run with psql, then delete version 23 from flyway_schema_history).
-- Rows in CHANGES_REQUESTED go back to REOPENED and APPROVED rows to SUBMITTED; the review comments and flags are lost.
DROP TABLE IF EXISTS "onboarding_review_flags";
ALTER TABLE "employee_onboardings" DROP CONSTRAINT IF EXISTS "employee_onboardings_reviewed_by_fkey";
ALTER TABLE "employee_onboardings" DROP COLUMN IF EXISTS "approved_at";
ALTER TABLE "employee_onboardings" DROP COLUMN IF EXISTS "reviewed_at";
ALTER TABLE "employee_onboardings" DROP COLUMN IF EXISTS "reviewed_by";
ALTER TABLE "employee_onboardings" DROP COLUMN IF EXISTS "review_comment";
ALTER TABLE "employee_onboardings" DROP CONSTRAINT "employee_onboardings_status_check";
UPDATE "employee_onboardings" SET "status" = 'REOPENED' WHERE "status" = 'CHANGES_REQUESTED';
UPDATE "employee_onboardings" SET "status" = 'SUBMITTED' WHERE "status" = 'APPROVED';
ALTER TABLE "employee_onboardings" ADD CONSTRAINT "employee_onboardings_status_check"
    CHECK ("status" IN ('NOT_REQUIRED', 'NOT_STARTED', 'IN_PROGRESS', 'SUBMITTED', 'REOPENED'));
