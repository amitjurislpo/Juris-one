package com.jurislpo.jurisone.repository;

import java.sql.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** The employee onboarding wizard's tables (V22): the header row and one table per section. Rows come back as plain maps; the service maps them. */
@Repository
public class EmployeeOnboardingRepository {

    private final JdbcTemplate jdbc;

    public EmployeeOnboardingRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    static Date date(String iso) {
        return iso == null || iso.isBlank() ? null : Date.valueOf(iso.trim());
    }

    // ───────────────────────── header ─────────────────────────

    public List<Map<String, Object>> findByEmployee(UUID employeeId) {
        return jdbc.queryForList("SELECT id, employee_id, status, current_step, step_status::text AS step_status, submitted_at, review_comment, reviewed_at, approved_at FROM employee_onboardings WHERE employee_id = ?", employeeId);
    }

    /** The statuses of one employee (empty when the employee has no wizard row). */
    public List<String> findStatus(UUID employeeId) {
        return jdbc.queryForList("SELECT status FROM employee_onboardings WHERE employee_id = ?", String.class, employeeId);
    }

    /** Code, name, department, designation, time zone, photo marker, manager and the father's name of the employee master. */
    public List<Map<String, Object>> findEmployeeHeader(UUID employeeId) {
        return jdbc.queryForList("""
            SELECT e.code, e.full_name, e.designation, e.time_zone, e.father_name, e.photo_updated_at, d.name AS department_name,
                   m.full_name AS manager_name, m.email AS manager_email, m.phone_enc AS manager_phone_enc
            FROM employees e LEFT JOIN departments d ON d.id = e.department_id LEFT JOIN employees m ON m.id = e.reporting_manager_id WHERE e.id = ?""", employeeId);
    }

    public void saveProgress(UUID id, String status, int currentStep, String stepStatusJson) {
        jdbc.update("UPDATE employee_onboardings SET status = ?, current_step = ?, step_status = ?::jsonb, updated_at = now() WHERE id = ?", status, currentStep, stepStatusJson, id);
    }

    public void saveCurrentStep(UUID id, int currentStep) {
        jdbc.update("UPDATE employee_onboardings SET current_step = ?, updated_at = now() WHERE id = ?", currentStep, id);
    }

    /** SUBMITTED; a (re)submission also clears the previous review: comment, reviewer and flags. */
    public void markSubmitted(UUID id) {
        jdbc.update("UPDATE employee_onboardings SET status = 'SUBMITTED', submitted_at = now(), review_comment = NULL, reviewed_by = NULL, reviewed_at = NULL, approved_at = NULL, updated_at = now() WHERE id = ?", id);
        jdbc.update("DELETE FROM onboarding_review_flags WHERE onboarding_id = ?", id);
    }

    public void markApproved(UUID id, UUID reviewerId) {
        jdbc.update("UPDATE employee_onboardings SET status = 'APPROVED', review_comment = NULL, reviewed_by = ?, reviewed_at = now(), approved_at = now(), updated_at = now() WHERE id = ?", reviewerId, id);
    }

    /** CHANGES_REQUESTED with the overall comment; the flags are written with {@link #insertReviewFlag}. */
    public void markChangesRequested(UUID id, UUID reviewerId, String comment) {
        jdbc.update("UPDATE employee_onboardings SET status = 'CHANGES_REQUESTED', review_comment = ?, reviewed_by = ?, reviewed_at = now(), approved_at = NULL, updated_at = now() WHERE id = ?", comment, reviewerId, id);
        jdbc.update("DELETE FROM onboarding_review_flags WHERE onboarding_id = ?", id);
    }

    public void insertReviewFlag(UUID id, String section, String comment) {
        jdbc.update("INSERT INTO onboarding_review_flags (id, onboarding_id, section, comment, created_at) VALUES (?, ?, ?, ?, clock_timestamp())", UUID.randomUUID(), id, section, comment);
    }

    /** The flags of the sections that were just corrected (the employee or HR saved their step) go away; the rest stay until the next submission. */
    public void deleteReviewFlags(UUID id, List<String> sections) {
        for (String section : sections) jdbc.update("DELETE FROM onboarding_review_flags WHERE onboarding_id = ? AND section = ?", id, section);
    }

