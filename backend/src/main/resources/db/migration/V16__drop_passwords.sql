-- S16: sign-in is by emailed code only. No passwords, and no invite / reset links.
-- Written to be re-runnable (IF EXISTS).

DROP TABLE IF EXISTS "auth_tokens";
DROP TYPE IF EXISTS "AuthTokenPurpose";

ALTER TABLE "users" DROP COLUMN IF EXISTS "password_hash";
ALTER TABLE "users" DROP COLUMN IF EXISTS "password_set_at";
