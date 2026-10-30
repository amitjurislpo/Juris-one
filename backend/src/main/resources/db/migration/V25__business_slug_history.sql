-- When the super admin renames a business's web address (businesses.code), the old address is retired for good: it is written
-- here and can never be taken by any business again (a rename or a new business that asks for it is refused with 409).
-- Platform-level table: only the super admin (system mode, no business set) reads or writes it; a business sees nothing in it.
CREATE TABLE "business_slug_history" (
    "slug"        TEXT        NOT NULL,
    "business_id" UUID        NOT NULL,
    "released_at" TIMESTAMPTZ NOT NULL DEFAULT now(),
    "released_by" UUID,
    CONSTRAINT "business_slug_history_pkey" PRIMARY KEY ("slug"),
    CONSTRAINT "business_slug_history_slug_check" CHECK ("slug" ~ '^[a-z0-9]([a-z0-9-]{1,38}[a-z0-9])$'),
    CONSTRAINT "business_slug_history_business_id_fkey" FOREIGN KEY ("business_id") REFERENCES "businesses"("id") ON DELETE RESTRICT ON UPDATE CASCADE
);
CREATE INDEX "business_slug_history_business_id_idx" ON "business_slug_history"("business_id", "released_at" DESC);

ALTER TABLE "business_slug_history" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "business_slug_history" FORCE ROW LEVEL SECURITY;
CREATE POLICY "platform_only" ON "business_slug_history" USING (jurisone_tenant_id() IS NULL) WITH CHECK (jurisone_tenant_id() IS NULL);
GRANT SELECT, INSERT, UPDATE, DELETE ON "business_slug_history" TO jurisone_tenant;

-- Yes / no only: has this address been retired? Looks past row-level security (its own app.business_id is empty), like jurisone_email_in_use.
CREATE FUNCTION jurisone_slug_retired(p_slug TEXT) RETURNS BOOLEAN LANGUAGE sql STABLE SET app.business_id = '' AS $$
    SELECT EXISTS (SELECT 1 FROM business_slug_history WHERE slug = p_slug)
$$;
GRANT EXECUTE ON FUNCTION jurisone_slug_retired(TEXT) TO jurisone_tenant;

-- Whatever the application does, no business may be created with, or changed to, a retired address.
CREATE FUNCTION jurisone_refuse_retired_slug() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF (TG_OP = 'INSERT' OR NEW."code" IS DISTINCT FROM OLD."code") AND jurisone_slug_retired(NEW."code") THEN
        RAISE EXCEPTION 'The web address "%" has been retired and can never be used again.', NEW."code" USING ERRCODE = '23505';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER "businesses_refuse_retired_slug" BEFORE INSERT OR UPDATE OF "code" ON "businesses"
    FOR EACH ROW EXECUTE FUNCTION jurisone_refuse_retired_slug();
