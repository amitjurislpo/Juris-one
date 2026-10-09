package com.jurislpo.jurisone.repository;

import static com.jurislpo.jurisone.service.OnboardingService.KINDS;
import static com.jurislpo.jurisone.service.OnboardingService.iso;

import java.sql.Date;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Onboardings, their documents, and the employee fields filled from a verified onboarding. */
@Repository
public class OnboardingRepository {

    private final JdbcTemplate jdbc;

    public OnboardingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── onboardings ──

    /** The onboarding row (empty when not found). */
    public List<Map<String, Object>> findById(UUID id) {
        return jdbc.queryForList("SELECT * FROM onboardings WHERE id = ?", id);
    }

    /** The onboarding behind a link's token hash (empty when none). */
    public List<Map<String, Object>> findByTokenHash(String tokenHash) {
        return jdbc.queryForList("SELECT * FROM onboardings WHERE token_hash = ?", tokenHash);
    }

    /** Every onboarding for the HR list: open ones first, then the most recently updated. */
    public List<Map<String, Object>> findAll() {
        return jdbc.query("""
            SELECT o.id::text, o.candidate_name, o.personal_email, o.designation, d.name, o.joining_date, o.status, o.updated_at, o.token_expires_at,
                   (SELECT count(*) FROM onboarding_documents x WHERE x.onboarding_id = o.id AND x.status <> 'REJECTED') AS docs,
                   (SELECT count(*) FROM onboarding_documents x WHERE x.onboarding_id = o.id AND x.status = 'VERIFIED') AS verified, e.code
            FROM onboardings o LEFT JOIN departments d ON d.id = o.department_id LEFT JOIN employees e ON e.id = o.employee_id
            ORDER BY o.status IN ('ADDED', 'CANCELLED'), o.updated_at DESC""", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString(1));
            m.put("candidateName", rs.getString(2));
            m.put("personalEmail", rs.getString(3));
            m.put("designation", rs.getString(4));
            m.put("department", rs.getString(5));
            m.put("joiningDate", rs.getDate(6) == null ? null : rs.getDate(6).toLocalDate().toString());
            m.put("status", rs.getString(7));
            m.put("updatedAt", iso(rs.getTimestamp(8)));
            m.put("linkExpiresAt", iso(rs.getTimestamp(9)));
            m.put("documents", rs.getLong(10));
            m.put("verified", rs.getLong(11));
            m.put("employeeCode", rs.getString(12));
            return m;
        });
    }

    /** Candidate names of other open onboardings for the email ({@code exceptId} may be null). */
    public List<String> findOpenCandidateNames(String email, UUID exceptId) {
        return jdbc.queryForList("SELECT candidate_name FROM onboardings WHERE personal_email = ? AND status NOT IN ('ADDED', 'CANCELLED') "
                + "AND (?::uuid IS NULL OR id <> ?::uuid)", String.class, email, exceptId, exceptId);
    }

    public boolean departmentIsActive(UUID id) {
        return !jdbc.queryForList("SELECT 1 FROM departments WHERE id = ? AND archived_at IS NULL", id).isEmpty();
    }

    /** True for a role other than owner / super admin. */
    public boolean roleIsAssignable(UUID id) {
        return !jdbc.queryForList("SELECT 1 FROM roles WHERE id = ? AND key NOT IN ('owner', 'super_admin')", id).isEmpty();
    }

    public boolean employeeIsCurrent(UUID id) {
        return !jdbc.queryForList("SELECT 1 FROM employees WHERE id = ? AND status <> 'EXITED'", id).isEmpty();
    }

    public void insert(UUID id, String name, String email, String designation, UUID departmentId, UUID roleId, UUID managerId, Date joining, String location,
                       String[] required, UUID createdBy) {
        jdbc.update("""
            INSERT INTO onboardings (id, candidate_name, personal_email, designation, department_id, role_id, manager_id, joining_date, location, required_docs, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", ps -> {
            ps.setObject(1, id);
            ps.setString(2, name);
            ps.setString(3, email);
            ps.setString(4, designation);
            ps.setObject(5, departmentId);
            ps.setObject(6, roleId);
            ps.setObject(7, managerId);
            ps.setObject(8, joining);
            ps.setString(9, location);
            ps.setArray(10, ps.getConnection().createArrayOf("text", required));
            ps.setObject(11, createdBy);
        });
    }

    /** Saves the planned job. */
    public void updatePlan(UUID id, String name, String email, String designation, UUID departmentId, UUID roleId, UUID managerId, Date joining,
                           String location, String[] required) {
        jdbc.update("""
            UPDATE onboardings SET candidate_name = ?, personal_email = ?, designation = ?, department_id = ?, role_id = ?, manager_id = ?, joining_date = ?,
              location = ?, required_docs = ?, updated_at = now() WHERE id = ?""", ps -> {
            ps.setString(1, name);
            ps.setString(2, email);
            ps.setString(3, designation);
            ps.setObject(4, departmentId);
            ps.setObject(5, roleId);
            ps.setObject(6, managerId);
            ps.setObject(7, joining);
            ps.setString(8, location);
            ps.setArray(9, ps.getConnection().createArrayOf("text", required));
            ps.setObject(10, id);
        });
    }

    /** Replaces the link: stores the token's hash and expiry. */
    public void setLink(UUID id, String tokenHash, Timestamp expiresAt) {
        jdbc.update("UPDATE onboardings SET token_hash = ?, token_expires_at = ?, updated_at = now() WHERE id = ?", tokenHash, expiresAt, id);
    }

    public void touch(UUID id) {
        jdbc.update("UPDATE onboardings SET updated_at = now() WHERE id = ?", id);
    }

    /** Sends the onboarding back with HR's comment and clears any verification. */
    public void requestChanges(UUID id, String comment) {
        jdbc.update("UPDATE onboardings SET status = 'CHANGES_REQUESTED', hr_comment = ?, verified_at = NULL, verified_by = NULL, updated_at = now() WHERE id = ?", comment, id);
    }

    public void markVerified(UUID id, UUID verifiedBy) {
        jdbc.update("UPDATE onboardings SET status = 'VERIFIED', verified_by = ?, verified_at = now(), updated_at = now() WHERE id = ?", verifiedBy, id);
    }

    /** Cancels the onboarding and voids its link. */
    public void cancel(UUID id) {
        jdbc.update("UPDATE onboardings SET status = 'CANCELLED', token_hash = NULL, token_expires_at = NULL, updated_at = now() WHERE id = ?", id);
    }

    /** Closes the onboarding as added (linking the employee) and voids its link. */
    public void markAdded(UUID id, UUID employeeId) {
        jdbc.update("UPDATE onboardings SET status = 'ADDED', employee_id = ?, token_hash = NULL, token_expires_at = NULL, updated_at = now() WHERE id = ?", employeeId, id);
    }

    /** Saves the candidate's encrypted answers; an INVITED onboarding becomes IN_PROGRESS. */
    public void saveForm(UUID id, String formEnc) {
        jdbc.update("""
            UPDATE onboardings SET form_enc = ?, status = CASE WHEN status = 'INVITED' THEN 'IN_PROGRESS' ELSE status END, updated_at = now() WHERE id = ?""",
                formEnc, id);
    }

    /** An INVITED onboarding becomes IN_PROGRESS. */
    public void markInProgress(UUID id) {
        jdbc.update("UPDATE onboardings SET status = CASE WHEN status = 'INVITED' THEN 'IN_PROGRESS' ELSE status END, updated_at = now() WHERE id = ?", id);
    }

    public void markSubmitted(UUID id) {
        jdbc.update("UPDATE onboardings SET status = 'SUBMITTED', submitted_at = now(), updated_at = now() WHERE id = ?", id);
    }

    // ── businesses & employees ──

    public String findBusinessName(UUID businessId) {
        return jdbc.queryForObject("SELECT name FROM businesses WHERE id = ?", String.class, businessId);
    }

    /** The business's status column. */
    public Map<String, Object> findBusinessStatus(UUID businessId) {
        return jdbc.queryForMap("SELECT status FROM businesses WHERE id = ?", businessId);
    }

    public String findEmployeeCode(UUID employeeId) {
        return jdbc.queryForObject("SELECT code FROM employees WHERE id = ?", String.class, employeeId);
    }

    public void updateEmployeeBank(UUID employeeId, String accountEnc, String accountLast4, String ifsc, String bankName) {
        jdbc.update("UPDATE employees SET bank_account_enc = ?, bank_account_last4 = ?, bank_ifsc = ?, bank_name = ? WHERE id = ?",
                accountEnc, accountLast4, ifsc, bankName, employeeId);
    }

    /** Personal details the employee record keeps from the candidate's form. */
    public void updateEmployeePersonal(UUID employeeId, Date dateOfBirth, String permanentAddressEnc, String emergencyName, String emergencyRelation,
                                       String emergencyPhone) {
        jdbc.update("UPDATE employees SET date_of_birth = ?, permanent_address_enc = ?, emergency_name = ?, emergency_relation = ?, emergency_phone = ? WHERE id = ?",
                dateOfBirth, permanentAddressEnc, emergencyName, emergencyRelation, emergencyPhone, employeeId);
    }

    // ── documents ──

    /** The onboarding's documents, oldest upload first. */
    public List<Map<String, Object>> findDocuments(UUID onboardingId) {
        return jdbc.query("""
            SELECT d.id::text, d.kind, d.file_name, d.mime, d.size_bytes, d.status, d.comment, d.uploaded_at, e.full_name AS verified_by, d.verified_at
            FROM onboarding_documents d LEFT JOIN employees e ON e.id = d.verified_by WHERE d.onboarding_id = ? ORDER BY d.uploaded_at""", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString(1));
            m.put("kind", rs.getString(2));
            m.put("label", KINDS.get(rs.getString(2)));
            m.put("fileName", rs.getString(3));
            m.put("mime", rs.getString(4));
            m.put("size", rs.getInt(5));
            m.put("status", rs.getString(6));
            m.put("comment", rs.getString(7));
            m.put("uploadedAt", iso(rs.getTimestamp(8)));
            m.put("verifiedBy", rs.getString(9));
            m.put("verifiedAt", iso(rs.getTimestamp(10)));
            return m;
        }, onboardingId);
    }

    /** Sets a document's decision; returns the number of rows changed (0 when it is not this onboarding's). */
    public int decideDocument(UUID docId, UUID onboardingId, String status, String comment, UUID verifiedBy) {
        return jdbc.update("UPDATE onboarding_documents SET status = ?, comment = ?, verified_by = ?, verified_at = now() WHERE id = ? AND onboarding_id = ?",
                status, comment, verifiedBy, docId, onboardingId);
    }

    /** Moves the onboarding's verified documents onto the employee's record. */
    public void attachVerifiedDocuments(UUID onboardingId, UUID employeeId) {
        jdbc.update("UPDATE onboarding_documents SET employee_id = ? WHERE onboarding_id = ? AND status = 'VERIFIED'", employeeId, onboardingId);
    }

    /** file_path, file_name and mime of an onboarding's document (empty when not found). */
    public List<Map<String, Object>> findDocumentFile(UUID docId, UUID onboardingId) {
        return jdbc.queryForList("SELECT file_path, file_name, mime FROM onboarding_documents WHERE id = ? AND onboarding_id = ?", docId, onboardingId);
    }

    /** Documents on an employee's record, by kind then upload time. */
    public List<Map<String, Object>> findEmployeeDocuments(UUID employeeId) {
        return jdbc.query("SELECT id::text, kind, file_name, uploaded_at FROM onboarding_documents WHERE employee_id = ? ORDER BY kind, uploaded_at", (rs, i) -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", rs.getString(1));
            m.put("kind", rs.getString(2));
            m.put("label", KINDS.get(rs.getString(2)));
            m.put("fileName", rs.getString(3));
            m.put("uploadedAt", iso(rs.getTimestamp(4)));
            return m;
        }, employeeId);
    }

    /** file_path, file_name and mime of a document on an employee's record (empty when not found). */
    public List<Map<String, Object>> findEmployeeDocumentFile(UUID docId, UUID employeeId) {
        return jdbc.queryForList("SELECT file_path, file_name, mime FROM onboarding_documents WHERE id = ? AND employee_id = ?", docId, employeeId);
    }

    public Integer countDocuments(UUID onboardingId) {
        return jdbc.queryForObject("SELECT count(*) FROM onboarding_documents WHERE onboarding_id = ?", Integer.class, onboardingId);
    }

    public void insertDocument(UUID id, UUID onboardingId, String kind, String filePath, String fileName, String mime, int sizeBytes, String sha256) {
        jdbc.update("""
            INSERT INTO onboarding_documents (id, onboarding_id, kind, file_path, file_name, mime, size_bytes, sha256) VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                id, onboardingId, kind, filePath, fileName, mime, sizeBytes, sha256);
    }

    /** A document's status (empty when it is not this onboarding's). */
    public List<String> findDocumentStatus(UUID docId, UUID onboardingId) {
        return jdbc.queryForList("SELECT status FROM onboarding_documents WHERE id = ? AND onboarding_id = ?", String.class, docId, onboardingId);
    }

    public void deleteDocument(UUID docId) {
        jdbc.update("DELETE FROM onboarding_documents WHERE id = ?", docId);
    }
}
