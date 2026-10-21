-- Subscriptions (SAAS_PLAN S3). The super admin keeps the plan catalogue and assigns plans to businesses; a business
-- can be used only while one of its subscriptions covers today. Modules (Juris Track) can come with the plan or be
-- granted to a business on their own (complimentary or paid) — used from S4.

CREATE TABLE "subscription_plans" (
    "id" UUID NOT NULL,
    "name" TEXT NOT NULL,
    "description" TEXT,
    "price_paise" BIGINT NOT NULL DEFAULT 0,
    "period_months" INTEGER NOT NULL,
    "max_employees" INTEGER,                 -- NULL = no limit
    "modules" TEXT[] NOT NULL DEFAULT '{}',  -- e.g. {JURIS_TRACK}
    "active" BOOLEAN NOT NULL DEFAULT true,
    "created_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "subscription_plans_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "subscription_plans_period_check" CHECK ("period_months" BETWEEN 1 AND 60),
    CONSTRAINT "subscription_plans_price_check" CHECK ("price_paise" >= 0),
    CONSTRAINT "subscription_plans_max_check" CHECK ("max_employees" IS NULL OR "max_employees" > 0)
);
CREATE UNIQUE INDEX "subscription_plans_name_key" ON "subscription_plans"("name");

CREATE TABLE "business_subscriptions" (
    "id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id(),
    "plan_id" UUID NOT NULL,
    "starts_on" DATE NOT NULL,
    "ends_on" DATE NOT NULL,
    "price_paise" BIGINT NOT NULL,           -- price agreed for this period (the plan's price can change later)
    "status" TEXT NOT NULL DEFAULT 'ACTIVE',
    "notes" TEXT,
    "created_by" UUID,
    "created_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "cancelled_at" TIMESTAMPTZ(6),

    CONSTRAINT "business_subscriptions_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "business_subscriptions_status_check" CHECK ("status" IN ('ACTIVE', 'CANCELLED')),
    CONSTRAINT "business_subscriptions_dates_check" CHECK ("ends_on" >= "starts_on")
);
CREATE INDEX "business_subscriptions_business_id_ends_on_idx" ON "business_subscriptions"("business_id", "ends_on");
ALTER TABLE "business_subscriptions" ADD CONSTRAINT "business_subscriptions_business_id_fkey" FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE;
ALTER TABLE "business_subscriptions" ADD CONSTRAINT "business_subscriptions_plan_id_fkey" FOREIGN KEY ("plan_id") REFERENCES "subscription_plans"("id") ON DELETE RESTRICT ON UPDATE CASCADE;

CREATE TABLE "business_modules" (
    "id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id(),
    "module" TEXT NOT NULL,
    "source" TEXT NOT NULL,                  -- COMPLIMENTARY / PAID (modules that come with the plan are not stored here)
    "price_paise" BIGINT NOT NULL DEFAULT 0,
    "starts_on" DATE NOT NULL,
    "ends_on" DATE,                          -- NULL = no end
    "notes" TEXT,
    "granted_by" UUID,
    "created_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "revoked_at" TIMESTAMPTZ(6),

    CONSTRAINT "business_modules_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "business_modules_module_check" CHECK ("module" IN ('JURIS_TRACK')),
    CONSTRAINT "business_modules_source_check" CHECK ("source" IN ('COMPLIMENTARY', 'PAID')),
    CONSTRAINT "business_modules_dates_check" CHECK ("ends_on" IS NULL OR "ends_on" >= "starts_on")
);
CREATE INDEX "business_modules_business_id_idx" ON "business_modules"("business_id");
ALTER TABLE "business_modules" ADD CONSTRAINT "business_modules_business_id_fkey" FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE;

-- A business sees only its own subscriptions and modules (the plan catalogue is shared and read-only to businesses).
DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['business_subscriptions', 'business_modules'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('CREATE POLICY "tenant_isolation" ON %I USING (jurisone_tenant_id() IS NULL OR business_id = jurisone_tenant_id()) '
                    || 'WITH CHECK (jurisone_tenant_id() IS NULL)', t);   -- only the super admin (system mode) writes them
    END LOOP;
END $$;
GRANT SELECT, INSERT, UPDATE, DELETE ON "subscription_plans", "business_subscriptions", "business_modules" TO jurisone_tenant;
