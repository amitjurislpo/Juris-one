-- Reverses V40__attendance_info.sql (run with psql, then delete version 40 from flyway_schema_history).
-- Lost on purpose: every access card number and every per-employee punch mode. A business set to MACHINE / PER_EMPLOYEE in
-- attendance.policy should be set back to MANUAL first (the setting itself is only JSON and stays in the settings table, unused).
DROP INDEX IF EXISTS "employees_business_access_card_number_key";
ALTER TABLE "employees" DROP CONSTRAINT IF EXISTS "employees_punch_mode_check";
ALTER TABLE "employees" DROP CONSTRAINT IF EXISTS "employees_access_card_number_format";
ALTER TABLE "employees" DROP COLUMN IF EXISTS "punch_mode";
ALTER TABLE "employees" DROP COLUMN IF EXISTS "access_card_number";
