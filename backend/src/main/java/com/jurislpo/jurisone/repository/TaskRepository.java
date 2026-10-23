package com.jurislpo.jurisone.repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Time;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Tasks, their labels, followers and checklist items (tenant-scoped by row-level security). */
@Repository
public class TaskRepository {

    /**
     * One task with everything the access rules need about {@code employeeId}: their project role, whether they follow it, whether
     * the direct assignee reports to them (depth ≤ 2) and whether they hold {@code tasks.manage}.
     */
    public record TaskFacts(UUID id, UUID projectId, Integer number, String title, String description, String type, UUID statusId, String statusName,
                            String statusCategory, String priority, UUID assigneeId, UUID indirectAssigneeId, UUID reporterId, UUID parentTaskId,
                            LocalDate startDate, LocalDate dueDate, LocalTime dueTime, Integer estimateMinutes, int version, String projectKey, String projectName,
                            String visibility, String projectStatus, String projectRole, boolean isWatcher, boolean isManagerOfAssignee, boolean isTaskAdmin) {

        public String key() {
            return projectKey == null || number == null ? null : projectKey + "-" + number;
        }

        public boolean isDone() {
            return "DONE".equals(statusCategory);
        }
    }

    static final String TEAM_OF = """
        WITH RECURSIVE team(employee_id, depth) AS (
            SELECT id, 1 FROM employees WHERE reporting_manager_id = ? AND status <> 'EXITED'
            UNION ALL
            SELECT e.id, team.depth + 1 FROM employees e JOIN team ON e.reporting_manager_id = team.employee_id WHERE team.depth < 2 AND e.status <> 'EXITED')
        SELECT employee_id FROM team""";

    private static final String FACTS = """
        SELECT t.id, t.project_id, t.number, t.title, t.description, t.type, t.status_id, s.name AS status_name, s.category AS status_category, t.priority,
               t.assignee_id, t.indirect_assignee_id, t.reporter_id, t.parent_task_id, t.start_date, t.due_date, t.due_time, t.estimate_minutes, t.version,
               p.key AS project_key, p.name AS project_name, p.visibility, p.status AS project_status,
               (SELECT m.role FROM project_members m WHERE m.project_id = t.project_id AND m.employee_id = ?) AS project_role,
               EXISTS (SELECT 1 FROM task_watchers w WHERE w.task_id = t.id AND w.employee_id = ?) AS is_watcher,
               coalesce(t.assignee_id IN (%s), false) AS is_manager,
               EXISTS (SELECT 1 FROM employees e JOIN role_permissions rp ON rp.role_id = e.role_id WHERE e.id = ? AND rp.permission_key = 'tasks.manage') AS is_task_admin
        FROM tasks t
        JOIN task_statuses s ON s.id = t.status_id
        LEFT JOIN projects p ON p.id = t.project_id
        WHERE t.id = ? AND t.deleted_at IS NULL AND (p.id IS NULL OR p.deleted_at IS NULL)""".formatted(TEAM_OF);

    private final JdbcTemplate jdbc;

    public TaskRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static UUID uuid(ResultSet resultSet, String column) throws SQLException {
        return (UUID) resultSet.getObject(column);
    }

    static TaskFacts facts(ResultSet resultSet) throws SQLException {
        Date startDate = resultSet.getDate("start_date"), dueDate = resultSet.getDate("due_date");
        Time dueTime = resultSet.getTime("due_time");
        return new TaskFacts(uuid(resultSet, "id"), uuid(resultSet, "project_id"), (Integer) resultSet.getObject("number"), resultSet.getString("title"),
                resultSet.getString("description"), resultSet.getString("type"), uuid(resultSet, "status_id"), resultSet.getString("status_name"),
                resultSet.getString("status_category"), resultSet.getString("priority"), uuid(resultSet, "assignee_id"), uuid(resultSet, "indirect_assignee_id"),
                uuid(resultSet, "reporter_id"), uuid(resultSet, "parent_task_id"), startDate == null ? null : startDate.toLocalDate(),
                dueDate == null ? null : dueDate.toLocalDate(), dueTime == null ? null : dueTime.toLocalTime(), (Integer) resultSet.getObject("estimate_minutes"),
                resultSet.getInt("version"), resultSet.getString("project_key"), resultSet.getString("project_name"), resultSet.getString("visibility"),
                resultSet.getString("project_status"), resultSet.getString("project_role"), resultSet.getBoolean("is_watcher"), resultSet.getBoolean("is_manager"),
                resultSet.getBoolean("is_task_admin"));
    }

