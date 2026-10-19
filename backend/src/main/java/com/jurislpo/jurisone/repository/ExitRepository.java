package com.jurislpo.jurisone.repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Employee exit cases and their clearance checklists (row-level security keeps every query inside the current business). */
@Repository
public class ExitRepository {

    private final JdbcTemplate jdbc;

    public ExitRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT = """
        SELECT x.id, x.employee_id, x.kind, x.status, x.reason, x.requested_last_day, x.last_working_day, x.notice_start_date, x.notice_days,
               x.notice_policy_days, x.notice_reason, x.hr_comment, x.initiated_at, x.accepted_at, x.settled_at, x.closed_at, x.ended_reason,
               e.code AS emp_code, e.full_name AS emp_name, e.designation AS emp_designation, e.date_of_joining, e.reporting_manager_id,
               e.status::text AS emp_status, e.region::text AS emp_region, d.name AS department, ib.full_name AS initiated_by_name,
               (SELECT count(*) FROM exit_clearance_items c WHERE c.exit_id = x.id) AS items,
               (SELECT count(*) FROM exit_clearance_items c WHERE c.exit_id = x.id AND c.cleared) AS items_cleared,
               (SELECT s.status FROM fnf_settlements s WHERE s.exit_id = x.id) AS settlement_status
        FROM employee_exits x JOIN employees e ON e.id = x.employee_id LEFT JOIN departments d ON d.id = e.department_id
             LEFT JOIN employees ib ON ib.id = x.initiated_by""";

    public Map<String, Object> findById(UUID id) {
        List<Map<String, Object>> r = jdbc.queryForList(SELECT + " WHERE x.id = ?", id);
        return r.isEmpty() ? null : r.get(0);
    }

    /** The employee's open case, or null. */
    public Map<String, Object> findOpen(UUID employeeId) {
        List<Map<String, Object>> r = jdbc.queryForList(SELECT + " WHERE x.employee_id = ? AND x.status NOT IN ('CLOSED', 'WITHDRAWN', 'CANCELLED')", employeeId);
        return r.isEmpty() ? null : r.get(0);
    }

    /** The employee's open case, else their most recent one, or null. */
    public Map<String, Object> findCurrentOrLatest(UUID employeeId) {
        List<Map<String, Object>> r = jdbc.queryForList(SELECT + " WHERE x.employee_id = ? ORDER BY (x.status NOT IN ('CLOSED', 'WITHDRAWN', 'CANCELLED')) DESC, x.created_at DESC LIMIT 1", employeeId);
        return r.isEmpty() ? null : r.get(0);
    }

