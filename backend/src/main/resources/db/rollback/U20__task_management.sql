-- Reverses V20__task_management.sql (run with psql, then delete version 20 from flyway_schema_history).
-- WARNING: this destroys every project, task, comment, attachment record and the task history: export them first.
-- Stored attachment files under <storage-dir>/tasks/ are not removed.
DELETE FROM "role_permissions" WHERE "permission_key" IN ('tasks.manage', 'projects.create');
UPDATE "role_templates" SET "permissions" = array_remove(array_remove("permissions", 'tasks.manage'), 'projects.create');
DROP TABLE IF EXISTS "task_activity";
DROP TABLE IF EXISTS "task_attachments";
DROP TABLE IF EXISTS "task_comments";
DROP TABLE IF EXISTS "task_checklist_items";
DROP TABLE IF EXISTS "task_watchers";
DROP TABLE IF EXISTS "task_label_links";
DROP TABLE IF EXISTS "task_labels";
DROP TABLE IF EXISTS "tasks";
DROP TABLE IF EXISTS "task_statuses";
DROP TABLE IF EXISTS "project_members";
DROP TABLE IF EXISTS "projects";
