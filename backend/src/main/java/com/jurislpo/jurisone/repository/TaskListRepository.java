package com.jurislpo.jurisone.repository;

import java.util.LinkedHashMap;
import com.jurislpo.jurisone.service.EmployeeNameDirectory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Runs the task list queries built by {@link TaskQuery}, plus the Team tasks summary and the people picker. */
@Repository
public class TaskListRepository {

    private final JdbcTemplate jdbc;
    private final com.jurislpo.jurisone.crypto.EmployeeNameReader nameReader;
    private final com.jurislpo.jurisone.service.EmployeeNameDirectory nameDirectory;

    public TaskListRepository(JdbcTemplate jdbc, com.jurislpo.jurisone.crypto.EmployeeNameReader nameReader, com.jurislpo.jurisone.service.EmployeeNameDirectory nameDirectory) {
        this.nameDirectory = nameDirectory;
        this.nameReader = nameReader;
        this.jdbc = jdbc;
    }

    public List<Map<String, Object>> findRows(TaskQuery query) {
        return resolveNames(jdbc.queryForList(query.rowsSql(), query.rowsArgs()));
    }

    public List<Map<String, Object>> findBoard(TaskQuery query, int limit) {
        return resolveNames(jdbc.queryForList(query.boardSql(), query.boardArgs(limit)));
    }

    private List<Map<String, Object>> resolveNames(List<Map<String, Object>> rows) {
        return nameReader.resolveAll(rows, "assigneeName", "indirectAssigneeName", "reporterName");
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
        return nameReader.resolveAll(jdbc.queryForList("""
            WITH RECURSIVE team(employee_id, depth) AS (
                SELECT id, 1 FROM employees WHERE reporting_manager_id = ? AND status <> 'EXITED'
                UNION ALL
                SELECT e.id, team.depth + 1 FROM employees e JOIN team ON e.reporting_manager_id = team.employee_id WHERE team.depth < ? AND e.status <> 'EXITED')
            SELECT e.id::text AS "employeeId", e.full_name AS "name", e.full_name_enc AS "name_enc", e.code AS "code", (extract(epoch FROM e.photo_updated_at) * 1000)::bigint AS "photo",
                   min(team.depth) AS "depth",
                   count(DISTINCT t.id) FILTER (WHERE t.completed_at IS NULL) AS "open",
                   count(DISTINCT t.id) FILTER (WHERE %s) AS "overdue",
                   count(DISTINCT t.id) FILTER (WHERE t.completed_at >= now() - interval '7 days') AS "doneLast7Days"
            FROM team
            JOIN employees e ON e.id = team.employee_id
            LEFT JOIN tasks t ON t.assignee_id = e.id AND t.deleted_at IS NULL
                AND NOT EXISTS (SELECT 1 FROM projects p WHERE p.id = t.project_id AND (p.deleted_at IS NOT NULL OR p.status = 'ARCHIVED'))
            CROSS JOIN LATERAL (SELECT (now() AT TIME ZONE e.time_zone) AS local_now) clock
            GROUP BY e.id, e.full_name, e.full_name_enc, e.code, e.photo_updated_at""".formatted(TaskQuery.OVERDUE), managerId, depth), "name").stream()
                .sorted(java.util.Comparator.comparingLong((Map<String, Object> row) -> ((Number) row.get("depth")).longValue())
                        .thenComparing(row -> (String) row.get("name"), com.jurislpo.jurisone.crypto.EmployeeNameReader.NAME_ORDER)).toList();
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
    public List<Map<String, Object>> findAssignees(UUID projectId, String search) {
        List<Object> args = new ArrayList<>();
        args.add(projectId);
        String searchFilter = "";
        if (search != null) {
            String nameMatch = nameDirectory.contains(EmployeeNameDirectory.Field.FULL_NAME, "e.id", "e.full_name", search, args);
            args.add(PeopleQuery.like(search));
            searchFilter = " AND (" + nameMatch + " OR e.code ILIKE ?)";
        }
        String nameOrder = nameDirectory.orderBy(EmployeeNameDirectory.Field.FULL_NAME, "e.id", "e.full_name", true, args);
        return nameReader.resolveAll(jdbc.queryForList("""
            SELECT e.id::text AS "id", e.code AS "code", e.full_name AS "name", e.full_name_enc AS "name_enc", (extract(epoch FROM e.photo_updated_at) * 1000)::bigint AS "photo",
                   e.designation AS "designation", e.designation_enc AS "designation_enc",
                   EXISTS (SELECT 1 FROM project_members m WHERE m.project_id = ?::uuid AND m.employee_id = e.id) AS "isMember",
                   e.reporting_manager_id::text AS "reportingManagerId", m.full_name AS "reportingManagerName", m.full_name_enc AS "reportingManagerName_enc"
            FROM employees e
            LEFT JOIN employees m ON m.id = e.reporting_manager_id
            WHERE e.status <> 'EXITED'""" + searchFilter + " ORDER BY \"isMember\" DESC, " + nameOrder + " LIMIT 20", args.toArray()), "name", "designation", "reportingManagerName");
    }
}
