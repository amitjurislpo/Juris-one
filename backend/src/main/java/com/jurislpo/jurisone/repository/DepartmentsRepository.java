package com.jurislpo.jurisone.repository;

import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.service.DepartmentsService.Person;
import com.jurislpo.jurisone.service.DepartmentsService.Row;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Departments and the counts behind adding or removing one. */
@Repository
public class DepartmentsRepository {

    private final JdbcTemplate jdbc;

    public DepartmentsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every non-archived department with its head, creator and current headcount; "on" is the creation date in {@code timeZone}. */
    public List<Row> findActive(String timeZone) {
        return jdbc.query("""
            SELECT d.id, d.name, d.description, h.full_name AS head, c.full_name AS by, d.created_at,
                   (SELECT count(*) FROM employees e WHERE e.department_id = d.id AND e.status <> 'EXITED') AS n
            FROM departments d LEFT JOIN employees h ON h.id = d.head_employee_id LEFT JOIN employees c ON c.id = d.created_by
            WHERE d.archived_at IS NULL""",
                (rs, i) -> new Row(rs.getString("id"), rs.getString("name"), rs.getString("description") == null ? "" : rs.getString("description"),
                        rs.getString("head") == null ? "" : rs.getString("head"), rs.getInt("n"), rs.getString("by") == null ? "—" : rs.getString("by"),
                        Format.isoInZone(rs.getTimestamp("created_at").toInstant(), timeZone).toString()));
    }

    /** Current employees who can be picked as head, by name. */
    public List<Person> findHeadCandidates() {
        return jdbc.query("SELECT id, full_name, designation FROM employees WHERE status <> 'EXITED' ORDER BY full_name ASC",
                (rs, i) -> new Person(rs.getString(1), rs.getString(2), rs.getString(3)));
    }

    /** id and archived_at of the department with this name, ignoring case (at most one). */
    public List<Map<String, Object>> findByNameIgnoreCase(String name) {
        return jdbc.queryForList("SELECT id, archived_at FROM departments WHERE lower(name) = lower(?) LIMIT 1", name);
    }

    /** The employee's status (empty when not found). */
    public List<String> findEmployeeStatus(UUID employeeId) {
        return jdbc.queryForList("SELECT status::text FROM employees WHERE id = ?", String.class, employeeId);
    }

    /** Brings an archived department back with new details. */
    public void restore(UUID id, String name, UUID headId, String description, UUID createdBy, Timestamp createdAt) {
        jdbc.update("UPDATE departments SET name = ?, archived_at = NULL, head_employee_id = ?, description = ?, created_by = ?, created_at = ? WHERE id = ?",
                name, headId, description, createdBy, createdAt, id);
    }

    public void insert(UUID id, String name, UUID headId, String description, UUID createdBy) {
        jdbc.update("INSERT INTO departments (id, name, head_employee_id, description, created_by, created_at) VALUES (?, ?, ?, ?, ?, now())",
                id, name, headId, description, createdBy);
    }

    /** name, description and archived_at of a department (empty when not found). */
    public List<Map<String, Object>> findForDelete(UUID id) {
        return jdbc.queryForList("SELECT name, description, archived_at FROM departments WHERE id = ?", id);
    }

    /** All employees ever in the department, past ones included. */
    public Integer countEmployees(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM employees WHERE department_id = ?", Integer.class, id);
    }

    public Integer countCurrentEmployees(UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM employees WHERE department_id = ? AND status <> 'EXITED'", Integer.class, id);
    }

    /** Archives the department and clears its head. */
    public void archive(UUID id) {
        jdbc.update("UPDATE departments SET archived_at = now(), head_employee_id = NULL WHERE id = ?", id);
    }

    public void delete(UUID id) {
        jdbc.update("DELETE FROM departments WHERE id = ?", id);
    }
}
