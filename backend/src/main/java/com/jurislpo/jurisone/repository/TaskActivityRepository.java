package com.jurislpo.jurisone.repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The History tab of a task ({@code task_activity}). */
@Repository
public class TaskActivityRepository {

    private final JdbcTemplate jdbc;

    public TaskActivityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code before} / {@code after} are JSON texts (or null). Stamped with the clock (not the transaction start), so rows of one request keep their order. */
    public void insert(UUID id, UUID taskId, UUID projectId, UUID actorId, String kind, String before, String after) {
        jdbc.update("INSERT INTO task_activity (id, task_id, project_id, actor_id, kind, before, after, occurred_at) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, clock_timestamp())",
                id, taskId, projectId, actorId, kind, before, after);
    }

    /** One page, newest first; {@code before} / {@code after} as JSON text. */
    public List<Map<String, Object>> findPage(UUID taskId, int limit, int offset) {
        return JdbcRows.parseJson(jdbc.queryForList("""
            SELECT a.id::text AS "id", a.kind AS "kind", e.full_name AS "actorName", a.before::text AS "before", a.after::text AS "after", %s AS "occurredAt"
            FROM task_activity a LEFT JOIN employees e ON e.id = a.actor_id WHERE a.task_id = ? ORDER BY a.occurred_at DESC, a.id LIMIT ? OFFSET ?"""
                .formatted(TaskQuery.iso("a.occurred_at")), taskId, limit, offset), "before", "after");
    }

    public int count(UUID taskId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM task_activity WHERE task_id = ?", Integer.class, taskId);
        return count == null ? 0 : count;
    }
}
