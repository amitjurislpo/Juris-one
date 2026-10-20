-- Employee identity fields: first / middle / last name (full_name stays as the display name, built from them),
-- the phone's country code, the joining office, and self-service profile details.
ALTER TABLE "employees" ADD COLUMN "first_name" TEXT;
ALTER TABLE "employees" ADD COLUMN "middle_name" TEXT;
ALTER TABLE "employees" ADD COLUMN "last_name" TEXT;
ALTER TABLE "employees" ADD COLUMN "phone_country" TEXT;         -- e.g. '+91'; NULL for numbers saved before (stored with their code)
ALTER TABLE "employees" ADD COLUMN "joining_office" TEXT;        -- where they join (free text)
ALTER TABLE "employees" ADD COLUMN "date_of_birth" DATE;
ALTER TABLE "employees" ADD COLUMN "permanent_address_enc" TEXT;
ALTER TABLE "employees" ADD COLUMN "emergency_name" TEXT;
ALTER TABLE "employees" ADD COLUMN "emergency_relation" TEXT;
ALTER TABLE "employees" ADD COLUMN "emergency_phone" TEXT;

-- existing people: first word = first name, last word = last name, anything between = middle name
UPDATE "employees" SET
    "first_name" = split_part(btrim("full_name"), ' ', 1),
    "last_name" = CASE WHEN position(' ' IN btrim("full_name")) > 0 THEN regexp_replace(btrim("full_name"), '^.*\s', '') END,
    "middle_name" = NULLIF(btrim(regexp_replace(regexp_replace(btrim("full_name"), '^\S+\s*', ''), '\s*\S+$', '')), '')
WHERE "first_name" IS NULL;
