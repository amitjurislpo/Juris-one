package com.jurislpo.jurisone.repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The additional roles of employees (table {@code employee_additional_roles}); every query runs inside the signed-in business. */
@Repository
public class AdditionalRolesRepository {

    public record RoleRow(UUID id, String key, String name) {}

    private final JdbcTemplate jdbc;

    public AdditionalRolesRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The additional roles of an employee, oldest assignment first. */
    public List<RoleRow> findAdditionalRoles(UUID employeeId) {
        return jdbc.query("""
                SELECT r.id, r.key, r.name FROM employee_additional_roles a JOIN roles r ON r.id = a.role_id
                WHERE a.employee_id = ? ORDER BY a.assigned_at, r.name""",
                (resultSet, i) -> new RoleRow((UUID) resultSet.getObject(1), resultSet.getString(2), resultSet.getString(3)), employeeId);
    }

    /** The main role of an employee (empty when the employee is not in this business). */
    public List<RoleRow> findMainRole(UUID employeeId) {
        return jdbc.query("SELECT r.id, r.key, r.name FROM employees e JOIN roles r ON r.id = e.role_id WHERE e.id = ?",
                (resultSet, i) -> new RoleRow((UUID) resultSet.getObject(1), resultSet.getString(2), resultSet.getString(3)), employeeId);
    }

    /** The roles of this business with these ids (ids that are not in this business are simply missing). */
    public List<RoleRow> findRoles(List<UUID> roleIds) {
        if (roleIds.isEmpty()) return List.of();
        return jdbc.query("SELECT id, key, name FROM roles WHERE id = ANY (?::uuid[]) ORDER BY name",
                (resultSet, i) -> new RoleRow((UUID) resultSet.getObject(1), resultSet.getString(2), resultSet.getString(3)),
                (Object) roleIds.stream().map(UUID::toString).toArray(String[]::new));
    }

    /** Every role of this business, oldest first. */
    public List<RoleRow> findAllRoles() {
        return jdbc.query("SELECT id, key, name FROM roles ORDER BY created_at, name",
                (resultSet, i) -> new RoleRow((UUID) resultSet.getObject(1), resultSet.getString(2), resultSet.getString(3)));
    }

    /** The permission keys of each given role. */
    public Map<UUID, List<String>> findPermissionKeys(List<UUID> roleIds) {
        Map<UUID, List<String>> byRole = new java.util.HashMap<>();
        if (roleIds.isEmpty()) return byRole;
        jdbc.query("SELECT role_id, permission_key FROM role_permissions WHERE role_id = ANY (?::uuid[])",
                resultSet -> { byRole.computeIfAbsent((UUID) resultSet.getObject(1), id -> new java.util.ArrayList<>()).add(resultSet.getString(2)); },
                (Object) roleIds.stream().map(UUID::toString).toArray(String[]::new));
        return byRole;
    }

    public void deleteAll(UUID employeeId) {
        jdbc.update("DELETE FROM employee_additional_roles WHERE employee_id = ?", employeeId);
    }

    public void insert(UUID employeeId, UUID roleId, UUID assignedBy) {
        jdbc.update("INSERT INTO employee_additional_roles (employee_id, role_id, assigned_by) VALUES (?, ?, ?)", employeeId, roleId, assignedBy);
    }

}
