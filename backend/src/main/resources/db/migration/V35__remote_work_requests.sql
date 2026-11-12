-- S35: remote work requests. An employee asks to work remotely for a range of days (Admin / HR approve); an approved day is a normal
-- working day (never Absent for lack of a punch). One row per request, a date range, no per-day rows.
-- Rows belong to an employee, so tenant isolation follows the employee's business (like regularization_requests).

CREATE TABLE "remote_work_requests" (
    "id"             UUID NOT NULL,
    "employee_id"    UUID NOT NULL REFERENCES "employees"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    "from_date"      DATE NOT NULL,
    "to_date"        DATE NOT NULL,
    "reason"         TEXT NOT NULL,
    "status"         "RequestStatus" NOT NULL DEFAULT 'PENDING',
    "decided_by"     UUID REFERENCES "employees"("id") ON DELETE SET NULL ON UPDATE CASCADE,
    "decided_at"     TIMESTAMPTZ(6),
    "decided_role"   TEXT,
    "decision_note"  TEXT,
    "created_by"     UUID REFERENCES "employees"("id") ON DELETE SET NULL ON UPDATE CASCADE,
    "created_at"     TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at"     TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "remote_work_requests_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "remote_work_requests_range_check" CHECK ("to_date" >= "from_date" AND "to_date" - "from_date" < 31),
    CONSTRAINT "remote_work_requests_reason_check" CHECK (char_length("reason") BETWEEN 1 AND 500)
);
-- The same employee can not have two open (pending or approved) requests that start on the same day; overlaps are refused by the service under a per-employee lock.
CREATE UNIQUE INDEX "remote_work_requests_one_open_per_start" ON "remote_work_requests"("employee_id", "from_date") WHERE "status" IN ('PENDING', 'APPROVED');
CREATE INDEX "remote_work_requests_employee_idx" ON "remote_work_requests"("employee_id", "from_date");
CREATE INDEX "remote_work_requests_range_idx" ON "remote_work_requests"("from_date", "to_date", "status");

ALTER TABLE "remote_work_requests" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "remote_work_requests" FORCE ROW LEVEL SECURITY;
CREATE POLICY "tenant_isolation" ON "remote_work_requests"
    USING (jurisone_tenant_id() IS NULL OR EXISTS (SELECT 1 FROM employees e WHERE e.id = "remote_work_requests".employee_id))
    WITH CHECK (jurisone_tenant_id() IS NULL OR EXISTS (SELECT 1 FROM employees e WHERE e.id = "remote_work_requests".employee_id));
GRANT SELECT, INSERT, UPDATE, DELETE ON "remote_work_requests" TO jurisone_tenant;
