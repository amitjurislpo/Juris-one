-- Reverses V38__employee_email_encrypted_blind_index.sql (run with psql, then delete version 38 from flyway_schema_history).
-- Set jurisone.security.encrypt-email.lookup-by-blind-index=false and ...dual-write=false first (and restart), so nothing uses the columns.
-- Safe at any time in this stage: the plaintext email column is still complete, so only the encrypted copy and the index are lost.
DROP FUNCTION IF EXISTS jurisone_email_blind_index_in_use(TEXT, UUID);
DROP INDEX IF EXISTS "employees_email_blind_index_key";
ALTER TABLE "employees" DROP CONSTRAINT IF EXISTS "employees_email_blind_index_format";
ALTER TABLE "employees" DROP COLUMN "email_blind_index";
ALTER TABLE "employees" DROP COLUMN "email_enc";
