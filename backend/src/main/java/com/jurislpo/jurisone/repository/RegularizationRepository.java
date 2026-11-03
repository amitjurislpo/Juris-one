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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Regularization requests (v2: Old/New values, reasons, attachment) and the reason list. Row-level security keeps every query inside the business. */
@Repository
public class RegularizationRepository {

    private final JdbcTemplate jdbc;

    public RegularizationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Reason(String id, String label, boolean requiresDescription, boolean active, int sortOrder) {}

    /** A request as the list and the details drawer show it. Instants are ISO strings; {@code status} is the stored one (DECLINED shows as "Rejected" in the app). */
    public record Item(String id, String employeeId, String employeeCode, String employeeName, String department, String date, String kind, String status,
                       String checkIn, String checkOut, String oldCheckIn, String oldCheckOut, Integer oldMinutes, Integer newMinutes, String oldStatus, String newStatus,
                       String reasonId, String reason, String description, String decidedBy, String decidedAt, String decisionNote,
                       boolean hasAttachment, String attachmentName, String createdAt) {}

    public record NewRequest(UUID id, UUID employeeId, LocalDate workDate, String kind, Instant checkIn, Instant checkOut, String reasonText, UUID reasonId, String description,
                             Instant oldCheckIn, Instant oldCheckOut, Integer oldMinutes, String oldStatus, Integer newMinutes, String newStatus, UUID createdBy,
                             String attachmentName, String attachmentMime, Integer attachmentBytes, String attachmentPath, UUID batchId) {}

    /** Filters of the list; every value is bound as a parameter. Null = no filter. */
    public record Filter(UUID employeeId, String status, LocalDate from, LocalDate to, String search, UUID departmentId) {}

    private static final String SELECT = """
        SELECT r.id::text, r.employee_id::text, e.code, e.full_name, d.name AS department, r.work_date, r.kind::text, r.status::text, r.proposed_in, r.proposed_out,
               r.old_check_in, r.old_check_out, r.old_minutes, r.new_minutes, r.old_status, r.new_status, r.reason_id::text, r.reason, r.description,
               dd.full_name AS decided_by, r.decided_at, r.decision_note, (r.attachment_path IS NOT NULL) AS has_attachment, r.attachment_name, r.created_at
        FROM regularization_requests r JOIN employees e ON e.id = r.employee_id LEFT JOIN departments d ON d.id = e.department_id LEFT JOIN employees dd ON dd.id = r.decided_by""";

    private static Item item(ResultSet resultSet, int i) throws SQLException {
        return new Item(resultSet.getString(1), resultSet.getString(2), resultSet.getString(3), resultSet.getString(4), resultSet.getString(5), resultSet.getDate(6).toLocalDate().toString(),
                resultSet.getString(7), resultSet.getString(8), iso(resultSet.getTimestamp(9)), iso(resultSet.getTimestamp(10)), iso(resultSet.getTimestamp(11)), iso(resultSet.getTimestamp(12)),
                (Integer) resultSet.getObject(13), (Integer) resultSet.getObject(14), resultSet.getString(15), resultSet.getString(16), resultSet.getString(17), resultSet.getString(18),
                resultSet.getString(19), resultSet.getString(20), iso(resultSet.getTimestamp(21)), resultSet.getString(22), resultSet.getBoolean(23), resultSet.getString(24), iso(resultSet.getTimestamp(25)));
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
        return where.toString();
    }

    /** One page of requests, newest work date first. */
    public List<Item> list(Filter filter, int skip, int take) {
        List<Object> args = new ArrayList<>();
        String where = where(filter, args);
        args.add(take);
        args.add(skip);
        return jdbc.query(SELECT + where + " ORDER BY r.work_date DESC, r.created_at DESC LIMIT ? OFFSET ?", RegularizationRepository::item, args.toArray());
    }

    public int count(Filter filter) {
        List<Object> args = new ArrayList<>();
        String where = where(filter, args);
        Integer count = jdbc.queryForObject("SELECT count(*) FROM regularization_requests r JOIN employees e ON e.id = r.employee_id" + where, Integer.class, args.toArray());
        return count == null ? 0 : count;
    }

    public Item find(UUID id) {
        List<Item> items = jdbc.query(SELECT + " WHERE r.id = ?", RegularizationRepository::item, id);
        return items.isEmpty() ? null : items.get(0);
    }

    /** employee_id, status and the stored attachment of a request (empty when there is none). */
    public List<Map<String, Object>> findOwnerAndAttachment(UUID id) {
        return jdbc.queryForList("SELECT employee_id, status::text AS status, attachment_name, attachment_mime, attachment_path FROM regularization_requests WHERE id = ?", id);
    }

