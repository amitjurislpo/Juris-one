package com.jurislpo.jurisone.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The platform company (the operator's own business), its super admin role and the super admins placed in it. */
@Repository
public class PlatformCompanyRepository {

    private final JdbcTemplate jdbc;

    public PlatformCompanyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Ids of the platform business(es). */
    public List<UUID> findPlatformIds() {
        return jdbc.queryForList("SELECT id FROM businesses WHERE is_platform", UUID.class);
    }

    /** Whether the code {@code jurisone-platform} is already taken. */
    public boolean defaultCodeTaken() {
        return !jdbc.queryForList("SELECT 1 FROM businesses WHERE code = 'jurisone-platform'").isEmpty();
    }

    public void markPlatform(UUID businessId) {
        jdbc.update("UPDATE businesses SET is_platform = true WHERE id = ?", businessId);
    }

    /** Ids of the business's roles with this key. */
    public List<UUID> findRoleIds(UUID businessId, String key) {
        return jdbc.queryForList("SELECT id FROM roles WHERE business_id = ? AND key = ?", UUID.class, businessId, key);
    }

    /** Inserts the locked built-in "Super admin" role. */
    public void insertSuperAdminRole(UUID roleId, String key, UUID businessId) {
        jdbc.update("INSERT INTO roles (id, key, name, is_builtin, is_locked, business_id) VALUES (?, ?, 'Super admin', true, true, ?)", roleId, key, businessId);
    }

    public void grantPermission(UUID roleId, String permission) {
        jdbc.update("INSERT INTO role_permissions (role_id, permission_key) VALUES (?, ?) ON CONFLICT DO NOTHING", roleId, permission);
    }

    /** Lets the role assign every role of the business except owner and itself ({@code key}). */
    public void grantAssignableRoles(UUID roleId, UUID businessId, String key) {
        jdbc.update("""
            INSERT INTO role_assignable_roles (role_id, assignable_role_id)
            SELECT ?, r.id FROM roles r WHERE r.business_id = ? AND r.key NOT IN ('owner', ?) ON CONFLICT DO NOTHING""", roleId, businessId, key);
    }

    /** Moves every super admin into the business with the role; returns how many employees changed. */
    public int placeSuperAdmins(UUID businessId, UUID roleId) {
        return jdbc.update("""
            UPDATE employees e SET business_id = ?, role_id = ?, updated_at = now() FROM users u
            WHERE u.employee_id = e.id AND u.is_super_admin AND (e.business_id IS DISTINCT FROM ? OR e.role_id <> ?)""", businessId, roleId, businessId, roleId);
    }

    /** The business's is_platform flag; throws when it does not exist. */
    public Boolean isPlatform(UUID businessId) {
        return jdbc.queryForObject("SELECT is_platform FROM businesses WHERE id = ?", Boolean.class, businessId);
    }
}