    /** All cases, open ones first. {@code status} "open" / "closed" / a status / null = all; {@code q} matches name or code. */
    public List<Map<String, Object>> findAll(String status, String q) {
        String where = "";
        if ("open".equals(status)) where = " AND x.status NOT IN ('CLOSED', 'WITHDRAWN', 'CANCELLED')";
        else if ("ended".equals(status)) where = " AND x.status IN ('CLOSED', 'WITHDRAWN', 'CANCELLED')";
        else if (status != null && !status.isEmpty()) where = " AND x.status = '" + status.replaceAll("[^A-Z_]", "") + "'";
        String like = "%" + (q == null ? "" : q.trim().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")) + "%";
        return jdbc.queryForList(SELECT + " WHERE (lower(e.full_name) LIKE ? OR lower(e.code) LIKE ?)" + where
                + " ORDER BY (x.status IN ('CLOSED', 'WITHDRAWN', 'CANCELLED')), x.last_working_day ASC NULLS LAST, x.created_at DESC", like, like);
    }

    public void insert(UUID id, UUID employeeId, String kind, String status, String reason, LocalDate requestedLastDay, LocalDate lastWorkingDay,
                       LocalDate noticeStart, int noticeDays, int policyDays, String noticeReason, UUID initiatedBy) {
        jdbc.update("""
            INSERT INTO employee_exits (id, employee_id, kind, status, reason, requested_last_day, last_working_day, notice_start_date, notice_days,
                                        notice_policy_days, notice_reason, initiated_by)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", id, employeeId, kind, status, reason, sql(requestedLastDay), sql(lastWorkingDay), sql(noticeStart),
                noticeDays, policyDays, noticeReason, initiatedBy);
    }

    private static Date sql(LocalDate d) {
        return d == null ? null : Date.valueOf(d);
    }

    /** HR's agreed last working day and notice for the case. */
    public void setNotice(UUID id, LocalDate lastWorkingDay, int noticeDays, String noticeReason) {
        jdbc.update("UPDATE employee_exits SET last_working_day = ?, notice_days = ?, notice_reason = ?, updated_at = now() WHERE id = ?",
                sql(lastWorkingDay), noticeDays, noticeReason, id);
    }

    public void setStatus(UUID id, String status) {
        jdbc.update("UPDATE employee_exits SET status = ?, updated_at = now() WHERE id = ?", status, id);
    }

    /** RESIGNED / INITIATED → NOTICE: HR accepted; notice starts counting on {@code noticeStart}. */
    public void accept(UUID id, UUID by, LocalDate noticeStart, String comment) {
        jdbc.update("UPDATE employee_exits SET status = 'NOTICE', accepted_by = ?, accepted_at = now(), notice_start_date = ?, hr_comment = COALESCE(?, hr_comment), "
                + "updated_at = now() WHERE id = ?", by, sql(noticeStart), comment, id);
    }

    /** → SETTLED, when the settlement is approved. */
    public void settle(UUID id) {
        jdbc.update("UPDATE employee_exits SET status = 'SETTLED', settled_at = now(), updated_at = now() WHERE id = ?", id);
    }

    /** SETTLED → CLOSED, after the employee is marked as a past employee. */
    public void close(UUID id, UUID by) {
        jdbc.update("UPDATE employee_exits SET status = 'CLOSED', closed_by = ?, closed_at = now(), updated_at = now() WHERE id = ?", by, id);
    }

    /** → WITHDRAWN / CANCELLED with the reason. */
    public void end(UUID id, String status, String reason) {
        jdbc.update("UPDATE employee_exits SET status = ?, ended_reason = ?, updated_at = now() WHERE id = ?", status, reason, id);
    }

    // ── clearance ──

    public List<Map<String, Object>> items(UUID exitId) {
        return jdbc.queryForList("""
            SELECT c.id, c.dept, c.title, c.cleared, c.remarks, c.cleared_at, u.full_name AS cleared_by_name
            FROM exit_clearance_items c LEFT JOIN employees u ON u.id = c.cleared_by
            WHERE c.exit_id = ? ORDER BY c.sort_order, c.created_at""", exitId);
    }

    public Map<String, Object> findItem(UUID exitId, UUID itemId) {
        List<Map<String, Object>> r = jdbc.queryForList("SELECT id, dept, title, cleared, remarks FROM exit_clearance_items WHERE exit_id = ? AND id = ?", exitId, itemId);
        return r.isEmpty() ? null : r.get(0);
    }

    public void insertItem(UUID exitId, String dept, String title, int sortOrder) {
        jdbc.update("INSERT INTO exit_clearance_items (id, exit_id, dept, title, sort_order) VALUES (?, ?, ?, ?, ?)", UUID.randomUUID(), exitId, dept, title, sortOrder);
    }

    public int nextItemOrder(UUID exitId) {
        return jdbc.queryForObject("SELECT COALESCE(max(sort_order), 0) + 1 FROM exit_clearance_items WHERE exit_id = ?", Integer.class, exitId);
    }

    public void setItem(UUID itemId, boolean cleared, String remarks, UUID by) {
        jdbc.update("UPDATE exit_clearance_items SET cleared = ?, remarks = ?, cleared_by = ?, cleared_at = CASE WHEN ? THEN now() ELSE NULL END WHERE id = ?",
                cleared, remarks, cleared ? by : null, cleared, itemId);
    }

    public int uncleared(UUID exitId) {
        return jdbc.queryForObject("SELECT count(*) FROM exit_clearance_items WHERE exit_id = ? AND NOT cleared", Integer.class, exitId);
    }

    // ── people involved ──

    /** Active employees who hold exit.manage (HR managers, owners) — told when someone resigns. */
    public List<UUID> exitManagers() {
        return jdbc.query("""
            SELECT DISTINCT e.id FROM employees e JOIN role_permissions p ON p.role_id = e.role_id AND p.permission_key = 'exit.manage'
            WHERE e.status <> 'EXITED' AND e.portal_access""", (rs, i) -> (UUID) rs.getObject(1));
    }

    /** The employee row an exit starts from (null when not in this business). */
    public Map<String, Object> findEmployee(UUID id) {
        List<Map<String, Object>> r = jdbc.queryForList("""
            SELECT e.id, e.code, e.full_name, e.status::text AS status, e.date_of_joining, e.reporting_manager_id, e.region::text AS region,
                   r.key AS role_key
            FROM employees e JOIN roles r ON r.id = e.role_id WHERE e.id = ?""", id);
        return r.isEmpty() ? null : r.get(0);
    }
}
