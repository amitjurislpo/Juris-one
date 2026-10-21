package com.jurislpo.jurisone.repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Writes of the seed: platform rows (super admin role, shifts, leave types, tax tables, plans), the super admin and the
 * demo business's people and history. Every statement is idempotent or guarded by the caller.
 */
@Repository
public class SeedRepository {

    /** An employee who gets demo punches, with their shift start (null = no shift). */
    public record PunchEmployee(UUID id, String code, LocalDate doj, LocalDate exit, String tz, String region, String startLocal) {}

    /** A holiday's date and region. */
    public record HolidayDay(LocalDate date, String region) {}

    private static final String TAX_YEAR_SQL = """
        INSERT INTO tax_years (fy, regime, std_deduction, rebate_limit, marginal_relief, cess_rate, slabs, surcharge) VALUES (?, ?::"TaxRegime", ?, ?, ?, 0.04, ?::jsonb, ?::jsonb)
        ON CONFLICT (fy, regime) DO NOTHING""";

    private static final String TAX_PROFILE_SQL = """
        INSERT INTO tax_profiles (employee_id, fy, regime, regime_overridden, metro, parents_senior, prev_employer_income, prev_employer_tds, updated_at)
        VALUES (?, ?, ?::"TaxRegime", false, true, false, ?, ?, now()) ON CONFLICT (employee_id, fy) DO NOTHING""";

    private final JdbcTemplate jdbc;

    public SeedRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── platform role ──

    /** Ids of the platform-level (no business) roles with this key. */
    public List<UUID> findPlatformRoleIds(String key) {
        return jdbc.queryForList("SELECT id FROM roles WHERE key = ? AND business_id IS NULL", UUID.class, key);
    }

    public void insertPlatformRole(UUID id, String key) {
        jdbc.update("INSERT INTO roles (id, key, name, is_builtin, is_locked, business_id) VALUES (?, ?, 'Super admin', true, true, NULL)", id, key);
    }

    public void deletePermissions(UUID roleId) {
        jdbc.update("DELETE FROM role_permissions WHERE role_id = ?", roleId);
    }

    public void insertPermission(UUID roleId, String permission) {
        jdbc.update("INSERT INTO role_permissions (role_id, permission_key) VALUES (?, ?)", roleId, permission);
    }

    // ── shifts, leave types, tax ──

    /** Inserts or refreshes a shift by code; returns its id. */
    public UUID upsertShift(UUID id, String code, String name, String start, String end, String region, boolean crosses) {
        return jdbc.queryForObject("""
            INSERT INTO shifts (id, code, name, start_local, end_local, grace_minutes, region, crosses_midnight) VALUES (?, ?, ?, ?, ?, 15, ?::"Region", ?)
            ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, start_local = EXCLUDED.start_local, end_local = EXCLUDED.end_local,
              crosses_midnight = EXCLUDED.crosses_midnight RETURNING id""",
                UUID.class, id, code, name, start, end, region, crosses);
    }

    /** The four standard leave types (existing ones are left alone). */
    public void insertLeaveTypes() {
        jdbc.update("""
            INSERT INTO leave_types (code, name, annual_quota, accrual_per_month, carry_forward_cap, max_stretch, encash_per_year, encash_after_months, expiry_days, paid, needs_balance, sort_order, active)
            VALUES ('CL', 'Casual leave', 12, 1, 0, 3, 0, 0, NULL, true, true, 1, true),
                   ('EL', 'Earned leave', 18, 1.5, 30, NULL, 15, 12, NULL, true, true, 2, true),
                   ('CO', 'Comp-off', 0, 0, 0, NULL, 0, 0, 60, true, true, 3, true),
                   ('LOP', 'Leave without pay', 0, 0, 0, NULL, 0, 0, NULL, false, false, 4, true)
            ON CONFLICT (code) DO NOTHING""");
    }

    /** Inserts a tax year of a regime unless it exists ({@code slabs} / {@code surcharge}: JSON). */
    public void insertTaxYear(String fy, String regime, Object stdDeduction, Object rebateLimit, boolean marginalRelief, String slabs, String surcharge) {
        jdbc.update(TAX_YEAR_SQL, fy, regime, stdDeduction, rebateLimit, marginalRelief, slabs, surcharge);
    }

    public void insertTaxSection(String fy, Object key, Object label, Object cap, int sortOrder) {
        jdbc.update("INSERT INTO tax_sections (fy, key, label, cap, sort_order) VALUES (?, ?, ?, ?, ?) ON CONFLICT (fy, key) DO NOTHING",
                fy, key, label, cap, sortOrder);
    }

    // ── employees and users ──

    /** Ids of employees with this email. */
    public List<UUID> findEmployeeIdsByEmail(String email) {
        return jdbc.queryForList("SELECT id FROM employees WHERE email = ?", UUID.class, email);
    }

