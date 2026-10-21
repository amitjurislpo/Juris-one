-- Reverses V16__drop_passwords.sql. Run with psql, then delete version 16 from flyway_schema_history.
-- The dropped columns come back empty: passwords and unused links are not restored.
ALTER TABLE "users" ADD COLUMN IF NOT EXISTS "password_hash" TEXT;
ALTER TABLE "users" ADD COLUMN IF NOT EXISTS "password_set_at" TIMESTAMPTZ(6);

DO $$ BEGIN
    CREATE TYPE "AuthTokenPurpose" AS ENUM ('INVITE', 'RESET');
EXCEPTION WHEN duplicate_object THEN NULL;
END $$;

CREATE TABLE IF NOT EXISTS "auth_tokens" (
    "id" UUID NOT NULL,
    "token_hash" TEXT NOT NULL,
    "employee_id" UUID NOT NULL,
    "purpose" "AuthTokenPurpose" NOT NULL,
    "expires_at" TIMESTAMPTZ(6) NOT NULL,
    "used_at" TIMESTAMPTZ(6),
    "created_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "auth_tokens_pkey" PRIMARY KEY ("id")
);
CREATE UNIQUE INDEX IF NOT EXISTS "auth_tokens_token_hash_key" ON "auth_tokens"("token_hash");
CREATE INDEX IF NOT EXISTS "auth_tokens_employee_id_purpose_idx" ON "auth_tokens"("employee_id", "purpose");
ALTER TABLE "auth_tokens" ADD CONSTRAINT "auth_tokens_employee_id_fkey" FOREIGN KEY ("employee_id") REFERENCES "employees"("id") ON DELETE CASCADE ON UPDATE CASCADE;
