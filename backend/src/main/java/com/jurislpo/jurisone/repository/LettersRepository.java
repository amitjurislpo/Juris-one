package com.jurislpo.jurisone.repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Data access for HR letters: templates, the reference number sequence and the issued letters. */
@Repository
public class LettersRepository {

    private final JdbcTemplate jdbc;

    public LettersRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ───────────────────────── templates ─────────────────────────

    /** The default templates of a business (SQL function from V15; existing names are left alone). */
    public void seedDefaultTemplates(UUID businessId) {
        jdbc.query("SELECT jurisone_seed_letter_templates(?)", rs -> {}, businessId);
    }

    public List<Map<String, Object>> templates() {
        return jdbc.queryForList("SELECT id, type, name, subject, needs_salary, active, version, updated_at FROM letter_templates ORDER BY type, name");
    }

    public List<Map<String, Object>> template(UUID id) {
        return jdbc.queryForList("SELECT id, type, name, subject, body, needs_salary, active, version, updated_at FROM letter_templates WHERE id = ?", id);
    }

    public void insertTemplate(UUID id, String type, String name, String subject, String body, boolean needsSalary, UUID by) {
        jdbc.update("INSERT INTO letter_templates (id, type, name, subject, body, needs_salary, created_by, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id, type, name, subject, body, needsSalary, by, by);
    }

    public void updateTemplate(UUID id, String name, String subject, String body, boolean needsSalary, boolean active, UUID by) {
        jdbc.update("UPDATE letter_templates SET name = ?, subject = ?, body = ?, needs_salary = ?, active = ?, version = version + 1, updated_by = ?, updated_at = now() WHERE id = ?",
                name, subject, body, needsSalary, active, by, id);
    }

    // ───────────────────────── employees and their data ─────────────────────────

    public List<Map<String, Object>> employeeChoices() {
        return jdbc.queryForList("""
            SELECT e.id, e.code, e.full_name, e.designation, e.status::text AS status, e.exit_date FROM employees e JOIN roles r ON r.id = e.role_id
            WHERE r.key NOT IN ('owner', 'super_admin') ORDER BY e.full_name ASC""");
    }

    public List<Map<String, Object>> employee(UUID id) {
        return jdbc.queryForList("""
            SELECT e.id, e.code, e.full_name, e.email, e.designation, e.date_of_joining, e.exit_date, d.name AS dept
            FROM employees e LEFT JOIN departments d ON d.id = e.department_id WHERE e.id = ?""", id);
    }

    /** Monthly Basic + HRA + LTA + Special (paise) of the structure in force on the date; empty when none yet. */
    public List<Long> monthlyGrossOn(UUID employeeId, LocalDate on) {
        return jdbc.query("SELECT (basic + hra + lta + special)::bigint FROM salary_structures WHERE employee_id = ? AND effective_from <= ? ORDER BY effective_from DESC LIMIT 1",
                (rs, i) -> rs.getLong(1), employeeId, Date.valueOf(on));
    }

    // ───────────────────────── issued letters ─────────────────────────

    /** The next reference number of the financial year (atomic; rolls back with the issuing transaction). */
    public int nextNumber(String fy) {
        Integer n = jdbc.queryForObject("""
            INSERT INTO letter_sequences (fy, last_no) VALUES (?, 1)
            ON CONFLICT (business_id, fy) DO UPDATE SET last_no = letter_sequences.last_no + 1 RETURNING last_no""", Integer.class, fy);
        return n == null ? 1 : n;
    }

    public void insertIssued(UUID id, String reference, UUID templateId, String templateName, int templateVersion, String type, boolean needsSalary, UUID employeeId,
                             String employeeCode, String employeeName, String subject, String body, String bodySha, byte[] pdf, String pdfSha, UUID issuedBy, String issuedByName) {
        jdbc.update("""
            INSERT INTO issued_letters (id, reference_no, template_id, template_name, template_version, letter_type, needs_salary, employee_id, employee_code, employee_name,
                                        subject, body, body_sha256, pdf, pdf_sha256, issued_by, issued_by_name)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                id, reference, templateId, templateName, templateVersion, type, needsSalary, employeeId, employeeCode, employeeName, subject, body, bodySha, pdf, pdfSha, issuedBy,
                issuedByName);
    }

    private static final String LIST = "SELECT id, reference_no, letter_type, template_name, needs_salary, employee_id, employee_code, employee_name, subject, issued_by_name, issued_at FROM issued_letters";

    /** Recent letters (newest first), optionally of one employee. */
    public List<Map<String, Object>> issued(UUID employeeId) {
        if (employeeId == null) return jdbc.queryForList(LIST + " ORDER BY issued_at DESC LIMIT 300");
        return jdbc.queryForList(LIST + " WHERE employee_id = ? ORDER BY issued_at DESC LIMIT 300", employeeId);
    }

    public List<Map<String, Object>> issuedTo(UUID employeeId) {
        return jdbc.queryForList(LIST + " WHERE employee_id = ? ORDER BY issued_at DESC", employeeId);
    }

    public List<Map<String, Object>> issuedById(UUID id) {
        return jdbc.queryForList("SELECT id, reference_no, letter_type, template_name, needs_salary, employee_id, employee_code, employee_name, subject, body, body_sha256, pdf_sha256, "
                + "issued_by_name, issued_at FROM issued_letters WHERE id = ?", id);
    }

    public byte[] issuedPdf(UUID id) {
        List<byte[]> r = jdbc.query("SELECT pdf FROM issued_letters WHERE id = ?", (rs, i) -> rs.getBytes(1), id);
        return r.isEmpty() ? null : r.get(0);
    }
}
