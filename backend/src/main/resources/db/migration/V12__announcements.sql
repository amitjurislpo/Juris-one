-- S12: announcements posted by HR / the owner. Idempotent: nothing existing is dropped or recreated.

CREATE TABLE IF NOT EXISTS "announcements" (
    "id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id() REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    "title" TEXT NOT NULL,
    "body" TEXT NOT NULL DEFAULT '',
    "audience" TEXT NOT NULL DEFAULT 'ALL',          -- ALL | IN | US (the employee's region)
    "pinned" BOOLEAN NOT NULL DEFAULT false,
    "expires_on" DATE,                               -- last day it is shown; NULL = until unpublished
    "status" TEXT NOT NULL DEFAULT 'PUBLISHED',
    "created_by" UUID,
    "created_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "announcements_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "announcements_audience_check" CHECK ("audience" IN ('ALL', 'IN', 'US')),
    CONSTRAINT "announcements_status_check" CHECK ("status" IN ('PUBLISHED', 'UNPUBLISHED'))
);
CREATE INDEX IF NOT EXISTS "announcements_business_id_idx" ON "announcements"("business_id", "status", "created_at" DESC);

ALTER TABLE "announcements" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "announcements" FORCE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS "tenant_isolation" ON "announcements";
CREATE POLICY "tenant_isolation" ON "announcements" USING (jurisone_tenant_id() IS NULL OR business_id = jurisone_tenant_id())
    WITH CHECK (jurisone_tenant_id() IS NULL OR business_id = jurisone_tenant_id());
GRANT SELECT, INSERT, UPDATE, DELETE ON "announcements" TO jurisone_tenant;

INSERT INTO "role_permissions" ("role_id", "permission_key")
SELECT "id", 'announcements.manage' FROM "roles" WHERE "business_id" IS NOT NULL AND "key" IN ('owner', 'hrm')
ON CONFLICT DO NOTHING;
