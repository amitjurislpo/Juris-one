-- Stage 1 of encrypting employee names and designations (AES-256-GCM, "v1." format, see PiiCrypto): expand only.
-- The new columns are filled by the application (jurisone.security.encrypt-names.dual-write) and by the "encrypt-existing" command.
-- The plaintext columns stay exactly as they are and are still the ones that are read; nothing is dropped or made nullable here.
ALTER TABLE "employees" ADD COLUMN "full_name_enc" TEXT;
ALTER TABLE "employees" ADD COLUMN "first_name_enc" TEXT;
ALTER TABLE "employees" ADD COLUMN "last_name_enc" TEXT;
ALTER TABLE "employees" ADD COLUMN "designation_enc" TEXT;