    public void insert(NewRequest request) {
        jdbc.update("""
            INSERT INTO regularization_requests (id, employee_id, work_date, kind, proposed_in, proposed_out, reason, status, reason_id, description,
                old_check_in, old_check_out, old_minutes, old_status, new_minutes, new_status, created_by, attachment_name, attachment_mime, attachment_bytes, attachment_path, batch_id, created_at, updated_at)
            VALUES (?, ?, ?, ?::"RegularizationKind", ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())""",
                request.id(), request.employeeId(), Date.valueOf(request.workDate()), request.kind(), ts(request.checkIn()), ts(request.checkOut()), request.reasonText(), request.reasonId(),
                request.description(), ts(request.oldCheckIn()), ts(request.oldCheckOut()), request.oldMinutes(), request.oldStatus(), request.newMinutes(), request.newStatus(), request.createdBy(),
                request.attachmentName(), request.attachmentMime(), request.attachmentBytes(), request.attachmentPath(), request.batchId());
    }

    /** Is there already a pending request for this employee and day? */
    public boolean hasPending(UUID employeeId, LocalDate date) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM regularization_requests WHERE employee_id = ? AND work_date = ? AND status = 'PENDING'", Integer.class, employeeId, Date.valueOf(date));
        return count != null && count > 0;
    }

    /** Status of a pending or approved request of this kind for the day (empty when none). */
    public List<String> openStatus(UUID employeeId, LocalDate date, String kind) {
        return jdbc.queryForList("SELECT status::text FROM regularization_requests WHERE employee_id = ? AND work_date = ? AND kind = ?::\"RegularizationKind\" "
                + "AND status IN ('PENDING', 'APPROVED') LIMIT 1", String.class, employeeId, Date.valueOf(date), kind);
    }

    /** Requests of the employee for work dates in [first, last] that were not withdrawn (they count against the monthly limit). */
    public int countUsed(UUID employeeId, LocalDate first, LocalDate last) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM regularization_requests WHERE employee_id = ? AND work_date >= ? AND work_date <= ? AND status <> 'CANCELLED'",
                Integer.class, employeeId, Date.valueOf(first), Date.valueOf(last));
        return count == null ? 0 : count;
    }

    /** Withdraws the employee's own request if it is still pending; returns rows changed (0 = decided or withdrawn a moment ago). */
    public int cancel(UUID id, UUID employeeId) {
        return jdbc.update("UPDATE regularization_requests SET status = 'CANCELLED', updated_at = now() WHERE id = ? AND employee_id = ? AND status = 'PENDING'", id, employeeId);
    }

    /** Active employees who can approve regularizations, except the requester. */
    public List<UUID> approverIds(UUID exceptEmployeeId) {
        return jdbc.queryForList("""
            SELECT DISTINCT e.id FROM employees e JOIN role_permissions rp ON rp.role_id = e.role_id
            WHERE rp.permission_key = 'appr.reg' AND e.status IN ('ACTIVE', 'PROBATION') AND e.id <> ?""", UUID.class, exceptEmployeeId);
    }

    // ───────────────────────── reasons ─────────────────────────

    private static Reason reason(ResultSet resultSet, int i) throws SQLException {
        return new Reason(resultSet.getString(1), resultSet.getString(2), resultSet.getBoolean(3), resultSet.getBoolean(4), resultSet.getInt(5));
    }

    public List<Reason> reasons(boolean onlyActive) {
        return jdbc.query("SELECT id::text, label, requires_description, active, sort_order FROM regularization_reasons" + (onlyActive ? " WHERE active" : "") + " ORDER BY sort_order, label",
                RegularizationRepository::reason);
    }

    public Reason findReason(UUID id) {
        List<Reason> reasons = jdbc.query("SELECT id::text, label, requires_description, active, sort_order FROM regularization_reasons WHERE id = ?", RegularizationRepository::reason, id);
        return reasons.isEmpty() ? null : reasons.get(0);
    }

    public boolean labelExists(String label, UUID exceptId) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM regularization_reasons WHERE lower(label) = lower(?) AND (?::uuid IS NULL OR id <> ?::uuid)", Integer.class, label, exceptId, exceptId);
        return count != null && count > 0;
    }

    public void insertReason(UUID id, String label, boolean requiresDescription) {
        jdbc.update("INSERT INTO regularization_reasons (id, label, requires_description, active, sort_order) VALUES (?, ?, ?, true, "
                + "COALESCE((SELECT max(sort_order) FROM regularization_reasons), 0) + 1)", id, label, requiresDescription);
    }

    public void updateReason(UUID id, String label, boolean requiresDescription, boolean active) {
        jdbc.update("UPDATE regularization_reasons SET label = ?, requires_description = ?, active = ? WHERE id = ?", label, requiresDescription, active, id);
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