    public List<Map<String, Object>> findReviewFlags(UUID id) {
        return jdbc.queryForList("SELECT section, comment FROM onboarding_review_flags WHERE onboarding_id = ? ORDER BY created_at, id", id);
    }

    /** Employees who may add people (the onboarding reviewers): current, with portal access, whose role holds {@code people.add}. */
    public List<UUID> findReviewers() {
        return jdbc.queryForList("""
            SELECT e.id FROM employees e WHERE e.status <> 'EXITED' AND e.portal_access
              AND EXISTS (SELECT 1 FROM role_permissions p WHERE p.role_id = e.role_id AND p.permission_key = 'people.add')""", UUID.class);
    }

    /** Name and email of an employee (for the decision mail). */
    public List<Map<String, Object>> findEmployeeContact(UUID employeeId) {
        return jdbc.queryForList("SELECT full_name, email FROM employees WHERE id = ?", employeeId);
    }

    // ───────────────────────── reads ─────────────────────────

    public List<Map<String, Object>> findPersonal(UUID id) {
        return jdbc.queryForList("SELECT * FROM onboarding_personal WHERE onboarding_id = ?", id);
    }

    public List<Map<String, Object>> findAddresses(UUID id) {
        return jdbc.queryForList("SELECT * FROM onboarding_addresses WHERE onboarding_id = ?", id);
    }

    public List<Map<String, Object>> findEmergencyContact(UUID id) {
        return jdbc.queryForList("SELECT * FROM onboarding_emergency_contacts WHERE onboarding_id = ?", id);
    }

    public List<Map<String, Object>> findEducation(UUID id) {
        return jdbc.queryForList("SELECT * FROM onboarding_education WHERE onboarding_id = ? ORDER BY position", id);
    }

    public List<Map<String, Object>> findCourses(UUID id) {
        return jdbc.queryForList("SELECT * FROM onboarding_courses WHERE onboarding_id = ? ORDER BY position", id);
    }

    public List<Map<String, Object>> findEmployments(UUID id) {
        return jdbc.queryForList("SELECT * FROM onboarding_employments WHERE onboarding_id = ? ORDER BY position", id);
    }

    public List<Map<String, Object>> findBank(UUID id) {
        return jdbc.queryForList("SELECT * FROM onboarding_bank WHERE onboarding_id = ?", id);
    }

    public List<Map<String, Object>> findStatutory(UUID id) {
        return jdbc.queryForList("SELECT * FROM onboarding_statutory WHERE onboarding_id = ?", id);
    }

    public List<Map<String, Object>> findFamilyMembers(UUID id) {
        return jdbc.queryForList("SELECT * FROM onboarding_family_members WHERE onboarding_id = ? ORDER BY position", id);
    }

    public List<Map<String, Object>> findNominations(UUID id) {
        return jdbc.queryForList("SELECT * FROM onboarding_nominations WHERE onboarding_id = ? ORDER BY scheme, position", id);
    }

    /** Every file of the wizard: id, slot key, name, type, size and upload time. */
    public List<Map<String, Object>> findAttachments(UUID id) {
        return jdbc.queryForList("SELECT id, slot_key, file_name, mime, size_bytes, uploaded_at FROM onboarding_attachments WHERE onboarding_id = ? ORDER BY uploaded_at, id", id);
    }

    /** The wizard files on an employee's record (the "documents" of the profile). */
    public List<Map<String, Object>> findEmployeeAttachments(UUID employeeId) {
        return jdbc.queryForList("""
            SELECT a.id, a.slot_key, a.file_name, a.mime, a.uploaded_at, a.storage_path FROM onboarding_attachments a
            JOIN employee_onboardings o ON o.id = a.onboarding_id WHERE o.employee_id = ? ORDER BY a.slot_key, a.uploaded_at""", employeeId);
    }

    public List<Map<String, Object>> findEmployeeAttachment(UUID employeeId, UUID attachmentId) {
        return jdbc.queryForList("""
            SELECT a.file_name, a.mime, a.storage_path FROM onboarding_attachments a
            JOIN employee_onboardings o ON o.id = a.onboarding_id WHERE o.employee_id = ? AND a.id = ?""", employeeId, attachmentId);
    }

    // ───────────────────────── writes (a step replaces its section) ─────────────────────────

