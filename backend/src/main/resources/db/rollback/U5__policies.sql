-- Reverses V5__policies.sql (run with psql, then delete version 5 from flyway_schema_history).
-- This destroys the acknowledgement record: export the evidence first.
DELETE FROM "role_permissions" WHERE "permission_key" = 'policies.manage';
DROP TABLE IF EXISTS "policy_acknowledgements";
DROP TABLE IF EXISTS "policy_versions";
DROP TABLE IF EXISTS "policies";
DROP FUNCTION IF EXISTS jurisone_append_only();
