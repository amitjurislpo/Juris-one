-- Reverses V25__business_slug_history.sql (run with psql, then delete version 25 from flyway_schema_history).
-- The list of retired addresses is lost: take a copy of "business_slug_history" first if retired addresses must stay retired.
DROP TRIGGER IF EXISTS "businesses_refuse_retired_slug" ON "businesses";
DROP FUNCTION IF EXISTS jurisone_refuse_retired_slug();
DROP FUNCTION IF EXISTS jurisone_slug_retired(TEXT);
DROP TABLE IF EXISTS "business_slug_history";
