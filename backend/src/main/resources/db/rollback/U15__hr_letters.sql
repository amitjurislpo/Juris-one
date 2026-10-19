-- Reverses V15__hr_letters.sql (run with psql, then delete version 15 from flyway_schema_history).
-- WARNING: issued letters are permanent records; export them first. The triggers are dropped so the tables can go.
DELETE FROM "role_permissions" WHERE "permission_key" = 'letters.manage';
DROP FUNCTION IF EXISTS jurisone_seed_letter_templates(UUID);
DROP TRIGGER IF EXISTS "issued_letters_append_only" ON "issued_letters";
DROP TRIGGER IF EXISTS "issued_letters_no_truncate" ON "issued_letters";
DROP TABLE IF EXISTS "issued_letters";
DROP TABLE IF EXISTS "letter_sequences";
DROP TABLE IF EXISTS "letter_templates";
