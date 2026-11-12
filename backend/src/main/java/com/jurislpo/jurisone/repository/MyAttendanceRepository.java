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
    private final com.jurislpo.jurisone.crypto.EmployeeNameReader nameReader;

    public MyAttendanceRepository(JdbcTemplate jdbc, com.jurislpo.jurisone.crypto.EmployeeNameReader nameReader) {
        this.nameReader = nameReader;
        this.jdbc = jdbc;
    }

    /** An employee's regularization requests for work dates in [first, last], newest first. */
    public List<RegularizationView> regularizations(UUID employeeId, LocalDate first, LocalDate last) {
        return jdbc.query("""
            SELECT r.id::text AS id, r.work_date, r.kind::text AS kind, r.status::text AS status, d.full_name AS by, d.full_name_enc AS by_enc, r.reason FROM regularization_requests r
            LEFT JOIN employees d ON d.id = r.decided_by WHERE r.employee_id = ? AND r.work_date >= ? AND r.work_date <= ? ORDER BY r.created_at DESC""",
                (resultSet, i) -> new RegularizationView(resultSet.getString("id"), resultSet.getDate("work_date").toLocalDate().toString(), resultSet.getString("kind"), resultSet.getString("status"), nameReader.read(resultSet, "by"), resultSet.getString("reason")),
                employeeId, Date.valueOf(first), Date.valueOf(last));
    }

    /** Holidays from {@code from} for a region plus "Everyone", soonest first. */
    public List<HolidayView> upcomingHolidays(String region, LocalDate from, int take) {
        return jdbc.query("SELECT id::text, date, name, region::text FROM holidays WHERE date >= ? AND region::text IN ('ALL', ?) ORDER BY date ASC LIMIT ?",
                (resultSet, i) -> new HolidayView(resultSet.getString(1), resultSet.getDate(2).toLocalDate().toString(), resultSet.getString(3), resultSet.getString(4)), Date.valueOf(from), region, take);
    }

    /** The employee's latest punch (at, kind), if any. */
    public List<Map<String, Object>> lastPunch(UUID employeeId) {
        return jdbc.queryForList("SELECT at, kind::text AS kind FROM punches WHERE employee_id = ? ORDER BY at DESC LIMIT 1", employeeId);
    }

    /** {@code note}: the optional check-out note (already validated), or null. */
    public void insertWebPunch(UUID employeeId, Instant instant, String kind, String ipAddress, String note) {
        insertPunch(employeeId, instant, kind, "WEB", ipAddress, note);
    }

    /** {@code source}: a value of the "PunchSource" type (WEB, JURISTRACK, ...). */
    public void insertPunch(UUID employeeId, Instant instant, String kind, String source, String ipAddress, String note) {
        jdbc.update("INSERT INTO punches (id, employee_id, at, kind, source, ip, note, created_at) VALUES (?, ?, ?, ?::\"PunchKind\", ?::\"PunchSource\", ?, ?, now())",
                UUID.randomUUID(), employeeId, Timestamp.from(instant), kind, source, ipAddress, note);
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
