-- S6: notification box. Notifications are read one by one; a "POLICY" notification stays unread until the policy
-- version it announces has been accepted (or replaced / archived).
ALTER TABLE "notifications" ADD COLUMN "kind" TEXT;        -- NULL = plain; 'POLICY' = cleared by accepting the policy
ALTER TABLE "notifications" ADD COLUMN "ref_id" UUID;      -- POLICY: the policy id
CREATE INDEX "notifications_employee_unread_idx" ON "notifications"("employee_id", "read_at", "created_at" DESC);
