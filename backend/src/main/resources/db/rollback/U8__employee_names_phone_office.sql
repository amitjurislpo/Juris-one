-- Reverses V8__employee_names_phone_office.sql (run with psql, then delete version 8 from flyway_schema_history).
ALTER TABLE "employees" DROP COLUMN IF EXISTS "emergency_phone", DROP COLUMN IF EXISTS "emergency_relation", DROP COLUMN IF EXISTS "emergency_name",
    DROP COLUMN IF EXISTS "permanent_address_enc", DROP COLUMN IF EXISTS "date_of_birth", DROP COLUMN IF EXISTS "joining_office",
    DROP COLUMN IF EXISTS "phone_country", DROP COLUMN IF EXISTS "last_name", DROP COLUMN IF EXISTS "middle_name", DROP COLUMN IF EXISTS "first_name";
