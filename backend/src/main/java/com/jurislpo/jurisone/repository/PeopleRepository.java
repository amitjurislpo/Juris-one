package com.jurislpo.jurisone.repository;

import com.jurislpo.jurisone.service.PhotoService;
import com.jurislpo.jurisone.engine.PeopleRules;
import com.jurislpo.jurisone.engine.PeopleRules.Target;
import com.jurislpo.jurisone.entity.EmployeeStatus;
import com.jurislpo.jurisone.service.PeopleService.DepartmentOption;
import com.jurislpo.jurisone.service.PeopleService.DepartmentSummary;
import com.jurislpo.jurisone.service.PeopleService.DirectoryRow;
import com.jurislpo.jurisone.service.PeopleService.Manager;
import com.jurislpo.jurisone.service.PeopleService.PersonRow;
import com.jurislpo.jurisone.service.PeopleService.RoleRow;
import java.sql.Date;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Employee records behind the People pages: list, directory, profile, add/edit, access, exit and export. */
@Repository
public class PeopleRepository {

    private final JdbcTemplate jdbc;

    public PeopleRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ── list ──

    /** One page of the People list for the query; {@code canToggle} decides each row's access switch. */
    public List<PersonRow> findPage(PeopleQuery peopleQuery, Predicate<Target> canToggle) {
        List<Object> args = new ArrayList<>(peopleQuery.args);
        args.add(peopleQuery.listParams.take());
        args.add(peopleQuery.listParams.skip());
        return jdbc.query("""
            SELECT e.id::text AS id, e.code, e.full_name, e.designation, e.status::text AS status, e.portal_access, e.time_zone, e.region::text AS region,
                   e.role_id::text AS role_id, e.date_of_joining, d.name AS department_name, m.full_name AS manager, r.name AS role_name, r.key AS role_key,
                   (SELECT u.is_super_admin FROM users u WHERE u.employee_id = e.id) AS super_admin, e.photo_updated_at
            """ + PeopleQuery.FROM + peopleQuery.where + peopleQuery.orderBy + " LIMIT ? OFFSET ?",
                (resultSet, i) -> {
                    Target target = new Target(resultSet.getString("id"), resultSet.getString("role_id"), resultSet.getString("role_key"), EmployeeStatus.valueOf(resultSet.getString("status")),
                            resultSet.getBoolean("super_admin"));
                    return new PersonRow(resultSet.getString("id"), resultSet.getString("code"), resultSet.getString("full_name"), resultSet.getString("designation"),
                            resultSet.getString("department_name") == null ? "—" : resultSet.getString("department_name"), resultSet.getString("manager") == null ? "—" : resultSet.getString("manager"),
                            PeopleRules.locationOfTimeZone(resultSet.getString("time_zone")).label(), "IN".equals(resultSet.getString("region")), resultSet.getString("role_name"),
                            resultSet.getString("status"), resultSet.getBoolean("portal_access"), resultSet.getDate("date_of_joining").toLocalDate().toString(),
                            canToggle.test(target), PhotoService.version(resultSet.getTimestamp("photo_updated_at")));
                }, args.toArray());
    }

    /** How many rows the query matches in all. */
    public Integer count(PeopleQuery peopleQuery) {
        return jdbc.queryForObject("SELECT count(*) " + PeopleQuery.FROM + peopleQuery.where, Integer.class, peopleQuery.args.toArray());
    }

    /** Non-archived departments by name. */
    public List<DepartmentSummary> findActiveDepartments() {
        return jdbc.query("SELECT id::text, name FROM departments WHERE archived_at IS NULL ORDER BY name ASC", (resultSet, i) -> new DepartmentSummary(resultSet.getString(1), resultSet.getString(2)));
    }

