-- Reverses V2__refresh_tokens.sql. Run with psql, then delete version 2 from flyway_schema_history.
-- Sessions revoked by V2 stay revoked (users simply sign in again).
DROP TABLE IF EXISTS "refresh_tokens";
