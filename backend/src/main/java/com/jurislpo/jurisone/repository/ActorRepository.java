package com.jurislpo.jurisone.repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Lookups behind the signed-in actor and a target employee record. */
@Repository
public class ActorRepository {

    private final JdbcTemplate jdbc;

    public ActorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Ids of the roles a role may assign. */
    public List<String> findAssignableRoleIds(UUID roleId) {
        return jdbc.queryForList("SELECT assignable_role_id::text FROM role_assignable_roles WHERE role_id = ?", String.class, roleId);
    }

    /** The employee's department id (null when none); throws when the employee does not exist. */
    public String findDepartmentId(UUID employeeId) {
        return jdbc.queryForObject("SELECT department_id::text FROM employees WHERE id = ?", String.class, employeeId);
    }

    /** Ids of the role keyed 'employee' (zero or one). */
    public List<String> findEmployeeRoleIds() {
        return jdbc.queryForList("SELECT id::text FROM roles WHERE key = 'employee'", String.class);
    }

    /** id, role_id, status, role_key and is_super_admin of an employee (empty when not found). */
    public List<Map<String, Object>> findTarget(UUID id) {
        return jdbc.queryForList("""
            SELECT e.id::text AS id, e.role_id::text AS role_id, e.status::text AS status, r.key AS role_key, u.is_super_admin
            FROM employees e JOIN roles r ON r.id = e.role_id LEFT JOIN users u ON u.employee_id = e.id WHERE e.id = ?""", id);
    }
}
