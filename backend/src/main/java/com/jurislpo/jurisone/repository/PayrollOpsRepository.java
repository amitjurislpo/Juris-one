package com.jurislpo.jurisone.repository;

import com.jurislpo.jurisone.service.PayrollOpsService.Rev;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Data access for {@code PayrollOpsService}: runs, adjustments, close & publish, salary structures, payslips and exports. */
@Repository
public class PayrollOpsRepository {

    private final JdbcTemplate jdbc;

    public PayrollOpsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── runs ──

    /** Names of India employees on the books by {@code lastDay} without a structure effective by then. */
    public List<String> namesWithoutStructure(LocalDate lastDay) {
        return jdbc.queryForList("""
            SELECT full_name FROM employees e WHERE region = 'IN' AND status <> 'EXITED' AND date_of_joining <= ?
              AND NOT EXISTS (SELECT 1 FROM salary_structures s WHERE s.employee_id = e.id AND s.effective_from <= ?) ORDER BY full_name ASC""",
                String.class, Date.valueOf(lastDay), Date.valueOf(lastDay));
    }

    public void insertOpenRun(UUID id, String month) {
        jdbc.update("INSERT INTO payroll_runs (id, month, status, stage, created_at) VALUES (?, ?, 'OPEN', 'LOCK_ATTENDANCE', now())", id, month);
    }

    public void approveRun(UUID approvedBy, UUID runId) {
        jdbc.update("UPDATE payroll_runs SET stage = 'APPROVED', approved_by = ?, approved_at = now() WHERE id = ?", approvedBy, runId);
    }

    /** id, attendance_locked_at, approved_by, approved_at of the month's run (empty when there is none). */
    public List<Map<String, Object>> runForClose(String ym) {
        return jdbc.queryForList("SELECT id, attendance_locked_at, approved_by, approved_at FROM payroll_runs WHERE month = ?", ym);
    }

    /** Closes the run if it is still open; returns the rows updated (0 = already closed). */
    public int closeOpenRun(UUID actorId, Instant now, String payDate, UUID runId) {
        return jdbc.update("""
            UPDATE payroll_runs SET status = 'CLOSED', stage = 'CLOSED', closed_by = ?, closed_at = ?, pay_date = ?, attendance_locked_at = COALESCE(attendance_locked_at, ?),
              computed_at = ?, approved_by = COALESCE(approved_by, ?), approved_at = COALESCE(approved_at, ?) WHERE id = ? AND status = 'OPEN'""",
                actorId, Timestamp.from(now), Date.valueOf(payDate), Timestamp.from(now), Timestamp.from(now), actorId, Timestamp.from(now), runId);
    }

    public List<String> lastClosedMonth() {
        return jdbc.queryForList("SELECT month FROM payroll_runs WHERE status = 'CLOSED' ORDER BY month DESC LIMIT 1", String.class);
    }

    public List<String> openRunMonths() {
        return jdbc.queryForList("SELECT month FROM payroll_runs WHERE status = 'OPEN' ORDER BY month DESC", String.class);
    }

    // ── adjustments ──

    /** full_name and region of an employee (empty when not found). */
    public List<Map<String, Object>> employeeRegion(UUID employeeId) {
        return jdbc.queryForList("SELECT full_name, region::text AS region FROM employees WHERE id = ?", employeeId);
    }

    public List<Map<String, Object>> findAdjustment(UUID runId, UUID employeeId) {
        return jdbc.queryForList("SELECT lop_days, bonus, other_deduction, note FROM payroll_adjustments WHERE run_id = ? AND employee_id = ?", runId, employeeId);
    }

    public void upsertAdjustment(UUID runId, UUID employeeId, BigDecimal lopDays, Long bonus, Long otherDeduction, String note, UUID updatedBy) {
        jdbc.update("""
            INSERT INTO payroll_adjustments (run_id, employee_id, lop_days, bonus, other_deduction, note, updated_by, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (run_id, employee_id) DO UPDATE SET lop_days = EXCLUDED.lop_days, bonus = EXCLUDED.bonus, other_deduction = EXCLUDED.other_deduction,
              note = EXCLUDED.note, updated_by = EXCLUDED.updated_by, updated_at = now()""",
                runId, employeeId, lopDays, bonus, otherDeduction, note, updatedBy);
    }

    /** An edited run goes back to review and loses its approval. */
    public void resetRunToReview(UUID runId) {
        jdbc.update("UPDATE payroll_runs SET stage = 'REVIEW', approved_at = NULL, approved_by = NULL WHERE id = ?", runId);
    }

    public int deleteAdjustment(UUID runId, UUID employeeId) {
        return jdbc.update("DELETE FROM payroll_adjustments WHERE run_id = ? AND employee_id = ?", runId, employeeId);
    }

    // ── payslips ──

    /** The masked identifiers and contact details printed on / used for a payslip. */
    public Map<String, Object> payslipEmployee(UUID employeeId) {
        return jdbc.queryForMap("SELECT pan_last4, uan_last4, bank_name, bank_account_last4, email, full_name FROM employees WHERE id = ?", employeeId);
    }

