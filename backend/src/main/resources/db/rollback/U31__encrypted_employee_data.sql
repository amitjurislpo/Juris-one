-- Reverses V31__encrypted_employee_data.sql (run with psql, then delete version 31 from flyway_schema_history).
-- Refuses while any value exists only in encrypted form: SQL cannot decrypt, so first run the application's decrypt path
-- (there is none yet: restore the pre-backfill dump instead) - see the open questions in the encryption notes.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM "salary_structures" WHERE "basic" IS NULL)
       OR EXISTS (SELECT 1 FROM "payslips" WHERE "snapshot" IS NULL)
       OR EXISTS (SELECT 1 FROM "employees" WHERE "date_of_birth_enc" IS NOT NULL AND "date_of_birth" IS NULL)
       OR EXISTS (SELECT 1 FROM "onboarding_personal" WHERE "date_of_birth_enc" IS NOT NULL AND "date_of_birth" IS NULL) THEN
        RAISE EXCEPTION 'Encrypted-only values exist; restore from the backup taken before the backfill instead';
    END IF;
END $$;
ALTER TABLE "payslips" DROP CONSTRAINT "payslips_snapshot_present";
ALTER TABLE "payslips" ALTER COLUMN "snapshot" SET NOT NULL;
ALTER TABLE "payslips" DROP COLUMN "snapshot_enc";
ALTER TABLE "salary_structures" DROP CONSTRAINT "salary_structures_amounts_present";
ALTER TABLE "salary_structures" ALTER COLUMN "basic" SET NOT NULL, ALTER COLUMN "hra" SET NOT NULL, ALTER COLUMN "lta" SET NOT NULL, ALTER COLUMN "special" SET NOT NULL;
ALTER TABLE "salary_structures" DROP COLUMN "amounts_enc";
ALTER TABLE "onboarding_personal" DROP COLUMN "date_of_birth_enc";
ALTER TABLE "employees" DROP COLUMN "date_of_birth_enc";
