-- S26: attendance regularization v2. One pending request per employee per day, the real check-in / check-out times the
-- employee asks for, the Old and New hours and status (computed by the server), an admin-editable reason list, one
-- optional attachment, the decision remarks, and Finance no longer approves regularizations (Admin and HR only).
-- Nothing is dropped: the old columns (kind, proposed_in, proposed_out, reason) stay and old rows stay readable.

-- 1. The reason list (per business, edited by Admin / HR). Rows are seeded for every existing business below; new businesses get them on setup.
CREATE TABLE "regularization_reasons" (
    "id"                    UUID NOT NULL,
    "business_id"           UUID NOT NULL DEFAULT jurisone_tenant_id() REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    "label"                 TEXT NOT NULL,
    "requires_description"  BOOLEAN NOT NULL DEFAULT false,
    "active"                BOOLEAN NOT NULL DEFAULT true,
    "sort_order"            INTEGER NOT NULL DEFAULT 100,
    "created_at"            TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "regularization_reasons_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "regularization_reasons_label_check" CHECK (char_length("label") BETWEEN 1 AND 80)
);
CREATE UNIQUE INDEX "regularization_reasons_label_key" ON "regularization_reasons"("business_id", lower("label"));

ALTER TABLE "regularization_reasons" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "regularization_reasons" FORCE ROW LEVEL SECURITY;
CREATE POLICY "tenant_isolation" ON "regularization_reasons" USING (jurisone_tenant_id() IS NULL OR business_id = jurisone_tenant_id())
    WITH CHECK (jurisone_tenant_id() IS NULL OR business_id = jurisone_tenant_id());
GRANT SELECT, INSERT, UPDATE, DELETE ON "regularization_reasons" TO jurisone_tenant;

INSERT INTO "regularization_reasons" ("id", "business_id", "label", "requires_description", "sort_order")
SELECT gen_random_uuid(), b."id", r."label", r."requires_description", r."sort_order"
FROM "businesses" b
CROSS JOIN (VALUES ('Forgot to check-in', false, 1), ('Forgot to check-out', false, 2), ('System / biometric issue', false, 3),
                   ('Client visit', false, 4), ('Other', true, 5)) AS r("label", "requires_description", "sort_order");

-- 2. New columns on the request. Old/New are filled by the server when the request is made.
ALTER TABLE "regularization_requests"
    ADD COLUMN "reason_id"               UUID REFERENCES "regularization_reasons"("id") ON DELETE SET NULL,
    ADD COLUMN "description"             TEXT,
    ADD COLUMN "old_check_in"            TIMESTAMPTZ(6),
    ADD COLUMN "old_check_out"           TIMESTAMPTZ(6),
    ADD COLUMN "old_minutes"             INTEGER,
    ADD COLUMN "old_status"              TEXT,
    ADD COLUMN "new_minutes"             INTEGER,
    ADD COLUMN "new_status"              TEXT,
    ADD COLUMN "decision_note"           TEXT,
    ADD COLUMN "created_by"              UUID REFERENCES "employees"("id") ON DELETE SET NULL,   -- the employee, or HR on behalf (later)
    ADD COLUMN "attachment_name"         TEXT,
    ADD COLUMN "attachment_mime"         TEXT,
    ADD COLUMN "attachment_bytes"        INTEGER,
    ADD COLUMN "attachment_path"         TEXT,                                                   -- relative to jurisone.storage-dir
    ADD COLUMN "updated_at"              TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- 3. One PENDING request per employee per day. Old data may already break that: keep the newest pending one, cancel the rest.
UPDATE "regularization_requests" r SET "status" = 'CANCELLED'
WHERE r."status" = 'PENDING' AND EXISTS (
    SELECT 1 FROM "regularization_requests" newer
    WHERE newer."employee_id" = r."employee_id" AND newer."work_date" = r."work_date" AND newer."status" = 'PENDING'
      AND (newer."created_at" > r."created_at" OR (newer."created_at" = r."created_at" AND newer."id" > r."id")));
CREATE UNIQUE INDEX "regularization_requests_one_pending_per_day" ON "regularization_requests"("employee_id", "work_date") WHERE "status" = 'PENDING';
CREATE INDEX "regularization_requests_work_date_idx" ON "regularization_requests"("work_date", "status");

-- 4. Only Admin (Owner) and HR approve regularizations: Finance loses "appr.reg" (template and every business's Finance roles).
UPDATE "role_templates" SET "permissions" = array_remove("permissions", 'appr.reg') WHERE "key" IN ('fm', 'fe');
DELETE FROM "role_permissions" WHERE "permission_key" = 'appr.reg'
  AND "role_id" IN (SELECT "id" FROM "roles" WHERE "key" IN ('fm', 'fe') AND "is_builtin");
