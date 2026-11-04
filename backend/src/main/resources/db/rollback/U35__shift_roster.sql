-- Reverses V35__shift_roster.sql (run with psql, then delete version 35 from flyway_schema_history).
-- Business-made shifts are removed (employees keep their employees.shift_id, which never points at one).
DELETE FROM "role_permissions" WHERE "permission_key" = 'shifts.manage';
UPDATE "role_templates" SET "permissions" = array_remove("permissions", 'shifts.manage');
DROP TABLE "shift_assignments";
DROP POLICY "shifts_write" ON "shifts";
DROP POLICY "shifts_read" ON "shifts";
ALTER TABLE "shifts" DISABLE ROW LEVEL SECURITY;
DELETE FROM "shifts" WHERE "business_id" IS NOT NULL;
DROP INDEX "shifts_business_code_key";
CREATE UNIQUE INDEX "shifts_code_key" ON "shifts"("code");
ALTER TABLE "shifts" DROP CONSTRAINT "shifts_grace_check";
ALTER TABLE "shifts" DROP CONSTRAINT "shifts_times_check";
ALTER TABLE "shifts" DROP COLUMN "created_at";
ALTER TABLE "shifts" DROP COLUMN "business_id";
