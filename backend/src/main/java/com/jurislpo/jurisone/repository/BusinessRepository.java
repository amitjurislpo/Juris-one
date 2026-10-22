package com.jurislpo.jurisone.repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The owner's view of their business: its profile and the employee export rows. */
@Repository
public class BusinessRepository {

    private final JdbcTemplate jdbc;

    public BusinessRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The business's profile columns; throws when it does not exist. */
    public Map<String, Object> findProfile(UUID businessId) {
        return jdbc.queryForMap("SELECT name, legal_name, code, employee_prefix, contact_email, contact_phone, address, gstin, time_zone FROM businesses WHERE id = ?", businessId);
    }

    /** Every employee of the current business (except the owner / super admin) for the export, by code. */
    public List<Map<String, Object>> findEmployeesForExport() {
        return jdbc.queryForList("""
            SELECT e.code, e.full_name, e.father_name, e.email, e.phone_enc, e.phone_last4, e.designation, d.name AS department_name, r.name AS role,
                   m.full_name AS manager, e.status::text AS status, e.date_of_joining, e.exit_date, e.region::text AS region, e.time_zone,
                   s.code AS shift_code, s.start_local, s.end_local, e.portal_access, e.pan_enc, e.pan_last4, e.aadhaar_enc, e.aadhaar_last4,
                   e.uan_enc, e.uan_last4, e.bank_name, e.bank_account_enc, e.bank_account_last4, e.bank_ifsc, e.address_enc
            FROM employees e JOIN roles r ON r.id = e.role_id
            LEFT JOIN departments d ON d.id = e.department_id LEFT JOIN employees m ON m.id = e.reporting_manager_id LEFT JOIN shifts s ON s.id = e.shift_id
            WHERE r.key NOT IN ('owner', 'super_admin') -- the business admin's own details are never exported
            ORDER BY e.code""");
    }

    /** The business's code; throws when it does not exist. */
    public String findCode(UUID businessId) {
        return jdbc.queryForObject("SELECT code FROM businesses WHERE id = ?", String.class, businessId);
    }
}
