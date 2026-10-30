-- A business's short name (businesses.code) becomes its web address (<code>.<base-domain>), so it must be a safe DNS label:
-- 3 to 40 lowercase letters, digits and hyphens, no leading or trailing hyphen, no hyphens at positions 3 and 4 (this also rules out
-- punycode "xn--"). The reserved names (admin, www, api, ...) are enforced in the application (config/BusinessSlugs) and checked
-- here once for the existing rows.
DO $$
DECLARE
    bad TEXT;
BEGIN
    SELECT string_agg('"' || "code" || '"', ', ' ORDER BY "code") INTO bad
    FROM "businesses"
    WHERE "code" !~ '^[a-z0-9]([a-z0-9-]{1,38}[a-z0-9])$'
       OR substring("code" FROM 3 FOR 2) = '--'
       OR "code" IN ('admin', 'www', 'api', 'app', 'mail', 'smtp', 'static', 'assets', 'cdn', 'login', 'auth', 'sso', 'status', 'support', 'help',
                     'billing', 'platform', 'jurisone', 'juristrack', 'track', 'one', 'test', 'staging', 'dev', 'uat', 'sqe', 'prd', 'localhost', 'ns1', 'ns2');
    IF bad IS NOT NULL THEN
        RAISE EXCEPTION 'Business short names (businesses.code) that break the web-address rules or are reserved: %. Rename them (3-40 lowercase letters, digits, dashes; not reserved) and run the migration again.', bad;
    END IF;
END $$;

ALTER TABLE "businesses" ADD CONSTRAINT "businesses_code_slug_check"
    CHECK ("code" ~ '^[a-z0-9]([a-z0-9-]{1,38}[a-z0-9])$' AND substring("code" FROM 3 FOR 2) <> '--') NOT VALID;
ALTER TABLE "businesses" VALIDATE CONSTRAINT "businesses_code_slug_check";
