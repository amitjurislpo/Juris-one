package com.jurislpo.jurisone.repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Runs the task list queries built by {@link TaskQuery}, plus the Team tasks summary and the people picker. */
@Repository
public class TaskListRepository {

    private final JdbcTemplate jdbc;

    public TaskListRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> findRows(TaskQuery query) {
        return jdbc.queryForList(query.rowsSql(), query.rowsArgs());
    }

    public List<Map<String, Object>> findBoard(TaskQuery query, int limit) {
        return jdbc.queryForList(query.boardSql(), query.boardArgs(limit));
    }

    public int count(TaskQuery query) {
        Integer count = jdbc.queryForObject(query.countSql(), Integer.class, query.countArgs());
        return count == null ? 0 : count;
    }

    public Map<String, Object> bucketCounts(TaskQuery query) {
        return jdbc.queryForMap(query.bucketCountsSql(), query.countArgs());
    }

    /** Matching tasks per step id. */
    public Map<UUID, Long> countByStatus(TaskQuery query) {
        Map<UUID, Long> out = new LinkedHashMap<>();
        jdbc.query(query.statusCountsSql(), resultSet -> {
            out.put((UUID) resultSet.getObject("status_id"), resultSet.getLong("total"));
        }, query.countArgs());
        return out;
    }

    /**
     * The people in the manager's reporting scope (depth ≤ {@code depth}) with their task counts as direct assignee: open, overdue
     * (in their time zone) and done in the last 7 days.
     */
    public List<Map<String, Object>> findTeamPeople(UUID managerId, int depth) {
        return jdbc.queryForList("""
            WITH RECURSIVE team(employee_id, depth) AS (
                SELECT id, 1 FROM employees WHERE reporting_manager_id = ? AND status <> 'EXITED'
                UNION ALL
                SELECT e.id, team.depth + 1 FROM employees e JOIN team ON e.reporting_manager_id = team.employee_id WHERE team.depth < ? AND e.status <> 'EXITED')
            SELECT e.id::text AS "employeeId", e.full_name AS "name", e.code AS "code", (extract(epoch FROM e.photo_updated_at) * 1000)::bigint AS "photo",
                   min(team.depth) AS "depth",
                   count(DISTINCT t.id) FILTER (WHERE t.completed_at IS NULL) AS "open",
                   count(DISTINCT t.id) FILTER (WHERE %s) AS "overdue",
                   count(DISTINCT t.id) FILTER (WHERE t.completed_at >= now() - interval '7 days') AS "doneLast7Days"
            FROM team
            JOIN employees e ON e.id = team.employee_id
            LEFT JOIN tasks t ON t.assignee_id = e.id AND t.deleted_at IS NULL
                AND NOT EXISTS (SELECT 1 FROM projects p WHERE p.id = t.project_id AND (p.deleted_at IS NOT NULL OR p.status = 'ARCHIVED'))
            CROSS JOIN LATERAL (SELECT (now() AT TIME ZONE e.time_zone) AS local_now) clock
            GROUP BY e.id, e.full_name, e.code, e.photo_updated_at
            ORDER BY min(team.depth), e.full_name""".formatted(TaskQuery.OVERDUE), managerId, depth);
    }

    /** Whether the employee is in the manager's reporting scope (depth ≤ {@code depth}). */
    public boolean isInTeam(UUID managerId, UUID employeeId, int depth) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
            WITH RECURSIVE team(employee_id, depth) AS (
                SELECT id, 1 FROM employees WHERE reporting_manager_id = ? AND status <> 'EXITED'
                UNION ALL
                SELECT e.id, team.depth + 1 FROM employees e JOIN team ON e.reporting_manager_id = team.employee_id WHERE team.depth < ? AND e.status <> 'EXITED')
            SELECT EXISTS (SELECT 1 FROM team WHERE employee_id = ?)""", Boolean.class, managerId, depth, employeeId));
    }

    /** Active employees for the assignee pickers (at most 20), project members first when a project is given. */
    public List<Map<String, Object>> findAssignees(UUID projectId, String like) {
        return jdbc.queryForList("""
            SELECT e.id::text AS "id", e.code AS "code", e.full_name AS "name", (extract(epoch FROM e.photo_updated_at) * 1000)::bigint AS "photo",
                   e.designation AS "designation",
                   EXISTS (SELECT 1 FROM project_members m WHERE m.project_id = ?::uuid AND m.employee_id = e.id) AS "isMember",
                   e.reporting_manager_id::text AS "reportingManagerId", m.full_name AS "reportingManagerName"
            FROM employees e
            LEFT JOIN employees m ON m.id = e.reporting_manager_id
            WHERE e.status <> 'EXITED' AND (?::text IS NULL OR e.full_name ILIKE ? OR e.code ILIKE ?)
            ORDER BY 6 DESC, e.full_name LIMIT 20""", projectId, like, like, like);
    }
}
