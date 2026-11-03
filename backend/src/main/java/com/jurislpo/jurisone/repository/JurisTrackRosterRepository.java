package com.jurislpo.jurisone.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** What the roster sync to JurisTrack reads: a business and its employees with their shifts. Reads only (run in system mode, filtered by business). */
@Repository
public class JurisTrackRosterRepository {

    public record BusinessRow(UUID id, String name, String timeZone) {}

    /**
     * One employee as the roster needs them. {@code isJurisTrackManager}: holds the JurisTrack manager permission through the main role or any additional role. The shift columns are null without a shift.
     */
    public record EmployeeRow(UUID id, String email, String fullName, String firstName, String lastName, String code, String status, UUID managerId, boolean isJurisTrackManager,
                              String timeZone, String shiftCode, String shiftName, String shiftStart, String shiftEnd, String shiftRegion) {}

    private final JdbcTemplate jdbc;

    public JurisTrackRosterRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The customer business (never the platform company); empty when unknown. */
    public Optional<BusinessRow> findBusiness(UUID businessId) {
        return jdbc.query("SELECT id, name, time_zone FROM businesses WHERE id = ? AND NOT is_platform",
                (resultSet, i) -> new BusinessRow((UUID) resultSet.getObject(1), resultSet.getString(2), resultSet.getString(3)), businessId).stream().findFirst();
    }

    /** Whether the super admin suspended the business. */
    public boolean isSuspended(UUID businessId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT status = 'SUSPENDED' FROM businesses WHERE id = ?", Boolean.class, businessId));
    }

    /** Every customer business (the platform company has no subscription and no JurisTrack). */
    public List<UUID> findCustomerBusinessIds() {
        return jdbc.queryForList("SELECT id FROM businesses WHERE NOT is_platform ORDER BY created_at", UUID.class);
    }

    /** Every employee of the business, whatever the status (past employees go as deactivated), oldest first. */
    public List<EmployeeRow> findEmployees(UUID businessId) {
        return jdbc.query("""
                SELECT e.id, e.email, e.full_name, e.first_name, e.last_name, e.code, e.status::text, e.reporting_manager_id,
                       jurisone_employee_has_permission(e.id, 'juristrack.manager') AS is_juristrack_manager,
                       e.time_zone, s.code, s.name, s.start_local, s.end_local, s.region::text
                FROM employees e LEFT JOIN shifts s ON s.id = e.shift_id
                WHERE e.business_id = ? ORDER BY e.created_at, e.code""",
                (resultSet, i) -> new EmployeeRow((UUID) resultSet.getObject(1), resultSet.getString(2), resultSet.getString(3), resultSet.getString(4), resultSet.getString(5),
                        resultSet.getString(6), resultSet.getString(7), (UUID) resultSet.getObject(8), resultSet.getBoolean(9), resultSet.getString(10),
                        resultSet.getString(11), resultSet.getString(12), resultSet.getString(13), resultSet.getString(14), resultSet.getString(15)), businessId);
    }
}