    /** Tab counts (current, united states, access off, past) over everyone but the owner, super admin and the viewer. */
    public Map<String, Integer> countTabs(UUID viewerId) {
        return jdbc.queryForObject("""
            SELECT count(*) FILTER (WHERE status <> 'EXITED') AS current_count, count(*) FILTER (WHERE status <> 'EXITED' AND region = 'US') AS united_states_count,
                   count(*) FILTER (WHERE status <> 'EXITED' AND NOT portal_access) AS off, count(*) FILTER (WHERE status = 'EXITED') AS past
            FROM employees e JOIN roles r ON r.id = e.role_id WHERE r.key NOT IN ('owner', 'super_admin') AND e.id <> ?""",
                (resultSet, i) -> {
                    Map<String, Integer> counts = new LinkedHashMap<>();
                    counts.put("current", resultSet.getInt("current_count"));
                    counts.put("usTeam", resultSet.getInt("united_states_count"));
                    counts.put("accessDisabled", resultSet.getInt("off"));
                    counts.put("past", resultSet.getInt("past"));
                    return counts;
                }, viewerId);
    }

    // ── directory ──

    /** A directory page; {@code where} (may be empty) and {@code order} are built by the service, {@code args} bind the where. */
    public List<DirectoryRow> findDirectoryPage(String where, String order, List<Object> args, int take, int skip) {
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(take);
        pageArgs.add(skip);
        return jdbc.query("SELECT id::text, full_name, email, designation, status::text, photo_updated_at FROM employees" + where + " ORDER BY " + order + " LIMIT ? OFFSET ?",
                (resultSet, i) -> new DirectoryRow(resultSet.getString(1), resultSet.getString(2), resultSet.getString(3), resultSet.getString(4), resultSet.getString(5), PhotoService.version(resultSet.getTimestamp(6))), pageArgs.toArray());
    }

    public Integer countDirectory(String where, List<Object> args) {
        return jdbc.queryForObject("SELECT count(*) FROM employees" + where, Integer.class, args.toArray());
    }

    public Integer countCurrent() {
        return jdbc.queryForObject("SELECT count(*) FROM employees WHERE status <> 'EXITED'", Integer.class);
    }

    // ── profile drawer ──

    /** The employee row with department, manager, role, shift and user columns (empty when not found). */
    public List<Map<String, Object>> findProfile(UUID id) {
        return jdbc.queryForList("""
            SELECT e.*, e.status::text AS status_s, e.region::text AS region_s, d.name AS department_name, m.full_name AS manager, r.name AS role_name, r.key AS role_key,
                   s.code AS shift_code, s.start_local, s.end_local, us.is_super_admin
            FROM employees e LEFT JOIN departments d ON d.id = e.department_id LEFT JOIN employees m ON m.id = e.reporting_manager_id
            JOIN roles r ON r.id = e.role_id LEFT JOIN shifts s ON s.id = e.shift_id LEFT JOIN users us ON us.employee_id = e.id WHERE e.id = ?""", id);
    }

    /** Monthly gross of the salary structure in force on {@code on} (empty when none). */
    public List<Long> findGross(UUID employeeId, Date date) {
        return jdbc.queryForList("SELECT basic + hra + lta + special FROM salary_structures WHERE employee_id = ? AND effective_from <= ? ORDER BY effective_from DESC LIMIT 1",
                Long.class, employeeId, date);
    }

    /** Names of the manager's current direct reports. */
    public List<String> findCurrentReportNames(UUID managerId) {
        return jdbc.queryForList("SELECT full_name FROM employees WHERE reporting_manager_id = ? AND status <> 'EXITED' ORDER BY full_name ASC", String.class, managerId);
    }

    public Map<String, Object> findContactEnc(UUID id) {
        return jdbc.queryForMap("SELECT phone_enc, address_enc FROM employees WHERE id = ?", id);
    }

    // ── access & exit ──

    public Map<String, Object> findNameAndAccess(UUID id) {
        return jdbc.queryForMap("SELECT full_name, portal_access FROM employees WHERE id = ?", id);
    }

