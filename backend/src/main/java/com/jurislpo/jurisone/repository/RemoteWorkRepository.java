package com.jurislpo.jurisone.repository;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Remote work requests (a date range per request). Row-level security keeps every query inside the business. */
@Repository
public class RemoteWorkRepository {

    private final JdbcTemplate jdbc;

    public RemoteWorkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** A request as the list and the details show it; {@code status} is the stored one (DECLINED shows as "Rejected" in the app); {@code days} counts calendar days, both ends included. */
    public record Item(String id, String employeeId, String employeeCode, String employeeName, String department, String fromDate, String toDate, int days, String reason, String status,
                       String decidedBy, String decidedAt, String decisionNote, String createdAt) {}

    /** Filters of the list; every value is bound as a parameter. Null = no filter. {@code managerId}: only the direct reports of that manager. */
    public record Filter(UUID employeeId, UUID managerId, String status, LocalDate monthFirst, LocalDate monthLast, String search, UUID departmentId) {}

    private static final String SELECT = """
        SELECT r.id::text, r.employee_id::text, e.code, e.full_name, d.name AS department, r.from_date, r.to_date, r.reason, r.status::text,
               dd.full_name AS decided_by, r.decided_at, r.decision_note, r.created_at
        FROM remote_work_requests r JOIN employees e ON e.id = r.employee_id LEFT JOIN departments d ON d.id = e.department_id LEFT JOIN employees dd ON dd.id = r.decided_by""";

    private static Item item(ResultSet resultSet, int i) throws SQLException {
        LocalDate from = resultSet.getDate(6).toLocalDate(), to = resultSet.getDate(7).toLocalDate();
        return new Item(resultSet.getString(1), resultSet.getString(2), resultSet.getString(3), resultSet.getString(4), resultSet.getString(5), from.toString(), to.toString(),
                (int) (to.toEpochDay() - from.toEpochDay()) + 1, resultSet.getString(8), resultSet.getString(9), resultSet.getString(10), iso(resultSet.getTimestamp(11)), resultSet.getString(12),
                iso(resultSet.getTimestamp(13)));
    }

    private static String iso(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    private static String where(Filter filter, List<Object> args) {
        StringBuilder where = new StringBuilder(" WHERE true");
        if (filter.employeeId() != null) { where.append(" AND r.employee_id = ?"); args.add(filter.employeeId()); }
        if (filter.managerId() != null) { where.append(" AND e.reporting_manager_id = ?"); args.add(filter.managerId()); }
        if (filter.status() != null) { where.append(" AND r.status = ?::\"RequestStatus\""); args.add(filter.status()); }
        if (filter.monthFirst() != null) { where.append(" AND r.to_date >= ? AND r.from_date <= ?"); args.add(Date.valueOf(filter.monthFirst())); args.add(Date.valueOf(filter.monthLast())); }
        if (filter.departmentId() != null) { where.append(" AND e.department_id = ?"); args.add(filter.departmentId()); }
        if (filter.search() != null && !filter.search().isEmpty()) { where.append(" AND e.full_name ILIKE ?"); args.add(PeopleQuery.like(filter.search())); }
        return where.toString();
    }

    /** One page of requests, latest start day first. */
    public List<Item> list(Filter filter, int skip, int take) {
        List<Object> args = new ArrayList<>();
        String where = where(filter, args);
        args.add(take);
        args.add(skip);
        return jdbc.query(SELECT + where + " ORDER BY r.from_date DESC, r.created_at DESC, r.id LIMIT ? OFFSET ?", RemoteWorkRepository::item, args.toArray());
    }

    public int count(Filter filter) {
        List<Object> args = new ArrayList<>();
        String where = where(filter, args);
        Integer count = jdbc.queryForObject("SELECT count(*) FROM remote_work_requests r JOIN employees e ON e.id = r.employee_id" + where, Integer.class, args.toArray());
        return count == null ? 0 : count;
    }

    public Item find(UUID id) {
        List<Item> items = jdbc.query(SELECT + " WHERE r.id = ?", RemoteWorkRepository::item, id);
        return items.isEmpty() ? null : items.get(0);
    }

    /** employee_id, the employee's reporting manager, status and dates of a request (empty when there is none). */
    public List<Map<String, Object>> findForDecision(UUID id) {
        return jdbc.queryForList("""
            SELECT r.employee_id, e.reporting_manager_id AS manager_id, r.status::text AS status, r.from_date, r.to_date, e.full_name
            FROM remote_work_requests r JOIN employees e ON e.id = r.employee_id WHERE r.id = ?""", id);
    }

    /** Serialises the requests of one employee inside the running transaction, so two overlapping requests can't both pass the overlap check. */
    public void lockEmployee(UUID employeeId) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?, 35))", Object.class, "remote-work:" + employeeId);
    }

    /** Does the employee have a pending or approved request touching [from, to]? */
    public boolean hasOpenOverlap(UUID employeeId, LocalDate from, LocalDate to) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM remote_work_requests WHERE employee_id = ? AND status IN ('PENDING', 'APPROVED') AND from_date <= ? AND to_date >= ?",
                Integer.class, employeeId, Date.valueOf(to), Date.valueOf(from));
        return count != null && count > 0;
    }

    public void insert(UUID id, UUID employeeId, LocalDate from, LocalDate to, String reason, UUID createdBy) {
        jdbc.update("INSERT INTO remote_work_requests (id, employee_id, from_date, to_date, reason, status, created_by, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'PENDING', ?, now(), now())",
                id, employeeId, Date.valueOf(from), Date.valueOf(to), reason, createdBy);
    }

    /** Withdraws the employee's own request if it is still pending; returns rows changed (0 = decided or withdrawn a moment ago). */
    public int cancel(UUID id, UUID employeeId) {
        return jdbc.update("UPDATE remote_work_requests SET status = 'CANCELLED', updated_at = now() WHERE id = ? AND employee_id = ? AND status = 'PENDING'", id, employeeId);
    }

    /** First decision wins: only a still pending request changes; returns rows changed. */
    public int decide(UUID id, String status, UUID decidedBy, String decidedRole, String note) {
        return jdbc.update("UPDATE remote_work_requests SET status = ?::\"RequestStatus\", decided_by = ?, decided_at = now(), decided_role = ?, decision_note = ?, updated_at = now() "
                + "WHERE id = ? AND status = 'PENDING'", status, decidedBy, decidedRole, note, id);
    }

    /** Active employees who can approve (appr.reg, through their main or an additional role), except the requester. */
    public List<UUID> approverIds(UUID exceptEmployeeId) {
        return jdbc.queryForList("""
            SELECT DISTINCT e.id FROM employees e
            JOIN role_permissions rp ON rp.role_id = e.role_id OR rp.role_id IN (SELECT ar.role_id FROM employee_additional_roles ar WHERE ar.employee_id = e.id)
            WHERE rp.permission_key = 'appr.reg' AND e.status IN ('ACTIVE', 'PROBATION') AND e.id <> ?""", UUID.class, exceptEmployeeId);
    }
}
