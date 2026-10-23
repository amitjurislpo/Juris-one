package com.jurislpo.jurisone.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Task projects and their members (tenant-scoped by row-level security). Soft-deleted projects are never returned. */
@Repository
public class ProjectRepository {

    /** The facts the access rules need about one project for one employee. */
    public record ProjectFacts(UUID id, String key, String name, String visibility, String status, int version, String myRole) {
        public boolean isArchived() {
            return "ARCHIVED".equals(status);
        }
    }

    /** Open and overdue task counts of project {@code p} (overdue in each task's time zone). */
    static final String STATS = """
        LEFT JOIN LATERAL (
            SELECT count(*) AS open, count(*) FILTER (WHERE %s) AS overdue
            FROM tasks t
            LEFT JOIN employees ae ON ae.id = t.assignee_id
            LEFT JOIN employees ie ON ie.id = t.indirect_assignee_id
            JOIN employees re ON re.id = t.reporter_id
            CROSS JOIN LATERAL (SELECT coalesce(ae.time_zone, ie.time_zone, re.time_zone, 'Asia/Kolkata') AS zone) tz
            CROSS JOIN LATERAL (SELECT (now() AT TIME ZONE tz.zone) AS local_now) clock
            WHERE t.project_id = p.id AND t.deleted_at IS NULL AND t.completed_at IS NULL) stats ON true""".formatted(TaskQuery.OVERDUE);

    /** ProjectRow (plan §12.4); one argument: the viewer's id (for {@code myRole}). */
    static final String ROW = """
        SELECT p.id::text AS "id", p.key AS "key", p.name AS "name", p.description AS "description", p.visibility AS "visibility", p.status AS "status",
               p.color AS "color", p.department_id::text AS "departmentId", d.name AS "departmentName",
               (SELECT coalesce(array_agg(e.full_name ORDER BY e.full_name), '{}') FROM project_members m JOIN employees e ON e.id = m.employee_id
                WHERE m.project_id = p.id AND m.role = 'OWNER') AS "ownerNames",
               mine.role AS "myRole", (SELECT count(*) FROM project_members m WHERE m.project_id = p.id) AS "memberCount",
               stats.open AS "openTaskCount", stats.overdue AS "overdueTaskCount", %s AS "updatedAt"
        FROM projects p
        LEFT JOIN departments d ON d.id = p.department_id
        LEFT JOIN project_members mine ON mine.project_id = p.id AND mine.employee_id = ?
        %s""".formatted(TaskQuery.iso("p.updated_at"), STATS);

    private final JdbcTemplate jdbc;

    public ProjectRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The projects the viewer can see ({@code isTaskAdmin}: all), filtered; {@code orderBy} is a fixed SQL fragment chosen by the service. */
    public List<Map<String, Object>> findRows(UUID viewerId, boolean isTaskAdmin, String status, String search, String orderBy, int limit, int offset) {
        List<Object> args = new ArrayList<>();
        args.add(viewerId);
        String where = where(isTaskAdmin, status, search, args);
        args.add(limit);
        args.add(offset);
        return JdbcRows.normalize(jdbc.queryForList(ROW + where + " ORDER BY " + orderBy + ", lower(p.name) LIMIT ? OFFSET ?", args.toArray()));
    }

    public int countRows(UUID viewerId, boolean isTaskAdmin, String status, String search) {
        List<Object> args = new ArrayList<>();
        args.add(viewerId);
        String where = where(isTaskAdmin, status, search, args);
        Integer count = jdbc.queryForObject("SELECT count(*) FROM projects p LEFT JOIN project_members mine ON mine.project_id = p.id AND mine.employee_id = ?" + where,
                Integer.class, args.toArray());
        return count == null ? 0 : count;
    }

    private static String where(boolean isTaskAdmin, String status, String search, List<Object> args) {
        StringBuilder where = new StringBuilder(" WHERE p.deleted_at IS NULL AND (?::boolean OR mine.role IS NOT NULL OR p.visibility = 'BUSINESS')");
        args.add(isTaskAdmin);
        if (!"ALL".equals(status)) {
            where.append(" AND p.status = ?");
            args.add(status);
        }
        if (!search.isEmpty()) {
            where.append(" AND (p.name ILIKE ? OR p.key ILIKE ?)");
            String like = PeopleQuery.like(search);
            args.add(like);
            args.add(like);
        }
        return where.toString();
    }

    public List<Map<String, Object>> findRowById(UUID projectId, UUID viewerId) {
        return JdbcRows.normalize(jdbc.queryForList(ROW + " WHERE p.id = ? AND p.deleted_at IS NULL", viewerId, projectId));
    }

    public List<ProjectFacts> findFacts(UUID projectId, UUID employeeId) {
        return jdbc.query("""
            SELECT p.id, p.key, p.name, p.visibility, p.status, p.version, (SELECT m.role FROM project_members m WHERE m.project_id = p.id AND m.employee_id = ?) AS my_role
            FROM projects p WHERE p.id = ? AND p.deleted_at IS NULL""",
                (resultSet, rowNumber) -> new ProjectFacts((UUID) resultSet.getObject("id"), resultSet.getString("key"), resultSet.getString("name"),
                        resultSet.getString("visibility"), resultSet.getString("status"), resultSet.getInt("version"), resultSet.getString("my_role")),
                employeeId, projectId);
    }