    public void setPortalAccess(UUID id, boolean enabled) {
        jdbc.update("UPDATE employees SET portal_access = ?, updated_at = now() WHERE id = ?", enabled, id);
    }

    /** full_name, date_of_joining, status, portal_access and the count of current direct reports. */
    public Map<String, Object> findForExit(UUID id) {
        return jdbc.queryForMap("""
            SELECT full_name, date_of_joining, status::text AS status, portal_access,
                   (SELECT count(*) FROM employees r WHERE r.reporting_manager_id = e.id AND r.status <> 'EXITED') AS reports
            FROM employees e WHERE id = ?""", id);
    }

    /** Probation / onboarding → permanent (Active). */
    public void markConfirmed(UUID id) {
        jdbc.update("UPDATE employees SET status = 'ACTIVE', updated_at = now() WHERE id = ?", id);
    }

    /** Marks the employee exited on {@code exitDate} with portal access off. */
    public void markExited(UUID id, Date exitDate) {
        jdbc.update("UPDATE employees SET status = 'EXITED', exit_date = ?, portal_access = false, updated_at = now() WHERE id = ?", exitDate, id);
    }

    /** Clears the employee as head of any department. */
    public void clearDepartmentHead(UUID employeeId) {
        jdbc.update("UPDATE departments SET head_employee_id = NULL WHERE head_employee_id = ?", employeeId);
    }

    // ── add / edit ──

    /** The current business's employee ID prefix. */
    public String findEmployeePrefix() {
        return jdbc.queryForObject("SELECT employee_prefix FROM businesses WHERE id = jurisone_tenant_id()", String.class);
    }

    /** The highest number among codes shaped {@code <prefix>-<digits>} (null when none). */
    public Integer findMaxCodeNumber(String prefix) {
        return jdbc.queryForObject("SELECT max(substring(code from ?)::int) FROM employees WHERE code ~ ?", Integer.class,
                prefix.length() + 2, "^" + prefix + "-[0-9]+$");
    }

    /** The employee row being edited, with region_s and status_s. */
    public Map<String, Object> findEditable(UUID id) {
        return jdbc.queryForMap("SELECT e.*, e.region::text AS region_s, e.status::text AS status_s FROM employees e WHERE id = ?", id);
    }

    public List<DepartmentOption> findDepartmentOptions() {
        return jdbc.query("SELECT id::text, name, head_employee_id::text FROM departments WHERE archived_at IS NULL ORDER BY name ASC",
                (resultSet, i) -> new DepartmentOption(resultSet.getString(1), resultSet.getString(2), resultSet.getString(3)));
    }

    /** The one department (if non-archived) as an option. */
    public List<DepartmentOption> findDepartmentOption(String departmentId) {
        return jdbc.query("SELECT id::text, name, head_employee_id::text FROM departments WHERE archived_at IS NULL AND id = ?::uuid ORDER BY name ASC",
                (resultSet, i) -> new DepartmentOption(resultSet.getString(1), resultSet.getString(2), resultSet.getString(3)), departmentId);
    }

    /** Current employees who can be a reporting manager, excluding {@code excludeId} (may be null). */
    public List<Manager> findManagerOptions(UUID excludeId) {
        return jdbc.query("""
            SELECT e.id::text, e.full_name, e.designation, d.name FROM employees e LEFT JOIN departments d ON d.id = e.department_id
            WHERE e.status <> 'EXITED' AND (?::uuid IS NULL OR e.id <> ?::uuid) ORDER BY e.full_name ASC""",
                (resultSet, i) -> new Manager(resultSet.getString(1), resultSet.getString(2), resultSet.getString(3) + (resultSet.getString(4) != null ? " · " + resultSet.getString(4) : "")),
                excludeId, excludeId);
    }

    /** Every role with its permission count, by creation. */
    public List<RoleRow> findRoles() {
        return jdbc.query("SELECT r.id::text, r.key, r.name, (SELECT count(*) FROM role_permissions p WHERE p.role_id = r.id) FROM roles r ORDER BY r.created_at ASC",
                (resultSet, i) -> new RoleRow(resultSet.getString(1), resultSet.getString(2), resultSet.getString(3), resultSet.getInt(4)));
    }