    /** The task as {@code employeeId} sees it; empty when it does not exist or was deleted. */
    public List<TaskFacts> findFacts(UUID taskId, UUID employeeId) {
        return jdbc.query(FACTS, (resultSet, rowNumber) -> facts(resultSet), employeeId, employeeId, employeeId, employeeId, taskId);
    }

    /** The task id for a key such as {@code HR-12} (current key first, then a key it had before it moved project). */
    public List<UUID> findIdByKey(String projectKey, int number, String formattedKey) {
        List<UUID> found = jdbc.queryForList("SELECT t.id FROM tasks t JOIN projects p ON p.id = t.project_id WHERE p.key = ? AND t.number = ? AND t.deleted_at IS NULL",
                UUID.class, projectKey, number);
        if (!found.isEmpty()) return found;
        return jdbc.queryForList("SELECT id FROM tasks WHERE ? = ANY (previous_keys) AND deleted_at IS NULL ORDER BY updated_at DESC LIMIT 1", UUID.class, formattedKey);
    }

    /** Takes the next task number of a project (the row lock serialises parallel creates). */
    public int takeNextNumber(UUID projectId) {
        Integer number = jdbc.queryForObject("UPDATE projects SET next_task_number = next_task_number + 1 WHERE id = ? RETURNING next_task_number - 1", Integer.class, projectId);
        return number == null ? 1 : number;
    }

    /** The lowest card position of a board column, or null for an empty column. */
    public Long findMaxPosition(UUID projectId, UUID statusId) {
        return jdbc.queryForObject("SELECT max(position) FROM tasks WHERE project_id IS NOT DISTINCT FROM ?::uuid AND status_id = ? AND deleted_at IS NULL", Long.class, projectId, statusId);
    }

    public record NewTask(UUID id, UUID projectId, Integer number, UUID parentTaskId, String title, String description, String type, UUID statusId, String priority,
                          UUID assigneeId, UUID indirectAssigneeId, UUID reporterId, LocalDate startDate, LocalDate dueDate, LocalTime dueTime, Integer estimateMinutes,
                          long position, boolean isCompleted) {}

    public void insert(NewTask task) {
        jdbc.update("""
            INSERT INTO tasks (id, project_id, number, parent_task_id, title, description, type, status_id, priority, assignee_id, indirect_assignee_id, reporter_id,
                               start_date, due_date, due_time, estimate_minutes, position, completed_at, completed_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CASE WHEN ? THEN now() END, CASE WHEN ? THEN ?::uuid END)""",
                task.id(), task.projectId(), task.number(), task.parentTaskId(), task.title(), task.description(), task.type(), task.statusId(), task.priority(),
                task.assigneeId(), task.indirectAssigneeId(), task.reporterId(), date(task.startDate()), date(task.dueDate()), time(task.dueTime()),
                task.estimateMinutes(), task.position(), task.isCompleted(), task.isCompleted(), task.reporterId());
    }

    static Date date(LocalDate value) {
        return value == null ? null : Date.valueOf(value);
    }

    static Time time(LocalTime value) {
        return value == null ? null : Time.valueOf(value);
    }

    /**
     * Applies {@code clauses} ({@code column = ?} parts with their {@code values}) if the task still has {@code version}; returns the new
     * version, or null when someone else changed it first.
     */
    public Integer update(UUID taskId, int version, List<String> clauses, List<Object> values) {
        List<Object> all = new ArrayList<>(values);
        all.add(taskId);
        all.add(version);
        List<Integer> updated = jdbc.queryForList("UPDATE tasks SET " + String.join(", ", clauses) + ", version = version + 1, updated_at = now() "
                + "WHERE id = ? AND version = ? AND deleted_at IS NULL RETURNING version", Integer.class, all.toArray());
        return updated.isEmpty() ? null : updated.get(0);
    }

