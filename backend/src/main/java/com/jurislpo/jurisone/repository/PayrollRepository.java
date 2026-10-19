package com.jurislpo.jurisone.repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Data access for {@code PayrollService}: payroll runs, adjustments, payroll employees and payslip snapshots. */
@Repository
public class PayrollRepository {

    private final JdbcTemplate jdbc;

    public PayrollRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The id of the month's payroll run (empty when there is none). */
    public List<Map<String, Object>> runIds(String ym) {
        return jdbc.queryForList("SELECT id FROM payroll_runs WHERE month = ?", ym);
    }

    public List<Map<String, Object>> adjustments(Object runId) {
        return jdbc.queryForList("SELECT * FROM payroll_adjustments WHERE run_id = ?", runId);
    }

    /**
     * India employees on payroll for the month: a structure on its last day and employed some day of it, by name.
     * {@code args} are (last, first, last, month[, ids]) matching {@code idFilter}. Not included: employees whose approved full &
     * final settlement is for {@code month} or an earlier one (their salary was paid through it).
     */
    public List<Map<String, Object>> payrollEmployees(String idFilter, Object[] args) {
        return jdbc.queryForList("""
            SELECT e.id, e.code, e.full_name, e.designation, e.date_of_joining, e.exit_date, d.name AS dept
            FROM employees e LEFT JOIN departments d ON d.id = e.department_id
            WHERE e.region = 'IN' AND e.date_of_joining <= ? AND (e.exit_date IS NULL OR e.exit_date >= ?)
              AND EXISTS (SELECT 1 FROM salary_structures s WHERE s.employee_id = e.id AND s.effective_from <= ?)
              AND NOT EXISTS (SELECT 1 FROM fnf_settlements f WHERE f.employee_id = e.id AND f.status = 'APPROVED' AND f.month <= ?)""" + idFilter + " ORDER BY e.full_name ASC",
                args);
    }

    /** India and company-wide holidays in [first, last]. */
    public List<LocalDate> indiaHolidays(LocalDate first, LocalDate last) {
        return jdbc.query("SELECT date FROM holidays WHERE date >= ? AND date <= ? AND region::text IN ('IN', 'ALL')",
                (rs, i) -> rs.getDate(1).toLocalDate(), Date.valueOf(first), Date.valueOf(last));
    }

    /** The salary structure in force on {@code onDate}. */
    public Map<String, Object> structureOn(UUID employeeId, LocalDate onDate) {
        return jdbc.queryForMap("SELECT basic, hra, lta, special FROM salary_structures WHERE employee_id = ? AND effective_from <= ? ORDER BY effective_from DESC LIMIT 1",
                employeeId, Date.valueOf(onDate));
    }

    /** (employee id, snapshot JSON text) of every payslip in the month's run. */
    public List<Map.Entry<UUID, String>> payslipSnapshots(String ym) {
        return jdbc.query("SELECT p.employee_id, p.snapshot::text FROM payslips p JOIN payroll_runs r ON r.id = p.run_id WHERE r.month = ?",
                (rs, i) -> Map.entry((UUID) rs.getObject(1), rs.getString(2)), ym);
    }

    public List<Map<String, Object>> findRun(String ym) {
        return jdbc.queryForList("SELECT id, month, status::text AS status, approved_at, closed_at, pay_date, attendance_locked_at, approved_by FROM payroll_runs WHERE month = ?", ym);
    }

    /** month and status of every run, oldest first. */
    public List<Map<String, Object>> runMonths() {
        return jdbc.queryForList("SELECT month, status::text AS status FROM payroll_runs ORDER BY month ASC");
    }
}
