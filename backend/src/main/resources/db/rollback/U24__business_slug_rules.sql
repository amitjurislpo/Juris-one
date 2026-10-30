-- Reverses V24__business_slug_rules.sql (run with psql, then delete version 24 from flyway_schema_history).
ALTER TABLE "businesses" DROP CONSTRAINT IF EXISTS "businesses_code_slug_check";