    /**
     * A board move to another column (needs no version, but bumps it): status, position and the completion stamp ({@code isCompleted} =
     * the new status is a done step; a task that stays done keeps its first completion).
     */
    public void setStatusAndPosition(UUID taskId, UUID statusId, long position, boolean isCompleted, UUID actorId) {
        jdbc.update("UPDATE tasks SET status_id = ?, position = ?, completed_at = CASE WHEN ? THEN coalesce(completed_at, now()) END, "
                + "completed_by = CASE WHEN ? THEN coalesce(completed_by, ?::uuid) END, version = version + 1, updated_at = now() WHERE id = ?",
                statusId, position, isCompleted, isCompleted, actorId, taskId);
    }

    public void touch(UUID taskId) {
        jdbc.update("UPDATE tasks SET updated_at = now() WHERE id = ?", taskId);
    }

    /** {@code id, position} of the open cards of one column, in order. */
    public List<Map<String, Object>> findColumn(UUID projectId, UUID statusId) {
        return jdbc.queryForList("SELECT id, position FROM tasks WHERE project_id IS NOT DISTINCT FROM ?::uuid AND status_id = ? AND deleted_at IS NULL ORDER BY position, created_at",
                projectId, statusId);
    }

    public void setPosition(UUID taskId, long position) {
        jdbc.update("UPDATE tasks SET position = ? WHERE id = ?", position, taskId);
    }

    /** The position of a card if it is in that column (not deleted), else null. */
    public Long findPositionInColumn(UUID taskId, UUID projectId, UUID statusId) {
        List<Long> found = jdbc.queryForList("SELECT position FROM tasks WHERE id = ? AND project_id IS NOT DISTINCT FROM ?::uuid AND status_id = ? AND deleted_at IS NULL",
                Long.class, taskId, projectId, statusId);
        return found.isEmpty() ? null : found.get(0);
    }

    /** Soft-deletes the task and its sub-tasks; returns how many rows. */
    public int softDelete(UUID taskId, UUID actorId) {
        return jdbc.update("UPDATE tasks SET deleted_at = now(), deleted_by = ?, updated_at = now() WHERE (id = ? OR parent_task_id = ?) AND deleted_at IS NULL",
                actorId, taskId, taskId);
    }

    public int countSubtasks(UUID parentTaskId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM tasks WHERE parent_task_id = ? AND deleted_at IS NULL", Integer.class, parentTaskId);
        return count == null ? 0 : count;
    }

    // ── labels ──

    public List<UUID> findLabelIds(UUID taskId) {
        return jdbc.queryForList("SELECT label_id FROM task_label_links WHERE task_id = ? ORDER BY label_id", UUID.class, taskId);
    }

    public void setLabels(UUID taskId, List<UUID> labelIds) {
        jdbc.update("DELETE FROM task_label_links WHERE task_id = ?", taskId);
        for (UUID labelId : labelIds) jdbc.update("INSERT INTO task_label_links (task_id, label_id) VALUES (?, ?) ON CONFLICT DO NOTHING", taskId, labelId);
    }

    /** {@code taskId, id, name, color} of the labels of these tasks. */
    public List<Map<String, Object>> findLabelsOfTasks(List<UUID> taskIds) {
        if (taskIds.isEmpty()) return List.of();
        String marks = String.join(", ", taskIds.stream().map(id -> "?").toList());
        return jdbc.queryForList("SELECT ll.task_id::text AS \"taskId\", l.id::text AS \"id\", l.name AS \"name\", l.color AS \"color\" FROM task_label_links ll "
                + "JOIN task_labels l ON l.id = ll.label_id WHERE ll.task_id IN (" + marks + ") ORDER BY lower(l.name)", taskIds.toArray());
    }

    // ── followers ──

    public void addWatcher(UUID taskId, UUID employeeId) {
        jdbc.update("INSERT INTO task_watchers (task_id, employee_id) VALUES (?, ?) ON CONFLICT DO NOTHING", taskId, employeeId);
    }

    public int removeWatcher(UUID taskId, UUID employeeId) {
        return jdbc.update("DELETE FROM task_watchers WHERE task_id = ? AND employee_id = ?", taskId, employeeId);
    }