    /** {@code version, key, name, description, visibility, color, departmentId} of a project (for the audit "before"). */
    public Map<String, Object> findEditable(UUID projectId) {
        return jdbc.queryForMap("SELECT version AS \"version\", key AS \"key\", name AS \"name\", description AS \"description\", visibility AS \"visibility\", "
                + "color AS \"color\", department_id::text AS \"departmentId\" FROM projects WHERE id = ?", projectId);
    }

    /** Keys stay taken by deleted projects too (old task links contain them). */
    public boolean keyTaken(String key, UUID exceptId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM projects WHERE key = ? AND (?::uuid IS NULL OR id <> ?::uuid))",
                Boolean.class, key, exceptId, exceptId));
    }

    public boolean nameTaken(String name, UUID exceptId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM projects WHERE lower(name) = lower(?) AND deleted_at IS NULL AND (?::uuid IS NULL OR id <> ?::uuid))",
                Boolean.class, name, exceptId, exceptId));
    }

    public boolean isActiveEmployee(UUID employeeId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM employees WHERE id = ? AND status <> 'EXITED')", Boolean.class, employeeId));
    }

    public boolean departmentExists(UUID departmentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM departments WHERE id = ?)", Boolean.class, departmentId));
    }

    public void insert(UUID id, String key, String name, String description, String visibility, String color, UUID departmentId, UUID createdBy) {
        jdbc.update("INSERT INTO projects (id, key, name, description, visibility, color, department_id, created_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, key, name, description, visibility, color, departmentId, createdBy);
    }

    /** Saves the project if it still has {@code version}; returns the new version or null. */
    public Integer update(UUID id, int version, String key, String name, String description, String visibility, String color, UUID departmentId) {
        List<Integer> updated = jdbc.queryForList("UPDATE projects SET key = ?, name = ?, description = ?, visibility = ?, color = ?, department_id = ?, "
                + "version = version + 1, updated_at = now() WHERE id = ? AND version = ? AND deleted_at IS NULL RETURNING version",
                Integer.class, key, name, description, visibility, color, departmentId, id, version);
        return updated.isEmpty() ? null : updated.get(0);
    }

    public void setStatus(UUID id, String status) {
        jdbc.update("UPDATE projects SET status = ?, archived_at = CASE WHEN ? = 'ARCHIVED' THEN now() END, updated_at = now() WHERE id = ?", status, status, id);
    }

    public void softDelete(UUID id, UUID actorId) {
        jdbc.update("UPDATE projects SET deleted_at = now(), deleted_by = ?, updated_at = now() WHERE id = ?", actorId, id);
    }

    public void touch(UUID id) {
        jdbc.update("UPDATE projects SET updated_at = now() WHERE id = ?", id);
    }

    /** Tasks of the project; {@code isDeletedIncluded} counts soft-deleted ones too. */
    public int countTasks(UUID projectId, boolean isDeletedIncluded) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM tasks WHERE project_id = ?" + (isDeletedIncluded ? "" : " AND deleted_at IS NULL"), Integer.class, projectId);
        return count == null ? 0 : count;
    }

    // ── members ──

    public List<Map<String, Object>> findMembers(UUID projectId) {
        return jdbc.queryForList("""
            SELECT m.employee_id::text AS "employeeId", e.code AS "code", e.full_name AS "name", (extract(epoch FROM e.photo_updated_at) * 1000)::bigint AS "photo",
                   e.designation AS "designation", m.role AS "role", e.status <> 'EXITED' AS "isActive"
            FROM project_members m JOIN employees e ON e.id = m.employee_id WHERE m.project_id = ?
            ORDER BY CASE m.role WHEN 'OWNER' THEN 0 WHEN 'MEMBER' THEN 1 ELSE 2 END, e.full_name""", projectId);
    }

    public String findMemberRole(UUID projectId, UUID employeeId) {
        List<String> roles = jdbc.queryForList("SELECT role FROM project_members WHERE project_id = ? AND employee_id = ?", String.class, projectId, employeeId);
        return roles.isEmpty() ? null : roles.get(0);
    }

    /** Adds a member unless they already are one; returns whether a row was added. */
    public boolean addMember(UUID projectId, UUID employeeId, String role, UUID addedBy) {
        return jdbc.update("INSERT INTO project_members (project_id, employee_id, role, added_by) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
                projectId, employeeId, role, addedBy) > 0;
    }

    public void setMemberRole(UUID projectId, UUID employeeId, String role) {
        jdbc.update("UPDATE project_members SET role = ? WHERE project_id = ? AND employee_id = ?", role, projectId, employeeId);
    }

    public void removeMember(UUID projectId, UUID employeeId) {
        jdbc.update("DELETE FROM project_members WHERE project_id = ? AND employee_id = ?", projectId, employeeId);
    }

    public int countOwners(UUID projectId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM project_members WHERE project_id = ? AND role = 'OWNER'", Integer.class, projectId);
        return count == null ? 0 : count;
    }

    public int countMembers(UUID projectId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM project_members WHERE project_id = ?", Integer.class, projectId);
        return count == null ? 0 : count;
    }
}
