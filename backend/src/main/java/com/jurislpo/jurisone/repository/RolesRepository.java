package com.jurislpo.jurisone.repository;

import com.jurislpo.jurisone.service.RolesService.Member;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Roles, their permissions and the roles each may assign. */
@Repository
public class RolesRepository {

    private final JdbcTemplate jdbc;

    public RolesRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** id, key, name, is_builtin and is_locked of every role; built-ins first, then by creation. */
    public List<Map<String, Object>> findAll() {
        return jdbc.queryForList("SELECT id, key, name, is_builtin, is_locked FROM roles ORDER BY is_builtin DESC, created_at ASC");
    }

    public List<String> findPermissionsSorted(UUID roleId) {
        return jdbc.queryForList("SELECT permission_key FROM role_permissions WHERE role_id = ? ORDER BY permission_key", String.class, roleId);
    }

    /** The role's permission keys, unordered. */
    public List<String> findPermissions(UUID roleId) {
        return jdbc.queryForList("SELECT permission_key FROM role_permissions WHERE role_id = ?", String.class, roleId);
    }

    public List<String> findAssignableRoleIds(UUID roleId) {
        return jdbc.queryForList("SELECT assignable_role_id::text FROM role_assignable_roles WHERE role_id = ?", String.class, roleId);
    }

    /** Current (not exited) employees with the role, by name. */
    public List<Member> findCurrentMembers(UUID roleId) {
        return jdbc.query("SELECT id, full_name FROM employees WHERE role_id = ? AND status <> 'EXITED' ORDER BY full_name ASC",
                (rs, i) -> new Member(rs.getString(1), rs.getString(2)), roleId);
    }

    /** Employees ever given the role, past ones included. */
    public Integer countEmployees(UUID roleId) {
        return jdbc.queryForObject("SELECT count(*) FROM employees WHERE role_id = ?", Integer.class, roleId);
    }

    /** id, name and is_locked of a role (empty when not found). */
    public List<Map<String, Object>> findForEdit(UUID roleId) {
        return jdbc.queryForList("SELECT id, name, is_locked FROM roles WHERE id = ?", roleId);
    }

    public void grantPermission(UUID roleId, String perm) {
        jdbc.update("INSERT INTO role_permissions (role_id, permission_key) VALUES (?, ?) ON CONFLICT DO NOTHING", roleId, perm);
    }

    public void revokePermission(UUID roleId, String perm) {
        jdbc.update("DELETE FROM role_permissions WHERE role_id = ? AND permission_key = ?", roleId, perm);
    }

    /** The role's name (empty when not found). */
    public List<String> findName(UUID roleId) {
        return jdbc.queryForList("SELECT name FROM roles WHERE id = ?", String.class, roleId);
    }

    public void addAssignable(UUID roleId, UUID assignableId) {
        jdbc.update("INSERT INTO role_assignable_roles (role_id, assignable_role_id) VALUES (?, ?) ON CONFLICT DO NOTHING", roleId, assignableId);
    }

    public void removeAssignable(UUID roleId, UUID assignableId) {
        jdbc.update("DELETE FROM role_assignable_roles WHERE role_id = ? AND assignable_role_id = ?", roleId, assignableId);
    }

    public Integer countByNameIgnoreCase(String name) {
        return jdbc.queryForObject("SELECT count(*) FROM roles WHERE lower(name) = lower(?)", Integer.class, name);
    }

    /** Inserts a custom (not built-in, not locked) role. */
    public void insertCustom(UUID id, String key, String name) {
        jdbc.update("INSERT INTO roles (id, key, name, is_builtin, is_locked, created_at) VALUES (?, ?, ?, false, false, now())", id, key, name);
    }

    public void insertPermission(UUID roleId, String perm) {
        jdbc.update("INSERT INTO role_permissions (role_id, permission_key) VALUES (?, ?)", roleId, perm);
    }

    /** name and is_builtin of a role (empty when not found). */
    public List<Map<String, Object>> findForDelete(UUID roleId) {
        return jdbc.queryForList("SELECT name, is_builtin FROM roles WHERE id = ?", roleId);
    }

    public void delete(UUID roleId) {
        jdbc.update("DELETE FROM roles WHERE id = ?", roleId);
    }
}
