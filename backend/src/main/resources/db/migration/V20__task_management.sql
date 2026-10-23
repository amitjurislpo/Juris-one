-- S20: task management (projects, workflow steps, tasks, labels, followers, checklists, comments, attachments, activity).
-- Free for every business (no plan module). Every table is business-owned with row-level security, like V11__assets.sql.
-- Nothing is hard-deleted by the app: tasks, comments, attachments and projects carry deleted_at.

CREATE TABLE IF NOT EXISTS "projects" (
    "id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id() REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    "key" TEXT NOT NULL,
    "name" TEXT NOT NULL,
    "description" TEXT,
    "visibility" TEXT NOT NULL DEFAULT 'PRIVATE',
    "status" TEXT NOT NULL DEFAULT 'ACTIVE',
    "color" TEXT,
    "department_id" UUID REFERENCES "departments"("id") ON DELETE SET NULL ON UPDATE CASCADE,
    "next_task_number" INTEGER NOT NULL DEFAULT 1,
    "created_by" UUID,
    "created_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "archived_at" TIMESTAMPTZ(6),
    "deleted_at" TIMESTAMPTZ(6),
    "deleted_by" UUID,
    "version" INTEGER NOT NULL DEFAULT 0,

    CONSTRAINT "projects_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "projects_key_check" CHECK ("key" ~ '^[A-Z][A-Z0-9]{1,5}$'),
    CONSTRAINT "projects_visibility_check" CHECK ("visibility" IN ('PRIVATE', 'BUSINESS')),
    CONSTRAINT "projects_status_check" CHECK ("status" IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT "projects_color_check" CHECK ("color" IS NULL OR "color" IN ('purple', 'blue', 'green', 'amber', 'red', 'grey'))
);
CREATE UNIQUE INDEX IF NOT EXISTS "projects_business_key_key" ON "projects"("business_id", "key");
CREATE UNIQUE INDEX IF NOT EXISTS "projects_business_name_key" ON "projects"("business_id", lower("name")) WHERE "deleted_at" IS NULL;
CREATE INDEX IF NOT EXISTS "projects_business_status_idx" ON "projects"("business_id", "status");

CREATE TABLE IF NOT EXISTS "project_members" (
    "project_id" UUID NOT NULL REFERENCES "projects"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    "employee_id" UUID NOT NULL REFERENCES "employees"("id") ON DELETE RESTRICT ON UPDATE RESTRICT,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id() REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    "role" TEXT NOT NULL,
    "added_by" UUID,
    "added_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "project_members_pkey" PRIMARY KEY ("project_id", "employee_id"),
    CONSTRAINT "project_members_role_check" CHECK ("role" IN ('OWNER', 'MEMBER', 'VIEWER'))
);
CREATE INDEX IF NOT EXISTS "project_members_employee_idx" ON "project_members"("employee_id");

-- Workflow steps. project_id NULL = the business default set (personal tasks; copied into each new project).
CREATE TABLE IF NOT EXISTS "task_statuses" (
    "id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id() REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    "project_id" UUID REFERENCES "projects"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    "name" TEXT NOT NULL,
    "category" TEXT NOT NULL,
    "position" INTEGER NOT NULL,
    "color" TEXT,
    "is_initial" BOOLEAN NOT NULL DEFAULT false,

    CONSTRAINT "task_statuses_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "task_statuses_category_check" CHECK ("category" IN ('TODO', 'IN_PROGRESS', 'DONE')),
    CONSTRAINT "task_statuses_color_check" CHECK ("color" IS NULL OR "color" IN ('purple', 'blue', 'green', 'amber', 'red', 'grey')),
    CONSTRAINT "task_statuses_initial_check" CHECK (NOT "is_initial" OR "category" = 'TODO')
);
CREATE UNIQUE INDEX IF NOT EXISTS "task_statuses_set_name_key"
    ON "task_statuses"("business_id", coalesce("project_id", '00000000-0000-0000-0000-000000000000'::uuid), lower("name"));
CREATE UNIQUE INDEX IF NOT EXISTS "task_statuses_set_initial_key"
    ON "task_statuses"("business_id", coalesce("project_id", '00000000-0000-0000-0000-000000000000'::uuid)) WHERE "is_initial";
CREATE INDEX IF NOT EXISTS "task_statuses_project_idx" ON "task_statuses"("project_id", "position");

CREATE TABLE IF NOT EXISTS "tasks" (
    "id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id() REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    "project_id" UUID REFERENCES "projects"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    "number" INTEGER,
    "previous_keys" TEXT[] NOT NULL DEFAULT '{}',
    "parent_task_id" UUID REFERENCES "tasks"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    "title" TEXT NOT NULL,
    "description" TEXT,
    "type" TEXT NOT NULL DEFAULT 'TASK',
    "status_id" UUID NOT NULL REFERENCES "task_statuses"("id") ON DELETE RESTRICT ON UPDATE CASCADE,
    "priority" TEXT NOT NULL DEFAULT 'NONE',
    "assignee_id" UUID REFERENCES "employees"("id") ON DELETE RESTRICT ON UPDATE RESTRICT,
    "indirect_assignee_id" UUID REFERENCES "employees"("id") ON DELETE RESTRICT ON UPDATE RESTRICT,
    "reporter_id" UUID NOT NULL REFERENCES "employees"("id") ON DELETE RESTRICT ON UPDATE RESTRICT,
    "start_date" DATE,
    "due_date" DATE,
    "due_time" TIME,
    "estimate_minutes" INTEGER,
    "position" BIGINT NOT NULL,
    "completed_at" TIMESTAMPTZ(6),
    "completed_by" UUID,
    "due_soon_notified_for" DATE,
    "due_today_notified_for" DATE,
    "overdue_notified_for" DATE,
    "created_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "updated_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted_at" TIMESTAMPTZ(6),
    "deleted_by" UUID,
    "version" INTEGER NOT NULL DEFAULT 0,
    "search_vector" tsvector GENERATED ALWAYS AS (to_tsvector('simple', coalesce("title", '') || ' ' || coalesce("description", ''))) STORED,

    CONSTRAINT "tasks_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "tasks_type_check" CHECK ("type" IN ('TASK', 'ISSUE', 'REQUEST', 'MILESTONE')),
    CONSTRAINT "tasks_priority_check" CHECK ("priority" IN ('URGENT', 'HIGH', 'MEDIUM', 'LOW', 'NONE')),
    CONSTRAINT "tasks_indirect_assignee_check" CHECK ("indirect_assignee_id" IS NULL OR "indirect_assignee_id" <> "assignee_id"),
    CONSTRAINT "tasks_dates_check" CHECK ("due_date" IS NULL OR "start_date" IS NULL OR "due_date" >= "start_date"),
    CONSTRAINT "tasks_due_time_check" CHECK ("due_time" IS NULL OR "due_date" IS NOT NULL),
    CONSTRAINT "tasks_estimate_check" CHECK ("estimate_minutes" IS NULL OR "estimate_minutes" BETWEEN 0 AND 100000),
    CONSTRAINT "tasks_number_check" CHECK (("project_id" IS NULL) = ("number" IS NULL))
);
CREATE UNIQUE INDEX IF NOT EXISTS "tasks_project_number_key" ON "tasks"("project_id", "number");
CREATE INDEX IF NOT EXISTS "tasks_project_board_idx" ON "tasks"("project_id", "status_id", "position") WHERE "deleted_at" IS NULL;
CREATE INDEX IF NOT EXISTS "tasks_assignee_open_idx" ON "tasks"("assignee_id", "due_date") WHERE "completed_at" IS NULL AND "deleted_at" IS NULL;
CREATE INDEX IF NOT EXISTS "tasks_indirect_assignee_open_idx" ON "tasks"("indirect_assignee_id", "due_date") WHERE "completed_at" IS NULL AND "deleted_at" IS NULL;
CREATE INDEX IF NOT EXISTS "tasks_reporter_idx" ON "tasks"("reporter_id") WHERE "deleted_at" IS NULL;
CREATE INDEX IF NOT EXISTS "tasks_parent_idx" ON "tasks"("parent_task_id") WHERE "deleted_at" IS NULL;
CREATE INDEX IF NOT EXISTS "tasks_business_due_idx" ON "tasks"("business_id", "due_date") WHERE "deleted_at" IS NULL;
CREATE INDEX IF NOT EXISTS "tasks_search_idx" ON "tasks" USING GIN ("search_vector");
CREATE INDEX IF NOT EXISTS "tasks_previous_keys_idx" ON "tasks" USING GIN ("previous_keys");

-- Labels: project_id NULL = business-wide (Task settings).
CREATE TABLE IF NOT EXISTS "task_labels" (
    "id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id() REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    "project_id" UUID REFERENCES "projects"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    "name" TEXT NOT NULL,
    "color" TEXT,
    "created_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "task_labels_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "task_labels_color_check" CHECK ("color" IS NULL OR "color" IN ('purple', 'blue', 'green', 'amber', 'red', 'grey'))
);
CREATE UNIQUE INDEX IF NOT EXISTS "task_labels_set_name_key"
    ON "task_labels"("business_id", coalesce("project_id", '00000000-0000-0000-0000-000000000000'::uuid), lower("name"));

CREATE TABLE IF NOT EXISTS "task_label_links" (
    "task_id" UUID NOT NULL REFERENCES "tasks"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    "label_id" UUID NOT NULL REFERENCES "task_labels"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id() REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE,

    CONSTRAINT "task_label_links_pkey" PRIMARY KEY ("task_id", "label_id")
);
CREATE INDEX IF NOT EXISTS "task_label_links_label_idx" ON "task_label_links"("label_id");

-- Followers (watchers).
CREATE TABLE IF NOT EXISTS "task_watchers" (
    "task_id" UUID NOT NULL REFERENCES "tasks"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    "employee_id" UUID NOT NULL REFERENCES "employees"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id() REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    "added_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "task_watchers_pkey" PRIMARY KEY ("task_id", "employee_id")
);
CREATE INDEX IF NOT EXISTS "task_watchers_employee_idx" ON "task_watchers"("employee_id");

CREATE TABLE IF NOT EXISTS "task_checklist_items" (
    "id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id() REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    "task_id" UUID NOT NULL REFERENCES "tasks"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    "text" TEXT NOT NULL,
    "is_done" BOOLEAN NOT NULL DEFAULT false,
    "position" INTEGER NOT NULL,
    "done_by" UUID,
    "done_at" TIMESTAMPTZ(6),
    "created_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "task_checklist_items_pkey" PRIMARY KEY ("id")
);
CREATE INDEX IF NOT EXISTS "task_checklist_items_task_idx" ON "task_checklist_items"("task_id", "position");

CREATE TABLE IF NOT EXISTS "task_comments" (
    "id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id() REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    "task_id" UUID NOT NULL REFERENCES "tasks"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    "author_id" UUID NOT NULL REFERENCES "employees"("id") ON DELETE RESTRICT ON UPDATE RESTRICT,
    "body" TEXT NOT NULL,
    "mentioned_employee_ids" UUID[] NOT NULL DEFAULT '{}',
    "created_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "edited_at" TIMESTAMPTZ(6),
    "deleted_at" TIMESTAMPTZ(6),
    "deleted_by" UUID,

    CONSTRAINT "task_comments_pkey" PRIMARY KEY ("id")
);
CREATE INDEX IF NOT EXISTS "task_comments_task_idx" ON "task_comments"("task_id", "created_at");

CREATE TABLE IF NOT EXISTS "task_attachments" (
    "id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id() REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    "task_id" UUID NOT NULL REFERENCES "tasks"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    "file_path" TEXT NOT NULL,
    "file_name" TEXT NOT NULL,
    "content_type" TEXT NOT NULL,
    "size_bytes" BIGINT NOT NULL,
    "sha256" TEXT NOT NULL,
    "uploaded_by" UUID,
    "uploaded_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    "deleted_at" TIMESTAMPTZ(6),
    "deleted_by" UUID,

    CONSTRAINT "task_attachments_pkey" PRIMARY KEY ("id")
);
CREATE INDEX IF NOT EXISTS "task_attachments_task_idx" ON "task_attachments"("task_id");

-- The History tab of a task (the company audit log is written next to it).
CREATE TABLE IF NOT EXISTS "task_activity" (
    "id" UUID NOT NULL,
    "business_id" UUID NOT NULL DEFAULT jurisone_tenant_id() REFERENCES "businesses"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    "task_id" UUID NOT NULL REFERENCES "tasks"("id") ON DELETE CASCADE ON UPDATE CASCADE,
    "project_id" UUID,
    "actor_id" UUID,
    "kind" TEXT NOT NULL,
    "before" JSONB,
    "after" JSONB,
    "occurred_at" TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT "task_activity_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "task_activity_kind_check" CHECK ("kind" IN ('CREATED', 'TITLE_CHANGED', 'DESCRIPTION_CHANGED', 'TYPE_CHANGED', 'STATUS_CHANGED', 'PRIORITY_CHANGED',
        'ASSIGNEE_CHANGED', 'INDIRECT_ASSIGNEE_CHANGED', 'DATES_CHANGED', 'ESTIMATE_CHANGED', 'LABELS_CHANGED', 'PARENT_CHANGED', 'MOVED_PROJECT', 'CHECKLIST_CHANGED',
        'ATTACHMENT_ADDED', 'ATTACHMENT_REMOVED', 'COMMENT_DELETED', 'DELETED'))
);
CREATE INDEX IF NOT EXISTS "task_activity_task_idx" ON "task_activity"("task_id", "occurred_at" DESC);
CREATE INDEX IF NOT EXISTS "task_activity_project_idx" ON "task_activity"("project_id", "occurred_at" DESC);

DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['projects', 'project_members', 'task_statuses', 'tasks', 'task_labels', 'task_label_links', 'task_watchers',
                             'task_checklist_items', 'task_comments', 'task_attachments', 'task_activity'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format('DROP POLICY IF EXISTS "tenant_isolation" ON %I', t);
        EXECUTE format('CREATE POLICY "tenant_isolation" ON %I USING (jurisone_tenant_id() IS NULL OR business_id = jurisone_tenant_id()) '
                    || 'WITH CHECK (jurisone_tenant_id() IS NULL OR business_id = jurisone_tenant_id())', t);
    END LOOP;
END $$;
GRANT SELECT, INSERT, UPDATE, DELETE ON "projects", "project_members", "task_statuses", "tasks", "task_labels", "task_label_links", "task_watchers",
    "task_checklist_items", "task_comments", "task_attachments", "task_activity" TO jurisone_tenant;

-- The default workflow (To do, In progress, In review, Done, Cancelled) for every existing business; new businesses get it on
-- their first task write (WorkflowStatusService.ensureDefaultStatuses).
INSERT INTO "task_statuses" ("id", "business_id", "project_id", "name", "category", "position", "color", "is_initial")
SELECT gen_random_uuid(), b."id", NULL, s."name", s."category", s."position", s."color", s."is_initial"
FROM "businesses" b
CROSS JOIN (VALUES ('To do', 'TODO', 1, 'grey', true), ('In progress', 'IN_PROGRESS', 2, 'blue', false), ('In review', 'IN_PROGRESS', 3, 'purple', false),
                   ('Done', 'DONE', 4, 'green', false), ('Cancelled', 'DONE', 5, 'red', false)) AS s("name", "category", "position", "color", "is_initial")
ON CONFLICT DO NOTHING;

-- The two new permissions: tasks.manage for the Owner template, projects.create for the Owner and HR Manager templates
UPDATE "role_templates" SET "permissions" = array_append("permissions", 'tasks.manage')
WHERE "key" = 'owner' AND NOT ('tasks.manage' = ANY ("permissions"));
UPDATE "role_templates" SET "permissions" = array_append("permissions", 'projects.create')
WHERE "key" IN ('owner', 'hrm') AND NOT ('projects.create' = ANY ("permissions"));

-- ... and the same for the roles businesses already have
INSERT INTO "role_permissions" ("role_id", "permission_key")
SELECT "id", 'tasks.manage' FROM "roles" WHERE "business_id" IS NOT NULL AND "key" = 'owner'
ON CONFLICT DO NOTHING;
INSERT INTO "role_permissions" ("role_id", "permission_key")
SELECT "id", 'projects.create' FROM "roles" WHERE "business_id" IS NOT NULL AND "key" IN ('owner', 'hrm')
ON CONFLICT DO NOTHING;
