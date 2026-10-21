-- S17: role templates. The super admin keeps a catalogue of role templates (name, permissions, which roles each may assign) and
-- picks which of them a business gets when it is onboarded. A business's role is a snapshot copy of its template; the
-- business owner (sub admin) can't create or change roles. Platform-level table (no business_id, no row-level security),
-- like subscription_plans. Mandatory templates go to every new business.

CREATE TABLE "role_templates" (
    "key"         TEXT NOT NULL,
    "name"        TEXT NOT NULL,
    "locked"      BOOLEAN NOT NULL DEFAULT false,     -- full access, fixed (the owner)
    "mandatory"   BOOLEAN NOT NULL DEFAULT false,     -- every business gets it
    "permissions" TEXT[] NOT NULL DEFAULT '{}',
    "assignable"  TEXT[] NOT NULL DEFAULT '{}',       -- template keys a holder may assign to others
    "sort_order"  INTEGER NOT NULL DEFAULT 100,
    "active"      BOOLEAN NOT NULL DEFAULT true,
    "created_at"  TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT "role_templates_pkey" PRIMARY KEY ("key"),
    CONSTRAINT "role_templates_key_check" CHECK ("key" ~ '^[a-z][a-z0-9_]{0,39}$')
);
CREATE UNIQUE INDEX "role_templates_name_key" ON "role_templates"(lower("name"));

INSERT INTO "role_templates" ("key", "name", "locked", "mandatory", "permissions", "assignable", "sort_order") VALUES
('owner', 'Owner', true, true,
    ARRAY['dir','att.all','appr.leave','appr.reg','people.view','people.add','access.toggle','dept.manage','holidays.manage','letters.manage',
          'pay.view','pay.close','struct.manage','tax.view','proofs.verify','policies.manage','assets.manage','announcements.manage','roles.manage','business.manage'],
    ARRAY['hrm','hre','fm','fe','employee'], 1),
('hrm', 'HR Manager', false, true,
    ARRAY['self','dir','att.all','appr.leave','appr.reg','people.view','people.add','access.toggle','dept.manage','holidays.manage','policies.manage',
          'assets.manage','announcements.manage','letters.manage'],
    ARRAY['hre','employee'], 2),
('hre', 'HR Executive', false, false,
    ARRAY['self','dir','att.all','appr.leave','appr.reg','people.view'], ARRAY[]::TEXT[], 3),
('fm', 'Finance Manager', false, true,
    ARRAY['self','dir','appr.reg','people.view','people.add','access.toggle','dept.manage','pay.view','pay.close','struct.manage','tax.view','proofs.verify'],
    ARRAY['fe'], 4),
('fe', 'Finance Executive', false, false,
    ARRAY['self','dir','appr.reg','pay.view','tax.view','proofs.verify'], ARRAY[]::TEXT[], 5),
('employee', 'Employee', false, true,
    ARRAY['self','dir'], ARRAY[]::TEXT[], 6);

GRANT SELECT, INSERT, UPDATE, DELETE ON "role_templates" TO jurisone_tenant;
