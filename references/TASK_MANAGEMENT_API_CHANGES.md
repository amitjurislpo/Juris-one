# Task management: API changes against the plan

Backend deviations from `TASK_MANAGEMENT_BACKEND_PLAN.md` (the plan's endpoint tables stay the source of truth for everything not listed here).
One line each: endpoint, what changed, why. New user-visible messages are in `TASK_MANAGEMENT_API_MESSAGES.txt`.

## T1a

- Nav: only the MVP items are added (`my-tasks`, `team-tasks`, `projects`, `task-settings`); `task-calendar` and `task-reports` come with T2, so they are not in the menu yet.
- `POST /api/v1/projects` (P2): `key` is trimmed and upper-cased before the check (`"pay"` becomes `PAY`); a key stays taken by a deleted project (old task links contain it), a name only by projects that are not deleted.
- `PUT /api/v1/projects/{projectId}` (P4): the body is the create body plus `version`, and `key` is required (send the current key; a different key is refused with 422 once the project has any task). Response `data: {version}`.
- `PUT /api/v1/projects/{projectId}/status` (P5): setting the status the project already has answers 200 without an audit row (idempotent).
- `GET /api/v1/projects/{projectId}/board` (P15): `project` is a ProjectRow; each column is `{status: {id, name, category, color, position, isInitial}, taskCount, tasks}`; `taskCount` is the number of matching tasks in that column (open ones, or done within `doneWithinDays`) even when the cards are capped at 500.
- `DELETE /api/v1/projects/{projectId}/workflow-statuses/{statusId}` (P14, also S5): the starting step can't be deleted (422 "Make another step the starting step first."); a step that only has deleted tasks moves them to the starting step without needing `moveTasksToStatusId`.
- `PUT /api/v1/projects/{projectId}/workflow-statuses/{statusId}` (P12, also S3): `isInitial` may be left out (unchanged); `isInitial: false` on the current starting step is refused (422, make another step the start instead). Changing a step's category to or from done stamps or clears `completedAt` of its tasks.
- `GET /api/v1/tasks` (T1): when `projectId` is given, tasks of an archived project are included without `isArchivedProjectIncluded` (the project's own List tab).
- `GET /api/v1/tasks/assignees` (T4): an empty `search` returns the first 20 active employees (project members first when `projectId` is given) instead of nothing.
- `PATCH /api/v1/tasks/{taskId}` (T7): a field sent with its current value is not a change (no right needed, no history row); when nothing changes the answer is the current `version` (not incremented).
- `PATCH /api/v1/tasks/{taskId}` (T7) with `projectId`: only a task without a parent and without sub-tasks can move (422 otherwise); it lands in the first step of the same category in the target project, at the bottom of that column; labels of the old project are dropped (business labels stay); `projectId: null` makes it a personal task (no number). The mover needs `canEdit` on the task and member/owner rights in the target project.
- `PUT /api/v1/tasks/{taskId}/position` (T8): a move into another column is a status change: it bumps the task's `version` (re-read the task before a later PATCH), writes a `STATUS_CHANGED` history row and notifies followers. Every move writes an audit row `task.move`.
- `PATCH /api/v1/tasks/{taskId}/checklist-items/{itemId}` (T13): `position` is the item's new place in the list, counted from 1 (the list is renumbered 1..n).
- `PUT /api/v1/tasks/{taskId}/watchers/{employeeId}` (T10): on a personal task, adding another person is how it is shared, so there is no "can see" check (there is for project tasks: 422). Unknown/exited employee: 404.
- New audit actions (all in the existing `tasks` audit filter group, prefix `task.`): `task.move`, `task.watcher_add`, `task.watcher_remove`, `task.checklist_change` (every write has an audit row; the plan's list had none for these).
- `@mentions` (task description and comments): tokens of people who are not active or can't see the task are dropped silently (stored text keeps the token; they are neither notified nor made followers). No 400.
- Labels (L2): at most 50 labels per set, the business set included (plan said 50 per project).

- TaskRow (every task list: `GET /api/v1/tasks`, `/mine`, `/team`, board cards, sub-tasks, task detail): new field `version`, so a one-click write (tick done, board move then PATCH) needs no extra GET. Asked by the frontend.
- `GET /api/v1/tasks/assignees` (T4): new field `reportingManagerName` next to `reportingManagerId` (the "Accountable" one-click suggestion shows the name). Asked by the frontend.

## T1b

- `GET /api/v1/task-settings` (S1): `defaultStatuses` rows have `id: null` (the built-in steps, `taskCount: 0`) while the business has no default set stored yet; every existing business got its set in the migration, a new business gets it on its first task write or any S2–S5 call. S2 (add a step) therefore always works; S3/S4/S5 need the ids, so reload after S2 if the ids were null.
- `GET /api/v1/tasks/team` (T3): an `employeeId` outside the caller's team (at the chosen `depth`) returns an empty list (not 403). `people[].depth` is the person's nearest level.
- `GET /api/v1/tasks/{taskId}/comments` (C1): rows also carry `authorId`; a deleted comment has `body: null`, `mentionedPeople: {}` and `isDeleted: true`.
- `POST /api/v1/tasks/{taskId}/attachments` (C6): message "File attached", `data: {id, name}` (`name` = the cleaned file name).
- `GET /api/v1/tasks/{taskId}/activity` (C5) is part of T1a (the history is written from T1a on).

## Extra reminder before the due moment (Q15 = yes)

- `POST /api/v1/tasks` (T6) and `PATCH /api/v1/tasks/{taskId}` (T7): new optional field `reminderBeforeMinutes` (15, 60, 240, 1440 or `null`). Another number or a non-integer: 400 "Choose 15 minutes, 1 hour, 4 hours or 1 day before the due time."; a reminder without a due date: 400 "Choose a due date first.". Clearing `dueDate` also clears the reminder. Changing it needs the same right as the dates (`canEdit`), writes a `DATES_CHANGED` history row and the `task.update` audit row (before/after include `reminderBeforeMinutes`).
- TaskRow (list, board, detail): new field `reminderBeforeMinutes`.
- Job `tasks.reminder` (in `TaskJobs`): the summary of the task jobs has a new key `reminders`. The bell text is "{key} is due in {15 minutes|1 hour|4 hours|1 day} (due {date}[ {time}]): {title}".
- Migration: the columns `tasks.reminder_before_minutes` and `tasks.reminder_notified_for` and a check constraint were added to `V20__task_management.sql` itself (V20 is not pushed yet); the rollback `U20` drops the whole table, so it needed no change.
