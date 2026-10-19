package com.jurislpo.jurisone.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Full & final settlements (one per exit) and the figures they are computed from. */
@Repository
public class SettlementRepository {

    private final JdbcTemplate jdbc;

    public SettlementRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The exit's settlement (null when none yet); JSON columns come back as text. */
    public Map<String, Object> findByExit(UUID exitId) {
        List<Map<String, Object>> r = jdbc.queryForList("""
            SELECT s.id, s.exit_id, s.employee_id, s.month, s.status, s.additions::text AS additions, s.deductions::text AS deductions, s.note,
                   s.calc::text AS calc, s.net_paise, s.snapshot::text AS snapshot, s.drafted_at, s.approved_at,
                   db.full_name AS drafted_by_name, ab.full_name AS approved_by_name
            FROM fnf_settlements s LEFT JOIN employees db ON db.id = s.drafted_by LEFT JOIN employees ab ON ab.id = s.approved_by
            WHERE s.exit_id = ?""", exitId);
        return r.isEmpty() ? null : r.get(0);
    }

    /** Creates or replaces the draft (the caller has checked it is not approved). */
    public void saveDraft(UUID exitId, UUID employeeId, String month, String additionsJson, String deductionsJson, String note, String calcJson, long netPaise,
                          UUID by) {
        jdbc.update("""
            INSERT INTO fnf_settlements (id, exit_id, employee_id, month, status, additions, deductions, note, calc, net_paise, drafted_by, drafted_at)
            VALUES (?, ?, ?, ?, 'DRAFT', CAST(? AS jsonb), CAST(? AS jsonb), ?, CAST(? AS jsonb), ?, ?, now())
            ON CONFLICT (exit_id) DO UPDATE SET month = EXCLUDED.month, additions = EXCLUDED.additions, deductions = EXCLUDED.deductions, note = EXCLUDED.note,
                calc = EXCLUDED.calc, net_paise = EXCLUDED.net_paise, drafted_by = EXCLUDED.drafted_by, drafted_at = now(), updated_at = now()""",
                UUID.randomUUID(), exitId, employeeId, month, additionsJson, deductionsJson, note, calcJson, netPaise, by);
    }

    /** Freezes the draft: the snapshot is stored and the row becomes immutable. */
    public int approve(UUID exitId, String month, String calcJson, long netPaise, String snapshotJson, UUID by) {
        return jdbc.update("""
            UPDATE fnf_settlements SET status = 'APPROVED', month = ?, calc = CAST(? AS jsonb), net_paise = ?, snapshot = CAST(? AS jsonb), approved_by = ?,
                   approved_at = now(), updated_at = now()
            WHERE exit_id = ? AND status = 'DRAFT'""", month, calcJson, netPaise, snapshotJson, by, exitId);
    }

    // ── inputs ──

    /** Ledger balance of one leave type (0 when there are no entries). */
    public Map<String, BigDecimal> ledgerBalances(UUID employeeId, List<String> types) {
        Map<String, BigDecimal> out = new java.util.LinkedHashMap<>();
        jdbc.query("SELECT leave_type, sum(delta) FROM leave_ledger WHERE employee_id = ? AND leave_type = ANY (?) GROUP BY leave_type",
                rs -> { out.put(rs.getString(1), rs.getBigDecimal(2)); }, employeeId, types.toArray(String[]::new));
        return out;
    }

    /** Takes the encashed days off the leave balance. */
    public void insertEncashment(UUID employeeId, String leaveType, BigDecimal delta, String exitId) {
        jdbc.update("INSERT INTO leave_ledger (id, employee_id, leave_type, delta, reason, ref_id, note, at) VALUES (?, ?, ?, ?, 'ENCASHED', ?, 'Encashed in full & final settlement', now())",
                UUID.randomUUID(), employeeId, leaveType, delta, exitId);
    }

    /** The employee's closed payslip for the month (snapshot JSON text), or null. */
    public String closedPayslip(UUID employeeId, String month) {
        List<String> r = jdbc.queryForList("SELECT p.snapshot::text FROM payslips p JOIN payroll_runs r ON r.id = p.run_id "
                + "WHERE p.employee_id = ? AND r.month = ? AND r.status = 'CLOSED'", String.class, employeeId, month);
        return r.isEmpty() ? null : r.get(0);
    }

    /** Printed on the statement: department and the masked identity / bank details. */
    public Map<String, Object> employeeCard(UUID employeeId) {
        return jdbc.queryForMap("""
            SELECT e.code, e.full_name, e.designation, e.date_of_joining, e.pan_last4, e.uan_last4, e.bank_name, e.bank_account_last4,
                   COALESCE(d.name, '—') AS department
            FROM employees e LEFT JOIN departments d ON d.id = e.department_id WHERE e.id = ?""", employeeId);
    }
}
