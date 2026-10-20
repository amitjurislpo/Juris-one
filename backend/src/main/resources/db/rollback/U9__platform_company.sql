-- Reverses V9__platform_company.sql (run with psql, then delete version 9 from flyway_schema_history).
-- Super admins go back to having no business first.
UPDATE "employees" e SET "business_id" = NULL FROM "users" u WHERE u."employee_id" = e."id" AND u."is_super_admin";
DROP INDEX IF EXISTS "businesses_one_platform_key";
ALTER TABLE "businesses" DROP COLUMN IF EXISTS "is_platform";
