-- Reverses V14__form16.sql (run with psql, then delete version 14 from flyway_schema_history).
-- WARNING: published Form 16s are permanent records; export them first. The triggers are dropped so the table can go.
DROP TRIGGER IF EXISTS "form16_forms_append_only" ON "form16_forms";
DROP TRIGGER IF EXISTS "form16_forms_no_truncate" ON "form16_forms";
DROP TABLE IF EXISTS "form16_forms";
