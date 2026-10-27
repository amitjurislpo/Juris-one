-- Reverses V22__employee_onboarding.sql (run with psql, then delete version 22 from flyway_schema_history).
-- The old candidate-flow tables (V7: onboardings / onboarding_documents) are not restored; re-run the V7 statements if you need them back (empty).
-- Uploaded files under <storage-dir>/onboarding-forms/ are not removed.
DROP TABLE IF EXISTS "onboarding_attachments";
DROP TABLE IF EXISTS "onboarding_nominations";
DROP TABLE IF EXISTS "onboarding_family_members";
DROP TABLE IF EXISTS "onboarding_statutory";
DROP TABLE IF EXISTS "onboarding_bank";
DROP TABLE IF EXISTS "onboarding_employments";
DROP TABLE IF EXISTS "onboarding_courses";
DROP TABLE IF EXISTS "onboarding_education";
DROP TABLE IF EXISTS "onboarding_emergency_contacts";
DROP TABLE IF EXISTS "onboarding_addresses";
DROP TABLE IF EXISTS "onboarding_personal";
DROP TABLE IF EXISTS "employee_onboardings";
