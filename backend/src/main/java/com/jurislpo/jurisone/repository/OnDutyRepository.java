package com.jurislpo.jurisone.repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.jurislpo.jurisone.service.AttendanceApprovalPolicy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** On Duty requests. Row-level security keeps every query inside the signed-in user's business; every value is a bound parameter. */
@Repository
public class OnDutyRepository {

    private final JdbcTemplate jdbc;

    public OnDutyRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A request as the list and the details dialog show it. {@code status} is the stored one (DECLINED shows as "Rejected" in the app). */
    public record Item(String id, String employeeId, String employeeCode, String employeeName, String department, String date, String part, String location, String reason,
                       String status, String decidedBy, String decidedAt, String decisionNote, String createdAt, String createdBy) {}

    public record NewRequest(UUID id, UUID employeeId, LocalDate workDate, String part, String location, String reason, UUID createdBy, UUID batchId) {}

    /** Filters of the list. Null = no filter. */
    /** {@code hrReportsOf}: only requests of HR people whose reporting manager is this employee (what a manager sees and may decide). */
    public record Filter(UUID employeeId, String status, LocalDate from, LocalDate to, String search, UUID departmentId, UUID hrReportsOf) {}

    private static final String SELECT = """
        SELECT r.id::text, r.employee_id::text, e.code, e.full_name, d.name AS department, r.work_date, r.part, r.location, r.reason, r.status::text,
               dd.full_name AS decided_by, r.decided_at, r.decision_note, r.created_at, r.created_by::text
        FROM on_duty_requests r JOIN employees e ON e.id = r.employee_id LEFT JOIN departments d ON d.id = e.department_id LEFT JOIN employees dd ON dd.id = r.decided_by""";

    private static Item item(ResultSet resultSet, int i) throws SQLException {
        return new Item(resultSet.getString(1), resultSet.getString(2), resultSet.getString(3), resultSet.getString(4), resultSet.getString(5), resultSet.getDate(6).toLocalDate().toString(),
                resultSet.getString(7), resultSet.getString(8), resultSet.getString(9), resultSet.getString(10), resultSet.getString(11), iso(resultSet.getTimestamp(12)),
                resultSet.getString(13), iso(resultSet.getTimestamp(14)), resultSet.getString(15));
    }

