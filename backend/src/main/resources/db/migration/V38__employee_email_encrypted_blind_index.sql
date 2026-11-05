-- Next stage of encrypting employee data: the work email (the sign-in name). Expand only, like V32.
-- "email_enc" is the AES-256-GCM copy ("v1." format, see PiiCrypto). "email_blind_index" is a keyed HMAC-SHA256 (hex, 64 characters) of the
-- normalised address (trimmed, lower-case) made with its own key (jurisone.security.email-blind-index-key); with it an address can be found
-- (sign-in, "email already used") without the database holding anything but the plaintext column and the encrypted copy.
-- Both columns are filled by the application (jurisone.security.encrypt-email.dual-write) and by the "encrypt-existing" command.
-- The plaintext "email" column and its unique index stay exactly as they are; nothing is dropped or made nullable here.
ALTER TABLE "employees" ADD COLUMN "email_enc" TEXT;
ALTER TABLE "employees" ADD COLUMN "email_blind_index" TEXT;
ALTER TABLE "employees" ADD CONSTRAINT "employees_email_blind_index_format" CHECK ("email_blind_index" IS NULL OR "email_blind_index" ~ '^[0-9a-f]{64}$');

-- The plaintext index is unique across every business (the work email is the sign-in), so the blind index is too. Rows without one (not yet filled) are ignored.
CREATE UNIQUE INDEX "employees_email_blind_index_key" ON "employees"("email_blind_index") WHERE "email_blind_index" IS NOT NULL;

-- Same as jurisone_email_in_use, by blind index: looks past row-level security but only answers yes / no.
CREATE FUNCTION jurisone_email_blind_index_in_use(p_blind_index TEXT, p_except UUID) RETURNS BOOLEAN LANGUAGE sql STABLE SET app.business_id = '' AS $$
    SELECT EXISTS (SELECT 1 FROM employees WHERE email_blind_index = p_blind_index AND (p_except IS NULL OR id <> p_except))
$$;
GRANT EXECUTE ON FUNCTION jurisone_email_blind_index_in_use(TEXT, UUID) TO jurisone_tenant;
