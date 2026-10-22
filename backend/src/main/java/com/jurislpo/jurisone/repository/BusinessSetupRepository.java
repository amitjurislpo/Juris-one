package com.jurislpo.jurisone.repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** A new business and its starting defaults (roles, permissions, settings, departments) and sub admins (owners). */
@Repository
public class BusinessSetupRepository {

    private final JdbcTemplate jdbc;

    public BusinessSetupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertBusiness(UUID id, String name, String legalName, String code, String employeePrefix, String timeZone, String contactEmail,
                               String contactPhone, String address, String gstin, UUID createdBy) {
        jdbc.update("""
            INSERT INTO businesses (id, name, legal_name, code, employee_prefix, time_zone, contact_email, contact_phone, address, gstin, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, name, legalName, code, employeePrefix, timeZone, contactEmail, contactPhone, address, gstin, createdBy);
    }

    /** Ids of the current business's roles with this key. */
    public List<UUID> findRoleIds(String key) {
        return jdbc.queryForList("SELECT id FROM roles WHERE key = ?", UUID.class, key);
    }

    /** Inserts a built-in role. */
    public void insertRole(UUID id, String key, String name, boolean locked) {
        jdbc.update("INSERT INTO roles (id, key, name, is_builtin, is_locked) VALUES (?, ?, ?, true, ?)", id, key, name, locked);
    }

    /** id and key of every role of the current business. */
    public List<Map<String, Object>> findRoleKeys() {
        return jdbc.queryForList("SELECT id, key FROM roles");
    }

    public void addAssignableRole(UUID roleId, UUID assignableRoleId) {
        jdbc.update("INSERT INTO role_assignable_roles (role_id, assignable_role_id) VALUES (?, ?) ON CONFLICT DO NOTHING", roleId, assignableRoleId);
    }

    public long countPermissions(UUID roleId) {
        return jdbc.queryForObject("SELECT count(*) FROM role_permissions WHERE role_id = ?", Long.class, roleId);
    }

    public void deletePermissions(UUID roleId) {
        jdbc.update("DELETE FROM role_permissions WHERE role_id = ?", roleId);
    }

    public void insertPermission(UUID roleId, String permission) {
        jdbc.update("INSERT INTO role_permissions (role_id, permission_key) VALUES (?, ?)", roleId, permission);
    }

    public long countAssignableRoles(UUID roleId) {
        return jdbc.queryForObject("SELECT count(*) FROM role_assignable_roles WHERE role_id = ?", Long.class, roleId);
    }

    public void deleteAssignableRoles(UUID roleId) {
        jdbc.update("DELETE FROM role_assignable_roles WHERE role_id = ?", roleId);
    }

    public void insertAssignableRole(UUID roleId, UUID assignableRoleId) {
        jdbc.update("INSERT INTO role_assignable_roles (role_id, assignable_role_id) VALUES (?, ?)", roleId, assignableRoleId);
    }

    /** Inserts a setting unless the business already has it. */
    public void insertSettingIfMissing(String key, String jsonValue) {
        jdbc.update("INSERT INTO settings (key, value, updated_at) VALUES (?, ?::jsonb, now()) ON CONFLICT (business_id, key) DO NOTHING", key, jsonValue);
    }

    /** Inserts a department unless the business already has one with the name. */
    /** The default HR letter templates of the business (SQL function from V15; templates that exist are left alone). */
    public void seedLetterTemplates(UUID businessId) {
        jdbc.query("SELECT jurisone_seed_letter_templates(?)", resultSet -> {}, businessId);
    }

    public void insertDepartmentIfMissing(UUID id, String name, String description, UUID createdBy) {
        jdbc.update("INSERT INTO departments (id, name, description, created_by) VALUES (?, ?, ?, ?) ON CONFLICT (business_id, name) DO NOTHING",
                id, name, description, createdBy);
    }

    /** employee_prefix and time_zone of the current business. */
    public Map<String, Object> findCurrentPrefixAndZone() {
        return jdbc.queryForMap("SELECT employee_prefix, time_zone FROM businesses WHERE id = jurisone_tenant_id()");
    }

    /** The id of the current business's role with this key; throws when there is none. */
    public UUID findRoleId(String key) {
        return jdbc.queryForObject("SELECT id FROM roles WHERE key = ?", UUID.class, key);
    }

    /** Takes the current business's employee-code allocation lock until the transaction ends. */
    public void lockCodeAllocation() {
        jdbc.execute("SELECT pg_advisory_xact_lock(74201, hashtext(jurisone_tenant_id()::text))");
    }

    /** The highest number among employee codes {@code <prefix>-<n>}, or null when there is none. */
    public Integer findMaxCodeNumber(String prefix) {
        return jdbc.queryForObject("SELECT max(substring(code from ?)::int) FROM employees WHERE code ~ ?", Integer.class,
                prefix.length() + 2, "^" + prefix + "-[0-9]+$");
    }

    /** Inserts an ACTIVE employee with portal access. */
    public void insertOwnerEmployee(UUID id, String code, String fullName, String email, String designation, UUID roleId, LocalDate joined, String timeZone,
                                    String region, UUID createdBy) {
        jdbc.update("""
            INSERT INTO employees (id, code, full_name, email, designation, role_id, date_of_joining, status, time_zone, region, portal_access, created_by, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?::"Region", true, ?, now())""",
                id, code, fullName, email, designation, roleId, Date.valueOf(joined), timeZone, region, createdBy);
    }

    /** Inserts a (non super admin) user. */
    public void insertUser(UUID employeeId) {
        jdbc.update("INSERT INTO users (employee_id, is_super_admin) VALUES (?, false)", employeeId);
    }
}