    private static String iso(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    private static String where(Filter filter, List<Object> args) {
        StringBuilder where = new StringBuilder(" WHERE true");
        if (filter.employeeId() != null) { where.append(" AND r.employee_id = ?"); args.add(filter.employeeId()); }
        if (filter.status() != null) { where.append(" AND r.status = ?::\"RequestStatus\""); args.add(filter.status()); }
        if (filter.from() != null) { where.append(" AND r.work_date >= ?"); args.add(Date.valueOf(filter.from())); }
        if (filter.to() != null) { where.append(" AND r.work_date <= ?"); args.add(Date.valueOf(filter.to())); }
        if (filter.departmentId() != null) { where.append(" AND e.department_id = ?"); args.add(filter.departmentId()); }
        if (filter.search() != null && !filter.search().isEmpty()) { where.append(" AND e.full_name ILIKE ?"); args.add(PeopleQuery.like(filter.search())); }
        if (filter.hrReportsOf() != null) {
            where.append(" AND e.reporting_manager_id = ? AND ").append(AttendanceApprovalPolicy.SUBJECT_IS_HR);
            args.add(filter.hrReportsOf());
            args.add("appr.reg");
            args.add("roles.manage");
        }
        return where.toString();
    }

    /** One page of requests, newest work date first. */
    public List<Item> list(Filter filter, int skip, int take) {
        List<Object> args = new ArrayList<>();
        String where = where(filter, args);
        args.add(take);
        args.add(skip);
        return jdbc.query(SELECT + where + " ORDER BY r.work_date DESC, r.created_at DESC LIMIT ? OFFSET ?", OnDutyRepository::item, args.toArray());
    }

    public int count(Filter filter) {
        List<Object> args = new ArrayList<>();
        String where = where(filter, args);
        Integer count = jdbc.queryForObject("SELECT count(*) FROM on_duty_requests r JOIN employees e ON e.id = r.employee_id" + where, Integer.class, args.toArray());
        return count == null ? 0 : count;
    }

    public Item find(UUID id) {
        List<Item> items = jdbc.query(SELECT + " WHERE r.id = ?", OnDutyRepository::item, id);
        return items.isEmpty() ? null : items.get(0);
    }

    /** employee_id, created_by, work_date, part and status of a request, for a decision, an edit or a withdrawal (empty when there is none). */
    public List<Map<String, Object>> findForDecision(UUID id) {
        return jdbc.queryForList("""
            SELECT r.employee_id, r.created_by, r.work_date, r.part, r.status::text AS status, e.full_name FROM on_duty_requests r JOIN employees e ON e.id = r.employee_id WHERE r.id = ?""", id);
    }

    public void insert(NewRequest request) {
        jdbc.update("""
            INSERT INTO on_duty_requests (id, employee_id, work_date, part, location, reason, status, created_by, batch_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, now(), now())""",
                request.id(), request.employeeId(), Date.valueOf(request.workDate()), request.part(), request.location(), request.reason(), request.createdBy(), request.batchId());
    }

    /** Status of the pending or approved request for this employee and day (empty when there is none). */
    public List<String> openStatus(UUID employeeId, LocalDate date) {
        return jdbc.queryForList("SELECT status::text FROM on_duty_requests WHERE employee_id = ? AND work_date = ? AND status IN ('PENDING', 'APPROVED') LIMIT 1",
                String.class, employeeId, Date.valueOf(date));
    }

    /** Status of the pending or approved request for this employee and day, other than {@code exceptId} (empty when there is none). */
    public List<String> openStatusExcept(UUID employeeId, LocalDate date, UUID exceptId) {
        return jdbc.queryForList("SELECT status::text FROM on_duty_requests WHERE employee_id = ? AND work_date = ? AND id <> ? AND status IN ('PENDING', 'APPROVED') LIMIT 1",
                String.class, employeeId, Date.valueOf(date), exceptId);
    }

    /** Days of the employee's pending or approved requests in [first, last] (they count against the monthly On Duty limit), other than {@code exceptId}. */
    public int countUsed(UUID employeeId, LocalDate first, LocalDate last, UUID exceptId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM on_duty_requests WHERE employee_id = ? AND work_date >= ? AND work_date <= ? AND status IN ('PENDING', 'APPROVED') AND id <> ?",
                Integer.class, employeeId, Date.valueOf(first), Date.valueOf(last), exceptId);
        return count == null ? 0 : count;
    }

    /** Changes a request that is still pending (the requester edits it); returns rows changed (0 = decided or withdrawn a moment ago). */
    public int updatePending(UUID id, LocalDate date, String part, String location, String reason) {
        return jdbc.update("UPDATE on_duty_requests SET work_date = ?, part = ?, location = ?, reason = ?, updated_at = now() WHERE id = ? AND status = 'PENDING'",
                Date.valueOf(date), part, location, reason, id);
    }

    /** Withdraws the employee's own request if it is still pending; returns rows changed (0 = decided or withdrawn a moment ago). */
    public int cancel(UUID id, UUID employeeId) {
        return jdbc.update("UPDATE on_duty_requests SET status = 'CANCELLED', updated_at = now() WHERE id = ? AND employee_id = ? AND status = 'PENDING'", id, employeeId);
    }

    /** The first decision wins: returns rows changed (0 = somebody decided or the employee withdrew it a moment ago). */
    public int decide(UUID id, String status, UUID decidedBy, Instant decidedAt, String decidedRole, String note) {
        return jdbc.update("UPDATE on_duty_requests SET status = ?::\"RequestStatus\", decided_by = ?, decided_at = ?, decided_role = ?, decision_note = ?, updated_at = now() WHERE id = ? AND status = 'PENDING'",
                status, decidedBy, Timestamp.from(decidedAt), decidedRole, note, id);
    }
}
