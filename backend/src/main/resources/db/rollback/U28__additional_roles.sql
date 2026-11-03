-- Reverses V28__additional_roles.sql (run with psql, then delete version 28 from flyway_schema_history).
-- Businesses that got the "juristrack_manager" role keep it (and its permission) until it is removed by hand:
--   DELETE FROM employee_additional_roles WHERE role_id IN (SELECT id FROM roles WHERE key = 'juristrack_manager');
--   UPDATE employees SET role_id = (SELECT id FROM roles r WHERE r.key = 'employee' AND r.business_id = employees.business_id) WHERE role_id IN (SELECT id FROM roles WHERE key = 'juristrack_manager');
--   DELETE FROM roles WHERE key = 'juristrack_manager';
-- (the roles created by the backfill stay: remove them with the statements above)
DELETE FROM "role_templates" WHERE "key" = 'juristrack_manager';
DROP FUNCTION IF EXISTS jurisone_employee_has_permission(UUID, TEXT);
DROP TABLE IF EXISTS "employee_additional_roles";
DROP FUNCTION IF EXISTS jurisone_additional_role_business();
