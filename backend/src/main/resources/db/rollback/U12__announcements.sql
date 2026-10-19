-- Reverses V12__announcements.sql (run with psql, then delete version 12 from flyway_schema_history).
DELETE FROM "role_permissions" WHERE "permission_key" = 'announcements.manage';
DROP TABLE IF EXISTS "announcements";
