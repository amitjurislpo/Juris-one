package com.jurislpo.jurisone.repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Data access for {@code TaxService}: tax tables, declarations, tax profiles and the inputs of the projection. */
@Repository
public class TaxRepository {

    private final JdbcTemplate jdbc;

    public TaxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Both regimes' tax tables for a financial year (slabs and surcharge as JSON text). */
    public List<Map<String, Object>> taxYears(String financialYear) {
        return jdbc.queryForList("SELECT regime::text AS regime, std_deduction, rebate_limit, marginal_relief, cess_rate, slabs::text AS slabs, surcharge::text AS surcharge FROM tax_years WHERE fy = ?", financialYear);
    }

    public List<Map<String, Object>> taxSections(String financialYear) {
        return jdbc.queryForList("SELECT key, label, cap FROM tax_sections WHERE fy = ?", financialYear);
    }

    public List<Map<String, Object>> declarationItems(UUID employeeId, String financialYear) {
        return jdbc.queryForList("SELECT item_key, amount, status::text AS status FROM declaration_items WHERE employee_id = ? AND fy = ?", employeeId, financialYear);
    }

    /** metro and parents_senior of the employee's tax profile (empty when none). */
    public List<Map<String, Object>> profileFlags(UUID employeeId, String financialYear) {
        return jdbc.queryForList("SELECT metro, parents_senior FROM tax_profiles WHERE employee_id = ? AND fy = ?", employeeId, financialYear);
    }

    /** Regime and previous-employer figures of the employee's tax profile (empty when none). */
    public List<Map<String, Object>> profileRegime(UUID employeeId, String financialYear) {
        return jdbc.queryForList("SELECT regime::text AS regime, regime_overridden, prev_employer_income, prev_employer_tds FROM tax_profiles WHERE employee_id = ? AND fy = ?", employeeId, financialYear);
    }

    /** Snapshots of the employee's closed payslips for months in [fromYm, beforeYm). */
    public List<Map<String, Object>> closedPayslipSnapshots(UUID employeeId, String fromMonth, String beforeMonth) {
        return jdbc.queryForList("""
            SELECT p.snapshot::text AS snapshot, r.month FROM payslips p JOIN payroll_runs r ON r.id = p.run_id
            WHERE p.employee_id = ? AND r.status = 'CLOSED' AND r.month >= ? AND r.month < ?""", employeeId, fromMonth, beforeMonth);
    }

    public Map<String, Object> employmentDates(UUID employeeId) {
        return jdbc.queryForMap("SELECT date_of_joining, exit_date FROM employees WHERE id = ?", employeeId);
    }

    /** The employee's salary structures, oldest first. */
    public List<Map<String, Object>> salaryStructures(UUID employeeId) {
        return jdbc.queryForList("SELECT effective_from, basic, hra, lta, special FROM salary_structures WHERE employee_id = ? ORDER BY effective_from ASC", employeeId);
    }
}