    /** Inserts the super admin employee (no business, code SA-001). */
    public void insertSuperAdminEmployee(UUID id, String email, UUID roleId, Date joined) {
        jdbc.update("""
            INSERT INTO employees (id, business_id, code, full_name, email, designation, role_id, date_of_joining, status, time_zone, region, portal_access, updated_at)
            VALUES (?, NULL, 'SA-001', 'JurisOne Administrator', ?, 'Super Administrator', ?, ?, 'ACTIVE', 'Asia/Kolkata', 'IN', true, now())""",
                id, email, roleId, joined);
    }

    /** Moves an existing employee onto the platform role (no business, department or manager; access on). */
    public void moveToPlatformRole(UUID id, UUID roleId) {
        jdbc.update("UPDATE employees SET business_id = NULL, role_id = ?, department_id = NULL, reporting_manager_id = NULL, portal_access = true, updated_at = now() WHERE id = ?",
                roleId, id);
    }

    /** Ids of employees with this code. */
    public List<UUID> findEmployeeIdsByCode(String code) {
        return jdbc.queryForList("SELECT id FROM employees WHERE code = ?", UUID.class, code);
    }

    public void updateReportingManager(UUID id, UUID managerId) {
        jdbc.update("UPDATE employees SET reporting_manager_id = ?, updated_at = now() WHERE id = ?", managerId, id);
    }

    /** Inserts a demo employee with encrypted identity numbers. */
    public void insertEmployee(UUID id, String code, String name, String father, String email, String title, UUID roleId, UUID deptId, UUID managerId, Date doj,
                               Date exit, String status, String tz, String region, UUID shiftId, boolean portalAccess, UUID createdBy, String phoneEnc, String phoneLast4,
                               String addressEnc, String panEnc, String panLast4, String aadhaarEnc, String aadhaarLast4, String uanEnc, String uanLast4, String bankEnc,
                               String bankLast4, String bankName, String bankIfsc) {
        jdbc.update("""
            INSERT INTO employees (id, code, full_name, father_name, email, designation, role_id, department_id, reporting_manager_id, date_of_joining, exit_date,
              status, time_zone, region, shift_id, portal_access, created_by, phone_enc, phone_last4, address_enc, pan_enc, pan_last4, aadhaar_enc, aadhaar_last4,
              uan_enc, uan_last4, bank_account_enc, bank_account_last4, bank_name, bank_ifsc, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::"EmployeeStatus", ?, ?::"Region", ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())""",
                id, code, name, father, email, title, roleId, deptId, managerId, doj,
                exit, status, tz, region, shiftId,
                portalAccess, createdBy,
                phoneEnc, phoneLast4, addressEnc, panEnc, panLast4,
                aadhaarEnc, aadhaarLast4, uanEnc, uanLast4, bankEnc,
                bankLast4, bankName, bankIfsc);
    }

    /** is_super_admin of the employee's user (zero or one row). */
    public List<Map<String, Object>> findUser(UUID employeeId) {
        return jdbc.queryForList("SELECT is_super_admin FROM users WHERE employee_id = ?", employeeId);
    }

    public void markSuperAdmin(UUID employeeId) {
        jdbc.update("UPDATE users SET is_super_admin = true WHERE employee_id = ?", employeeId);
    }

    public void insertUser(UUID employeeId, boolean superAdmin) {
        jdbc.update("INSERT INTO users (employee_id, is_super_admin) VALUES (?, ?)", employeeId, superAdmin);
    }

    // ── demo attendance, payroll, proofs, requests ──

    /** Whether the seed step {@code key} already ran (job_runs). */
    public boolean seedStepRan(String key) {
        return !jdbc.queryForList("SELECT 1 FROM job_runs WHERE name = 'seed' AND key = ?", key).isEmpty();
    }

    /** Employees with these codes whose role includes {@code self}, with their shift start. */
    public List<PunchEmployee> findPunchEmployees(String[] codes) {
        return jdbc.query("""
            SELECT e.id, e.code, e.date_of_joining, e.exit_date, e.time_zone, e.region::text, s.start_local FROM employees e LEFT JOIN shifts s ON s.id = e.shift_id
            WHERE e.code = ANY (?) AND EXISTS (SELECT 1 FROM role_permissions rp WHERE rp.role_id = e.role_id AND rp.permission_key = 'self')""",
                (rs, i) -> new PunchEmployee((UUID) rs.getObject(1), rs.getString(2), rs.getDate(3).toLocalDate(), rs.getDate(4) == null ? null : rs.getDate(4).toLocalDate(),
                        rs.getString(5), rs.getString(6), rs.getString(7)), (Object) codes);
    }

