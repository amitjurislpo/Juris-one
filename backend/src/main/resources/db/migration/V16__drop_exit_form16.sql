-- Removes the employee exit / full & final settlement and Form 16 modules (V13, V14).
-- Their tables are dropped with the data in them; export first if it is needed. U16 recreates the empty tables.
DROP TRIGGER IF EXISTS "fnf_settlements_immutable" ON "fnf_settlements";
DROP TRIGGER IF EXISTS "form16_forms_append_only" ON "form16_forms";
DROP TRIGGER IF EXISTS "form16_forms_no_truncate" ON "form16_forms";
DROP TABLE IF EXISTS "fnf_settlements";
DROP TABLE IF EXISTS "exit_clearance_items";
DROP TABLE IF EXISTS "employee_exits";
DROP TABLE IF EXISTS "form16_forms";
DROP FUNCTION IF EXISTS jurisone_fnf_immutable();
DELETE FROM "role_permissions" WHERE "permission_key" = 'exit.manage';