    public void savePersonal(UUID id, Map<String, Object> columns) {
        jdbc.update("DELETE FROM onboarding_personal WHERE onboarding_id = ?", id);
        jdbc.update("""
            INSERT INTO onboarding_personal (onboarding_id, blood_group, personal_email, father_name, marital_status, marriage_date, spouse_name, country_of_origin, nationality,
                is_international_employee, is_physically_challenged, disability_type, gender, date_of_birth, height_cm, weight_kg, identification_mark, religion, caste, hobby)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, columns.get("bloodGroup"), columns.get("personalEmail"), columns.get("fatherName"),
                columns.get("maritalStatus"), date((String) columns.get("marriageDate")), columns.get("spouseName"), columns.get("countryOfOrigin"), columns.get("nationality"),
                columns.get("isInternationalEmployee"), columns.get("isPhysicallyChallenged"), columns.get("disabilityType"), columns.get("gender"), date((String) columns.get("dateOfBirth")),
                columns.get("heightCm"), columns.get("weightKg"), columns.get("identificationMark"), columns.get("religion"), columns.get("caste"), columns.get("hobby"));
    }

    public void saveAddress(UUID id, String kind, String[] lines, boolean isSameAsPermanent) {
        jdbc.update("DELETE FROM onboarding_addresses WHERE onboarding_id = ? AND kind = ?", id, kind);
        jdbc.update("INSERT INTO onboarding_addresses (id, onboarding_id, kind, line1, line2, line3, city, district, state, pin, country, phone, is_same_as_permanent) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), id, kind, lines[0], lines[1], lines[2], lines[3], lines[4], lines[5], lines[6], lines[7], lines[8], isSameAsPermanent);
    }

    public void saveEmergencyContact(UUID id, String name, String mobile, String relationship) {
        jdbc.update("DELETE FROM onboarding_emergency_contacts WHERE onboarding_id = ?", id);
        jdbc.update("INSERT INTO onboarding_emergency_contacts (onboarding_id, name, mobile, relationship) VALUES (?, ?, ?, ?)", id, name, mobile, relationship);
    }

    /** Deletes the rows of {@code table} (education, courses, employments, family members) whose id is not in {@code keep}. */
    public void deleteOthers(String table, UUID id, List<UUID> keep) {
        for (UUID existing : jdbc.queryForList("SELECT id FROM " + table + " WHERE onboarding_id = ?", UUID.class, id)) {
            if (!keep.contains(existing)) jdbc.update("DELETE FROM " + table + " WHERE id = ?", existing);
        }
    }

    public void upsertEducation(UUID rowId, UUID id, int position, String qualification, int fromYear, int toYear, String institute, String grade) {
        jdbc.update("""
            INSERT INTO onboarding_education (id, onboarding_id, position, qualification, from_year, to_year, institute, grade_or_percentage) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET position = EXCLUDED.position, qualification = EXCLUDED.qualification, from_year = EXCLUDED.from_year, to_year = EXCLUDED.to_year,
                institute = EXCLUDED.institute, grade_or_percentage = EXCLUDED.grade_or_percentage""", rowId, id, position, qualification, fromYear, toYear, institute, grade);
    }

    public void upsertCourse(UUID rowId, UUID id, int position, String courseName, String institute, Integer fromYear, Integer toYear) {
        jdbc.update("""
            INSERT INTO onboarding_courses (id, onboarding_id, position, course_name, institute, from_year, to_year) VALUES (?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET position = EXCLUDED.position, course_name = EXCLUDED.course_name, institute = EXCLUDED.institute, from_year = EXCLUDED.from_year,
                to_year = EXCLUDED.to_year""", rowId, id, position, courseName, institute, fromYear, toYear);
    }

    public void upsertEmployment(UUID rowId, UUID id, int position, String companyName, String designation, Long salaryPaise, String fromDate, String toDate, String reason, String address) {
        jdbc.update("""
            INSERT INTO onboarding_employments (id, onboarding_id, position, company_name, designation, last_drawn_salary_paise, from_date, to_date, reason_for_leaving, company_address)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET position = EXCLUDED.position, company_name = EXCLUDED.company_name, designation = EXCLUDED.designation,
                last_drawn_salary_paise = EXCLUDED.last_drawn_salary_paise, from_date = EXCLUDED.from_date, to_date = EXCLUDED.to_date, reason_for_leaving = EXCLUDED.reason_for_leaving,
                company_address = EXCLUDED.company_address""", rowId, id, position, companyName, designation, salaryPaise, date(fromDate), date(toDate), reason, address);
    }

    public void saveBank(UUID id, String country, String accountEnc, String accountLast4, String ifsc, String bankName, String branch, String branchAddress, String nameAsPerBank, String accountType) {
        jdbc.update("DELETE FROM onboarding_bank WHERE onboarding_id = ?", id);
        jdbc.update("INSERT INTO onboarding_bank (onboarding_id, country, account_number_enc, account_number_last4, ifsc, bank_name, branch, branch_address, name_as_per_bank, account_type) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, country, accountEnc, accountLast4, ifsc, bankName, branch, branchAddress, nameAsPerBank, accountType);
    }

    public void saveStatutory(UUID id, Map<String, Object> columns) {
        jdbc.update("DELETE FROM onboarding_statutory WHERE onboarding_id = ?", id);
        jdbc.update("""
            INSERT INTO onboarding_statutory (onboarding_id, previous_pf_number, uan_enc, uan_last4, pan_enc, pan_last4, name_per_pan, aadhaar_enc, aadhaar_last4, name_per_aadhaar,
                passport_number, name_per_passport, passport_expiry) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, columns.get("previousPfNumber"), columns.get("uanEnc"),
                columns.get("uanLast4"), columns.get("panEnc"), columns.get("panLast4"), columns.get("namePerPan"), columns.get("aadhaarEnc"), columns.get("aadhaarLast4"),
                columns.get("namePerAadhaar"), columns.get("passportNumber"), columns.get("namePerPassport"), date((String) columns.get("passportExpiry")));
    }

    public void upsertFamilyMember(UUID rowId, UUID id, int position, Map<String, Object> columns) {
        jdbc.update("""
            INSERT INTO onboarding_family_members (id, onboarding_id, position, name, relationship, date_of_birth, gender, blood_group, nationality, is_minor, has_mental_illness,
                guardian_name, guardian_relationship, guardian_line1, guardian_line2, guardian_line3, guardian_city, guardian_state, guardian_country, guardian_pin, guardian_mobile, guardian_email)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO UPDATE SET position = EXCLUDED.position, name = EXCLUDED.name, relationship = EXCLUDED.relationship, date_of_birth = EXCLUDED.date_of_birth,
                gender = EXCLUDED.gender, blood_group = EXCLUDED.blood_group, nationality = EXCLUDED.nationality, is_minor = EXCLUDED.is_minor, has_mental_illness = EXCLUDED.has_mental_illness,
                guardian_name = EXCLUDED.guardian_name, guardian_relationship = EXCLUDED.guardian_relationship, guardian_line1 = EXCLUDED.guardian_line1, guardian_line2 = EXCLUDED.guardian_line2,
                guardian_line3 = EXCLUDED.guardian_line3, guardian_city = EXCLUDED.guardian_city, guardian_state = EXCLUDED.guardian_state, guardian_country = EXCLUDED.guardian_country,
                guardian_pin = EXCLUDED.guardian_pin, guardian_mobile = EXCLUDED.guardian_mobile, guardian_email = EXCLUDED.guardian_email""",
                rowId, id, position, columns.get("name"), columns.get("relationship"), date((String) columns.get("dateOfBirth")), columns.get("gender"), columns.get("bloodGroup"),
                columns.get("nationality"), columns.get("isMinor"), columns.get("hasMentalIllness"), columns.get("guardianName"), columns.get("guardianRelationship"), columns.get("guardianLine1"),
                columns.get("guardianLine2"), columns.get("guardianLine3"), columns.get("guardianCity"), columns.get("guardianState"), columns.get("guardianCountry"), columns.get("guardianPin"),
                columns.get("guardianMobile"), columns.get("guardianEmail"));
    }

    public void deleteNominations(UUID id) {
        jdbc.update("DELETE FROM onboarding_nominations WHERE onboarding_id = ?", id);
    }

    public void insertNomination(UUID id, String scheme, int position, UUID familyMemberId, Integer percentage) {
        jdbc.update("INSERT INTO onboarding_nominations (id, onboarding_id, scheme, position, family_member_id, percentage) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), id, scheme, position, familyMemberId, percentage);
    }
}
