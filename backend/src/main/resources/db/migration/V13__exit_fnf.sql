-- S13: employee exit (resignation / termination, notice, clearance) and full & final (F&F) settlement.
-- Written to be re-runnable: every statement is IF NOT EXISTS / ON CONFLICT DO NOTHING / DROP ... IF EXISTS first.
-- Nothing existing is dropped or recreated.

-- One exit case per leaving employee. Status flow:
--   RESIGNED (employee) / INITIATED (HR) -> NOTICE -> CLEARANCE -> FNF_PENDING -> SETTLED -> CLOSED
--   and WITHDRAWN (employee, before clearance) / CANCELLED (HR, before the settlement is approved).
CREATE TABLE IF NOT EXISTS "employee_exits" (
    "id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id(),
    "employee_id" UUID NOT NULL,
    "kind" TEXT NOT NULL DEFAULT 'RESIGNATION',
    "status" TEXT NOT NULL,
    "reason" TEXT NOT NULL DEFAULT '',
    "requested_last_day" DATE,                 -- what the employee asked for
    "last_working_day" DATE,                   -- what HR agreed (null until set)
    "notice_start_date" DATE,                  -- the day notice starts counting (resignation date, or when HR started it)
    "notice_days" INTEGER NOT NULL DEFAULT 30, -- notice required for this person (HR can shorten / waive)
    "notice_policy_days" INTEGER NOT NULL DEFAULT 30, -- the business's setting when the case was opened
    "notice_reason" TEXT,                      -- why the notice was shortened / waived
    "hr_comment" TEXT,
    "initiated_by" UUID,
    "initiated_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "accepted_by" UUID,
    "accepted_at" TIMESTAMPTZ(6),
    "settled_at" TIMESTAMPTZ(6),
    "closed_by" UUID,
    "closed_at" TIMESTAMPTZ(6),
    "ended_reason" TEXT,                       -- why it was withdrawn / cancelled
    "created_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "employee_exits_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "employee_exits_kind_check" CHECK ("kind" IN ('RESIGNATION', 'TERMINATION', 'OTHER')),
    CONSTRAINT "employee_exits_status_check" CHECK ("status" IN ('RESIGNED', 'INITIATED', 'NOTICE', 'CLEARANCE', 'FNF_PENDING', 'SETTLED', 'CLOSED', 'WITHDRAWN', 'CANCELLED')),
    CONSTRAINT "employee_exits_notice_check" CHECK ("notice_days" >= 0),
    CONSTRAINT "employee_exits_business_id_fkey" FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "employee_exits_employee_id_fkey" FOREIGN KEY ("employee_id") REFERENCES "employees"("id") ON DELETE RESTRICT ON UPDATE CASCADE
);
CREATE INDEX IF NOT EXISTS "employee_exits_business_id_idx" ON "employee_exits"("business_id", "status");
CREATE INDEX IF NOT EXISTS "employee_exits_employee_id_idx" ON "employee_exits"("employee_id");
-- at most one open case per employee
CREATE UNIQUE INDEX IF NOT EXISTS "employee_exits_open_key" ON "employee_exits"("employee_id") WHERE "status" NOT IN ('CLOSED', 'WITHDRAWN', 'CANCELLED');

-- The clearance checklist: each department clears its part and leaves remarks.
CREATE TABLE IF NOT EXISTS "exit_clearance_items" (
    "id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id(),
    "exit_id" UUID NOT NULL,
    "dept" TEXT NOT NULL,
    "title" TEXT NOT NULL,
    "sort_order" INTEGER NOT NULL DEFAULT 0,
    "cleared" BOOLEAN NOT NULL DEFAULT false,
    "remarks" TEXT,
    "cleared_by" UUID,
    "cleared_at" TIMESTAMPTZ(6),
    "created_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "exit_clearance_items_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "exit_clearance_items_dept_check" CHECK ("dept" IN ('HR', 'FINANCE', 'IT', 'MANAGER')),
    CONSTRAINT "exit_clearance_items_exit_id_fkey" FOREIGN KEY ("exit_id") REFERENCES "employee_exits"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    CONSTRAINT "exit_clearance_items_business_id_fkey" FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE
);
CREATE INDEX IF NOT EXISTS "exit_clearance_items_exit_id_idx" ON "exit_clearance_items"("exit_id");