    /** Shift id → code. */
    public Map<String, String> findShiftCodesById() {
        Map<String, String> shiftCodes = new HashMap<>();
        jdbc.query("SELECT id::text, code FROM shifts", resultSet -> { shiftCodes.put(resultSet.getString(1), resultSet.getString(2)); });
        return shiftCodes;
    }

    /** id, key and name of a role (empty when not found). */
    public List<Map<String, Object>> findRole(String roleId) {
        return jdbc.queryForList("SELECT id::text AS id, key, name FROM roles WHERE id = ?::uuid", roleId);
    }

    /** archived_at of a department (empty when not found). */
    public List<Map<String, Object>> findDepartmentArchivedAt(String departmentId) {
        return jdbc.queryForList("SELECT archived_at FROM departments WHERE id = ?::uuid", departmentId);
    }

    /** The employee's status (empty when not found). */
    public List<String> findStatus(String employeeId) {
        return jdbc.queryForList("SELECT status::text FROM employees WHERE id = ?::uuid", String.class, employeeId);
    }

    /** Employee id → reporting manager id (null when none), for every employee. */
    public Map<String, String> findManagerByEmployee() {
        Map<String, String> managerOf = new HashMap<>();
        jdbc.query("SELECT id::text, reporting_manager_id::text FROM employees", resultSet -> { managerOf.put(resultSet.getString(1), resultSet.getString(2)); });
        return managerOf;
    }

    /** Code of an employee in this business already using the email (empty when free). */
    public List<String> findCodeByEmail(String email) {
        return jdbc.queryForList("SELECT code FROM employees WHERE email = ? LIMIT 1", String.class, email);
    }

    /** Code of another employee in this business already using the email (empty when free). */
    public List<String> findCodeByEmailExcept(String email, UUID exceptId) {
        return jdbc.queryForList("SELECT code FROM employees WHERE email = ? AND id <> ? LIMIT 1", String.class, email, exceptId);
    }

    /** Whether the email is taken in any business (by someone other than {@code exceptId}, which may be null). */
    public Boolean emailInUse(String email, UUID exceptId) {
        return jdbc.queryForObject("SELECT jurisone_email_in_use(?, ?)", Boolean.class, email, exceptId);
    }

    public UUID findShiftId(String code) {
        return jdbc.queryForObject("SELECT id FROM shifts WHERE code = ?", UUID.class, code);
    }

    /** Saves the edited (non-PII) employee fields. */
    public void updateEmployee(UUID id, String fullName, String firstName, String middleName, String lastName, String joiningOffice, String fatherName,
                               String email, String designation, String departmentId, String managerId, String roleId, Date dateOfJoining, String timeZone,
                               String region, UUID shiftId, String status) {
        jdbc.update("""
            UPDATE employees SET full_name = ?, first_name = ?, middle_name = ?, last_name = ?, joining_office = ?, father_name = ?, email = ?, designation = ?,
              department_id = ?::uuid, reporting_manager_id = ?::uuid,
              role_id = ?::uuid, date_of_joining = ?, time_zone = ?, region = ?::"Region", shift_id = ?, status = ?::"EmployeeStatus", updated_at = now()
            WHERE id = ?""", fullName, firstName, middleName, lastName, joiningOffice, fatherName, email, designation, departmentId, managerId, roleId, dateOfJoining,
                timeZone, region, shiftId, status, id);
    }

    /** Waits for this business's employee-code allocation lock (held until the transaction ends). */
    public void lockCodeAllocation() {
        jdbc.execute("SELECT pg_advisory_xact_lock(74201, hashtext(jurisone_tenant_id()::text))");
    }

