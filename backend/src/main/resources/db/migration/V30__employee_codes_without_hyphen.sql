-- S30: employee codes have the shape PREFIX + digits with no hyphen (JC-058 becomes JC058). Old codes keep their own digit count.
-- Refuses to run (and so rolls back) when stripping a hyphen would make two codes of one business equal.

DO $$
DECLARE clash TEXT;
BEGIN
    SELECT string_agg(DISTINCT replace(a."code", '-', ''), ', ') INTO clash
      FROM "employees" a
      JOIN "employees" b ON b."id" <> a."id" AND b."business_id" IS NOT DISTINCT FROM a."business_id" AND replace(b."code", '-', '') = replace(a."code", '-', '')
     WHERE a."code" ~ '^[A-Z]+-[0-9]+$';
    IF clash IS NOT NULL THEN
        RAISE EXCEPTION 'Stripping the hyphen from employee codes would create duplicates: %', clash;
    END IF;
END $$;

UPDATE "employees" SET "code" = replace("code", '-', '') WHERE "code" ~ '^[A-Z]+-[0-9]+$';

-- Copies of the code in issued letters, policy acknowledgements, audit entries and frozen payslip snapshots stay as they were issued (history).