    public boolean isWatcher(UUID taskId, UUID employeeId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM task_watchers WHERE task_id = ? AND employee_id = ?)", Boolean.class, taskId, employeeId));
    }

    public int countWatchers(UUID taskId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM task_watchers WHERE task_id = ?", Integer.class, taskId);
        return count == null ? 0 : count;
    }

    public List<UUID> findWatcherIds(UUID taskId) {
        return jdbc.queryForList("SELECT employee_id FROM task_watchers WHERE task_id = ?", UUID.class, taskId);
    }

    public List<Map<String, Object>> findWatchers(UUID taskId) {
        return jdbc.queryForList("""
            SELECT w.employee_id::text AS "employeeId", e.full_name AS "name", (extract(epoch FROM e.photo_updated_at) * 1000)::bigint AS "photo"
            FROM task_watchers w JOIN employees e ON e.id = w.employee_id WHERE w.task_id = ? ORDER BY e.full_name""", taskId);
    }

    // ── checklist ──

    public List<Map<String, Object>> findChecklist(UUID taskId) {
        return jdbc.queryForList("""
            SELECT c.id::text AS "id", c.text AS "text", c.is_done AS "isDone", c.position AS "position", d.full_name AS "doneByName", %s AS "doneAt"
            FROM task_checklist_items c LEFT JOIN employees d ON d.id = c.done_by WHERE c.task_id = ? ORDER BY c.position, c.created_at""".formatted(TaskQuery.iso("c.done_at")),
                taskId);
    }

    public int countChecklistItems(UUID taskId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM task_checklist_items WHERE task_id = ?", Integer.class, taskId);
        return count == null ? 0 : count;
    }

    public void insertChecklistItem(UUID id, UUID taskId, String text) {
        jdbc.update("INSERT INTO task_checklist_items (id, task_id, text, position) VALUES (?, ?, ?, (SELECT coalesce(max(position), 0) + 1 FROM task_checklist_items WHERE task_id = ?))",
                id, taskId, text, taskId);
    }

    /** {@code text, isDone} of one item of the task, or empty. */
    public List<Map<String, Object>> findChecklistItem(UUID taskId, UUID itemId) {
        return jdbc.queryForList("SELECT text AS \"text\", is_done AS \"isDone\" FROM task_checklist_items WHERE id = ? AND task_id = ?", itemId, taskId);
    }

    public void updateChecklistText(UUID itemId, String text) {
        jdbc.update("UPDATE task_checklist_items SET text = ? WHERE id = ?", text, itemId);
    }

    public void updateChecklistDone(UUID itemId, boolean isDone, UUID actorId) {
        jdbc.update("UPDATE task_checklist_items SET is_done = ?, done_by = CASE WHEN ? THEN ?::uuid END, done_at = CASE WHEN ? THEN now() END WHERE id = ?",
                isDone, isDone, actorId, isDone, itemId);
    }

    /** Moves an item to {@code position} (1-based) and renumbers the list 1..n. */
    public void moveChecklistItem(UUID taskId, UUID itemId, int position) {
        List<UUID> ids = new ArrayList<>(jdbc.queryForList("SELECT id FROM task_checklist_items WHERE task_id = ? ORDER BY position, created_at", UUID.class, taskId));
        ids.remove(itemId);
        ids.add(Math.max(0, Math.min(ids.size(), position - 1)), itemId);
        for (int i = 0; i < ids.size(); i++) jdbc.update("UPDATE task_checklist_items SET position = ? WHERE id = ?", i + 1, ids.get(i));
    }

    public void deleteChecklistItem(UUID itemId) {
        jdbc.update("DELETE FROM task_checklist_items WHERE id = ?", itemId);
    }

    // ── reminders (jobs) ──

    /** An open task with a due date that has not had its overdue reminder for that date yet. */
    public record ReminderCandidate(UUID id, String key, String title, LocalDate dueDate, LocalTime dueTime, String timeZone, UUID assigneeId, UUID indirectAssigneeId,
                                    LocalDate dueSoonNotifiedFor, LocalDate dueTodayNotifiedFor, LocalDate overdueNotifiedFor) {}

    static LocalDate localDate(ResultSet resultSet, String column) throws SQLException {
        Date value = resultSet.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    /** Open tasks of active projects (or personal) due on or before {@code dueUpTo} whose overdue reminder is still to come. */
    public List<ReminderCandidate> findReminderCandidates(LocalDate dueUpTo) {
        return jdbc.query("""
            SELECT t.id, CASE WHEN p.id IS NULL THEN NULL ELSE p.key || '-' || t.number END AS task_key, t.title, t.due_date, t.due_time,
                   coalesce(ae.time_zone, ie.time_zone, re.time_zone, 'Asia/Kolkata') AS zone, t.assignee_id, t.indirect_assignee_id,
                   t.due_soon_notified_for, t.due_today_notified_for, t.overdue_notified_for
            FROM tasks t
            LEFT JOIN projects p ON p.id = t.project_id
            LEFT JOIN employees ae ON ae.id = t.assignee_id
            LEFT JOIN employees ie ON ie.id = t.indirect_assignee_id
            JOIN employees re ON re.id = t.reporter_id
            WHERE t.deleted_at IS NULL AND t.completed_at IS NULL AND t.due_date IS NOT NULL AND t.due_date <= ?
              AND t.overdue_notified_for IS DISTINCT FROM t.due_date AND (t.assignee_id IS NOT NULL OR t.indirect_assignee_id IS NOT NULL)
              AND (p.id IS NULL OR (p.deleted_at IS NULL AND p.status = 'ACTIVE'))
            ORDER BY t.due_date, t.id""", (resultSet, rowNumber) -> {
                Time dueTime = resultSet.getTime("due_time");
                return new ReminderCandidate(uuid(resultSet, "id"), resultSet.getString("task_key"), resultSet.getString("title"), localDate(resultSet, "due_date"),
                        dueTime == null ? null : dueTime.toLocalTime(), resultSet.getString("zone"), uuid(resultSet, "assignee_id"), uuid(resultSet, "indirect_assignee_id"),
                        localDate(resultSet, "due_soon_notified_for"), localDate(resultSet, "due_today_notified_for"), localDate(resultSet, "overdue_notified_for"));
            }, Date.valueOf(dueUpTo));
    }

    public enum Reminder {
        DUE_SOON("due_soon_notified_for"), DUE_TODAY("due_today_notified_for"), OVERDUE("overdue_notified_for");

        final String column;

        Reminder(String column) {
            this.column = column;
        }
    }

    /** Records that a reminder was sent for this due date (sending it again for the same date is skipped). */
    public void markReminder(UUID taskId, Reminder reminder, LocalDate dueDate) {
        jdbc.update("UPDATE tasks SET " + reminder.column + " = ? WHERE id = ?", Date.valueOf(dueDate), taskId);
    }

    // ── people ──

    /** Active (not exited) employee of the business. */
    public boolean isActiveEmployee(UUID employeeId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM employees WHERE id = ? AND status <> 'EXITED')", Boolean.class, employeeId));
    }

    /** Ids of {@code employeeIds} that may be notified: active and with portal access. */
    public List<UUID> findNotifiable(List<UUID> employeeIds) {
        if (employeeIds.isEmpty()) return List.of();
        String marks = String.join(", ", employeeIds.stream().map(id -> "?").toList());
        return jdbc.queryForList("SELECT id FROM employees WHERE id IN (" + marks + ") AND status <> 'EXITED' AND portal_access", UUID.class, employeeIds.toArray());
    }

    /** {@code id → full name} of these employees. */
    public List<Map<String, Object>> findNames(List<UUID> employeeIds) {
        if (employeeIds.isEmpty()) return List.of();
        String marks = String.join(", ", employeeIds.stream().map(id -> "?").toList());
        return jdbc.queryForList("SELECT id::text AS \"id\", full_name AS \"name\" FROM employees WHERE id IN (" + marks + ")", employeeIds.toArray());
    }

    /** Details for the task panel: description, version, reporter photo, completed-by name, previous keys. */
    public Map<String, Object> findDetailExtras(UUID taskId) {
        return JdbcRows.normalize(jdbc.queryForList("""
            SELECT t.description AS "description", t.version AS "version", (extract(epoch FROM r.photo_updated_at) * 1000)::bigint AS "reporterPhoto",
                   c.full_name AS "completedByName", t.previous_keys AS "previousKeys"
            FROM tasks t JOIN employees r ON r.id = t.reporter_id LEFT JOIN employees c ON c.id = t.completed_by WHERE t.id = ?""", taskId)).get(0);
    }
}