    public List<HolidayDay> findHolidays() {
        return jdbc.query("SELECT date, region::text FROM holidays", (rs, i) -> new HolidayDay(rs.getDate(1).toLocalDate(), rs.getString(2)));
    }

    /** Inserts web punches; each row is {id, employee_id, at, kind}. */
    public void insertPunches(List<Object[]> rows) {
        jdbc.batchUpdate("INSERT INTO punches (id, employee_id, at, kind, source) VALUES (?, ?, ?, ?::\"PunchKind\", 'WEB')", rows);
    }

    /** Records the punches seed step with its count ({@code detail}: JSON). */
    public void insertPunchesRun(UUID id, String detail) {
        jdbc.update("INSERT INTO job_runs (id, name, key, detail) VALUES (?, 'seed', 'punches', ?::jsonb)", id, detail);
    }

    /** Status of the month's payroll run (zero or one). */
    public List<String> findPayrollRunStatus(String month) {
        return jdbc.queryForList("SELECT status::text FROM payroll_runs WHERE month = ?", String.class, month);
    }

    /** Declaration items that name a proof file but have none stored. */
    public List<Map<String, Object>> findItemsMissingProofFile() {
        return jdbc.queryForList("SELECT id, employee_id, item_key, amount FROM declaration_items WHERE proof_name IS NOT NULL AND proof_path IS NULL");
    }

    public void updateProofPath(Object itemId, String path) {
        jdbc.update("UPDATE declaration_items SET proof_path = ?, updated_at = now() WHERE id = ?", path, itemId);
    }

    public void insertLeaveRequest(UUID id, UUID employeeId, String type, Date from, Date to, int days, int lopDays, String reason, String status,
                                   Timestamp appliedAt, UUID decidedBy, Timestamp decidedAt) {
        jdbc.update("""
            INSERT INTO leave_requests (id, employee_id, leave_type, from_date, to_date, days, lop_days, reason, status, applied_at, decided_by, decided_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::"RequestStatus", ?, ?, ?)""",
                id, employeeId, type, from, to, days, lopDays, reason, status, appliedAt, decidedBy, decidedAt);
    }

    /** A USED leave ledger entry for a request. */
    public void insertLeaveUsed(UUID id, UUID employeeId, String type, BigDecimal delta, String refId) {
        jdbc.update("INSERT INTO leave_ledger (id, employee_id, leave_type, delta, reason, ref_id) VALUES (?, ?, ?, ?, 'USED', ?)",
                id, employeeId, type, delta, refId);
    }

    /** Id of one OUT punch of the employee in [from, to) (zero or one). */
    public List<UUID> findOutPunch(UUID employeeId, Timestamp from, Timestamp to) {
        return jdbc.queryForList("SELECT id FROM punches WHERE employee_id = ? AND kind = 'OUT' AND at >= ? AND at < ? LIMIT 1", UUID.class,
                employeeId, from, to);
    }

    public void deletePunch(UUID id) {
        jdbc.update("DELETE FROM punches WHERE id = ?", id);
    }

    public void insertMissedCheckout(UUID id, UUID employeeId, Date workDate, Timestamp proposedOut, String reason, Timestamp createdAt) {
        jdbc.update("""
            INSERT INTO regularization_requests (id, employee_id, work_date, kind, proposed_out, reason, created_at) VALUES (?, ?, ?, 'MISSED_CHECKOUT', ?, ?, ?)""",
                id, employeeId, workDate, proposedOut, reason, createdAt);
    }

    /** Inserts an IN and an OUT web punch. */
    public void insertPunchPair(UUID inId, UUID employeeId, Timestamp inAt, UUID outId, Timestamp outAt) {
        jdbc.update("INSERT INTO punches (id, employee_id, at, kind, source) VALUES (?, ?, ?, 'IN', 'WEB'), (?, ?, ?, 'OUT', 'WEB')",
                inId, employeeId, inAt, outId, employeeId, outAt);
    }

    public void insertHolidayWork(UUID id, UUID employeeId, Date workDate, String reason, Timestamp createdAt) {
        jdbc.update("INSERT INTO regularization_requests (id, employee_id, work_date, kind, reason, created_at) VALUES (?, ?, ?, 'HOLIDAY_WORK', ?, ?)",
                id, employeeId, workDate, reason, createdAt);
    }

    /** Records the requests seed step. */
    public void insertRequestsRun(UUID id) {
        jdbc.update("INSERT INTO job_runs (id, name, key) VALUES (?, 'seed', 'requests')", id);
    }

    // ── departments ──

