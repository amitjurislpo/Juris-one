package com.jurislpo.jurisone.repository;

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

/** Leave and regularization requests as the approvers see and decide them. */
@Repository
public class ApprovalsRepository {

    private final JdbcTemplate jdbc;

    public ApprovalsRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A decided leave request (last 30 days list). */
    public record DecidedLeave(String id, Instant decidedAt, String name, String typeName, LocalDate from, LocalDate to, String status, String decidedBy) {}

    /** A decided regularization (last 30 days list). */
    public record DecidedRegularization(String id, Instant decidedAt, String name, String kind, LocalDate workDate, String status, String decidedBy, String role) {}

    /** The leave request with its type name and the employee's name (empty when not found). */
    public List<Map<String, Object>> findLeaveForDecision(UUID id) {
        return jdbc.queryForList("""
            SELECT l.employee_id, l.leave_type, l.from_date, l.to_date, l.part::text AS part, l.days, l.lop_days, l.status::text AS status, t.name AS type_name,
                   e.full_name FROM leave_requests l JOIN leave_types t ON t.code = l.leave_type JOIN employees e ON e.id = l.employee_id WHERE l.id = ?""", id);
    }

    /** Decides the leave request if it is still pending; returns rows changed (0 = someone else decided first). */
    public int decideLeave(UUID id, String status, UUID decidedBy, Timestamp decidedAt, BigDecimal lopDays) {
        return jdbc.update("UPDATE leave_requests SET status = ?::\"RequestStatus\", decided_by = ?, decided_at = ?, lop_days = ? WHERE id = ? AND status = 'PENDING'",
                status, decidedBy, decidedAt, lopDays, id);
    }

    /** The regularization request with the employee's name (empty when not found). */
    public List<Map<String, Object>> findRegularizationForDecision(UUID id) {
        return jdbc.queryForList("""
            SELECT r.employee_id, r.work_date, r.kind::text AS kind, r.status::text AS status, r.proposed_in, r.proposed_out, e.full_name
            FROM regularization_requests r JOIN employees e ON e.id = r.employee_id WHERE r.id = ?""", id);
    }

    /** Decides the regularization if it is still pending; returns rows changed (0 = someone else decided first). */
    public int decideRegularization(UUID id, String status, UUID decidedBy, Timestamp decidedAt, String decidedRole) {
        return jdbc.update("UPDATE regularization_requests SET status = ?::\"RequestStatus\", decided_by = ?, decided_at = ?, decided_role = ? WHERE id = ? AND status = 'PENDING'",
                status, decidedBy, decidedAt, decidedRole, id);
    }

    public void insertRegularizationPunchIn(UUID employeeId, Timestamp timestamp) {
        jdbc.update("INSERT INTO punches (id, employee_id, at, kind, source, created_at) VALUES (?, ?, ?, 'IN', 'REGULARIZATION', now())", UUID.randomUUID(), employeeId, timestamp);
    }

    public void insertRegularizationPunchOut(UUID employeeId, Timestamp timestamp) {
        jdbc.update("INSERT INTO punches (id, employee_id, at, kind, source, created_at) VALUES (?, ?, ?, 'OUT', 'REGULARIZATION', now())", UUID.randomUUID(), employeeId, timestamp);
    }

    /** Days after which a comp-off expires (null when not set). */
    public Integer compOffExpiryDays() {
        return jdbc.queryForObject("SELECT expiry_days FROM leave_types WHERE code = 'CO'", Integer.class);
    }

    public void insertCompOffCredit(UUID employeeId, BigDecimal days, String refId, Date expiresOn, String note) {
        jdbc.update("INSERT INTO leave_ledger (id, employee_id, leave_type, delta, reason, ref_id, expires_on, note, at) VALUES (?, ?, 'CO', ?, 'COMP_OFF_CREDIT', ?, ?, ?, now())",
                UUID.randomUUID(), employeeId, days, refId, expiresOn, note);
    }

