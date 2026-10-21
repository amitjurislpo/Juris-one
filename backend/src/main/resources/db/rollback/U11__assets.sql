-- Reverses V11__assets.sql (run with psql, then delete version 11 from flyway_schema_history).
-- This destroys the asset register and its assignment history: export it first.
DELETE FROM "role_permissions" WHERE "permission_key" = 'assets.manage';
DROP TABLE IF EXISTS "asset_assignments";
DROP TABLE IF EXISTS "assets";
