package com.jurislpo.jurisone.repository;

import java.sql.Date;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The signed-in employee's own record ("My profile"). */
@Repository
public class ProfileRepository {

    private final JdbcTemplate jdbc;

    public ProfileRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The employee row plus department, manager, role and shift names. */
    public Map<String, Object> findProfile(UUID id) {
        return jdbc.queryForMap("""
            SELECT e.*, d.name AS dept, m.full_name AS manager, r.name AS role_name, s.name AS shift_name
            FROM employees e JOIN roles r ON r.id = e.role_id LEFT JOIN departments d ON d.id = e.department_id
            LEFT JOIN employees m ON m.id = e.reporting_manager_id LEFT JOIN shifts s ON s.id = e.shift_id WHERE e.id = ?""", id);
    }

    /** Saves the self-service fields (addresses already encrypted). */
    public void updateSelfService(UUID id, String fatherName, Date dateOfBirth, String addressEnc, String permanentAddressEnc, String emergencyName,
                                  String emergencyRelation, String emergencyPhone) {
        jdbc.update("""
            UPDATE employees SET father_name = ?, date_of_birth = ?, address_enc = ?, permanent_address_enc = ?, emergency_name = ?, emergency_relation = ?,
              emergency_phone = ?, updated_at = now() WHERE id = ?""",
                fatherName, dateOfBirth, addressEnc, permanentAddressEnc, emergencyName, emergencyRelation, emergencyPhone, id);
    }
}
