package com.jurislpo.jurisone.repository;

import com.jurislpo.jurisone.engine.WorkflowRules;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Workflow steps: one set per project, plus the business default set ({@code project_id IS NULL}). */
@Repository
public class WorkflowStatusRepository {

    public record StatusRow(UUID id, UUID projectId, String name, String category, String color, int position, boolean isInitial) {}

    private static final String STATUS = "SELECT id, project_id, name, category, color, position, is_initial FROM task_statuses";

    private final JdbcTemplate jdbc;

    public WorkflowStatusRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private List<StatusRow> query(String sql, Object... args) {
        return jdbc.query(sql, (resultSet, rowNumber) -> new StatusRow((UUID) resultSet.getObject("id"), (UUID) resultSet.getObject("project_id"),
                resultSet.getString("name"), resultSet.getString("category"), resultSet.getString("color"), resultSet.getInt("position"),
                resultSet.getBoolean("is_initial")), args);
    }

    /** The steps of a project (null = the business default set), in order. */
    public List<StatusRow> findSet(UUID projectId) {
        return query(STATUS + " WHERE project_id IS NOT DISTINCT FROM ?::uuid ORDER BY position, name", projectId);
    }

    /** The steps with their open-or-done (not deleted) task counts, as JSON rows. */
    public List<Map<String, Object>> findSetWithCounts(UUID projectId) {
        return jdbc.queryForList("""
            SELECT s.id::text AS "id", s.name AS "name", s.category AS "category", s.color AS "color", s.position AS "position", s.is_initial AS "isInitial",
                   (SELECT count(*) FROM tasks t WHERE t.status_id = s.id AND t.deleted_at IS NULL) AS "taskCount"
            FROM task_statuses s WHERE s.project_id IS NOT DISTINCT FROM ?::uuid ORDER BY s.position, s.name""", projectId);
    }

    public boolean hasDefaultSet() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM task_statuses WHERE project_id IS NULL)", Boolean.class));
    }

    /** Creates the business default set from {@code statuses} unless the business has one already. */
    public void insertDefaultSetIfMissing(List<WorkflowRules.Status> statuses) {
        if (hasDefaultSet()) return;
        for (int i = 0; i < statuses.size(); i++) {
            WorkflowRules.Status status = statuses.get(i);
            insert(UUID.randomUUID(), null, status.name(), status.category(), status.color(), i + 1, status.isInitial());
        }
    }

    /** Inserts a step; a step with the same name in the same set is skipped (two parallel first writes are safe). */
    public void insert(UUID id, UUID projectId, String name, String category, String color, int position, boolean isInitial) {
        jdbc.update("INSERT INTO task_statuses (id, project_id, name, category, color, position, is_initial) VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
                id, projectId, name, category, color, position, isInitial);
    }

    /** Copies the business default set into a new project. */
    public void copyDefaultSet(UUID projectId) {
        jdbc.update("INSERT INTO task_statuses (id, project_id, name, category, color, position, is_initial) "
                + "SELECT gen_random_uuid(), ?, name, category, color, position, is_initial FROM task_statuses WHERE project_id IS NULL", projectId);
    }

    public void update(UUID id, String name, String category, String color) {
        jdbc.update("UPDATE task_statuses SET name = ?, category = ?, color = ? WHERE id = ?", name, category, color, id);
    }

    /** Makes {@code id} the step new tasks start in (the old one first loses the flag: one per set). */
    public void makeInitial(UUID projectId, UUID id) {
        jdbc.update("UPDATE task_statuses SET is_initial = false WHERE project_id IS NOT DISTINCT FROM ?::uuid AND is_initial AND id <> ?", projectId, id);
        jdbc.update("UPDATE task_statuses SET is_initial = true WHERE id = ?", id);
    }

    public void setPosition(UUID id, int position) {
        jdbc.update("UPDATE task_statuses SET position = ? WHERE id = ?", position, id);
    }

    public void delete(UUID id) {
        jdbc.update("DELETE FROM task_statuses WHERE id = ?", id);
    }

    /** Tasks in a step; {@code isDeletedIncluded} also counts soft-deleted ones (they still point at it). */
    public int countTasks(UUID statusId, boolean isDeletedIncluded) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM tasks WHERE status_id = ?" + (isDeletedIncluded ? "" : " AND deleted_at IS NULL"), Integer.class, statusId);
        return count == null ? 0 : count;
    }

    /** Projects of the business (not deleted). */
    public int countProjects() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM projects WHERE deleted_at IS NULL", Integer.class);
        return count == null ? 0 : count;
    }

    /** Tasks of the business (not deleted). */
    public int countAllTasks() {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM tasks WHERE deleted_at IS NULL", Integer.class);
        return count == null ? 0 : count;
    }

    /** Moves every task (deleted ones too) to another step of the same set; the completion stamp follows the new step's category. */
    public int moveTasks(UUID fromStatusId, UUID toStatusId, boolean isDone, UUID actorId) {
        return jdbc.update("UPDATE tasks SET status_id = ?, completed_at = CASE WHEN ? THEN coalesce(completed_at, now()) END, "
                + "completed_by = CASE WHEN ? THEN coalesce(completed_by, ?::uuid) END, updated_at = now() WHERE status_id = ?",
                toStatusId, isDone, isDone, actorId, fromStatusId);
    }

    /** After a step changed category: its tasks become done (stamped now) or open again. */
    public void syncCompletion(UUID statusId, boolean isDone, UUID actorId) {
        jdbc.update("UPDATE tasks SET completed_at = CASE WHEN ? THEN coalesce(completed_at, now()) END, completed_by = CASE WHEN ? THEN coalesce(completed_by, ?::uuid) END "
                + "WHERE status_id = ?", isDone, isDone, actorId, statusId);
    }
}
