package com.jurislpo.jurisone.repository;

import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.service.BusinessAdminService.BusinessRow;
import com.jurislpo.jurisone.service.BusinessAdminService.Owner;
import com.jurislpo.jurisone.service.SubscriptionService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The super admin's view of the customer businesses and their sub admins (owners). */
@Repository
public class BusinessAdminRepository {

    private final JdbcTemplate jdbc;

    public BusinessAdminRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Every customer business by name; {@code subscription} gives each row's current subscription (called while mapping). */
    public List<BusinessRow> findAll(Function<UUID, SubscriptionService.Current> subscription) {
        return jdbc.query("""
            SELECT b.id, b.name, b.code, b.employee_prefix, b.status, b.created_at,
                   (SELECT count(*) FROM employees e WHERE e.business_id = b.id AND e.status <> 'EXITED') AS employees,
                   (SELECT count(*) FROM employees e JOIN roles r ON r.id = e.role_id WHERE e.business_id = b.id AND r.key = 'owner') AS owners
            FROM businesses b WHERE NOT b.is_platform ORDER BY b.name""",
                (rs, i) -> new BusinessRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getLong(7), rs.getLong(8),
                        Format.isoTimestamp(rs.getTimestamp(6).toInstant()), subscription.apply((UUID) rs.getObject(1))));
    }

    /** The profile row of a customer business (zero or one). */
    public List<Map<String, Object>> findProfile(UUID id) {
        return jdbc.queryForList("""
            SELECT id::text, name, legal_name, code, employee_prefix, gstin, address, contact_email, contact_phone, time_zone, status, created_at
            FROM businesses WHERE id = ? AND NOT is_platform""", id);
    }

    /** The business's owners by name. */
    public List<Owner> findOwners(UUID businessId) {
        return jdbc.query("""
            SELECT e.id::text, e.code, e.full_name, e.email, e.portal_access, e.status::text
            FROM employees e JOIN roles r ON r.id = e.role_id
            WHERE e.business_id = ? AND r.key = 'owner' ORDER BY e.full_name""",
                (rs, i) -> new Owner(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5), rs.getString(6)), businessId);
    }

    /** Ids of businesses (other than {@code editing}, when given) using the code. */
    public List<UUID> findIdsByCode(String code, UUID editing) {
        return jdbc.queryForList("SELECT id FROM businesses WHERE code = ? AND (?::uuid IS NULL OR id <> ?::uuid)", UUID.class, code, editing, editing);
    }

    /** The business's employee ID prefix; throws when it does not exist. */
    public String findEmployeePrefix(UUID id) {
        return jdbc.queryForObject("SELECT employee_prefix FROM businesses WHERE id = ?", String.class, id);
    }

    /** All employees of the business, exited included. */
    public long countEmployees(UUID businessId) {
        return jdbc.queryForObject("SELECT count(*) FROM employees WHERE business_id = ?", Long.class, businessId);
    }

    /** Whether a customer (non-platform) business with this id exists. */
    public record BusinessRole(String id, String key, String name, long employees) {}

    /** The business's roles (system mode), oldest first, with how many people hold each. */
    public List<BusinessRole> findRoles(UUID businessId) {
        return jdbc.query("""
            SELECT r.id::text, r.key, r.name, (SELECT count(*) FROM employees e WHERE e.role_id = r.id) AS employees
            FROM roles r WHERE r.business_id = ? ORDER BY r.created_at, r.name""",
                (rs, i) -> new BusinessRole(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)), businessId);
    }

    public void deleteRole(UUID businessId, UUID roleId) {
        jdbc.update("DELETE FROM roles WHERE id = ? AND business_id = ?", roleId, businessId);
    }

    public boolean customerBusinessExists(UUID id) {
        return !jdbc.queryForList("SELECT 1 FROM businesses WHERE id = ? AND NOT is_platform", id).isEmpty();
    }

    public void updateProfile(UUID id, String name, String legalName, String code, String prefix, String timeZone, String contactEmail, String contactPhone,
                              String address, String gstin) {
        jdbc.update("""
            UPDATE businesses SET name = ?, legal_name = ?, code = ?, employee_prefix = ?, time_zone = ?, contact_email = ?, contact_phone = ?, address = ?,
              gstin = ?, updated_at = now() WHERE id = ?""",
                name, legalName, code, prefix, timeZone, contactEmail, contactPhone, address, gstin, id);
    }

    /** Sets a customer business's status; returns the number of rows changed. */
    public int updateStatus(UUID id, String status) {
        return jdbc.update("UPDATE businesses SET status = ?, updated_at = now() WHERE id = ? AND NOT is_platform", status, id);
    }

    /** Revokes every active session of the business's employees (reason business_suspended). */
    public void revokeSessionsOfBusiness(UUID id) {
        jdbc.update("UPDATE sessions SET revoked_at = now(), revoked_reason = 'business_suspended' WHERE revoked_at IS NULL "
                + "AND employee_id IN (SELECT id FROM employees WHERE business_id = ?)", id);
    }

    /** Whether the email is used by any account. */
    public Boolean emailInUse(String email) {
        return jdbc.queryForObject("SELECT jurisone_email_in_use(?, NULL)", Boolean.class, email);
    }

    /** An employee's code; throws when the employee does not exist. */
    public String findEmployeeCode(UUID employeeId) {
        return jdbc.queryForObject("SELECT code FROM employees WHERE id = ?", String.class, employeeId);
    }

    /** Sets an owner's portal access; returns the number of rows changed (0 = not an owner of that business). */
    public int updateOwnerAccess(UUID ownerId, UUID businessId, boolean on) {
        return jdbc.update("""
            UPDATE employees e SET portal_access = ?, updated_at = now() FROM roles r
            WHERE r.id = e.role_id AND r.key = 'owner' AND e.id = ? AND e.business_id = ?""", on, ownerId, businessId);
    }
}
