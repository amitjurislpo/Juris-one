-- Attendance info (Hissa 1): the employee's punch-machine card number and an optional per-employee punch mode.
--   access_card_number : the number of the employee's access card, unique inside one business (NULL = none; platform users have no business_id and are never compared).
--   punch_mode         : MANUAL (web check-in / check-out) or MACHINE (punch-machine data); NULL = follow the business setting attendance.policy.punchMode.
-- Nothing is rewritten: every existing employee keeps NULL for both, i.e. follows the business, and a business without the setting records MANUAL.
ALTER TABLE "employees" ADD COLUMN "access_card_number" TEXT;
ALTER TABLE "employees" ADD COLUMN "punch_mode" TEXT;
ALTER TABLE "employees" ADD CONSTRAINT "employees_access_card_number_format" CHECK ("access_card_number" IS NULL OR "access_card_number" ~ '^[A-Z0-9-]{1,32}$');
ALTER TABLE "employees" ADD CONSTRAINT "employees_punch_mode_check" CHECK ("punch_mode" IS NULL OR "punch_mode" IN ('MANUAL', 'MACHINE'));
CREATE UNIQUE INDEX "employees_business_access_card_number_key" ON "employees"("business_id", "access_card_number") WHERE "access_card_number" IS NOT NULL;
