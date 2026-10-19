-- Email one-time-password sign-in. One row per code sent; only a salted SHA-256 of the code is stored.
-- Global table (sign-in runs before any business is known), so no business_id / RLS - like refresh_tokens.
CREATE TABLE IF NOT EXISTS "login_otps" (
    "id" UUID NOT NULL DEFAULT gen_random_uuid(),
    "email" TEXT NOT NULL,
    "code_hash" TEXT NOT NULL,
    "expires_at" TIMESTAMPTZ(6) NOT NULL,
    "attempts" INTEGER NOT NULL DEFAULT 0,
    "used_at" TIMESTAMPTZ(6),
    "created_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "ip" TEXT,

    CONSTRAINT "login_otps_pkey" PRIMARY KEY ("id")
);

CREATE INDEX IF NOT EXISTS "login_otps_email_created_at_idx" ON "login_otps"("email", "created_at" DESC);
