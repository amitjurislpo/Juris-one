package com.jurislpo.jurisone.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Badges, the next holiday and the profile card behind the app shell. */
@Repository
public class ShellRepository {

    private final JdbcTemplate jdbc;

    public ShellRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Pending leave requests of everyone but the given employee. */
    public Integer countPendingLeave(UUID exceptEmployeeId) {
        return jdbc.queryForObject("SELECT count(*) FROM leave_requests WHERE status = 'PENDING' AND employee_id <> ?", Integer.class, exceptEmployeeId);
    }

    /** Pending regularizations of everyone but the given employee. */
    public Integer countPendingRegularizations(UUID exceptEmployeeId) {
        return jdbc.queryForObject("SELECT count(*) FROM regularization_requests WHERE status = 'PENDING' AND employee_id <> ?", Integer.class, exceptEmployeeId);
    }

    /** Investment proofs to verify, except the given employee's own. */
    public Integer countPendingProofs(UUID exceptEmployeeId) {
        return jdbc.queryForObject("SELECT count(*) FROM declaration_items WHERE status = 'PENDING' AND employee_id <> ?", Integer.class, exceptEmployeeId);
    }

    /** My open tasks (direct or indirect assignee) that are overdue or due today, each in its task time zone: the My tasks badge. */
    public int countMyTasksDueByToday(UUID employeeId) {
        Integer count = jdbc.queryForObject("""
            SELECT count(*) FROM tasks t
            LEFT JOIN projects p ON p.id = t.project_id
            LEFT JOIN employees ae ON ae.id = t.assignee_id
            LEFT JOIN employees ie ON ie.id = t.indirect_assignee_id
            JOIN employees re ON re.id = t.reporter_id
            WHERE (t.assignee_id = ? OR t.indirect_assignee_id = ?) AND t.completed_at IS NULL AND t.deleted_at IS NULL AND t.due_date IS NOT NULL
              AND (p.id IS NULL OR (p.deleted_at IS NULL AND p.status = 'ACTIVE'))
              AND t.due_date <= (now() AT TIME ZONE coalesce(ae.time_zone, ie.time_zone, re.time_zone, 'Asia/Kolkata'))::date""", Integer.class, employeeId, employeeId);
        return count == null ? 0 : count;
    }

    /** name and date of the next holiday (from today) for the region or everyone; empty when none. */
    public List<Map<String, Object>> nextHoliday(LocalDate today, String region) {
        return jdbc.queryForList(
                "SELECT name, date FROM holidays WHERE date >= ? AND region::text IN ('ALL', ?) ORDER BY date ASC LIMIT 1", today, region);
    }

    /** department, manager, date_of_joining, shift_code, start_local, end_local of the employee. */
    public Map<String, Object> profileCard(UUID employeeId) {
        return jdbc.queryForMap("""
            SELECT d.name AS department, m.full_name AS manager, e.date_of_joining, s.code AS shift_code, s.start_local, s.end_local
            FROM employees e LEFT JOIN departments d ON d.id = e.department_id LEFT JOIN employees m ON m.id = e.reporting_manager_id
            LEFT JOIN shifts s ON s.id = e.shift_id WHERE e.id = ?""", employeeId);
    }

    public String businessName(UUID businessId) {
        return jdbc.queryForObject("SELECT name FROM businesses WHERE id = ?", String.class, businessId);
    }
}
