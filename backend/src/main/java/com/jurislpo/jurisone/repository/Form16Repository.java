package com.jurislpo.jurisone.repository;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Data access for Form 16 / 24Q: closed payroll months, payslip snapshots and the published, immutable Form 16 rows. */
@Repository
public class Form16Repository {

    private final JdbcTemplate jdbc;

    public Form16Repository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The closed payroll months within [fromYm, toYm]. */
    public List<String> closedMonths(String fromYm, String toYm) {
        return jdbc.queryForList("SELECT month FROM payroll_runs WHERE status = 'CLOSED' AND month >= ? AND month <= ? ORDER BY month", String.class, fromYm, toYm);
    }

    /** Every payroll month there is a run for (any status). */
    public List<String> runMonths() {
        return jdbc.queryForList("SELECT month FROM payroll_runs ORDER BY month", String.class);
    }

    /** (employee id, month, snapshot JSON text) of every payslip of the closed runs within [fromYm, toYm], by month. */
    public List<Map<String, Object>> closedPayslips(String fromYm, String toYm) {
        return jdbc.queryForList("""
            SELECT p.employee_id, r.month, p.snapshot::text AS snapshot FROM payslips p JOIN payroll_runs r ON r.id = p.run_id
            WHERE r.status = 'CLOSED' AND r.month >= ? AND r.month <= ? ORDER BY r.month ASC""", fromYm, toYm);
    }

    /** Identity, employment and PAN (encrypted) of some employees. */
    public List<Map<String, Object>> employees(List<UUID> ids) {
        return jdbc.queryForList("""
            SELECT e.id, e.code, e.full_name, e.designation, e.date_of_joining, e.exit_date, d.name AS dept, e.pan_enc, e.pan_last4
            FROM employees e LEFT JOIN departments d ON d.id = e.department_id WHERE e.id = ANY (?)""", (Object) ids.toArray(UUID[]::new));
    }

    // ───────────────────────── published forms ─────────────────────────

    /** Employees that already have a Form 16 for the year. */
    public Set<UUID> employeesWithForm(String fy) {
        return Set.copyOf(jdbc.query("SELECT employee_id FROM form16_forms WHERE fy = ?", (rs, i) -> (UUID) rs.getObject(1), fy));
    }

    /** Inserts a published Form 16; 0 when this employee already has one for the year (the first one stays). */
    public int insert(UUID id, String fy, UUID employeeId, String code, String name, String snapshot, String snapshotSha, byte[] pdf, String pdfSha, UUID publishedBy) {
        return jdbc.update("""
            INSERT INTO form16_forms (id, fy, employee_id, employee_code, employee_name, snapshot, snapshot_sha256, pdf, pdf_sha256, published_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (business_id, fy, employee_id) DO NOTHING""",
                id, fy, employeeId, code, name, snapshot, snapshotSha, pdf, pdfSha, publishedBy);
    }

    /** The published forms of one employee, newest year first (snapshot included). */
    public List<Map<String, Object>> findByEmployee(UUID employeeId) {
        return jdbc.queryForList("""
            SELECT id, fy, employee_id, snapshot, snapshot_sha256, pdf_sha256, published_at FROM form16_forms WHERE employee_id = ? ORDER BY fy DESC""", employeeId);
    }

    public List<Map<String, Object>> findById(UUID id) {
        return jdbc.queryForList("SELECT id, fy, employee_id, employee_code, snapshot, snapshot_sha256, pdf_sha256, published_at FROM form16_forms WHERE id = ?", id);
    }

    public byte[] findPdf(UUID id) {
        List<byte[]> r = jdbc.query("SELECT pdf FROM form16_forms WHERE id = ?", (rs, i) -> rs.getBytes(1), id);
        return r.isEmpty() ? null : r.get(0);
    }

    /** Every employee with a closed payslip in the range, with their form for the year when published. */
    public List<Map<String, Object>> overviewRows(String fy, String fromYm, String toYm) {
        return jdbc.queryForList("""
            SELECT e.id AS employee_id, e.code, e.full_name, x.slips, f.id AS form_id, f.published_at, f.snapshot_sha256
            FROM (SELECT p.employee_id, count(*) AS slips FROM payslips p JOIN payroll_runs r ON r.id = p.run_id
                  WHERE r.status = 'CLOSED' AND r.month >= ? AND r.month <= ? GROUP BY p.employee_id) x
            JOIN employees e ON e.id = x.employee_id
            LEFT JOIN form16_forms f ON f.employee_id = e.id AND f.fy = ?
            ORDER BY e.full_name ASC""", fromYm, toYm, fy);
    }

    /** Years that have published forms, newest first. */
    public List<String> publishedYears() {
        return jdbc.queryForList("SELECT DISTINCT fy FROM form16_forms ORDER BY fy DESC", String.class);
    }
}
