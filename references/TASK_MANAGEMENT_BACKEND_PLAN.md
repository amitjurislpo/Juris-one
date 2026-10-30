# Task management — backend plan (Spring Boot API)

Status: **plan only, nothing built yet** (written 2026-10-22, updated 2026-10-23 with the owner's decisions). Companion file:
`juris-one-frontend/references/TASK_MANAGEMENT_FRONTEND_PLAN.md` (same endpoint and field names; if you change one file, change the other in the same commit).

A future session must be able to build this from this file alone. Every pattern below is copied from code that exists today;
the source file is named next to it. Paths are relative to `backend/src/main/java/com/jurislpo/jurisone/` unless they start with
`backend/`, `references/` or the frontend repo.

## Contents

0. [Overview in plain words](#0-overview-in-plain-words)
1. [Standards, git rules and verify steps](#1-standards-git-rules-and-verify-steps)
2. [Product scope and decisions](#2-product-scope-and-decisions)
3. [Left side menu (nav items, permissions, badges)](#3-left-side-menu)
4. [Who sees what (permissions, assignees, visibility)](#4-who-sees-what)
5. [Availability and subscription](#5-availability-and-subscription)
6. [Code layout (packages and classes)](#6-code-layout)
7. [Data model (tables, keys, indexes, row-level security)](#7-data-model)
8. [Rules: numbering, ordering, concurrency, workflow, limits](#8-rules)
9. [Activity history and audit](#9-activity-history-and-audit)
10. [Notifications (bell only)](#10-notifications-bell-only)
11. [Attachments (file storage)](#11-attachments)
12. [REST API](#12-rest-api)
13. [Lists, filters, search and performance](#13-lists-filters-search-and-performance)
14. [Jobs (reminders, recurring tasks)](#14-jobs)
15. [Flyway migrations and rollbacks](#15-flyway-migrations-and-rollbacks)
16. [Seed and demo data](#16-seed-and-demo-data)
17. [Tests](#17-tests)
18. [REQUIREMENTS.md and other docs to update](#18-requirementsmd-and-other-docs-to-update)
19. [Later integrations](#19-later-integrations)
20. [Phases and gates (incl. "Later: email alerts")](#20-phases-and-gates)
21. [Risks](#21-risks)
22. [Decided by the owner, and open questions](#22-decided-by-the-owner-and-open-questions)
23. [How to start implementation (checklist)](#23-how-to-start-implementation)

---

## 0. Overview in plain words

What the owner gets:

1. A new **Tasks** section in the left menu of JurisOne, **free for every business**, for every team (HR, finance, operations, projects), not tied to one process.
2. **Projects**: a team creates a project (for example "Payroll October", "Website relaunch"). Each project has a short code, so tasks get numbers like `PAY-12`.
3. **Tasks** have a title, description, type, status, priority, labels, a start date, a due date with an optional due time, an estimate, sub-tasks and a checklist.
4. Every task has **one person who does the work** (direct assignee) and, optionally, **one person who is accountable** for it (indirect assignee, e.g. the manager or a reviewer). Both see it in My tasks and both are notified. Other interested people can **follow** the task.
5. Each project chooses its own **steps** (statuses) such as "To do → In review → Done". Every step belongs to one of three groups: to do, in progress, done.
6. People **comment** on a task, **mention** colleagues with `@name` and attach files (PDF, images, Word, Excel).
7. Every change is kept in the task's **history** and in the company audit log.
8. **My tasks** shows what is on my plate today, this week, and what is overdue. A **board** view lets people drag cards between steps.
9. A manager sees **Team tasks**: the tasks of the people who report to them (direct reports, and with one switch the next level down), using the reporting line that already exists.
10. **Notifications in the bell** (no emails for now): assigned to me, mentioned, a task I follow changes, my task is due tomorrow or overdue. Email alerts will be added later by the owner.
11. A project is **private** (members only) or **company-wide** (everyone can read it). The **Owner sees every project and task** of the business; opening a private one is recorded in the audit log. Nobody ever sees another company's data.
12. Later phases add a calendar, reports (workload, overdue, finished per week), recurring tasks, templates, CSV import/export, bulk changes, saved filters, dependencies, custom fields and a timeline.

Nothing is built yet. The first phase (MVP) is projects, tasks, board, comments, attachments, My tasks, Team tasks, bell notifications and reminders.

---

## 1. Standards, git rules and verify steps

**Read first:** `INSTRUCTIONS.md` and `CLAUDE.md` of this repo. The rules below are a summary; those files win.

| Topic | Rule |
|---|---|
| Names | Full words (`projectId`, `dueDate`, `estimateMinutes`, `indirectAssigneeId`), booleans read as questions (`isDone`, `isOverdue`, `canEdit`), functions are verbs. No abbreviations in classes, fields, JSON, query parameters, routes or columns. |
| Casing | Java classes/records/enums `PascalCase`; JSON fields and query parameters `camelCase`; URL segments `kebab-case`; tables/columns `snake_case`; constants `UPPER_SNAKE_CASE`. |
| Layers | `controller` maps HTTP only; rules live in `service` and pure rules in `engine`; SQL in `repository`. One class per file. Request/response bodies are `record`s named after what they carry. |
| REST | Nouns, ids in the path, `GET` never writes (audited reads are the only side effect), `POST` creates → `201` + `{id}`, `PUT` sets (idempotent), `PATCH` partial, `DELETE` removes. Own collection is `/mine`. Errors are RFC 7807 problem+json with `code` (`exception/Problems.java`). |
| Every endpoint | `auth.requirePage(navId)` or `auth.assertPerm(...)` in the controller, an integration test, an audit entry for every write (§9), and its rule in `references/REQUIREMENTS.md`. |
| Migrations | Never edit an applied migration. Add `backend/src/main/resources/db/migration/V<n>__<what>.sql` and `backend/src/main/resources/db/rollback/U<n>__<what>.sql`. Do not fill the gaps (there is no V13/V14). |
| Tenancy | Every new table: `business_id UUID NOT NULL DEFAULT jurisone_tenant_id()` + `ENABLE/FORCE ROW LEVEL SECURITY` + policy `tenant_isolation` + `GRANT ... TO jurisone_tenant` (copy the `DO $$ ... $$` block of `V11__assets.sql`). Switch `TenantContext` only outside a transaction. |
| Dates | `date` columns for due/start dates (ISO `YYYY-MM-DD` in JSON), a `time` column for the optional due time (`HH:mm` in JSON); `timestamptz` (UTC) for events; the app formats them. "Today" is computed in a person's time zone (`employees.time_zone`, `engine/Format.todayIn`). |

**Git:** cut `feature/task-management` from `dev` (both repos use the same branch name). PRs go to `dev`, never `main`. Never merge a PR yourself: open it, share the link, stop. Commit and push only when the owner asks. Never commit `.idea/`. Commit messages end with the co-author line the harness gives.

**Verify before saying "done"** (say plainly what was not run):

1. Backend: `./mvnw test` in `backend/` of a **fresh copy** of the repo (a new `git worktree` or clone, so stale `target/` files cannot hide a failure). Docker must be running (Testcontainers).
2. Backend still packages: `./mvnw -DskipTests package` (the frontend e2e driver builds the jar).
3. Frontend (other repo): `npm run typecheck`, `npm run lint`, `npm test`, `npm run build`, `npx knip`, and `npm run test:e2e` with `BACKEND_REPO` pointing at this checkout (the default is `../JurisOne`; the folder here is `../juris-one-backend`).

---

## 2. Product scope and decisions

| # | Topic | Decision (one-line reason) | Phase |
|---|---|---|---|
| D1 | Container | **Projects** are the only container (no separate "workspace" or "board" objects); a business is already the workspace, and a project's board is a view of its tasks. | MVP |
| D2 | Tasks without a project | Allowed: a **personal task** (`project_id` NULL) for one's own to-dos or a quick task given to a colleague; it has no number. | MVP |
| D3 | Description | **Plain text** in MVP (line breaks, blank-line paragraphs, auto-links, `@mentions`), like policy bodies (`PolicyService`); a small markdown subset (bold, italic, lists, code) is rendered by the app in v2 from the same stored text, so no data change. | MVP / v2 |
| D4 | Assignees (owner's decision) | **One direct assignee** (does the work) and **one optional indirect assignee** (accountable / oversight: a manager or reviewer). Both see the task in My tasks, both are notified, both show on cards and in the task panel. **Followers (watchers)** are a separate list. Details in §4.4. | MVP |
| D5 | Type | Fixed list `TASK`, `ISSUE`, `REQUEST`, `MILESTONE` (generic enough for HR/finance/ops); configurable types in v3. | MVP |
| D6 | Status | Custom **workflow per project**: named statuses, each in a category `TODO`, `IN_PROGRESS`, `DONE`. A business default set is copied into each new project. | MVP |
| D7 | Priority | `URGENT`, `HIGH`, `MEDIUM`, `LOW`, `NONE` (default `NONE`). | MVP |
| D8 | Labels | Business-wide labels (managed in Task settings) and project labels. | MVP |
| D9 | Sub-tasks | One level only (a sub-task cannot have sub-tasks); simpler lists and boards. Sub-tasks are real tasks (own status/assignees). | MVP |
| D10 | Checklist | Simple items inside a task (text + done), for steps that do not need an owner. | MVP |
| D11 | Dates (owner's decision) | `start_date` and `due_date` are **dates**; an optional **`due_time`** can be added when a due date is set. Due date/time are read in the **direct assignee's time zone** (the "task time zone", §8.6). A date-only task becomes overdue the day after its due date; a timed task at its due time. | MVP |
| D12 | Estimate | `estimate_minutes` (integer). Time actually spent comes later from JurisTrack (§19). | MVP |
| D13 | Comments | Plain text + `@mentions`; author can edit, author or project owner can delete (soft). | MVP |
| D14 | Attachments | On tasks (not comments), stored in `jurisone.storage-dir` like onboarding documents. | MVP |
| D15 | History | Per-task activity list + company audit log. | MVP |
| D16 | Views | MVP: My tasks (list + board), Project (list + board), Team tasks, task detail. v2: Calendar, Reports. v3: Timeline/Gantt. | — |
| D17 | Notifications (owner's decision) | **Bell only.** No emails in MVP or v2; email alerts are a separate later phase the owner will start (§20 "Later: email alerts"). | MVP |
| D18 | Availability (owner's decision) | **Free for every business**: no plan module, no module grant. | MVP |
| D19 | v2 tools | Dependencies, recurring tasks, templates, saved filters, bulk actions, CSV import/export, restore deleted. | v2 |
| D20 | v3 tools | Custom fields, configurable types, timeline, WIP limits, automation rules. | v3 |
| D21 | Search | Postgres full text (`simple` dictionary, prefix matching) on title + description, plus exact match on a task key; ILIKE only for short lookups (people pickers). | MVP |
| D22 | Deleting (owner's decision) | **Soft delete always, in every phase**: tasks, comments, attachments (and their files) and projects get `deleted_at`; the app never hard-deletes them. A project with tasks is archived rather than deleted (as `AssetService.delete`); an empty project is soft-deleted. T2 adds a 30-day "recently deleted" list with restore. Small configuration rows (labels, workflow statuses, checklist items, followers, members) are removed normally, with an activity/audit entry. | MVP |

---

## 3. Left side menu

How the menu works today: `engine/Nav.java` holds the list (`Nav.Item(id, label, icon, perm, needsReports, group, module)`);
`ShellService.shell()` builds the sidebar groups in the order the items appear in `Nav.NAV` (items with `group == null` go to
"My workspace", or "Workspace" for the owner who has no `self`). The frontend copy is `src/lib/nav.ts` (must stay identical) and
`src/components/shell/Shell.tsx` renders it; badges come from `Shell.badges` (`approvals`, `proofs` today).

### 3.1 New nav items (insert in `Nav.NAV` right after `juris-track`, before `team`)

That position makes the new **"Tasks"** group appear after "My workspace" and before "Team". The items have **no module** (`module = null`):
the group is there for every signed-in user of every business; each item still needs its permission.

| Order | Nav id (= route) | Label | Icon | Permission (any of) | Extra | Group | Phase | What the click opens |
|---|---|---|---|---|---|---|---|---|
| 1 | `my-tasks` | My tasks | `tasks` (new) | `self`, `dir`, `roles.manage` | — | Tasks | MVP | My open tasks (direct or indirect assignee) grouped by due (overdue, today, this week, later), list or board. |
| 2 | `team-tasks` | Team tasks | `team` | `self`, `dir`, `roles.manage` | `needsReports` | Tasks | MVP | Tasks of my direct reports (switch: include the next level), with a per-person summary. |
| 3 | `projects` | Projects | `folder` (new) | `self`, `dir`, `roles.manage` | — | Tasks | MVP | The projects I can see; a click opens the project with List / Board tabs. |
| 4 | `task-calendar` | Task calendar | `cal` | `self`, `dir`, `roles.manage` | — | Tasks | v2 | Month/week calendar of my (or my team's, or a project's) tasks by due date. |
| 5 | `task-reports` | Task reports | `chart` (new) | `tasks.manage`, `projects.create` | — | Tasks | v2 | Workload, overdue, finished per week, by person/project/status. |
| 6 | `task-settings` | Task settings | `sliders` | `tasks.manage` | — | Tasks | MVP | Default workflow, company labels; templates (v2) and custom fields (v3). |

- **No separate "Board" item:** a board always belongs to a project (or to My tasks), so it is a tab inside those pages; a menu item would first need a project picker.
- New icons (`tasks`, `folder`, `chart`) are added to the frontend `Icon.tsx`; existing icons are listed in the frontend plan.
- Java: the short helper `item(id, label, icon, perm, "Tasks")` for most items; `team-tasks` needs the long constructor for `needsReports`:
  `new Item("team-tasks", "Team tasks", "team", List.of(SELF, DIR, ROLES_MANAGE), true, "Tasks", null)`.
- The super admin (platform, no business) never sees these items (no `self`/`dir`/`roles.manage`).
- Hindi labels for every new nav label are required (frontend unit test `tests/unit/i18n.test.ts` "translates every sidebar label").

### 3.2 Who sees which item by default

| Role (template key) | My tasks | Team tasks | Projects | Task calendar | Task reports | Task settings |
|---|---|---|---|---|---|---|
| Owner (`owner`, no `self`) | yes | if has reports | yes (all projects) | yes | yes (`tasks.manage`) | yes (`tasks.manage`) |
| HR Manager (`hrm`) | yes | if has reports | yes | yes | yes (`projects.create`) | no |
| HR Executive (`hre`) | yes | if has reports | yes | yes | no | no |
| Finance Manager (`fm`) | yes | if has reports | yes | yes | no | no |
| Finance Executive (`fe`) | yes | if has reports | yes | yes | no | no |
| Employee (`employee`) | yes | if has reports | yes | yes | no | no |
| Super admin (platform) | no | no | no | no | no | no |

### 3.3 Badge

- `my-tasks` shows a badge = number of **my open tasks (as direct or indirect assignee) that are overdue or due today** (each task judged in its task time zone, §8.6).
- `ShellService.shell()` adds `badges.put("myTasks", count)`; the count comes from a new `ShellRepository.countMyTasksDueByToday(employeeId, today)` (uses `tasks_assignee_open_idx` and `tasks_indirect_assignee_open_idx`, §7).
- Frontend `Shell.tsx` reads `badges.myTasks` for the `my-tasks` item (exact mapping in the frontend plan).

---

## 4. Who sees what

### 4.1 New permissions (`engine/Permissions.java`, frontend `src/lib/permissions.ts`)

| Key | Label (catalogue text) | Group | Default holders |
|---|---|---|---|
| `tasks.manage` | "Task settings & every project and task" | new group **"Tasks"** | **Owner only** |
| `projects.create` | "Create task projects" | "Tasks" | **Owner, HR Manager** |

- Add both to `Permissions.LABELS` and a new `new Group("Tasks", List.of(TASKS_MANAGE, PROJECTS_CREATE))` in `Permissions.GROUPS`; same in the frontend file.
- Everyone with `self`, `dir` or `roles.manage` can **use** tasks (My tasks, be a project member, create personal tasks, create tasks inside projects where they are a member). No permission is needed for that, the same way `my-assets` works.
- Not salary permissions: they may go into HR templates (`RoleTemplateService.permissions()` blocks only `Permissions.SALARY`).
- `tasks.manage` gives the "sees everything" exception of §4.3, so it stays on the Owner template only (the super admin should not add it to other templates without the owner asking).
- Migration V20 adds them to the role templates and to existing business roles (§15), copying how `V11__assets.sql` granted `assets.manage`.

### 4.2 Project roles

| Project role | Can do |
|---|---|
| `OWNER` | Everything in the project: edit project, members, workflow, labels, archive; edit/delete any task; delete any comment. A project has at least one owner. |
| `MEMBER` | Create tasks, edit any task in the project, move cards, set both assignees, comment, attach, delete tasks they reported. |
| `VIEWER` | Read tasks, comment, follow. |

The creator of a project becomes its `OWNER`.

### 4.3 Visibility rules (pure function `engine/TaskAccess.java`, unit-tested as a matrix)

Inputs: `isTaskAdmin` (holds `tasks.manage` = the Owner), project `visibility` (`PRIVATE` / `BUSINESS`), the user's project role (or none),
`isDirectAssignee`, `isIndirectAssignee`, `isReporter`, `isWatcher`, `isManagerOfAssignee` (the direct assignee is in the user's reporting scope, depth ≤ 2).
Output: `TaskPermissions(canView, canComment, canEdit, canChangeStatus, canChangePriority, canChangeDirectAssignee, canChangeIndirectAssignee, canDelete, canManageProject)`.
`canEdit` means "every field"; the narrower flags exist for the indirect assignee (§4.4).

| Who | Project task | Personal task (no project) |
|---|---|---|
| **Owner** (`tasks.manage`) | acts as project `OWNER` on **every** project, private ones included | view + comment on **every** personal task of the business |
| Project owner / member / viewer | as in §4.2 | — |
| Non-member, `BUSINESS` project | like `VIEWER` (read + comment + follow) | — |
| Non-member, `PRIVATE` project | nothing (API answers **404**) | — |
| Direct assignee | view, comment, change status, checklist, attach; all fields when also a member (direct assignees are auto-added as `MEMBER`, below) | view, comment, edit |
| Indirect assignee | §4.4 rights on that task | §4.4 rights |
| Reporter | view + comment; edit if member | view, comment, edit, delete |
| Watcher (follower) | view + comment (a watcher must already be able to see a project task; on a personal task, adding a watcher is how it is shared) | view + comment |
| Manager of the direct assignee (direct, or depth 2) | **view + comment** on that task only, also inside private projects (decided by the owner; not the rest of the project) | view + comment |
| Anyone else | nothing (404) | nothing (404) |

**Rule "a task you cannot see is not found" (404, never 403, so its existence does not leak).** The **only exception is the Owner**
(`tasks.manage`): the Owner sees all projects and tasks of the business, including private projects and personal tasks.

**Owner access is recorded:** every write is audited as for anyone (§9); in addition, when the Owner opens a private project or a task of a
private project they are not a member of (or a personal task they are not part of), `ProjectService.detail` / `TaskService.detail` writes
`audit.log(user.id(), "project.private_access" | "task.private_access", "project" | "task", id, null, null, ipAddress)` — one row per open, the same way other sensitive reads are recorded.

Further rules:
- **Setting a direct assignee who is not a member** of the project adds them as `MEMBER` automatically (activity + audit entry). This keeps "the person doing the work can work on it" simple.
- The **indirect assignee is not added as a member**: their rights are task-level (§4.4), so they do not get edit rights on the rest of the project.
- People who can be chosen as assignees: active employees of the business (`status <> 'EXITED'`), same query shape as `AssetRepository.findAssignees()`.
- An exited employee keeps their tasks (shown with a "left" marker); they disappear from pickers.
- HR / Finance have **no special view** of tasks: tasks are not personnel records. Only the Owner (`tasks.manage`) has a cross-project view.
- Row-level security guarantees no cross-business leak even if a query forgets a filter (`TenantIsolationTest` pattern).

### 4.4 Direct and indirect assignee

| Item | Direct assignee | Indirect assignee |
|---|---|---|
| Meaning | Does the work | Accountable / oversight (manager, reviewer, process owner) |
| Column | `tasks.assignee_id` (NULL = unassigned) | `tasks.indirect_assignee_id` (optional); CHECK it differs from `assignee_id` |
| API field (read and write) | `assigneeId` (+ `assigneeName`, `assigneeCode`, `assigneePhoto`, `isAssigneeActive` in rows) | `indirectAssigneeId` (+ `indirectAssigneeName`, `indirectAssigneeCode`, `indirectAssigneePhoto`, `isIndirectAssigneeActive`) |
| Filter parameter | `assigneeId` (uuid or `none`) | `indirectAssigneeId` (uuid or `none`) |
| My tasks | `GET /api/v1/tasks/mine?relation=ASSIGNED` (default) returns tasks where I am **either**; `relation=DIRECT` / `relation=INDIRECT` narrow it; each row has `myAssignment` = `DIRECT` / `INDIRECT` / null | same |
| Notifications | assigned, mentioned, comments, status changes, due tomorrow, overdue | the same events ("You are accountable for {key}: {title}" when set) |
| Who may set / change it | project `OWNER`/`MEMBER`, the Owner (`tasks.manage`), **and the indirect assignee** (may reassign the direct assignee); personal task: reporter | project `OWNER`/`MEMBER`, the Owner; personal task: reporter. The indirect assignee may remove themself. |

**Indirect assignee's rights on that task** (decided by the owner 2026-10-23):
view and follow, **comment**, **change status** (incl. board moves), **change priority**, **reassign the direct assignee**.
They **cannot** edit the other fields (title, description, dates, labels, estimate, project, checklist, attachments) unless they are also a project `MEMBER`/`OWNER`,
and they **cannot delete** the task unless they are a project `OWNER` (or the Owner). `PATCH /api/v1/tasks/{taskId}` checks rights field by field and answers 403 for a field the caller may not change.

Both assignees are **auto-added as followers** (watchers) so they receive comment and status notifications; followers stay a separate list that anyone who can see the task may join or leave.

### 4.5 Team scope (reporting line) and how it relates to the indirect assignee

- Team tasks = tasks whose **direct assignee** is one of my reports: direct reports by default (`employees.reporting_manager_id = me AND status <> 'EXITED'`, as `DashboardRepository` / `SessionRepository.has_reports`), and with "Include indirect reports" depth 2 (the reports of my reports), copying the JurisTrack decision (`juris-track-backend/references/INTEGRATION.md` §3b). Depth is a parameter (`depth=1|2`) so it can be widened later.
- The reporting line is **automatic** (who works for me); the indirect assignee is a **per-task choice** (who answers for this task) and can be anyone, including someone outside the reporting line. They are independent:
  - a manager sees their team's work in **Team tasks** with view + comment rights (§4.3), even when they are not the indirect assignee;
  - when a manager is also set as indirect assignee, the task appears in **their own My tasks** too and they get the §4.4 rights (change status/priority, reassign);
  - Team tasks shows the indirect assignee as a column and accepts the `indirectAssigneeId` filter, so a manager can see "tasks of my team where I am accountable".
- Suggestion (decided by the owner): when a direct assignee is chosen, the create form offers that person's reporting manager as indirect assignee (one click, **never set automatically**). `GET /api/v1/tasks/assignees` returns `reportingManagerId` for this.
- SQL (in `TaskQuery`):
  ```sql
  WITH RECURSIVE scope(employee_id, depth) AS (
      SELECT id, 1 FROM employees WHERE reporting_manager_id = :me AND status <> 'EXITED'
      UNION ALL
      SELECT e.id, s.depth + 1 FROM employees e JOIN scope s ON e.reporting_manager_id = s.employee_id
      WHERE s.depth < :depth AND e.status <> 'EXITED')
  SELECT employee_id FROM scope
  ```
  The depth limit also protects against a reporting-line cycle.

---

## 5. Availability and subscription

- **Free for every business (owner's decision 2026-10-23):** no plan module, no module grant, no change to `SubscriptionService.MODULES`, `business_modules` or the platform screens. Controllers use the normal `auth.requirePage(navId)` (nav ids `my-tasks`, `projects`, `team-tasks`, `task-settings`, `task-reports` as named in §12).
- **Subscription ended:** the global rule applies: `SubscriptionGateFilter` answers 402 `SUBSCRIPTION_INACTIVE` to every write while reads keep working, so tasks become read-only automatically. Pending policy acknowledgement: `PolicyGateFilter` answers 428 as everywhere.
- **Jobs:** the task jobs run for every active business, inside the existing loop of `JobsService.runJobs` (§14).

---

## 6. Code layout

Follow the assets module end to end (`controller/AssetController.java`, `service/AssetService.java`, `repository/AssetRepository.java`, `V11__assets.sql`, test `assets/AssetApiTest.java`): **JdbcTemplate repositories returning rows as maps with camelCase aliases, no JPA entities** (so `ddl-auto=validate` is not affected and generated columns are fine).

| Layer | Class (new) | Holds |
|---|---|---|
| `controller` | `ProjectController` | `/api/v1/projects/**` (project, members, workflow statuses, board) |
| | `TaskController` | `/api/v1/tasks` collections, task CRUD, position, watchers, checklist items |
| | `TaskCollaborationController` | comments, activity, attachments under `/api/v1/tasks/{taskId}/…` |
| | `TaskLabelController` | `/api/v1/task-labels/**` |
| | `TaskSettingsController` | `/api/v1/task-settings/**` |
| | v2: `TaskTemplateController`, `TaskFilterController`, `TaskReportController`, `TaskImportController` | see §12.2 |
| `service` | `ProjectService` | projects, members, archive; key uniqueness; Owner private-access audit |
| | `WorkflowStatusService` | project and default statuses, order, delete with move; `ensureDefaultStatuses()` |
| | `TaskService` | create, patch (field-level rights), position, delete, watchers, checklist; calls access, activity, notifications |
| | `TaskQueryService` | list/mine/team/board/assignees; builds filters with `TaskQuery` |
| | `TaskCommentService`, `TaskAttachmentService`, `TaskLabelService` | as named |
| | `TaskActivityService` | writes a `task_activity` row **and** an `audit_log` row (`AuditService.log`) in the caller's transaction |
| | `TaskNotificationService` | recipients, dedupe, bell notifications (§10) |
| | `TaskJobs` | due-soon / overdue reminders (MVP), recurrence (v2); called from `JobsService.runForBusiness` |
| `repository` | `ProjectRepository`, `WorkflowStatusRepository`, `TaskRepository`, `TaskQuery` (dynamic SQL + args, like `PeopleQuery`), `TaskCommentRepository`, `TaskAttachmentRepository`, `TaskActivityRepository`, `TaskLabelRepository` | SQL only |
| `engine` (pure, no Spring) | `TaskAccess` | §4.3/§4.4 matrix |
| | `TaskPositions` | between / append / renumber math (§8.2) |
| | `TaskKeys` | project key rule, `formatKey("HR", 12)` → `HR-12`, `parseKey("hr-12")` |
| | `Mentions` | find `@{uuid}` tokens in text (§10.3) |
| | `WorkflowRules` | a workflow must keep ≥1 `TODO` and ≥1 `DONE` status, ≤ 12 statuses, unique names |
| | `TaskDueDates` | `isOverdue(dueDate, dueTime, taskTimeZone, now)`, `isDueTomorrow`, `isDueToday`, bucket (`OVERDUE`, `TODAY`, `THIS_WEEK`, `LATER`, `NO_DATE`) — §8.6 |
| | v2: `TaskRecurrenceRules` | next occurrence date |

Records (nested in the service, like `AssetService.AssetInput`): `ProjectInput`, `ProjectStatusInput`, `ProjectMemberInput`,
`WorkflowStatusInput`, `WorkflowStatusOrderInput`, `TaskInput`, `TaskPositionInput`, `ChecklistItemInput`, `CommentInput`, `LabelInput`.
`PATCH /api/v1/tasks/{taskId}` takes a Jackson `JsonNode` body so "field absent" (unchanged) and `null` (clear it) can be told apart; `TaskService` turns it into a `TaskChanges` record.

Validation messages are user-facing English sentences (e.g. "Enter a title.", "Keep the title under 200 characters.") thrown as
`UserException(HttpStatus.BAD_REQUEST, …)` for bad input and `UserException(…)` (422) for business rules, using `common/Check.java`
(`uuid`, `oneOf`, `trimmed`, `isoDate`). Each new message needs a Hindi entry in the frontend's `src/lib/i18n/messages/hi-api.ts` (the app translates fixed API messages).

---

## 7. Data model

All tables: `id UUID` primary key generated in Java (`UUID.randomUUID()`, as assets), `business_id UUID NOT NULL DEFAULT jurisone_tenant_id() REFERENCES businesses(id) ON DELETE CASCADE ON UPDATE CASCADE`,
row-level security with policy `tenant_isolation` (block copied from `V11__assets.sql`), `GRANT SELECT, INSERT, UPDATE, DELETE … TO jurisone_tenant`.
Text enums are `TEXT` + `CHECK` constraints (as `assets_status_check`), not PG enum types. Timestamps `TIMESTAMPTZ(6)`.

### 7.1 MVP tables (V20)

**`projects`**

| Column | Type | Notes |
|---|---|---|
| id, business_id | UUID | |
| key | TEXT NOT NULL | `^[A-Z][A-Z0-9]{1,5}$`; unique per business: `UNIQUE INDEX projects_business_key_key (business_id, key)`; cannot change once tasks exist (keys are in links) |
| name | TEXT NOT NULL | 1–80 chars; unique per business, case-insensitive (`lower(name)`) |
| description | TEXT | ≤ 2,000 |
| visibility | TEXT NOT NULL DEFAULT 'PRIVATE' | CHECK `PRIVATE`, `BUSINESS` |
| status | TEXT NOT NULL DEFAULT 'ACTIVE' | CHECK `ACTIVE`, `ARCHIVED` |
| color | TEXT | one of a fixed palette name (`purple`, `blue`, `green`, `amber`, `red`, `grey`) |
| department_id | UUID NULL | FK departments, ON DELETE SET NULL (filter/report only) |
| next_task_number | INTEGER NOT NULL DEFAULT 1 | numbering (§8.1) |
| created_by | UUID | employee |
| created_at, updated_at | TIMESTAMPTZ | |
| archived_at | TIMESTAMPTZ NULL | |
| deleted_at, deleted_by | TIMESTAMPTZ / UUID NULL | soft delete (only an empty project can be deleted) |
| version | INTEGER NOT NULL DEFAULT 0 | optimistic lock for project edits |

Indexes: `(business_id, status)`.

**`project_members`** — PK `(project_id, employee_id)`; columns `business_id`, `project_id` FK CASCADE, `employee_id` FK employees RESTRICT,
`role` TEXT CHECK `OWNER`,`MEMBER`,`VIEWER`, `added_by`, `added_at`. Index `(employee_id)`.

**`task_statuses`** (workflow steps)

| Column | Type | Notes |
|---|---|---|
| id, business_id | UUID | |
| project_id | UUID NULL | FK projects CASCADE; **NULL = the business default set** (used by personal tasks and copied into new projects) |
| name | TEXT NOT NULL | 1–30 chars; unique per set: `UNIQUE (business_id, coalesce(project_id, '00000000-0000-0000-0000-000000000000'), lower(name))` |
| category | TEXT NOT NULL | CHECK `TODO`, `IN_PROGRESS`, `DONE` |
| position | INTEGER NOT NULL | order of columns |
| color | TEXT | palette name |
| is_initial | BOOLEAN NOT NULL DEFAULT false | the status new tasks get; exactly one per set (partial unique index `WHERE is_initial`) |

Built-in default set (constant in `WorkflowRules.DEFAULT_STATUSES`): **To do** (TODO, initial), **In progress** (IN_PROGRESS), **In review** (IN_PROGRESS), **Done** (DONE), **Cancelled** (DONE).
`WorkflowStatusService.ensureDefaultStatuses()` inserts the business default set from that constant on the first write that needs it
(`INSERT … ON CONFLICT DO NOTHING` on the unique index, so two parallel requests are safe). No `GET` writes it; no change is needed where businesses are created (`BusinessSetupService`), and test businesses made by `TestData` work too.

**`tasks`**

| Column | Type | Notes |
|---|---|---|
| id, business_id | UUID | |
| project_id | UUID NULL | FK projects RESTRICT; NULL = personal task |
| number | INTEGER NULL | per project; NULL for personal tasks; `UNIQUE (project_id, number)` |
| previous_keys | TEXT[] NOT NULL DEFAULT '{}' | keys the task had before it moved project (old links keep working) |
| parent_task_id | UUID NULL | FK tasks; one level only (enforced in service) |
| title | TEXT NOT NULL | 1–200 |
| description | TEXT | ≤ 20,000 |
| type | TEXT NOT NULL DEFAULT 'TASK' | CHECK `TASK`, `ISSUE`, `REQUEST`, `MILESTONE` |
| status_id | UUID NOT NULL | FK task_statuses RESTRICT; must belong to the task's project set (or the default set for personal tasks) |
| priority | TEXT NOT NULL DEFAULT 'NONE' | CHECK `URGENT`, `HIGH`, `MEDIUM`, `LOW`, `NONE` |
| assignee_id | UUID NULL | **direct assignee**; FK employees RESTRICT |
| indirect_assignee_id | UUID NULL | **indirect assignee**; FK employees RESTRICT; CHECK `indirect_assignee_id IS NULL OR indirect_assignee_id <> assignee_id` |
| reporter_id | UUID NOT NULL | FK employees RESTRICT (creator) |
| start_date, due_date | DATE NULL | CHECK `due_date IS NULL OR start_date IS NULL OR due_date >= start_date` |
| due_time | TIME NULL | optional due time; CHECK `due_time IS NULL OR due_date IS NOT NULL`. Chosen over one `timestamptz` + flag because a date-only task must stay a plain calendar date (no zone shift), and the time is read in the task time zone at check time, so changing the direct assignee re-interprets it consistently (§8.6). |
| estimate_minutes | INTEGER NULL | CHECK 0–100,000 |
| position | BIGINT NOT NULL | order inside (project, status) (§8.2) |
| completed_at | TIMESTAMPTZ NULL | set when the status category becomes `DONE`, cleared when it leaves |
| completed_by | UUID NULL | |
| due_soon_notified_for, due_today_notified_for, overdue_notified_for | DATE NULL | reminder idempotency (§14) |
| reminder_before_minutes | INTEGER NULL | optional extra reminder, 15/60/240/1440 minutes before the due moment; needs `due_date` |
| reminder_notified_for | TIMESTAMPTZ NULL | the due moment the extra reminder was sent for (§14) |
| created_at, updated_at | TIMESTAMPTZ | |
| deleted_at, deleted_by | TIMESTAMPTZ / UUID NULL | soft delete |
| version | INTEGER NOT NULL DEFAULT 0 | optimistic lock (§8.3) |
| search_vector | tsvector | `GENERATED ALWAYS AS (to_tsvector('simple', coalesce(title,'') || ' ' || coalesce(description,''))) STORED` |

Indexes (all `WHERE deleted_at IS NULL`):
- `tasks_project_board_idx (project_id, status_id, position)`
- `tasks_assignee_open_idx (assignee_id, due_date) WHERE completed_at IS NULL AND deleted_at IS NULL`
- `tasks_indirect_assignee_open_idx (indirect_assignee_id, due_date) WHERE completed_at IS NULL AND deleted_at IS NULL`
- `tasks_reporter_idx (reporter_id)`
- `tasks_parent_idx (parent_task_id)`
- `tasks_business_due_idx (business_id, due_date)`
- `tasks_search_idx USING GIN (search_vector)`

**`task_labels`** — `id`, `business_id`, `project_id` NULL (NULL = business-wide) FK CASCADE, `name` (1–40), `color`, `created_at`; unique `(business_id, coalesce(project_id, zero-uuid), lower(name))`.
**`task_label_links`** — PK `(task_id, label_id)`, `business_id`; both FK CASCADE; index `(label_id)`.
**`task_watchers`** (followers) — PK `(task_id, employee_id)`, `business_id`, `added_at`; index `(employee_id)`.
**`task_checklist_items`** — `id`, `business_id`, `task_id` FK CASCADE, `text` (1–300), `is_done` BOOLEAN, `position` INTEGER, `done_by`, `done_at`, `created_at`; index `(task_id, position)`.
**`task_comments`** — `id`, `business_id`, `task_id` FK CASCADE, `author_id` FK employees, `body` (1–5,000), `mentioned_employee_ids UUID[] NOT NULL DEFAULT '{}'`, `created_at`, `edited_at`, `deleted_at`, `deleted_by`; index `(task_id, created_at)`.
**`task_attachments`** — `id`, `business_id`, `task_id` FK CASCADE, `file_path` (relative to storage dir), `file_name` (≤120), `content_type`, `size_bytes`, `sha256`, `uploaded_by`, `uploaded_at`, `deleted_at`, `deleted_by`; index `(task_id)`.
**`task_activity`** — `id`, `business_id`, `task_id` FK CASCADE, `project_id` NULL, `actor_id` NULL (NULL = system/job), `kind` TEXT (CHECK list in §9), `before` JSONB, `after` JSONB, `occurred_at` TIMESTAMPTZ; index `(task_id, occurred_at DESC)`, `(project_id, occurred_at DESC)`.

### 7.2 v2 tables (V21)

| Table | Columns (besides id, business_id, created_at) |
|---|---|
| `task_dependencies` | `task_id` (the blocked task), `depends_on_task_id` (the blocker), `created_by`; PK `(task_id, depends_on_task_id)`; CHECK they differ; no cycles (service, §8.5) |
| `task_recurrences` | `source_task_id` (the task copied each time), `frequency` CHECK `DAILY`,`WEEKLY`,`MONTHLY`,`YEARLY`, `interval_count` (1–52), `weekdays` SMALLINT[] (1=Mon…7=Sun), `day_of_month` (1–31, last day if shorter), `next_due_on` DATE, `create_days_before` (0–30, default 3), `ends_on` DATE NULL, `is_active`, `created_by` |
| `tasks.recurrence_id` + `tasks.occurrence_due_on` | new columns; `UNIQUE (recurrence_id, occurrence_due_on)` makes generation idempotent |
| `task_templates` | `project_id` NULL (NULL = business-wide), `name` (1–80), `content` JSONB (`title`, `description`, `type`, `priority`, `labelIds`, `estimateMinutes`, `dueInDays`, `checklist[]`, `subtasks[{title, dueInDays}]`), `created_by`, `updated_at` |
| `task_saved_filters` | `employee_id` (owner of the filter), `name` (1–60), `page` CHECK `MY_TASKS`,`TEAM_TASKS`,`PROJECT`, `project_id` NULL, `query` TEXT (the query string, e.g. `statusCategory=TODO&priority=HIGH`), `is_shared_with_project` BOOLEAN |

### 7.3 v3 tables (V22)

| Table | Columns |
|---|---|
| `task_custom_fields` | `project_id`, `name` (1–40), `field_type` CHECK `TEXT`,`NUMBER`,`DATE`,`SELECT`,`CHECKBOX`,`PERSON`, `options` JSONB (for SELECT), `is_required`, `position` |
| `task_custom_field_values` | PK `(task_id, field_id)`, `value` JSONB |
| `task_types` (if configurable types are wanted) | `project_id` NULL, `name`, `icon`; `tasks.type` then references it |

The later email-alerts phase adds its own table (`task_notification_preferences`, §20).

---

## 8. Rules

### 8.1 Numbering (`HR-12`)
- On create in a project: `UPDATE projects SET next_task_number = next_task_number + 1 WHERE id = ? RETURNING next_task_number - 1` (row lock serialises parallel creates; no gaps except after a rolled-back transaction, which is acceptable).
- The key is computed, not stored: `projects.key || '-' || tasks.number`. `GET /api/v1/tasks/{taskRef}` accepts a UUID **or** a key (case-insensitive), and also finds a key in `previous_keys`.
- Moving a task to another project gives it a new number there and appends the old key to `previous_keys`.

### 8.2 Card order (`engine/TaskPositions`)
- `position` is a `BIGINT` with gaps of `GAP = 65_536`. A new task goes to the bottom of its column: `max(position) + GAP`.
- Moving between two cards: `(previous + next) / 2`. When there is no room (`next - previous < 2`), renumber that column (`UPDATE … SET position = row_number() * GAP` ordered by the current position) in the same transaction, then place the card.
- Moving to the top: `next - GAP` (positions may be negative). Only open (non-deleted) tasks are renumbered.
- Columns are per `(project_id, status_id)`. On **My tasks** board (cross-project) the columns are the three categories; the cards are sorted by due date and priority, and dropping a card on a category sets the task to its project's first status of that category (the `is_initial` one for `TODO`, else the lowest `position`) through `PATCH /api/v1/tasks/{taskId}` with `statusCategory`. No manual order on My tasks.
- A position change alone writes no activity row (too noisy); a status change does. A move needs `canChangeStatus` (so the indirect assignee may move cards).

### 8.3 Concurrency
- `PATCH /api/v1/tasks/{taskId}` and `PUT /api/v1/projects/{projectId}` require the client's `version`; the update is `… WHERE id = ? AND version = ?` and increments `version`. 0 rows → **409** `CONFLICT` "Someone else changed this task. Reload to see their changes." The response returns the new `version`.
- Board moves (`PUT …/position`), comments, checklist items and watchers do not need a version (they do not overwrite each other's fields); last write wins.

### 8.4 Workflow
- At least one `TODO` and one `DONE` status, at most 12, exactly one `is_initial` (a `TODO` one), unique names (`WorkflowRules`).
- Deleting a status that still has tasks needs `moveTasksToStatusId` (same project), else **422**.
- Status change → category `DONE` sets `completed_at/by`; leaving `DONE` clears them. Closing a parent with open sub-tasks is allowed (the app warns). In v2, moving a task to `DONE` while a blocker is not `DONE` → **422** "Finish the tasks this one depends on first: HR-3."

### 8.5 Other rules
- Sub-tasks: the parent must be in the same project (or both personal), not deleted, and not itself a sub-task; ≤ 100 sub-tasks per parent.
- Assignees: both must be active employees of the business; they must differ (400 "The accountable person must be someone other than the assignee.").
- Dependencies (v2): same business; adding `A depends on B` is refused (422) if B already (transitively) depends on A — check with a recursive query, depth ≤ 50.
- Archived project: read-only (writes → 422 "This project is archived. Restore it to make changes."). Its tasks disappear from My tasks unless the filter asks for archived projects.
- Deleting a task soft-deletes its sub-tasks too. Deleting a project is allowed only when it has no tasks (else archive) — like `AssetService.delete` — and is a soft delete as well. Nothing in any phase hard-deletes tasks, comments, attachments, stored files or projects (owner's decision).
- Due time without a due date → 400 "Choose a due date first."; `dueTime` format `HH:mm` (24 h, minutes 00–59). Clearing `dueDate` also clears `dueTime`.
- Limits: title 200; description 20,000; comment 5,000; checklist item 300 and 100 items per task; 20 labels per task; 50 labels per project; 50 watchers; 20 attachments per task; 12 statuses; 500 members per project; project key 2–6.

### 8.6 Due date, due time and the task time zone (`engine/TaskDueDates`)
- **Task time zone** = the direct assignee's `employees.time_zone`; if there is no direct assignee, the indirect assignee's; if neither, the reporter's. Rows return it as `dueTimeZone` so the app can say "17:00 (New York time)" when it differs from the viewer's zone.
- **Due moment (owner's rule):** The due time is optional: if a time is given, the task is due at that time; otherwise the task is due at the **end of that day** (23:59:59 in the task time zone), so it becomes overdue at the start of the next day. A date-only task is never treated as due at 00:00 or at 09:00.
- **Overdue:** a date-only task is overdue when `today(taskTimeZone) > dueDate` (i.e. from the day after); a timed task when `now >= dueDate + dueTime` in the task time zone. Completed (`DONE`) and deleted tasks are never overdue.
- **Due today / due tomorrow / buckets:** computed against `today(taskTimeZone)`; `THIS_WEEK` = the rest of the current Monday–Sunday week, `LATER` after that.
- **Sorting** by due: `due_date`, then `due_time`; within the same day a **date-only task sorts after the timed tasks** (it is due at the end of the day); tasks with no due date last.
- Changing the direct assignee re-reads the same date/time in the new person's zone (stated in the UI hint "Due times are in the assignee's time zone").

---

## 9. Activity history and audit

Two records, written together by `TaskActivityService.record(user, taskRow, kind, before, after, ipAddress)` inside the caller's transaction:

1. **`task_activity`** — what people see in the task's History tab (who, when, what changed). Kinds:
   `CREATED`, `TITLE_CHANGED`, `DESCRIPTION_CHANGED`, `TYPE_CHANGED`, `STATUS_CHANGED`, `PRIORITY_CHANGED`, `ASSIGNEE_CHANGED`, `INDIRECT_ASSIGNEE_CHANGED`, `DATES_CHANGED`, `ESTIMATE_CHANGED`,
   `LABELS_CHANGED`, `PARENT_CHANGED`, `MOVED_PROJECT`, `CHECKLIST_CHANGED`, `ATTACHMENT_ADDED`, `ATTACHMENT_REMOVED`, `COMMENT_DELETED`, `DELETED`,
   v2: `RESTORED`, `DEPENDENCY_ADDED`, `DEPENDENCY_REMOVED`, `RECURRENCE_CHANGED`, `CREATED_FROM_RECURRENCE`, `CREATED_FROM_TEMPLATE`, `IMPORTED`.
   Comments themselves are listed from `task_comments`; the app merges both lists by time.
2. **`audit_log`** via `AuditService.log(actorId, action, entity, entityId, before, after, ipAddress)` (as `AssetService`). Actions:
   `project.create`, `project.update`, `project.archive`, `project.restore`, `project.delete`, `project.member_add`, `project.member_role`, `project.member_remove`, `project.private_access`,
   `workflow_status.create`, `workflow_status.update`, `workflow_status.order`, `workflow_status.delete`, `task_label.create`, `task_label.update`, `task_label.delete`,
   `task.create`, `task.update`, `task.delete`, `task.private_access`, `task.comment_add`, `task.comment_edit`, `task.comment_delete`, `task.attachment_add`, `task.attachment_delete`,
   v2: `task.restore`, `task.bulk_update`, `task.import`, `task_template.*`, `task.recurrence_set`, `task.recurrence_remove`.
   `before`/`after` carry only the changed fields; long text (description, comment body) is recorded as `{"length": n}`, never the full text.

- `*.private_access` rows are written when the Owner opens a private project/task they are not part of (§4.3).
- The request-level row (method, path, status, duration) is already written for every write by `security/AuditTrailFilter`; the export (`/api/v1/tasks/export`) matches its existing `SENSITIVE_GETS` pattern `^/api/v1/.*/export(/.*)?$`. Add `^/api/v1/tasks/[^/]+/attachments/[^/]+/file$` to `security/AuditTrail.SENSITIVE_GETS` so attachment downloads are recorded too.
- `AuditViewService.GROUPS`: add `GROUPS.put("tasks", List.of("project.", "task.", "workflow_status.", "task_label.", "task_template."))`; the frontend adds labels in `src/lib/audit-labels.ts`.
- Bulk changes (v2) write one activity row per task and one `task.bulk_update` audit row listing the task ids.

---

## 10. Notifications (bell only)

**No emails** in MVP or v2 (owner's decision 2026-10-23). Everything below goes to the bell and the `/notifications` page. Email alerts are a later phase (§20).

### 10.1 Events and recipients

Participants of a task = its followers (watchers). The reporter, **both assignees**, every commenter and every mentioned person (who can see the task) are **auto-added as followers**; anyone can stop following.
The actor never notifies themself. One request sends **at most one notification per person** (the most important event wins: mention > assignment > comment > status).

| Event | Recipients | Text (stored in English, like every bell notification today) |
|---|---|---|
| Assigned (direct) | new direct assignee | "{actor} assigned you {key}: {title}" |
| Made accountable (indirect) | new indirect assignee | "{actor} made you accountable for {key}: {title}" |
| Mentioned (description or comment) | mentioned people who can see the task | "{actor} mentioned you on {key}: {title}" |
| New comment | followers | "{actor} commented on {key}: {title}" |
| Status changed | followers | "{key} moved to {status} by {actor}" |
| Due tomorrow (job, 09:00) | direct and indirect assignee | "{key} is due tomorrow: {title}" (timed: "… due tomorrow at {time}: {title}") |
| Due today (job, 09:00, timed tasks only) | direct and indirect assignee | "{key} is due today at {time}: {title}" |
| Overdue (job, once per due date/time) | direct and indirect assignee | "{key} is overdue (due {date}): {title}" (timed: "(due {date} {time})") |
| Added to project | the new member | "You were added to the project {project}" |

Personal tasks show "Personal task" instead of `{key}`. Link: `/tasks/{key}` (or `/tasks/{uuid}` for personal tasks).

### 10.2 How (reuse)
- `NotificationService.notify(employeeId, text, link)` (text cut to 300 chars; joins the transaction, so a rolled-back change leaves no notification; shows in the bell and `/notifications`).
- No `MailService` call anywhere in the task module until the email-alerts phase.

### 10.3 Mentions
- Stored text contains `@{employee-uuid}` tokens (the app's mention picker inserts them and shows the name). `engine/Mentions.find(text)` returns the UUIDs.
- On save: each mentioned id must be an active employee of the business; people who cannot see the task are **not** notified and are not granted access (the app's picker only offers people who can see the task: project members, or everyone for a `BUSINESS` project; on personal tasks: participants).
- `task_comments.mentioned_employee_ids` keeps the list (to render and for "mentions of me" filters later). Responses include a `mentionedPeople` map `{id: name}` so the app can render names.

---

## 11. Attachments

Copy `OnboardingService.candidateUpload` (size check, magic-byte sniffing, file name cleaning, `storageDir.relativize`, SHA-256 via `PolicyService.sha256`).

| Item | Value |
|---|---|
| Endpoint | `POST /api/v1/tasks/{taskId}/attachments`, `multipart/form-data`, part `file` (like `OnboardingController`). |
| Who | `canEdit`, the direct assignee, or the reporter/direct assignee of a personal task (the indirect assignee only when also a project member). |
| Folder | `${jurisone.storage-dir}/tasks/<businessId>/<taskId>/<uuid>.<ext>` |
| Size | ≤ **10 MB** per file (`OnboardingService.MAX_FILE_MB`), ≤ 20 per task; the server limit is already 50 MB (`spring.servlet.multipart.max-file-size`). |
| Types | By magic bytes: PDF, PNG, JPG (existing `FILES` list), plus DOCX/XLSX/PPTX (`PK\x03\x04` **and** extension `.docx/.xlsx/.pptx`), plus `.txt`/`.csv` (extension + valid UTF-8, no NUL bytes). Anything else → 400 "Upload a PDF, image, Word, Excel, PowerPoint, text or CSV file." |
| Download | `GET /api/v1/tasks/{taskId}/attachments/{attachmentId}/file` with `Content-Disposition: attachment` (inline for PDF/images), `X-Content-Type-Options: nosniff`; path checked with `absolutePath.startsWith(storageDir)` as in `OnboardingService`. |
| Delete | Soft (`deleted_at`); the file stays for the audit trail. Uploader, project owner, or the Owner. |
| Reset/seed | `SeedService.clearStoredFiles()` must also clear `tasks/`. |

---

## 12. REST API

Conventions: success body `ApiResult` (`{ok, message?, data?}`, `common/ApiResult.java`); `POST` returns **201** with `data.id` (and `data.key` for tasks/projects); `DELETE` returns **200** with a message (the existing convention, e.g. `AssetController.delete`).
Errors: problem+json with default codes from `Problems.defaultCode` (400 `VALIDATION_FAILED`, 403 `FORBIDDEN`, 404 `NOT_FOUND`, 409 `CONFLICT`, 422 `BUSINESS_RULE`), plus the existing 402 `SUBSCRIPTION_INACTIVE` and 428 `POLICY_ACK_REQUIRED` from the filters. No new codes.
"Page" = `auth.requirePage(navId)` in the controller. Every write needs the Origin header (existing `OriginCheckFilter`).

### 12.1 MVP endpoints

**Projects** (page `projects`)

| # | Method + path | Who | Query / body | Success | Errors |
|---|---|---|---|---|---|
| P1 | `GET /api/v1/projects` | page; returns only visible projects (Owner: all) | `status=ACTIVE\|ARCHIVED\|ALL` (default ACTIVE), `search`, `sort=name\|key\|openTasks\|updatedAt`, `sortDirection`, `page` | 200 `{page, take, total, rows:[ProjectRow]}` | — |
| P2 | `POST /api/v1/projects` | `projects.create` or `tasks.manage` | `{key, name, description?, visibility, color?, departmentId?}` | 201 `{id, key}` | 400, 403, 409 key/name taken |
| P3 | `GET /api/v1/projects/{projectId}` | can view (Owner private access is audited) | — | 200 `ProjectDetail` | 404 |
| P4 | `PUT /api/v1/projects/{projectId}` | project owner | `{version, name, description, visibility, color, departmentId}` (key only while the project has no tasks) | 200 `{version}` | 400, 403, 404, 409 stale/taken, 422 archived |
| P5 | `PUT /api/v1/projects/{projectId}/status` | project owner | `{status: "ACTIVE"\|"ARCHIVED"}` | 200 | 403, 404 |
| P6 | `DELETE /api/v1/projects/{projectId}` | project owner | — (soft delete) | 200 | 422 has tasks |
| P7 | `GET /api/v1/projects/{projectId}/members` | can view | — | 200 `[{employeeId, code, name, photo, designation, role, isActive}]` | 404 |
| P8 | `POST /api/v1/projects/{projectId}/members` | project owner | `{employeeId, role}` | 201 | 400, 404, 409 already a member |
| P9 | `PUT /api/v1/projects/{projectId}/members/{employeeId}` | project owner | `{role}` | 200 | 422 last owner |
| P10 | `DELETE /api/v1/projects/{projectId}/members/{employeeId}` | project owner, or the member themself (leave) | — | 200 | 422 last owner |
| P11 | `POST /api/v1/projects/{projectId}/workflow-statuses` | project owner | `{name, category, color?}` | 201 `{id}` | 400, 409 name, 422 > 12 |
| P12 | `PUT /api/v1/projects/{projectId}/workflow-statuses/{statusId}` | project owner | `{name, category, color, isInitial}` | 200 | 422 would break WorkflowRules |
| P13 | `PUT /api/v1/projects/{projectId}/workflow-status-order` | project owner | `{statusIds:[…]}` (all ids, new order) | 200 | 400 ids differ |
| P14 | `DELETE /api/v1/projects/{projectId}/workflow-statuses/{statusId}` | project owner | `?moveTasksToStatusId=` | 200 | 422 tasks without target / last TODO/DONE |
| P15 | `GET /api/v1/projects/{projectId}/board` | can view | `assigneeId`, `indirectAssigneeId` (uuid or `none`), `labelId`, `priority`, `type`, `search`, `doneWithinDays` (default 14, max 90) | 200 `{project, columns:[{status, taskCount, tasks:[TaskRow]}], hasMoreDone}` (≤ 500 open cards; DONE columns limited by days) | 404 |

**Labels** (page `projects`)

| # | Method + path | Who | Body / query | Success | Errors |
|---|---|---|---|---|---|
| L1 | `GET /api/v1/task-labels` | page | `projectId?` → business labels + that project's | 200 `[{id, name, color, projectId}]` | — |
| L2 | `POST /api/v1/task-labels` | `tasks.manage` (business label) / project owner (`projectId` given) | `{name, color, projectId?}` | 201 `{id}` | 409 name |
| L3 | `PUT /api/v1/task-labels/{labelId}` | same | `{name, color}` | 200 | 404, 409 |
| L4 | `DELETE /api/v1/task-labels/{labelId}` | same | — | 200 (links removed) | 404 |

**Tasks** (page `my-tasks`; `/team` uses page `team-tasks`)

| # | Method + path | Who | Query / body | Success | Errors |
|---|---|---|---|---|---|
| T1 | `GET /api/v1/tasks` | page; only visible tasks (Owner: all) | task filters (§13.1) incl. `projectId` | 200 `{page, take, total, rows:[TaskRow]}` | — |
| T2 | `GET /api/v1/tasks/mine` | page | `relation=ASSIGNED\|DIRECT\|INDIRECT\|REPORTED\|WATCHING` (default `ASSIGNED` = direct or indirect) + task filters | 200 `{page, take, total, rows, counts:{overdue, today, thisWeek, later, noDate}}` | — |
| T3 | `GET /api/v1/tasks/team` | page `team-tasks` (has reports) | `depth=1\|2` (default 1), `employeeId?` + task filters (incl. `indirectAssigneeId`) | 200 `{page, take, total, rows, people:[{employeeId, name, code, photo, depth, open, overdue, doneLast7Days}]}` | 403 no reports |
| T4 | `GET /api/v1/tasks/assignees` | page | `projectId?`, `search` (≥ 1 char, ILIKE on name/code), max 20 | 200 `[{id, code, name, photo, designation, isMember, reportingManagerId}]` | — |
| T5 | `POST /api/v1/tasks` | project `OWNER`/`MEMBER` (or no project = anyone) | `TaskInput` (below) | 201 `{id, key}` | 400, 403, 404 project, 422 archived/parent rules |
| T6 | `GET /api/v1/tasks/{taskRef}` | can view (`taskRef` = uuid or key; Owner private access is audited) | — | 200 `TaskDetail` | 404 |
| T7 | `PATCH /api/v1/tasks/{taskId}` | field-level rights (§4.3, §4.4) | `{version, title?, description?, type?, statusId? \| statusCategory?, priority?, assigneeId?, indirectAssigneeId?, startDate?, dueDate?, dueTime?, estimateMinutes?, reminderBeforeMinutes?, labelIds?, parentTaskId?, projectId?}`; `null` clears; `statusCategory` (`TODO`/`IN_PROGRESS`/`DONE`) is an alternative to `statusId` (never both) and maps to the project's first status of that category (§8.2) — used by the My tasks checkbox and board | 200 `{version}` | 400, 403 (field not allowed), 404, 409 stale, 422 rules |
| T8 | `PUT /api/v1/tasks/{taskId}/position` | `canChangeStatus` | `{statusId, previousTaskId?, nextTaskId?}` (neighbours after the drop; both absent = bottom) | 200 `{statusId, position}` | 400 neighbour not in that column, 403, 404 |
| T9 | `DELETE /api/v1/tasks/{taskId}` | project owner, reporter-member, personal reporter, the Owner (not the indirect assignee unless project owner) | — | 200 | 403, 404 |
| T10 | `PUT /api/v1/tasks/{taskId}/watchers/{employeeId}` | self: anyone who can view; others: `canEdit` | — | 200 (idempotent) | 422 that person can't see the task |
| T11 | `DELETE /api/v1/tasks/{taskId}/watchers/{employeeId}` | self, or `canEdit` | — | 200 | — |
| T12 | `POST /api/v1/tasks/{taskId}/checklist-items` | `canEdit` or the direct assignee | `{text}` | 201 `{id}` | 400, 422 > 100 |
| T13 | `PATCH /api/v1/tasks/{taskId}/checklist-items/{itemId}` | same | `{text?, isDone?, position?}` | 200 | 404 |
| T14 | `DELETE /api/v1/tasks/{taskId}/checklist-items/{itemId}` | same | — | 200 | 404 |

`TaskInput` = `{projectId?, title, description?, type?, statusId?, priority?, assigneeId?, indirectAssigneeId?, startDate?, dueDate?, dueTime?, estimateMinutes?, reminderBeforeMinutes?, labelIds?, parentTaskId?, watcherIds?, checklist?: [text]}`; v2 adds `templateId?`.

**Collaboration** (page `my-tasks`)

| # | Method + path | Who | Query / body | Success | Errors |
|---|---|---|---|---|---|
| C1 | `GET /api/v1/tasks/{taskId}/comments` | can view | `page` (50 per page, oldest first) | 200 `{page, take, total, rows:[{id, authorId, authorName, authorPhoto, body, mentionedPeople, createdAt, editedAt, isDeleted, canEdit, canDelete}]}` | 404 |
| C2 | `POST /api/v1/tasks/{taskId}/comments` | `canComment` | `{body}` | 201 `{id}` | 400, 404 |
| C3 | `PATCH /api/v1/tasks/{taskId}/comments/{commentId}` | author | `{body}` | 200 | 403, 404 |
| C4 | `DELETE /api/v1/tasks/{taskId}/comments/{commentId}` | author, project owner, the Owner | — | 200 (body hidden, "Comment deleted" stays) | 403, 404 |
| C5 | `GET /api/v1/tasks/{taskId}/activity` | can view | `page` (50, newest first) | 200 `{page, take, total, rows:[{id, kind, actorName, before, after, occurredAt}]}` | 404 |
| C6 | `POST /api/v1/tasks/{taskId}/attachments` | §11 | multipart `file` | 201 `{id, name}` | 400 type/size, 422 > 20 |
| C7 | `GET /api/v1/tasks/{taskId}/attachments/{attachmentId}/file` | can view | — | 200 file bytes | 404 |
| C8 | `DELETE /api/v1/tasks/{taskId}/attachments/{attachmentId}` | §11 | — | 200 | 403, 404 |

**Settings** (page `task-settings`, permission `tasks.manage`)

| # | Method + path | Body | Success |
|---|---|---|---|
| S1 | `GET /api/v1/task-settings` | — | 200 `{defaultStatuses:[…], labels:[…], projectCount, taskCount}` (v2 adds `templates`) |
| S2 | `POST /api/v1/task-settings/workflow-statuses` | `{name, category, color?}` | 201 `{id}` |
| S3 | `PUT /api/v1/task-settings/workflow-statuses/{statusId}` | `{name, category, color, isInitial}` | 200 |
| S4 | `PUT /api/v1/task-settings/workflow-status-order` | `{statusIds}` | 200 |
| S5 | `DELETE /api/v1/task-settings/workflow-statuses/{statusId}` | `?moveTasksToStatusId=` (personal tasks) | 200 |

Changing the default set affects personal tasks and **new** projects only (existing projects keep their copy).

**Shell:** `GET /api/v1/shell` (existing) gains `badges.myTasks` (§3.3). No new view-model endpoint.

### 12.2 v2 endpoints

| Method + path | Who | Purpose |
|---|---|---|
| `PATCH /api/v1/tasks` | per task, field-level rights | Bulk: `{taskIds:[≤100], changes:{statusId?, assigneeId?, indirectAssigneeId?, priority?, dueDate?, dueTime?, addLabelIds?, removeLabelIds?, isDeleted?}}` → 200 `{updated, skipped:[{taskId, reason}]}` (precedent: `PATCH /api/v1/notifications` marks all) |
| `GET /api/v1/tasks/export` | page | Same filters as `GET /api/v1/tasks` (+ `scope=MINE\|TEAM\|PROJECT`), CSV via `engine/Csv.toCsv`, ≤ 50,000 rows (`AuditViewService.EXPORT_MAX` pattern) |
| `POST /api/v1/projects/{projectId}/task-imports` | project owner/member | multipart CSV (`title, description, type, status, priority, assigneeEmail, indirectAssigneeEmail, startDate, dueDate, dueTime, estimateMinutes, labels, parentKey`); `?isDryRun=true` validates only → 200 `{rows, errors:[{line, message}]}`; real run → 201 `{created, errors}`; ≤ 2,000 rows |
| `GET /api/v1/tasks/deleted` | the Owner / project owners | Deleted tasks of the last 30 days |
| `POST /api/v1/tasks/{taskId}/restoration` | same | Restore a deleted task (an action modelled as creating a resource, like `/acknowledgements`) → 201 |
| `POST /api/v1/tasks/{taskId}/dependencies` / `DELETE /api/v1/tasks/{taskId}/dependencies/{dependsOnTaskId}` | `canEdit` | `{dependsOnTaskId}`; 422 on a cycle |
| `PUT /api/v1/tasks/{taskId}/recurrence` / `DELETE /api/v1/tasks/{taskId}/recurrence` | `canEdit` | `{frequency, intervalCount, weekdays?, dayOfMonth?, createDaysBefore?, endsOn?}` |
| `GET /api/v1/task-templates?projectId=` · `POST /api/v1/task-templates` · `GET`/`PUT`/`DELETE /api/v1/task-templates/{templateId}` | read: page; write: project owner / `tasks.manage` | Templates; `POST /api/v1/tasks {templateId, …}` creates from one |
| `GET /api/v1/task-filters/mine` · `POST /api/v1/task-filters` · `PUT`/`DELETE /api/v1/task-filters/{filterId}` | owner of the filter | Saved filters |
| `GET /api/v1/task-reports/summary` | page `task-reports` | `projectId?`, `departmentId?`, `assigneeId?`, `from`, `to` → `{kpis:{open, overdue, completed, averageDaysToComplete}, byStatus, byAssignee:[{employeeId, name, open, overdue, completed, estimateMinutes}], byIndirectAssignee:[{employeeId, name, open, overdue}], byProject, completedPerWeek:[{weekStart, count}], createdPerWeek}`; scope = projects the user can view (Owner: all) |

Calendar (v2) needs **no new endpoint**: it calls `GET /api/v1/tasks/mine`, `/api/v1/tasks/team` or `/api/v1/tasks?projectId=` with `dueFrom`, `dueTo` and `pageSize=500`.

### 12.3 v3 endpoints
`GET`/`POST /api/v1/projects/{projectId}/custom-fields`, `PUT`/`DELETE /api/v1/projects/{projectId}/custom-fields/{fieldId}`; values through `PATCH /api/v1/tasks/{taskId}` (`customFieldValues: {fieldId: value}`); timeline reads `GET /api/v1/tasks?projectId=&dueFrom=&dueTo=&pageSize=500` plus dependencies in `TaskRow` (`dependsOnTaskIds`).

### 12.4 Response shapes (JSON field names are binding for both repos)

`ProjectRow`: `id, key, name, description, visibility, status, color, departmentId, departmentName, ownerNames[], myRole (OWNER|MEMBER|VIEWER|null), memberCount, openTaskCount, overdueTaskCount, updatedAt`.

`ProjectDetail`: `ProjectRow` fields + `version, statuses:[{id, name, category, color, position, isInitial, taskCount}], labels:[{id, name, color, projectId}], canManage, canCreateTasks`.

`TaskRow`: `id, key, number, projectId, projectKey, projectName, parentTaskId, parentKey, title, type, statusId, statusName, statusCategory, statusColor, priority, assigneeId, assigneeName, assigneeCode, assigneePhoto, isAssigneeActive, indirectAssigneeId, indirectAssigneeName, indirectAssigneeCode, indirectAssigneePhoto, isIndirectAssigneeActive, myAssignment (DIRECT|INDIRECT|null), reporterId, reporterName, startDate, dueDate, dueTime, dueTimeZone, estimateMinutes, reminderBeforeMinutes (15|60|240|1440|null), isOverdue, labels:[{id, name, color}], subtaskCount, subtaskDoneCount, checklistCount, checklistDoneCount, commentCount, attachmentCount, position, completedAt, createdAt, updatedAt`.
(`assigneePhoto` / `indirectAssigneePhoto` = epoch millis of `employees.photo_updated_at`, the same marker the app's `Avatar` uses; `dueTime` is `HH:mm` or null; `dueTimeZone` is the task time zone (IANA) and `isOverdue` is computed with it, §8.6.)

`TaskDetail`: `TaskRow` fields + `description, version, reporterPhoto, completedByName, previousKeys, watchers:[{employeeId, name, photo}], isWatching, subtasks:[TaskRow], checklist:[{id, text, isDone, position, doneByName, doneAt}], attachments:[{id, fileName, contentType, sizeBytes, uploadedByName, uploadedAt, canDelete}], mentionedPeople:{id: name}, statuses:[…the project's set…], permissions:{canEdit, canComment, canChangeStatus, canChangePriority, canChangeDirectAssignee, canChangeIndirectAssignee, canDelete, canManageProject}`.

---

## 13. Lists, filters, search and performance

### 13.1 Task filters (same names on every task collection)

| Parameter | Values |
|---|---|
| `projectId` | uuid |
| `statusId` | uuid |
| `statusCategory` | `TODO`, `IN_PROGRESS`, `DONE`, comma-separated; default `TODO,IN_PROGRESS` on My/Team tasks |
| `assigneeId` | direct assignee: uuid or `none` |
| `indirectAssigneeId` | indirect assignee: uuid or `none` |
| `reporterId` | uuid |
| `priority` | comma list |
| `type` | comma list |
| `labelId` | uuid |
| `due` | `OVERDUE`, `TODAY`, `THIS_WEEK`, `LATER`, `NO_DATE` (each task in its task time zone, §8.6; `TaskDueDates`) |
| `dueFrom`, `dueTo` | ISO dates (calendar) |
| `parentTaskId` | uuid (sub-tasks of one task); default lists show top-level tasks and sub-tasks both, with `parentKey` |
| `isArchivedProjectIncluded` | `true` to include tasks of archived projects (default false) |
| `search` | text (§13.2) |
| `sort` | `dueDate` (default), `priority`, `updatedAt`, `createdAt`, `key`, `title`, `status`, `assignee` |
| `sortDirection` | `asc` / `desc` |
| `page`, `pageSize` | page from 1; `pageSize` default 50, max 500 |

`GET /api/v1/tasks/mine` also takes `relation` (§12.1 T2); `GET /api/v1/tasks/team` also takes `depth` and `employeeId`.

- Parse with `common/ListParams` (`ListParams.of(queryParams, SORTS, "dueDate", pageSize, "asc")`, invalid values fall back silently) and `ListParams.pick` for enums, the way `AuditViewService.build` does; build SQL in `repository/TaskQuery` with `?` arguments only (never string-concatenate values).
- Note: the codebase's list parameters are `sort` and `sortDirection` (`ListParams`, frontend `SortableHeader`). INSTRUCTIONS.md mentions `sortBy` as an example; the owner decided to keep `sort` / `sortDirection` (consistent with every existing table).
- Sorting by priority uses `CASE priority WHEN 'URGENT' THEN 0 … END`; sorting by due uses `due_date`, `due_time` (§8.6); nulls (no due date) last.
- SQL for `due`/`isOverdue` joins the direct (else indirect, else reporter) assignee's `time_zone` and compares with `now() AT TIME ZONE tz` (date-only: `due_date < (now() AT TIME ZONE tz)::date`; timed: `(due_date + due_time) <= now() AT TIME ZONE tz`); the same rule as `TaskDueDates`, covered by one test that compares both.

### 13.2 Search
- Text: `t.search_vector @@ to_tsquery('simple', :query)` where `:query` is built from the words as `word1:* & word2:*` (prefix match; words cleaned to letters/digits; empty → no filter). `simple` because text mixes English and Hindi.
- A search that looks like a key (`^[A-Za-z][A-Za-z0-9]{1,5}-\d+$`) matches that task directly (plus `previous_keys`).
- People pickers use ILIKE with escaped wildcards (`AuditViewService.like`).

### 13.3 Performance
- Target (REQUIREMENTS §7): pages < 2 s; design for 50,000 tasks and 500 employees per business.
- One query per list with `count(*)` for `total` (like `AuditViewService.query`); counts of comments/sub-tasks/checklist/attachments via `LEFT JOIN LATERAL (SELECT count(*) …)` or grouped sub-queries, not N+1.
- Visibility predicate (inlined in `TaskQuery`): `t.deleted_at IS NULL AND (:isTaskAdmin OR p.visibility = 'BUSINESS' OR EXISTS (member) OR t.assignee_id = :me OR t.indirect_assignee_id = :me OR t.reporter_id = :me OR EXISTS (watcher))`; team lists use the scope CTE instead.
- Board: one query for all open tasks of a project (≤ 500, else `hasMore` per column) + DONE tasks of the last `doneWithinDays`.
- The shell badge query runs on every shell load: keep it on the two `*_assignee_open_idx` indexes.

---

## 14. Jobs

Reuse the runner: `JobsService.runForBusiness(now)` (advisory lock 74202, every 10 minutes via `JobsScheduler`, business by business under `TenantContext`). Add entries that call `TaskJobs`; each returns a small summary map like the other jobs. All reminders are **bell notifications** (no email).

| Job | Phase | What | Idempotency |
|---|---|---|---|
| `tasks.dueSoon` | MVP | For open tasks with `due_date = tomorrow` in the task time zone, once the local time there is ≥ 09:00: bell "due tomorrow" (with the time if set) to the direct and the indirect assignee. | `tasks.due_soon_notified_for = due_date` (set in the same transaction); changing the due date re-arms it. Columns, not `job_runs` rows, to avoid one row per task per day. |
| `tasks.dueToday` | MVP | **Timed tasks only**: `due_date = today` in the task time zone, at/after 09:00 local and before the due time: bell "due today at {time}". | `tasks.due_today_notified_for = due_date`. |
| `tasks.overdue` | MVP | Date-only tasks: overdue from the start of the day after `due_date` (task time zone); the bell is sent at the first run at/after 09:00 local that day (09:00 is only the reminder time, not the due moment). Timed tasks: at the first run after the due time has passed (within 10 minutes; not waiting for 09:00). Bell "overdue" to the direct and the indirect assignee, once per due date. | `tasks.overdue_notified_for = due_date`; changing the date or time re-arms it (the service clears the three columns when `dueDate`/`dueTime` change). |
| `tasks.reminder` | MVP | **Optional extra reminder (Q15 = yes).** Open tasks with `reminder_before_minutes` (15, 60, 240 or 1440): when `now >= dueMoment - offset` and `now < dueMoment` in the task time zone, bell "{key} is due in {1 hour} (due {date} {time}): {title}" to the direct and the indirect assignee. The due moment is the due time, or the end of the due day (00:00 of the next day) for a date-only task (§8.6); the 09:00 rule does not apply. Not for done or deleted tasks. | `tasks.reminder_notified_for = dueMoment` (timestamp); the service clears it when `dueDate`/`dueTime`/`reminderBeforeMinutes` change. Summary key `reminders`. |
| `tasks.recurrence` | v2 | For active `task_recurrences` with `next_due_on - create_days_before <= today` (business time zone, `businesses.time_zone`): copy the source task (title, description, type, priority, labels, both assignees, checklist, estimate; new number; status = initial; due = `next_due_on`), then advance `next_due_on` by `TaskRecurrenceRules.next(...)`; stop after `ends_on`. Activity `CREATED_FROM_RECURRENCE`, audit actor NULL. | `UNIQUE (recurrence_id, occurrence_due_on)` + `ON CONFLICT DO NOTHING`. |

Time zone rule (decided by the owner): **the task time zone (direct assignee's; §8.6) decides "today", "tomorrow", "overdue", the due time and the 09:00 send time for both recipients** (one send, one idempotency column per reminder). The reminder hour is fixed at 09:00. The optional extra reminder before the due moment (Q15, decided yes) is separate from the 09:00 reminders, which are unchanged.

Jobs never notify exited or access-disabled employees. The e2e environment runs the jobs once after the seed (frontend `scripts/e2e-env.mjs`), so demo overdue tasks produce bell notifications.

---

## 15. Flyway migrations and rollbacks

Latest applied today: **V19** (`V19__employee_photos.sql`). Use the next free numbers at implementation time (if another migration lands first, shift these).

| File | Phase | Content |
|---|---|---|
| `V20__task_management.sql` | MVP | 1) Tables `projects`, `project_members`, `task_statuses`, `tasks` (with `assignee_id` and `indirect_assignee_id`), `task_labels`, `task_label_links`, `task_watchers`, `task_checklist_items`, `task_comments`, `task_attachments`, `task_activity` with checks and indexes (§7.1). 2) RLS loop + `GRANT … TO jurisone_tenant` for all of them (copy `V11__assets.sql`). 3) Permissions: `UPDATE role_templates SET permissions = array_append(…)` for `owner` (`tasks.manage`, `projects.create`) and `hrm` (`projects.create`); `INSERT INTO role_permissions … SELECT id, '<key>' FROM roles WHERE business_id IS NOT NULL AND key IN (…) ON CONFLICT DO NOTHING`. Idempotent where possible (`IF NOT EXISTS`). No plan/module changes. |
| `U20__task_management.sql` | MVP | Reverse, with a warning comment that it destroys all task data (export first): delete `role_permissions` rows for the two keys; `array_remove` them from `role_templates`; drop the tables in reverse dependency order. Header: "run with psql, then delete version 20 from flyway_schema_history" (as `U11__assets.sql`). Note: stored files under `storage-dir/tasks/` are not removed. |
| `V21__task_planning_tools.sql` + `U21` | v2 | `task_dependencies`, `task_recurrences`, `tasks.recurrence_id` + `tasks.occurrence_due_on` + unique index, `task_templates`, `task_saved_filters` (+ RLS). |
| `V22__task_custom_fields.sql` + `U22` | v3 | `task_custom_fields`, `task_custom_field_values` (+ `task_types` if chosen). |
| `V<n>__task_email_alerts.sql` + `U<n>` | Later: email alerts | `task_notification_preferences` (§20). |

---

## 16. Seed and demo data

`SeedService.seedDemo` (demo business, REQUIREMENTS §8 people) gains `seedTasks(codeToId)`:

| Project | Key | Visibility | Owner | Members | Tasks |
|---|---|---|---|---|---|
| People operations | `PEO` | BUSINESS | Riya Malhotra (JC-012) | Pooja Nair (JC-015) member | 8 tasks: onboarding paperwork, policy refresh, holiday calendar 2027 …; mixed statuses; 2 overdue relative to the seed date; some with Riya as indirect assignee and Pooja as direct; one with Arjun Mehta (JC-004, not a member) as indirect assignee and Pooja as direct (for the indirect-rights e2e test); one with a checklist and comments with a mention |
| Website relaunch | `WEB` | PRIVATE | Nazneen Khan (JC-035) | her reports JC-049, JC-058, JC-072, JC-074 members | 10 tasks across all statuses, 2 sub-tasks, labels `frontend`/`content`; several with Nazneen as indirect assignee; two with a due time (one today 17:00, one yesterday 11:00) for the timed-due UI and reminders; one comment thread |
| Payroll October | `PAY` | PRIVATE | Arjun Mehta (JC-004) | Rohan Gupta (JC-018) | 4 tasks (HR must not see them; the Owner JC-002 can — useful for the visibility e2e test) |

Arjun (Finance Manager) no longer has `projects.create` by default, so the seed creates this project directly in SQL with Arjun as its `OWNER` (a project owner does not need `projects.create`).
Plus 2 personal tasks for JC-072. `clearStoredFiles()` also clears `tasks/`.

---

## 17. Tests

Package `src/test/java/com/jurislpo/jurisone/tasks/` (integration, `extends IntegrationTest`, MockMvc + `SignIn.session` + `TestData`, as `assets/AssetApiTest.java`) and `engine/` (pure JUnit).

| Test class | Covers |
|---|---|
| `engine/TaskAccessTest` | the full §4.3/§4.4 matrix (Owner, project owner, member, viewer, non-member on PRIVATE/BUSINESS, direct assignee, indirect assignee field rights, reporter, watcher, manager depth 1/2, personal tasks) |
| `engine/TaskPositionsTest` | append, between, top, no-room → renumber |
| `engine/TaskKeysTest`, `engine/MentionsTest`, `engine/WorkflowRulesTest`, `engine/TaskDueDatesTest` (time zones: Asia/Kolkata vs America/New_York at day boundaries; date-only overdue from the next day; timed overdue at the minute; fallback zone when there is no direct assignee; a SQL-vs-engine comparison for the `due` filter); v2 `TaskRecurrenceRulesTest` (month ends, leap years, weekdays) |
| `engine/NavTest` (extend) | task items have no module and show for employee/admin; `team-tasks` needs reports; `task-settings` needs `tasks.manage`; `task-reports` needs `tasks.manage` or `projects.create`; the super admin sees none |
| `tasks/ProjectApiTest` | create (201, key format, 409 duplicate), `projects.create` for Owner and HR Manager only (Finance Manager 403), members (last owner 422), workflow statuses (rules, delete with move), archive → writes 422, delete with tasks 422 |
| `tasks/TaskApiTest` | create in project/personal (201, `HR-1`, `HR-2`), both assignees (must differ, auto-member for direct only), patch with version (409 stale), null clears, status → `completedAt`, position (between, renumber), sub-task rules, labels, watchers, checklist, soft delete, key lookup incl. `previousKeys` after a move; `/mine` relations `ASSIGNED`/`DIRECT`/`INDIRECT` |
| `tasks/TaskVisibilityTest` | private project non-member 404 on detail/list/comments/attachments; BUSINESS project viewer can comment but not edit (403); Owner sees private projects and personal tasks and an `*.private_access` audit row is written; indirect assignee can change status/priority/direct assignee but not title or delete (403); manager sees a report's task read-only (depth 1 and 2), not depth 3; personal task visible only to participants and the Owner |
| `tasks/TaskCollaborationApiTest` | comments (edit by author only, delete by owner), mentions notify only people who can see the task, auto-follow, bell rows in `notifications` for both assignees and not for the actor, **no rows in `email_outbox`** |
| `tasks/TaskAttachmentApiTest` | allowed types by magic bytes, renamed `.exe` refused, 10 MB, 20 per task, download headers, path traversal guard |
| `tasks/TaskReadOnlyTest` | expired subscription: reads 200, writes 402 (global rule still applies) |
| `tasks/TaskJobsTest` | due-tomorrow, due-today (timed only) and overdue bell notifications once per due date, to both assignees, re-armed after a due date/time change, task time zone; timed task overdue right after its time, date-only the next day at 09:00 |
| `tenancy/TenantIsolationTest` (extend) | business B cannot read/write A's projects/tasks/comments/attachments even by id or key — the Owner of B included; RLS hides rows with no filter |
| `common/RestEndpointsTest` (extend) | 201 on every create, `PUT` idempotent (watchers), no verbs in new paths |
| `audit` tests (extend) | `tasks` group in the audit filter; attachment download recorded |

---

## 18. REQUIREMENTS.md and other docs to update

In the same PR as the code of each phase:

1. `references/REQUIREMENTS.md`
   - §3.1 Permission keys: add `tasks.manage`, `projects.create`.
   - §3.2 Default roles table: `tasks.manage` for Owner; `projects.create` for Owner and HR Manager.
   - **New §5.17 Tasks**: 5.17.1 Projects, members and visibility (incl. the Owner exception and its audit); 5.17.2 Workflow (statuses, categories); 5.17.3 Tasks (fields incl. due date + optional due time and the task time zone, direct and indirect assignee and their rights, numbering, sub-tasks, checklist, soft delete always, limits); 5.17.4 Comments, mentions, followers, attachments; 5.17.5 Views (My tasks, Team tasks with depth, Project list/board); 5.17.6 Bell notifications and reminders (no emails yet); 5.17.7 Free for every business, read-only after subscription expiry. v2/v3 add 5.17.8+ (calendar, reports, recurring, templates, import/export, bulk, dependencies, custom fields).
   - §5.14 Notifications & shell: task bell events; the My tasks badge; sidebar group "Tasks".
   - §5.16 Audit log: task/project events and Owner private access.
   - §6 Data model: the new tables.
   - §8 Seed: demo projects and tasks.
   - §9 Open questions: whatever stays open from §22.
2. `references/MIGRATION_PLAN.md` §14.2: add a "Tasks" row listing the endpoints.
3. `references/SAAS_PLAN.md`: one line under §1.2 that Tasks is part of every plan (not a module).
4. Frontend repo: `references/TASK_MANAGEMENT_FRONTEND_PLAN.md` stays in sync.

---

## 19. Later integrations (design hooks only, not built in MVP)

| Integration | How it would connect | Hook kept now |
|---|---|---|
| **JurisTrack (log time on a task)** | JurisTrack time entries carry an optional `taskId` (JurisOne task uuid) + `taskKey` for display. Through the gateway (`/api/v1/juris-track/**`, JurisTrack `INTEGRATION.md` §2) the JurisTrack UI asks JurisOne `GET /api/v1/tasks/mine?statusCategory=TODO,IN_PROGRESS&search=` to pick a task. JurisOne later shows "logged 3h 20m of 4h estimate" by asking JurisTrack for totals per task. Both use the same reporting-line scope (direct, optional depth 2). | stable task ids/keys, `estimate_minutes`, `/mine` endpoint |
| **Leave calendar** | People pickers and the board show "on leave" when an assignee has approved leave covering the due date (from `leave_requests`); warning when assigning a task due on a leave day. | `assigneeId`, `indirectAssigneeId` + `dueDate` on every row |
| **Onboarding checklists** | When an onboarding becomes `ADDED` (`OnboardingService`), create tasks from a business template "New joiner" (IT laptop → links to Assets, manager intro, policy reading) in the People operations project, with HR as indirect assignee. | v2 templates with `dueInDays` |
| **Policies / announcements** | "Read the new policy" is already enforced by the acknowledgement gate; tasks could be created for HR follow-ups (people who have not acknowledged after N days). | templates + `POST /api/v1/tasks` |
| **Assets** | A REQUEST task "Laptop for JC-090" linked to an asset tag (v3 custom field). | custom fields |

---

## 20. Phases and gates

Each phase = one feature branch PR to `dev` per repo (backend first, then frontend), demo to the owner, then wait for "continue".

| Phase | Backend content | Gate (all must pass) |
|---|---|---|
| **T1 — MVP** | V20; `Nav`/`Permissions`; projects + members + workflow statuses + board; tasks CRUD with direct and indirect assignee, field-level rights, position, sub-tasks, checklist, labels, followers; comments + mentions; attachments; activity + audit (incl. Owner private access); **bell** notifications; jobs `dueSoon`, `overdue` (bell); shell badge; task settings (default workflow, business labels); seed; tests of §17 (MVP rows); REQUIREMENTS §5.17.1–7. Suggested split into two PRs: **T1a** data model + projects + tasks + board + access; **T1b** collaboration + notifications + jobs + settings. | `./mvnw test` green in a fresh copy; `./mvnw -DskipTests package`; frontend T1 gate green (typecheck, lint, unit, build, knip, e2e); owner demo with the seeded data; REQUIREMENTS updated |
| **T2 — Planning tools** | V21; calendar support (filters already there); reports summary; recurring tasks + job; templates; saved filters; bulk PATCH; CSV export + import (dry run); restore deleted; dependencies; limited markdown (frontend only). Still bell only. | same, plus a load check: lists and board < 2 s with 50,000 seeded tasks (a dev-only seed script or SQL `generate_series`) |
| **T3 — Advanced** | V22; custom fields; configurable task types; timeline/Gantt support; WIP limits; first integrations (JurisTrack time per task, leave warnings, onboarding template trigger). | same; JurisTrack integration only after the owner says so (it is a separate repo and decision) |
| **Later: email alerts** (the owner will start it) | 1) **Per-person preferences**: table `task_notification_preferences` (PK `employee_id, event`; `is_in_app`, `is_email`) + `GET`/`PUT /api/v1/task-notification-preferences/mine`. 2) **Which events** get email (owner to choose; candidates: assigned/made accountable, mentioned, overdue; comments and status changes off by default). 3) **Daily digest** email at ~08:00 local time (job `tasks.digest`, idempotent with a `job_runs` row `name='task.digest'`, `key='<employeeId>:<date>'`). 4) **Send after commit**: `MailService.send` joins an open transaction and then talks to SMTP, so an email could go out for a change that later rolls back; send task emails from `TransactionSynchronization.afterCommit()` (`TransactionSynchronizationManager.registerSynchronization`) — new in the codebase. 5) Templates in `MailTemplates` (existing HTML shell, MIGRATION_PLAN §1e). 6) Tests: rows in `email_outbox` only after commit, never for the actor, respecting preferences. | same gate |

---

## 21. Risks

| Risk | Mitigation |
|---|---|
| Scope creep (task tools grow forever) | Fixed phase content above; anything new goes to T3 or an open question. |
| Visibility bugs leak private projects | One pure `TaskAccess` + one SQL predicate in `TaskQuery`, both tested as matrices; 404 for invisible tasks; RLS for businesses. |
| The Owner's "see everything" right is misused or given to other roles | It is tied to `tasks.manage`, which only the Owner template gets; private access is audited (`*.private_access`). |
| Two assignees confuse responsibility | Clear labels in the UI ("Assignee" does the work, "Accountable" oversees), one of each only, rights table §4.4. |
| Notification noise | Auto-follow rules, one notification per person per request, bell only; per-person preferences come with the email phase. |
| Board drag-and-drop races | Positions are server-calculated from neighbours; renumbering in one transaction; the app refetches after each move. |
| Large boards slow | 500-card cap, done-window, indexes on `(project_id, status_id, position)`. |
| Storage growth from attachments | 10 MB/20 files per task; files are never hard-deleted by the app (owner's decision), so watch disk use on the server and plan storage capacity. |
| Due times across time zones confuse people | One rule (task time zone = direct assignee's), `dueTimeZone` returned, the app shows the zone when it differs from the viewer's. |
| Migration number clash | Take the next free `V<n>` at implementation time; never fill gaps. |

---

## 22. Decided by the owner, and open questions

### 22.1 Decided by the owner (2026-10-23)

1. **Free for everybody:** Tasks is not a plan module; no module gating. The menu group shows for every signed-in user of every business (items still follow their permissions). Read-only after a subscription ends stays (global rule).
2. **`projects.create`:** Owner and HR Manager only. Everybody can create personal tasks.
3. **Owner sees everything:** all projects and tasks of the business, private ones included. Every write is audited; opening a private project/task they are not part of writes a `*.private_access` audit row. This is the only exception to "a task you cannot see is not found".
4. **Assignees:** one direct assignee (does the work) + one optional indirect assignee (accountable/oversight); both see it in My tasks, both are notified, both show on cards and in the task panel; followers stay a separate list.
5. **Indirect assignee's rights:** can comment, change status and priority, and reassign the direct assignee; cannot delete the task unless they are a project owner (other fields only if they are also a project member). See §4.4.
6. **Notifications:** bell only for now; no emails in T1 or T2. Email alerts are a later phase the owner will start (§20).
7. **Manager view (Q5):** a manager sees (read + comment) the tasks of their reports, also inside private projects (direct reports; one more level with the switch).
8. **Default steps (Q7):** To do, In progress, In review, Done, Cancelled (editable per project).
9. **Soft delete always (Q8):** tasks, comments, attachments with their files, and projects are never hard-deleted by the app, in any phase; T2 adds a 30-day "recently deleted" list with restore.
10. **List parameters (Q9):** keep `sort` / `sortDirection`.
11. **Due time (Q10):** a due date (required when a due is set) plus an optional due time; without a time the task is due at the end of that day (23:59:59, task time zone); read in the direct assignee's time zone; date-only tasks become overdue the day after, timed tasks at their time (§8.6).
12. **Reminder hour (Q11):** 09:00 in the direct assignee's time zone.
13. **Accountable suggestion (Q13):** when creating a task, the assignee's reporting manager is offered as a one-click "Accountable" suggestion; never set automatically.
14. **Owner private access (Q14):** no notification to project owners; the audit entry is enough.

### 22.2 Still open (recommended default in the last column)

| # | Question | Recommended default |
|---|---|---|
| Q15 | Should tasks also get a reminder shortly before the due time (e.g. 1 hour before)? | **Yes (owner's decision):** optional per task, `reminderBeforeMinutes` 15/60/240/1440, in addition to the 09:00 daily reminders (§14 `tasks.reminder`). |

---

## 23. How to start implementation

1. Read `INSTRUCTIONS.md`, `CLAUDE.md`, this file, and the frontend plan. Check §22.2 for questions the owner has answered since; use the defaults otherwise and say so.
2. `git fetch` and cut `feature/task-management` from `dev` in **both** repos (ask the owner before pushing anything).
3. Check the latest migration number in `backend/src/main/resources/db/migration` and rename V20/V21/V22 if needed.
4. Phase T1a (backend):
   1. Write `V20__task_management.sql` + `U20__task_management.sql`; run `./mvnw test` once to see Flyway apply it in Testcontainers.
   2. Add `Nav` items and `Permissions` keys/group; extend `NavTest`.
   3. Engine classes + unit tests (`TaskAccess` incl. indirect-assignee rights, `TaskPositions`, `TaskKeys`, `WorkflowRules`, `TaskDueDates`, `Mentions`).
   4. Repositories + services + controllers for projects, members, workflow statuses, labels, tasks, position, board, lists (`/mine` with relations, `/team`, `/assignees`).
   5. `TaskActivityService` (activity + `AuditService.log`), Owner private-access audit, `AuditViewService.GROUPS`.
   6. Integration tests: `ProjectApiTest`, `TaskApiTest`, `TaskVisibilityTest`, `TaskReadOnlyTest`, tenant isolation.
5. Phase T1b (backend): comments, mentions, followers auto-add, attachments, `TaskNotificationService` (bell only), jobs in `JobsService.runForBusiness`, shell badge, task settings, seed data; their tests.
6. Update `references/REQUIREMENTS.md` (§18), `MIGRATION_PLAN.md` §14.2, `SAAS_PLAN.md` note.
7. Verify (§1): fresh-copy `./mvnw test`, `./mvnw -DskipTests package`.
8. Then build the frontend T1 (frontend plan §17), run its full gate including e2e against this branch.
9. Commit only when the owner asks; open PRs to `dev` in both repos, share the links, stop. Reply to the owner in short Hindi/Hinglish: what changed, what was checked, what is left.
