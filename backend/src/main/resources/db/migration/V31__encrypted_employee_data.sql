-- Employee data moves from plaintext to encrypted columns (AES-256-GCM, "v1." format, see PiiCrypto).
-- Additive: the old plaintext columns stay (now nullable) so existing rows keep working; the "encrypt-existing" command fills the
-- new columns and clears the old ones, the application writes only the new ones.

-- date of birth (the employee, and the onboarding answers)
ALTER TABLE "employees" ADD COLUMN "date_of_birth_enc" TEXT;
ALTER TABLE "onboarding_personal" ADD COLUMN "date_of_birth_enc" TEXT;

-- salary structure: the four monthly amounts (paise) as one encrypted value
ALTER TABLE "salary_structures" ADD COLUMN "amounts_enc" TEXT;
ALTER TABLE "salary_structures" ALTER COLUMN "basic" DROP NOT NULL, ALTER COLUMN "hra" DROP NOT NULL, ALTER COLUMN "lta" DROP NOT NULL, ALTER COLUMN "special" DROP NOT NULL;
ALTER TABLE "salary_structures" ADD CONSTRAINT "salary_structures_amounts_present"
    CHECK ("amounts_enc" IS NOT NULL OR ("basic" IS NOT NULL AND "hra" IS NOT NULL AND "lta" IS NOT NULL AND "special" IS NOT NULL));

-- payslip snapshot (JSON text)
ALTER TABLE "payslips" ADD COLUMN "snapshot_enc" TEXT;
ALTER TABLE "payslips" ALTER COLUMN "snapshot" DROP NOT NULL;
ALTER TABLE "payslips" ADD CONSTRAINT "payslips_snapshot_present" CHECK ("snapshot" IS NOT NULL OR "snapshot_enc" IS NOT NULL);
