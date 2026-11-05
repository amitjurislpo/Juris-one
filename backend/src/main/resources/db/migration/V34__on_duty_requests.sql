-- S34: On Duty requests (the Attendance page's On Duty tab). An employee asks for a day (or half a day) spent on official work away
-- from the usual place (client visit, site, training); Admin and HR (appr.reg) approve. An approved On Duty day counts as a present,
-- payable day in the attendance engine. A range of days is one request per day sharing a batch id, like regularization.
-- Nothing existing is dropped or rewritten.

CREATE TABLE "on_duty_requests" (
    "id"            UUID NOT NULL,
    "business_id"   UUID NOT NULL DEFAULT jurisone_tenant_id() REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    "employee_id"   UUID NOT NULL REFERENCES "employees"("id") ON DELETE CASCADE,
    "work_date"     DATE NOT NULL,
    "part"          TEXT NOT NULL DEFAULT 'FULL',                       -- FULL | FIRST_HALF | SECOND_HALF
    "location"      TEXT,                                               -- where (client / site), optional
    "reason"        TEXT NOT NULL,
    "status"        "RequestStatus" NOT NULL DEFAULT 'PENDING',
    "batch_id"      UUID,
    "created_by"    UUID REFERENCES "employees"("id") ON DELETE SET NULL,
    "decided_by"    UUID REFERENCES "employees"("id") ON DELETE SET NULL,
    "decided_at"    TIMESTAMPTZ(6),
    "decided_role"  TEXT,
    "decision_note" TEXT,
    "created_at"    TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at"    TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "on_duty_requests_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "on_duty_requests_part_check" CHECK ("part" IN ('FULL', 'FIRST_HALF', 'SECOND_HALF')),
    CONSTRAINT "on_duty_requests_reason_check" CHECK (char_length("reason") BETWEEN 1 AND 500),
    CONSTRAINT "on_duty_requests_location_check" CHECK ("location" IS NULL OR char_length("location") BETWEEN 1 AND 120)
);

-- One open (pending or approved) request per employee per day: two requests for the same day can never both stand.
CREATE UNIQUE INDEX "on_duty_requests_one_open_per_day" ON "on_duty_requests"("employee_id", "work_date") WHERE "status" IN ('PENDING', 'APPROVED');
CREATE INDEX "on_duty_requests_work_date_idx" ON "on_duty_requests"("work_date", "status");
CREATE INDEX "on_duty_requests_batch_id_idx" ON "on_duty_requests"("batch_id") WHERE "batch_id" IS NOT NULL;

ALTER TABLE "on_duty_requests" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "on_duty_requests" FORCE ROW LEVEL SECURITY;
CREATE POLICY "tenant_isolation" ON "on_duty_requests" USING (jurisone_tenant_id() IS NULL OR business_id = jurisone_tenant_id())
    WITH CHECK (jurisone_tenant_id() IS NULL OR business_id = jurisone_tenant_id());
GRANT SELECT, INSERT, UPDATE, DELETE ON "on_duty_requests" TO jurisone_tenant;

-- A locked (payroll) day keeps how much of it was On Duty, so the Present / On duty counters stay the same after the month closes.
ALTER TABLE "attendance_days" ADD COLUMN "on_duty_days" NUMERIC(4,2) NOT NULL DEFAULT 0;
