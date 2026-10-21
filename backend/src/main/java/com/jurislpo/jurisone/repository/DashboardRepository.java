package com.jurislpo.jurisone.repository;

import com.jurislpo.jurisone.engine.Shifts;
import com.jurislpo.jurisone.service.DashboardService.MyLeave;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Data access for {@code DashboardService}: Home, company overview and My team. */
@Repository
public class DashboardRepository {

    public record CurrentEmployee(UUID id, String region, boolean portalAccess, UUID departmentId) {}

    public record Dept(UUID id, String name) {}

    public record Member(UUID id, String name, String title) {}

    public record TeamMember(UUID id, String code, String fullName, String designation, String region, String shift, Long photo) {}

    public record TeamLeave(UUID employeeId, LocalDate from, LocalDate to, String status) {}

    public record Holiday(LocalDate date, String region) {}

    private final JdbcTemplate jdbc;

    public DashboardRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** status and approved_at of the payroll run for a month (empty when there is none). */
    public List<Map<String, Object>> runState(String month) {
        return jdbc.queryForList("SELECT status::text AS status, approved_at FROM payroll_runs WHERE month = ?", month);
    }

    /** The latest closed payroll month (empty when no run is closed). */
    public List<String> lastClosedMonth() {
        return jdbc.queryForList("SELECT month FROM payroll_runs WHERE status = 'CLOSED' ORDER BY month DESC LIMIT 1", String.class);
    }

    /** Pending rows of a request table; {@code notMine} is "" or " AND employee_id <> ?" with its argument in {@code args}. */
    public Long countPending(String table, String notMine, Object[] args) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE status = 'PENDING'" + notMine, Long.class, args);
    }

    /** Everyone not exited. */
    public List<CurrentEmployee> currentEmployees() {
        return jdbc.query("SELECT id, region::text, portal_access, department_id FROM employees WHERE status <> 'EXITED'",
                (rs, i) -> new CurrentEmployee((UUID) rs.getObject(1), rs.getString(2), rs.getBoolean(3), (UUID) rs.getObject(4)));
    }

    public List<Dept> activeDepartments() {
        return jdbc.query("SELECT id, name FROM departments WHERE archived_at IS NULL ORDER BY name ASC", (rs, i) -> new Dept((UUID) rs.getObject(1), rs.getString(2)));
    }

    /** India employees on the books by {@code lastDay} without a salary structure effective by then. */
    public long countIndiaWithoutStructure(LocalDate lastDay) {
        return jdbc.queryForObject("""
            SELECT count(*) FROM employees e WHERE e.region = 'IN' AND e.status <> 'EXITED' AND e.date_of_joining <= ?
              AND NOT EXISTS (SELECT 1 FROM salary_structures s WHERE s.employee_id = e.id AND s.effective_from <= ?)""", Long.class, Date.valueOf(lastDay), Date.valueOf(lastDay));
    }

    /** The employee's three latest leave requests. */
    public List<MyLeave> recentLeaves(UUID employeeId) {
        return jdbc.query("""
            SELECT l.id::text, t.name, l.from_date, l.to_date, l.days, l.status::text FROM leave_requests l JOIN leave_types t ON t.code = l.leave_type
            WHERE l.employee_id = ? ORDER BY l.applied_at DESC LIMIT 3""",
                (rs, i) -> new MyLeave(rs.getString(1), rs.getString(2), rs.getDate(3).toLocalDate().toString(), rs.getDate(4).toLocalDate().toString(),
                        rs.getBigDecimal(5).doubleValue(), rs.getString(6)), employeeId);
    }

    /** Active direct reports, by name. */
    public List<Member> directReports(UUID managerId) {
        return jdbc.query("SELECT id, full_name, designation FROM employees WHERE reporting_manager_id = ? AND status <> 'EXITED' ORDER BY full_name ASC",
                (rs, i) -> new Member((UUID) rs.getObject(1), rs.getString(2), rs.getString(3)), managerId);
    }

    /** The employee's latest closed payslip month (empty when none). */
    public List<String> lastClosedPayslipMonth(UUID employeeId) {
        return jdbc.queryForList("""
            SELECT r.month FROM payslips p JOIN payroll_runs r ON r.id = p.run_id WHERE p.employee_id = ? AND r.status = 'CLOSED' ORDER BY r.month DESC LIMIT 1""",
                String.class, employeeId);
    }

    /** CL + EL balance. */
    public Double leaveAvailable(UUID employeeId) {
        return jdbc.queryForObject("SELECT COALESCE(sum(delta), 0)::float8 FROM leave_ledger WHERE employee_id = ? AND leave_type IN ('CL', 'EL')", Double.class, employeeId);
    }

    /** Active direct reports with their shift label (My team). */
    public List<TeamMember> teamMembers(UUID managerId) {
        return jdbc.query("""
            SELECT e.id, e.code, e.full_name, e.designation, e.region::text, s.code AS s_code, s.start_local, s.end_local, e.photo_updated_at
            FROM employees e LEFT JOIN shifts s ON s.id = e.shift_id WHERE e.reporting_manager_id = ? AND e.status <> 'EXITED'""",
                (rs, i) -> new TeamMember((UUID) rs.getObject(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        Shifts.label(rs.getString(6), rs.getString(7), rs.getString(8)), com.jurislpo.jurisone.service.PhotoService.version(rs.getTimestamp(9))), managerId);
    }

    /** Approved or pending leaves of these employees overlapping [from, to]. */
    public List<TeamLeave> leavesOverlapping(UUID[] ids, LocalDate from, LocalDate to) {
        return jdbc.query("""
            SELECT employee_id, from_date, to_date, status::text FROM leave_requests
            WHERE employee_id = ANY (?) AND status IN ('APPROVED', 'PENDING') AND from_date <= ? AND to_date >= ?""",
                (rs, i) -> new TeamLeave((UUID) rs.getObject(1), rs.getDate(2).toLocalDate(), rs.getDate(3).toLocalDate(), rs.getString(4)),
                ids, Date.valueOf(to), Date.valueOf(from));
    }

    public List<Holiday> holidays(LocalDate from, LocalDate to) {
        return jdbc.query("SELECT date, region::text FROM holidays WHERE date >= ? AND date <= ?",
                (rs, i) -> new Holiday(rs.getDate(1).toLocalDate(), rs.getString(2)), Date.valueOf(from), Date.valueOf(to));
    }
}
