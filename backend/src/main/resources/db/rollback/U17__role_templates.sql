-- Reverses V17__role_templates.sql (run with psql, then delete version 17 from flyway_schema_history).
-- Roles already given to businesses are ordinary rows and stay as they are.
DROP TABLE IF EXISTS "role_templates";
