-- Reverses V19__employee_photos.sql (run with psql, then delete version 19 from flyway_schema_history).
-- This destroys every uploaded profile photo.
DROP TABLE IF EXISTS "employee_photos";
ALTER TABLE "employees" DROP COLUMN IF EXISTS "photo_updated_at";
