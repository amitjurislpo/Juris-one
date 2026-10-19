-- Reverses V13__exit_fnf.sql (run with psql, then delete version 13 from flyway_schema_history).
-- Approved settlements are permanent records: dropping the tables discards them, so export them first if you need them.
DROP TRIGGER IF EXISTS "fnf_settlements_immutable" ON "fnf_settlements";
DROP TABLE IF EXISTS "fnf_settlements";
DROP TABLE IF EXISTS "exit_clearance_items";
DROP TABLE IF EXISTS "employee_exits";
DROP FUNCTION IF EXISTS jurisone_fnf_immutable();
DELETE FROM "role_permissions" WHERE "permission_key" = 'exit.manage';