    public void insertPayslip(UUID runId, UUID employeeId, String snapshot, String pdfPath) {
        jdbc.update("INSERT INTO payslips (run_id, employee_id, snapshot, pdf_path, published_at) VALUES (?, ?, ?::jsonb, ?, now())", runId, employeeId, snapshot, pdfPath);
    }

    /** A notification linking to the Payslips page. */
    public void insertPayslipNotification(UUID employeeId, String text) {
        jdbc.update("INSERT INTO notifications (id, employee_id, text, link, created_at) VALUES (?, ?, ?, '/payslips', now())",
                UUID.randomUUID(), employeeId, text);
    }

    /** month, pay_date and snapshot of the employee's closed payslips, newest first. */
    public List<Map<String, Object>> closedPayslips(UUID employeeId) {
        return jdbc.queryForList("""
            SELECT r.month, r.pay_date, p.snapshot::text AS snapshot FROM payslips p JOIN payroll_runs r ON r.id = p.run_id
            WHERE p.employee_id = ? AND r.status = 'CLOSED' ORDER BY r.month DESC""", employeeId);
    }

    /** pdf_path and snapshot of an employee's closed payslip for a month (empty when none). */
    public List<Map<String, Object>> closedPayslipFile(String month, String code) {
        return jdbc.queryForList("""
            SELECT p.pdf_path, p.snapshot::text AS snapshot FROM payslips p JOIN payroll_runs r ON r.id = p.run_id JOIN employees e ON e.id = p.employee_id
            WHERE r.month = ? AND r.status = 'CLOSED' AND e.code = ? LIMIT 1""", month, code);
    }

    /** Encrypted bank / UAN / PAN details for the payroll exports. */
    public Map<String, Object> exportDetails(UUID employeeId) {
        return jdbc.queryForMap("SELECT bank_account_enc, bank_ifsc, bank_name, uan_enc, pan_enc FROM employees WHERE id = ?", employeeId);
    }

    // ── salary structures ──

    /** India employees not exited, optionally in one department ({@code deptId} null = all). */
    public List<Map<String, Object>> structureEmployees(String deptId) {
        boolean byDept = deptId != null;
        return jdbc.queryForList("SELECT id, code, full_name, designation, date_of_joining FROM employees WHERE region = 'IN' AND status <> 'EXITED'"
                + (byDept ? " AND department_id = ?::uuid" : ""), byDept ? new Object[] {deptId} : new Object[0]);
    }

    /** An employee's salary revisions, newest first. */
    public List<Rev> revisions(Object employeeId) {
        return jdbc.query("SELECT effective_from, basic, hra, lta, special, reason::text FROM salary_structures WHERE employee_id = ? ORDER BY effective_from DESC",
                (rs, i) -> new Rev(rs.getDate(1).toLocalDate().toString(), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getString(6)), employeeId);
    }

    public List<Map<String, Object>> activeDepartments() {
        return jdbc.queryForList("SELECT id::text AS id, name FROM departments WHERE archived_at IS NULL ORDER BY name ASC");
    }

    /** full_name, region, date_of_joining and status of an employee (empty when not found). */
    public List<Map<String, Object>> structureEmployee(UUID employeeId) {
        return jdbc.queryForList("SELECT full_name, region::text AS region, date_of_joining, status::text AS status FROM employees WHERE id = ?", employeeId);
    }

    public Integer countStructuresEffective(UUID employeeId, String effectiveFrom) {
        return jdbc.queryForObject("SELECT count(*) FROM salary_structures WHERE employee_id = ? AND effective_from = ?", Integer.class, employeeId, Date.valueOf(effectiveFrom));
    }

    /** The revision in force just before {@code effectiveFrom} (empty when none). */
    public List<Map<String, Object>> previousStructure(UUID employeeId, String effectiveFrom) {
        return jdbc.queryForList("SELECT effective_from, basic, hra, lta, special FROM salary_structures WHERE employee_id = ? AND effective_from < ? ORDER BY effective_from DESC LIMIT 1",
                employeeId, Date.valueOf(effectiveFrom));
    }

    /** Amounts in paise. */
    public void insertStructure(UUID id, UUID employeeId, String effectiveFrom, long basic, long hra, long lta, long special, String reason, UUID createdBy) {
        jdbc.update("INSERT INTO salary_structures (id, employee_id, effective_from, basic, hra, lta, special, reason, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?::\"StructureReason\", ?, now())",
                id, employeeId, Date.valueOf(effectiveFrom), basic, hra, lta, special, reason, createdBy);
    }

    /** Marks the unread "salary structure pending" reminders whose text starts like {@code textPrefixLike} as read. */
    public void markStructureRemindersRead(String textPrefixLike) {
        jdbc.update("UPDATE notifications SET read_at = now() WHERE read_at IS NULL AND text LIKE ? AND text LIKE '%salary structure pending%'", textPrefixLike);
    }

    public Integer countStructures(UUID employeeId) {
        return jdbc.queryForObject("SELECT count(*) FROM salary_structures WHERE employee_id = ?", Integer.class, employeeId);
    }
}