    /** Inserts the department (or keeps the existing one with the name); returns its id. */
    public UUID upsertDepartment(UUID id, String name, String description, UUID createdBy) {
        return jdbc.queryForObject("""
            INSERT INTO departments (id, name, description, created_by) VALUES (?, ?, ?, ?)
            ON CONFLICT (business_id, name) DO UPDATE SET name = EXCLUDED.name RETURNING id""", UUID.class, id, name, description, createdBy);
    }

    /** Department ids by name. */
    public Map<String, UUID> findDepartmentIds() {
        Map<String, UUID> ids = new HashMap<>();
        jdbc.query("SELECT name, id FROM departments", rs -> { ids.put(rs.getString(1), (UUID) rs.getObject(2)); });
        return ids;
    }

    public void updateDepartmentCreator(String name, UUID createdBy) {
        jdbc.update("UPDATE departments SET created_by = ? WHERE name = ?", createdBy, name);
    }

    public void updateDepartmentHead(String name, UUID headId) {
        jdbc.update("UPDATE departments SET head_employee_id = ? WHERE name = ?", headId, name);
    }

    // ── demo business ──

    /** Ids of the demo business (code juris-consultants). */
    public List<UUID> findDemoBusinessIds() {
        return jdbc.queryForList("SELECT id FROM businesses WHERE code = 'juris-consultants'", UUID.class);
    }

    public Integer countSubscriptions(UUID businessId) {
        return jdbc.queryForObject("SELECT count(*) FROM business_subscriptions WHERE business_id = ?", Integer.class, businessId);
    }

    /** Id of the "Standard + Juris Track" plan; throws when there is none. */
    public UUID findDemoPlanId() {
        return jdbc.queryForObject("SELECT id FROM subscription_plans WHERE name = 'Standard + Juris Track'", UUID.class);
    }

    /** A free 12-month demo subscription starting today. */
    public void insertDemoSubscription(UUID id, UUID businessId, UUID planId, UUID createdBy) {
        jdbc.update("INSERT INTO business_subscriptions (id, business_id, plan_id, starts_on, ends_on, price_paise, notes, created_by) "
                + "VALUES (?, ?, ?, current_date, current_date + interval '12 months' - interval '1 day', 0, 'Demo subscription', ?)",
                id, businessId, planId, createdBy);
    }

    public void insertHoliday(UUID id, Date date, String name, String region, UUID createdBy) {
        jdbc.update("INSERT INTO holidays (id, date, name, region, created_by) VALUES (?, ?, ?, ?::\"HolidayRegion\", ?) ON CONFLICT (business_id, date, region) DO NOTHING",
                id, date, name, region, createdBy);
    }

    public void insertSalaryStructure(UUID id, UUID employeeId, Date effectiveFrom, long basic, long hra, long lta, long special, Object reason, UUID createdBy) {
        jdbc.update("""
            INSERT INTO salary_structures (id, employee_id, effective_from, basic, hra, lta, special, reason, created_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::"StructureReason", ?) ON CONFLICT (employee_id, effective_from) DO NOTHING""",
                id, employeeId, effectiveFrom, basic, hra, lta, special, reason, createdBy);
    }

    /** Inserts a tax profile unless it exists (metro, not senior parents). */
    public void insertTaxProfile(UUID employeeId, String fy, String regime, Object prevEmployerIncome, Object prevEmployerTds) {
        jdbc.update(TAX_PROFILE_SQL, employeeId, fy, regime, prevEmployerIncome, prevEmployerTds);
    }

    public void insertDeclarationItem(UUID id, UUID employeeId, String fy, Object section, String itemKey, long amount, String proofName, String status,
                                      UUID decidedBy, Timestamp decidedAt) {
        jdbc.update("""
            INSERT INTO declaration_items (id, employee_id, fy, section, item_key, amount, proof_name, status, decided_by, decided_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?::"DeclarationStatus", ?, ?, now()) ON CONFLICT (employee_id, fy, item_key) DO NOTHING""",
                id, employeeId, fy, section, itemKey, amount, proofName, status, decidedBy, decidedAt);
    }

    // ── plans ──

    /** How many plans have this name. */
    public Integer countPlansNamed(Object name) {
        return jdbc.queryForObject("SELECT count(*) FROM subscription_plans WHERE name = ?", Integer.class, name);
    }

    /** Inserts a free 12-month plan with no employee limit. */
    public void insertStarterPlan(UUID id, String name, String description, String[] modules) {
        jdbc.update("INSERT INTO subscription_plans (id, name, description, price_paise, period_months, max_employees, modules) VALUES (?, ?, ?, 0, 12, NULL, ?)",
                ps -> {
                    ps.setObject(1, id);
                    ps.setString(2, name);
                    ps.setString(3, description);
                    ps.setArray(4, ps.getConnection().createArrayOf("text", modules));
                });
    }

    /** Rows in a table (a fixed table name, never user input). */
    public long countRows(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }
}
