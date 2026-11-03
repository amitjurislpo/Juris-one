-- S29: Add employee + onboarding flow. New optional employee attributes (employment type, payment type, gender), a per-business
-- employee-code series setting (Auto or Manual, number of digits), and who fills an onboarding (the employee or HR).

ALTER TABLE "employees" ADD COLUMN "employment_type" TEXT;
ALTER TABLE "employees" ADD COLUMN "payment_type" TEXT;
ALTER TABLE "employees" ADD COLUMN "gender" TEXT;
ALTER TABLE "employees" ADD CONSTRAINT "employees_employment_type_check"
    CHECK ("employment_type" IS NULL OR "employment_type" IN ('PROBATION', 'PERMANENT', 'CONTRACT', 'INTERN', 'CONSULTANT', 'PART_TIME', 'FIXED_TERM'));
ALTER TABLE "employees" ADD CONSTRAINT "employees_payment_type_check"
    CHECK ("payment_type" IS NULL OR "payment_type" IN ('BANK_TRANSFER', 'CHEQUE', 'CASH'));
ALTER TABLE "employees" ADD CONSTRAINT "employees_gender_check"
    CHECK ("gender" IS NULL OR "gender" IN ('FEMALE', 'MALE', 'OTHER'));

ALTER TABLE "businesses" ADD COLUMN "code_series_mode" TEXT NOT NULL DEFAULT 'AUTO';
ALTER TABLE "businesses" ADD COLUMN "code_digits" INTEGER NOT NULL DEFAULT 2;
ALTER TABLE "businesses" ADD CONSTRAINT "businesses_code_series_mode_check" CHECK ("code_series_mode" IN ('AUTO', 'MANUAL'));
ALTER TABLE "businesses" ADD CONSTRAINT "businesses_code_digits_check" CHECK ("code_digits" BETWEEN 2 AND 6);

ALTER TABLE "employee_onboardings" ADD COLUMN "filled_by" TEXT NOT NULL DEFAULT 'EMPLOYEE';
ALTER TABLE "employee_onboardings" ADD COLUMN "invited_at" TIMESTAMPTZ(6);
ALTER TABLE "employee_onboardings" ADD CONSTRAINT "employee_onboardings_filled_by_check" CHECK ("filled_by" IN ('EMPLOYEE', 'HR'));