    public void insertEmployee(UUID id, String code, String fullName, String firstName, String middleName, String lastName, String joiningOffice,
                               String fatherName, String email, String designation, String departmentId, String managerId, String roleId, Date dateOfJoining,
                               String timeZone, String region, UUID shiftId, String status, boolean access, UUID createdBy) {
        jdbc.update("""
            INSERT INTO employees (id, code, full_name, first_name, middle_name, last_name, joining_office, father_name, email, designation, department_id,
              reporting_manager_id, role_id, date_of_joining, time_zone, region, shift_id, status, portal_access, created_by, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::uuid, ?::uuid, ?::uuid, ?, ?, ?::"Region", ?, ?::"EmployeeStatus", ?, ?, now(), now())""",
                id, code, fullName, firstName, middleName, lastName, joiningOffice, fatherName, email, designation, departmentId, managerId, roleId, dateOfJoining,
                timeZone, region, shiftId, status, access, createdBy);
    }

    /** The new employee's user row. */
    public void insertUser(UUID employeeId) {
        jdbc.update("INSERT INTO users (employee_id, failed_attempts, is_super_admin) VALUES (?, 0, false)", employeeId);
    }

    /** Current employees with portal access whose role may manage salary structures. */
    public List<UUID> findFinanceRecipients() {
        return jdbc.queryForList("""
            SELECT e.id FROM employees e WHERE e.status <> 'EXITED' AND e.portal_access
              AND EXISTS (SELECT 1 FROM role_permissions p WHERE p.role_id = e.role_id AND p.permission_key = 'struct.manage')""", UUID.class);
    }

    public void insertStructuresNotification(UUID employeeId, String text) {
        jdbc.update("INSERT INTO notifications (id, employee_id, text, link, created_at) VALUES (?, ?, ?, '/structures', now())",
                UUID.randomUUID(), employeeId, text);
    }

    /** Writes the changed PII columns (camelCase keys → snake_case columns), one statement per column in map order. */
    public void updatePii(UUID id, Map<String, String> cols) {
        for (Map.Entry<String, String> entry : cols.entrySet()) {
            String columnName = entry.getKey().replaceAll("([A-Z])", "_$1").toLowerCase();
            jdbc.update("UPDATE employees SET " + columnName + " = ? WHERE id = ?", entry.getValue(), id);
        }
    }

    // ── export ──

    /** Export rows (up to 20,000) for the query, as CSV cells. */
    public List<List<Object>> findExportRows(PeopleQuery peopleQuery) {
        List<Object> args = new ArrayList<>(peopleQuery.args);
        return jdbc.query("""
            SELECT e.code, e.full_name, e.email, e.designation, d.name AS department_name, m.full_name AS manager_name, m.code AS manager_code, e.time_zone, s.name AS shift,
                   r.name AS role, e.status::text AS status, e.portal_access, e.date_of_joining, e.exit_date
            """ + PeopleQuery.FROM + " LEFT JOIN shifts s ON s.id = e.shift_id" + peopleQuery.where + peopleQuery.orderBy + " LIMIT 20000",
                (resultSet, i) -> Arrays.asList(resultSet.getString("code"), resultSet.getString("full_name"), resultSet.getString("email"), resultSet.getString("designation"),
                        emptyIfNull(resultSet.getString("department_name")), emptyIfNull(resultSet.getString("manager_name")), emptyIfNull(resultSet.getString("manager_code")), PeopleRules.locationOfTimeZone(resultSet.getString("time_zone")).label(),
                        resultSet.getString("time_zone"), emptyIfNull(resultSet.getString("shift")), resultSet.getString("role"), resultSet.getString("status"),
                        resultSet.getBoolean("portal_access") ? "Enabled" : "Disabled", resultSet.getDate("date_of_joining").toLocalDate().toString(),
                        resultSet.getDate("exit_date") == null ? "" : resultSet.getDate("exit_date").toLocalDate().toString()),
                args.toArray());
    }

    private static String emptyIfNull(String text) {
        return text == null ? "" : text;
    }
}
