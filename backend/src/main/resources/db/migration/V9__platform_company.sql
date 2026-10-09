-- The platform company: the JurisOne operator's own business (its staff: HR, Finance, employees). It needs no
-- subscription, is not listed among the customers, and the super admins belong to it. Created at startup /
-- by the seed (PlatformCompanyService), not here: it needs the full business defaults.
ALTER TABLE "businesses" ADD COLUMN "is_platform" BOOLEAN NOT NULL DEFAULT false;
CREATE UNIQUE INDEX "businesses_one_platform_key" ON "businesses"("is_platform") WHERE "is_platform";
