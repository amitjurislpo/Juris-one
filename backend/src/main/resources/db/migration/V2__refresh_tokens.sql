-- JWT sign-in (SAAS_PLAN S1). A row in "sessions" is one signed-in device (the refresh-token family); the short-lived
-- JWT access token carries its id. Refresh tokens rotate on every use: each is stored once (SHA-256), marked used
-- when exchanged, and presenting a used one again revokes the whole session.
CREATE TABLE "refresh_tokens" (
    "token_hash" TEXT NOT NULL,
    "session_id" TEXT NOT NULL,
    "expires_at" TIMESTAMPTZ(6) NOT NULL,
    "used_at" TIMESTAMPTZ(6),
    "created_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "ip" TEXT,
    "user_agent" TEXT,

    CONSTRAINT "refresh_tokens_pkey" PRIMARY KEY ("token_hash")
);

CREATE INDEX "refresh_tokens_session_id_idx" ON "refresh_tokens"("session_id");

ALTER TABLE "refresh_tokens" ADD CONSTRAINT "refresh_tokens_session_id_fkey" FOREIGN KEY ("session_id") REFERENCES "sessions"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- Sessions made by the old opaque-cookie sign-in can't be refreshed; end them so nobody is left half signed in.
UPDATE "sessions" SET "revoked_at" = CURRENT_TIMESTAMP, "revoked_reason" = 'jwt_migration' WHERE "revoked_at" IS NULL;
