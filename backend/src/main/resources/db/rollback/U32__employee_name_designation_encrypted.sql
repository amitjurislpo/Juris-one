-- Reverses V32__employee_name_designation_encrypted.sql (run with psql, then delete version 32 from flyway_schema_history).
-- Safe at any time in stage 1: the plaintext columns are still complete, so only the encrypted copies are lost.
ALTER TABLE "employees" DROP COLUMN "designation_enc";
ALTER TABLE "employees" DROP COLUMN "last_name_enc";
ALTER TABLE "employees" DROP COLUMN "first_name_enc";
ALTER TABLE "employees" DROP COLUMN "full_name_enc";
