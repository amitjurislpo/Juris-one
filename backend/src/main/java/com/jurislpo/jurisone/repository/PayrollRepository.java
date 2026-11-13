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
    private final com.jurislpo.jurisone.crypto.EmployeeNameReader nameReader;

    private final com.jurislpo.jurisone.crypto.PayslipSnapshotCrypto snapshots;
    private final com.jurislpo.jurisone.crypto.SalaryAmountsCrypto salaryAmounts;

    public PayrollRepository(JdbcTemplate jdbc, com.jurislpo.jurisone.crypto.SalaryAmountsCrypto salaryAmounts, com.jurislpo.jurisone.crypto.PayslipSnapshotCrypto snapshots, com.jurislpo.jurisone.crypto.EmployeeNameReader nameReader) {
        this.nameReader = nameReader;
        this.jdbc = jdbc;
        this.salaryAmounts = salaryAmounts;
        this.snapshots = snapshots;
    }

    /** The id of the month's payroll run (empty when there is none). */
    public List<Map<String, Object>> runIds(String yearMonth) {
        return jdbc.queryForList("SELECT id FROM payroll_runs WHERE month = ?", yearMonth);
    }

    public List<Map<String, Object>> adjustments(Object runId) {
        return jdbc.queryForList("SELECT * FROM payroll_adjustments WHERE run_id = ?", runId);
    }

    /**
     * India employees on payroll for the month: a structure on its last day and employed some day of it, by name.
     * {@code args} are (last, first, last[, ids]) matching {@code idFilter}.
     */
    public List<Map<String, Object>> payrollEmployees(String idFilter, Object[] args) {
        return nameReader.resolveAll(jdbc.queryForList("""
            SELECT e.id, e.code, e.full_name, e.full_name_enc, e.designation, e.designation_enc, e.date_of_joining, e.exit_date, d.name AS department_name
            FROM employees e LEFT JOIN departments d ON d.id = e.department_id
            WHERE e.region = 'IN' AND e.date_of_joining <= ? AND (e.exit_date IS NULL OR e.exit_date >= ?)
              AND EXISTS (SELECT 1 FROM salary_structures s WHERE s.employee_id = e.id AND s.effective_from <= ?)""" + idFilter + "", args), "full_name", "designation").stream()
                .sorted(java.util.Comparator.comparing(row -> (String) row.get("full_name"), com.jurislpo.jurisone.crypto.EmployeeNameReader.NAME_ORDER)).toList();
    }

    /** India and company-wide holidays in [first, last]. */
    public List<LocalDate> indiaHolidays(LocalDate first, LocalDate last) {
        return jdbc.query("SELECT date FROM holidays WHERE date >= ? AND date <= ? AND region::text IN ('IN', 'ALL')",
                (resultSet, i) -> resultSet.getDate(1).toLocalDate(), Date.valueOf(first), Date.valueOf(last));
    }

    /** The salary structure in force on {@code onDate}. */
    public Map<String, Object> structureOn(UUID employeeId, LocalDate onDate) {
        return salaryAmounts.withAmounts(jdbc.queryForMap("SELECT amounts_enc, basic, hra, lta, special FROM salary_structures WHERE employee_id = ? AND effective_from <= ? ORDER BY effective_from DESC LIMIT 1",
                employeeId, Date.valueOf(onDate)));
    }

    /** (employee id, snapshot JSON text) of every payslip in the month's run. */
    public List<Map.Entry<UUID, String>> payslipSnapshots(String yearMonth) {
        return jdbc.query("SELECT p.employee_id, p.snapshot::text, p.snapshot_enc FROM payslips p JOIN payroll_runs r ON r.id = p.run_id WHERE r.month = ?",
                (resultSet, i) -> Map.entry((UUID) resultSet.getObject(1), snapshots.read(resultSet.getString(2), resultSet.getString(3))), yearMonth);
    }

    public List<Map<String, Object>> findRun(String yearMonth) {
        return jdbc.queryForList("SELECT id, month, status::text AS status, approved_at, closed_at, pay_date, attendance_locked_at, approved_by FROM payroll_runs WHERE month = ?", yearMonth);
    }

    /** month and status of every run, oldest first. */
    public List<Map<String, Object>> runMonths() {
        return jdbc.queryForList("SELECT month, status::text AS status FROM payroll_runs ORDER BY month ASC");
    }
}