    /**
     * Pending leave requests of everyone but the approver, oldest first. {@code nameFilter} is "" or an extra
     * {@code AND …} condition whose parameters follow the approver's id in {@code args}.
     */
    public List<Map<String, Object>> findPendingLeave(String nameFilter, Object[] args) {
        return jdbc.queryForList("""
            SELECT l.id, l.employee_id, l.leave_type, t.name AS type_name, l.from_date, l.to_date, l.part::text AS part, l.days, l.lop_days, l.reason,
                   e.full_name, e.department_id, d.name AS department_name
            FROM leave_requests l JOIN leave_types t ON t.code = l.leave_type JOIN employees e ON e.id = l.employee_id LEFT JOIN departments d ON d.id = e.department_id
            WHERE l.status = 'PENDING' AND l.employee_id <> ?""" + nameFilter + " ORDER BY l.from_date ASC", args);
    }

    /** Other pending or approved leave in the department overlapping from..to. */
    public Integer countDepartmentOverlap(Object requestId, UUID employeeId, Object departmentId, LocalDate from, LocalDate to) {
        return jdbc.queryForObject("""
            SELECT count(*) FROM leave_requests o JOIN employees oe ON oe.id = o.employee_id
            WHERE o.id <> ? AND o.employee_id <> ? AND o.status IN ('PENDING', 'APPROVED') AND oe.department_id = ? AND o.from_date <= ? AND o.to_date >= ?""",
                Integer.class, requestId, employeeId, departmentId, Date.valueOf(to), Date.valueOf(from));
    }

    /** Pending regularizations of everyone but the approver, oldest first; {@code nameFilter} / {@code args} as for leave. */
    public List<Map<String, Object>> findPendingRegularizations(String nameFilter, Object[] args) {
        return jdbc.queryForList("""
            SELECT r.id, r.employee_id, r.work_date, r.kind::text AS kind, r.proposed_in, r.proposed_out, r.reason, e.full_name, e.time_zone, d.name AS department_name
            FROM regularization_requests r JOIN employees e ON e.id = r.employee_id LEFT JOIN departments d ON d.id = e.department_id
            WHERE r.status = 'PENDING' AND r.employee_id <> ?""" + nameFilter + " ORDER BY r.work_date ASC", args);
    }

    public Integer countPendingLeave(UUID exceptEmployeeId) {
        return jdbc.queryForObject("SELECT count(*) FROM leave_requests WHERE status = 'PENDING' AND employee_id <> ?", Integer.class, exceptEmployeeId);
    }

    public Integer countPendingRegularizations(UUID exceptEmployeeId) {
        return jdbc.queryForObject("SELECT count(*) FROM regularization_requests WHERE status = 'PENDING' AND employee_id <> ?", Integer.class, exceptEmployeeId);
    }

    /** The 15 most recently decided leave requests since the given time. */
    public List<DecidedLeave> findDecidedLeave(Timestamp since) {
        return jdbc.query("""
            SELECT l.id::text, l.decided_at, e.full_name, t.name, l.from_date, l.to_date, l.status::text, dd.full_name AS by
            FROM leave_requests l JOIN employees e ON e.id = l.employee_id JOIN leave_types t ON t.code = l.leave_type LEFT JOIN employees dd ON dd.id = l.decided_by
            WHERE l.status IN ('APPROVED', 'DECLINED') AND l.decided_at >= ? ORDER BY l.decided_at DESC LIMIT 15""",
                (resultSet, i) -> new DecidedLeave(resultSet.getString(1), resultSet.getTimestamp(2).toInstant(), resultSet.getString(3), resultSet.getString(4), resultSet.getDate(5).toLocalDate(),
                        resultSet.getDate(6).toLocalDate(), resultSet.getString(7), resultSet.getString(8)), since);
    }

    /** The 15 most recently decided regularizations since the given time. */
    public List<DecidedRegularization> findDecidedRegularizations(Timestamp since) {
        return jdbc.query("""
            SELECT r.id::text, r.decided_at, e.full_name, r.kind::text, r.work_date, r.status::text, dd.full_name AS by, r.decided_role
            FROM regularization_requests r JOIN employees e ON e.id = r.employee_id LEFT JOIN employees dd ON dd.id = r.decided_by
            WHERE r.status IN ('APPROVED', 'DECLINED') AND r.decided_at >= ? ORDER BY r.decided_at DESC LIMIT 15""",
                (resultSet, i) -> new DecidedRegularization(resultSet.getString(1), resultSet.getTimestamp(2).toInstant(), resultSet.getString(3), resultSet.getString(4), resultSet.getDate(5).toLocalDate(),
                        resultSet.getString(6), resultSet.getString(7), resultSet.getString(8)), since);
    }
}
