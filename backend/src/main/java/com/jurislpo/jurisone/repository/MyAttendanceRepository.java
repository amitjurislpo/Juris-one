package com.jurislpo.jurisone.repository;

import com.jurislpo.jurisone.engine.AttendanceEngine.Punch;
import com.jurislpo.jurisone.entity.PunchKind;
import com.jurislpo.jurisone.service.MyAttendanceService.HolidayView;
import com.jurislpo.jurisone.service.MyAttendanceService.RegularizationView;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Data access for {@code MyAttendanceService}: own regularization requests, holidays and punches. */
@Repository
public class MyAttendanceRepository {

    private final JdbcTemplate jdbc;

    public MyAttendanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** An employee's regularization requests for work dates in [first, last], newest first. */
    public List<RegularizationView> regularizations(UUID employeeId, LocalDate first, LocalDate last) {
        return jdbc.query("""
            SELECT r.id::text, r.work_date, r.kind::text, r.status::text, d.full_name AS by, r.reason FROM regularization_requests r
            LEFT JOIN employees d ON d.id = r.decided_by WHERE r.employee_id = ? AND r.work_date >= ? AND r.work_date <= ? ORDER BY r.created_at DESC""",
                (resultSet, i) -> new RegularizationView(resultSet.getString(1), resultSet.getDate(2).toLocalDate().toString(), resultSet.getString(3), resultSet.getString(4), resultSet.getString(5), resultSet.getString(6)),
                employeeId, Date.valueOf(first), Date.valueOf(last));
    }

    /** Holidays from {@code from} for a region plus "Everyone", soonest first. */
    public List<HolidayView> upcomingHolidays(String region, LocalDate from, int take) {
        return jdbc.query("SELECT id::text, date, name, region::text FROM holidays WHERE date >= ? AND region::text IN ('ALL', ?) ORDER BY date ASC LIMIT ?",
                (resultSet, i) -> new HolidayView(resultSet.getString(1), resultSet.getDate(2).toLocalDate().toString(), resultSet.getString(3), resultSet.getString(4)), Date.valueOf(from), region, take);
    }

    /** Status of a pending or approved request of this kind for the day (empty when none). */
    public List<String> openRegularizationStatus(UUID employeeId, LocalDate date, String kind) {
        return jdbc.queryForList("SELECT status::text FROM regularization_requests WHERE employee_id = ? AND work_date = ? AND kind = ?::\"RegularizationKind\" "
                + "AND status IN ('PENDING', 'APPROVED') LIMIT 1", String.class, employeeId, Date.valueOf(date), kind);
    }

    public void insertRegularization(UUID id, UUID employeeId, LocalDate date, String kind, Instant proposedIn, Instant proposedOut, String reason) {
        jdbc.update("INSERT INTO regularization_requests (id, employee_id, work_date, kind, proposed_in, proposed_out, reason, status, created_at) "
                + "VALUES (?, ?, ?, ?::\"RegularizationKind\", ?, ?, ?, 'PENDING', now())",
                id, employeeId, Date.valueOf(date), kind, ts(proposedIn), ts(proposedOut), reason);
    }

    /** employee_id and status of a regularization request (empty when not found). */
    public List<Map<String, Object>> findRegularization(UUID id) {
        return jdbc.queryForList("SELECT employee_id, status::text AS status FROM regularization_requests WHERE id = ?", id);
    }

    public void cancelRegularization(UUID id) {
        jdbc.update("UPDATE regularization_requests SET status = 'CANCELLED' WHERE id = ?", id);
    }

    /** The employee's latest punch (at, kind), if any. */
    public List<Map<String, Object>> lastPunch(UUID employeeId) {
        return jdbc.queryForList("SELECT at, kind::text AS kind FROM punches WHERE employee_id = ? ORDER BY at DESC LIMIT 1", employeeId);
    }

    public void insertWebPunch(UUID employeeId, Instant instant, String kind, String ipAddress) {
        jdbc.update("INSERT INTO punches (id, employee_id, at, kind, source, ip, created_at) VALUES (?, ?, ?, ?::\"PunchKind\", 'WEB', ?, now())",
                UUID.randomUUID(), employeeId, Timestamp.from(instant), kind, ipAddress);
    }

    /** The employee's punches since {@code since}, oldest first. */
    public List<Punch> punchesSince(UUID employeeId, Instant since) {
        return jdbc.query("SELECT at, kind::text FROM punches WHERE employee_id = ? AND at >= ? ORDER BY at ASC",
                (resultSet, i) -> new Punch(resultSet.getTimestamp(1).toInstant(), PunchKind.valueOf(resultSet.getString(2))), employeeId, Timestamp.from(since));
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