-- The full & final settlement of an exit. Finance drafts it (inputs + the latest computation); on approval the
-- computation is frozen into "snapshot" and the row can never change again (trigger below). Amounts are integer paise.
CREATE TABLE IF NOT EXISTS "fnf_settlements" (
    "id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id(),
    "exit_id" UUID NOT NULL,
    "employee_id" UUID NOT NULL,
    "month" TEXT NOT NULL,                     -- the month of the last working day, "2026-10"
    "status" TEXT NOT NULL DEFAULT 'DRAFT',
    "additions" JSONB NOT NULL DEFAULT '[]'::jsonb,   -- [{label, amount}] entered by Finance (bonus, arrears, ...)
    "deductions" JSONB NOT NULL DEFAULT '[]'::jsonb,  -- [{label, amount}] entered by Finance (advances, damages, ...)
    "note" TEXT,
    "calc" JSONB,                              -- the latest computation of the draft
    "net_paise" BIGINT,                        -- net payable (negative = recoverable from the employee)
    "snapshot" JSONB,                          -- the frozen statement, set on approval
    "drafted_by" UUID,
    "drafted_at" TIMESTAMPTZ(6),
    "approved_by" UUID,
    "approved_at" TIMESTAMPTZ(6),
    "created_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "fnf_settlements_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "fnf_settlements_status_check" CHECK ("status" IN ('DRAFT', 'APPROVED')),
    CONSTRAINT "fnf_settlements_approved_check" CHECK ("status" <> 'APPROVED' OR ("snapshot" IS NOT NULL AND "approved_at" IS NOT NULL)),
    CONSTRAINT "fnf_settlements_exit_id_fkey" FOREIGN KEY ("exit_id") REFERENCES "employee_exits"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "fnf_settlements_employee_id_fkey" FOREIGN KEY ("employee_id") REFERENCES "employees"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    CONSTRAINT "fnf_settlements_business_id_fkey" FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE
);
CREATE UNIQUE INDEX IF NOT EXISTS "fnf_settlements_exit_id_key" ON "fnf_settlements"("exit_id");
CREATE INDEX IF NOT EXISTS "fnf_settlements_employee_id_idx" ON "fnf_settlements"("employee_id", "status");

-- An approved settlement is a permanent record: no update, no delete.
CREATE OR REPLACE FUNCTION jurisone_fnf_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD."status" = 'APPROVED' THEN
        RAISE EXCEPTION 'An approved settlement is a permanent record and can''t be changed or deleted' USING ERRCODE = 'insufficient_privilege';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END $$;
DROP TRIGGER IF EXISTS "fnf_settlements_immutable" ON "fnf_settlements";
CREATE TRIGGER "fnf_settlements_immutable" BEFORE UPDATE OR DELETE ON "fnf_settlements" FOR EACH ROW EXECUTE FUNCTION jurisone_fnf_immutable();

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['employee_exits', 'exit_clearance_items', 'fnf_settlements'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS "tenant_isolation" ON %I', t);
        EXECUTE format('CREATE POLICY "tenant_isolation" ON %I USING (jurisone_tenant_id() IS NULL OR business_id = jurisone_tenant_id()) '
                    || 'WITH CHECK (jurisone_tenant_id() IS NULL OR business_id = jurisone_tenant_id())', t);
    END LOOP;
END $$;
GRANT SELECT, INSERT, UPDATE ON "employee_exits", "fnf_settlements" TO jurisone_tenant;
GRANT SELECT, INSERT, UPDATE, DELETE ON "exit_clearance_items" TO jurisone_tenant;

-- the new permission: owners and HR managers of existing businesses get it (Finance sees the F&F through pay.view / pay.close)
INSERT INTO "role_permissions" ("role_id", "permission_key")
SELECT "id", 'exit.manage' FROM "roles" WHERE "business_id" IS NOT NULL AND "key" IN ('owner', 'hrm')
ON CONFLICT DO NOTHING;
