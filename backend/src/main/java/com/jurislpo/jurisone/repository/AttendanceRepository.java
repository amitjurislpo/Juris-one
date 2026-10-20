package com.jurislpo.jurisone.repository;

import com.jurislpo.jurisone.engine.AttendanceEngine.DayResult;
import com.jurislpo.jurisone.engine.AttendanceEngine.LeavePart;
import com.jurislpo.jurisone.entity.PunchKind;
import com.jurislpo.jurisone.service.AttendanceService.AttEmployee;
import com.jurislpo.jurisone.service.AttendanceService.Shift;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Data access for {@code AttendanceService}: the inputs of the attendance engine and the materialised days. */
@Repository
public class AttendanceRepository {

    public record LeaveRow(UUID id, UUID employeeId, String code, String name, boolean paid, LocalDate from, LocalDate to, LeavePart part, BigDecimal days,
                           BigDecimal lop) {}

    public record RegRow(UUID employeeId, LocalDate date, String kind) {}

    public record PunchRow(UUID employeeId, Instant at, PunchKind kind, String source) {}

    public record Hol(LocalDate date, String name, String region) {}

    static final String TRACKED = "EXISTS (SELECT 1 FROM role_permissions rp WHERE rp.role_id = e.role_id AND rp.permission_key = 'self')";

    private final JdbcTemplate jdbc;

    public AttendanceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Tracked employees matching {@code where} (SQL on alias {@code e}), ordered by name; employees without a shift get
     * {@code defaultShift}, and every shift gets {@code grace}.
     */
    public List<AttEmployee> findAttEmployees(String where, Shift defaultShift, int grace, Object... args) {
        String sql = """
            SELECT e.id, e.code, e.full_name, e.designation, d.name AS dept, e.department_id, e.time_zone, e.region::text AS region,
                   e.date_of_joining, e.exit_date, s.code AS s_code, s.name AS s_name, s.start_local, s.end_local, s.grace_minutes
            FROM employees e LEFT JOIN departments d ON d.id = e.department_id LEFT JOIN shifts s ON s.id = e.shift_id
            WHERE""" + " " + TRACKED + (where == null || where.isBlank() ? "" : " AND (" + where + ")") + " ORDER BY e.full_name ASC";
        return jdbc.query(sql, (rs, i) -> new AttEmployee(
                (UUID) rs.getObject("id"), rs.getString("code"), rs.getString("full_name"), rs.getString("designation"),
                rs.getString("dept") == null ? "—" : rs.getString("dept"), (UUID) rs.getObject("department_id"), rs.getString("time_zone"),
                rs.getString("region"), rs.getDate("date_of_joining").toLocalDate(), rs.getDate("exit_date") == null ? null : rs.getDate("exit_date").toLocalDate(),
                rs.getString("s_code") == null ? new Shift(defaultShift.code(), defaultShift.name(), defaultShift.startLocal(), defaultShift.endLocal(), grace)
                        : new Shift(rs.getString("s_code"), rs.getString("s_name"), rs.getString("start_local"), rs.getString("end_local"), grace)),
                args);
    }

    /** Approved leaves of these employees overlapping [from, to]. */
    public List<LeaveRow> approvedLeaves(UUID[] idArr, LocalDate from, LocalDate to) {
        return jdbc.query("""
            SELECT l.id, l.employee_id, l.leave_type, t.name, t.paid, l.from_date, l.to_date, l.part::text AS part, l.days, l.lop_days
            FROM leave_requests l JOIN leave_types t ON t.code = l.leave_type
            WHERE l.employee_id = ANY (?) AND l.status = 'APPROVED' AND l.from_date <= ? AND l.to_date >= ?""",
                (rs, i) -> new LeaveRow((UUID) rs.getObject("id"), (UUID) rs.getObject("employee_id"), rs.getString("leave_type"), rs.getString("name"),
                        rs.getBoolean("paid"), rs.getDate("from_date").toLocalDate(), rs.getDate("to_date").toLocalDate(), LeavePart.valueOf(rs.getString("part")),
                        rs.getBigDecimal("days"), rs.getBigDecimal("lop_days")),
                idArr, Date.valueOf(to), Date.valueOf(from));
    }

    /** Approved regularizations of these employees for work dates in [from, to]. */
    public List<RegRow> approvedRegularizations(UUID[] idArr, LocalDate from, LocalDate to) {
        return jdbc.query("""
            SELECT employee_id, work_date, kind::text AS kind FROM regularization_requests
            WHERE employee_id = ANY (?) AND status = 'APPROVED' AND work_date >= ? AND work_date <= ?""",
                (rs, i) -> new RegRow((UUID) rs.getObject(1), rs.getDate(2).toLocalDate(), rs.getString(3)), idArr, Date.valueOf(from), Date.valueOf(to));
    }

    /** Punches of these employees in [fromAt, toAt), oldest first. */
    public List<PunchRow> punches(UUID[] idArr, Instant fromAt, Instant toAt) {
        return jdbc.query("""
            SELECT employee_id, at, kind::text AS kind, source::text AS source FROM punches
            WHERE employee_id = ANY (?) AND at >= ? AND at < ? ORDER BY at ASC""",
                (rs, i) -> new PunchRow((UUID) rs.getObject(1), rs.getTimestamp(2).toInstant(), PunchKind.valueOf(rs.getString(3)), rs.getString(4)),
                idArr, Timestamp.from(fromAt), Timestamp.from(toAt));
    }

    /** Locked attendance days of these employees in [from, to], keyed "employeeId|date", as column → value. */
    public Map<String, Map<String, Object>> lockedDays(UUID[] idArr, LocalDate from, LocalDate to) {
        Map<String, Map<String, Object>> locked = new HashMap<>();
        jdbc.query("""
            SELECT employee_id, work_date, status::text AS status, label, first_in, last_out, worked_minutes, late, late_waived, missed_checkout, regularized,
                   lop_days, leave_days FROM attendance_days WHERE employee_id = ANY (?) AND locked AND work_date >= ? AND work_date <= ?""",
                rs -> {
                    Map<String, Object> m = new HashMap<>();
                    for (String c : List.of("status", "label", "first_in", "last_out", "worked_minutes", "late", "late_waived", "missed_checkout", "regularized", "lop_days", "leave_days")) {
                        m.put(c, rs.getObject(c));
                    }
                    locked.put(rs.getObject("employee_id") + "|" + rs.getDate("work_date").toLocalDate(), m);
                }, idArr, Date.valueOf(from), Date.valueOf(to));
        return locked;
    }

    public List<Hol> holidays(LocalDate from, LocalDate to) {
        return jdbc.query("SELECT date, name, region::text FROM holidays WHERE date >= ? AND date <= ?",
                (rs, i) -> new Hol(rs.getDate(1).toLocalDate(), rs.getString(2), rs.getString(3)), Date.valueOf(from), Date.valueOf(to));
    }

    /** Keys "employeeId|date" of the locked attendance days in [from, to]. */
    public Set<String> lockedDayKeys(UUID[] idArr, LocalDate from, LocalDate to) {
        Set<String> isLocked = new HashSet<>();
        jdbc.query("SELECT employee_id, work_date FROM attendance_days WHERE employee_id = ANY (?) AND locked AND work_date >= ? AND work_date <= ?",
                rs -> { isLocked.add(rs.getObject(1) + "|" + rs.getDate(2).toLocalDate()); }, idArr, Date.valueOf(from), Date.valueOf(to));
        return isLocked;
    }

    /** Insert or overwrite one computed attendance day. */
    public void upsertDay(UUID employeeId, DayResult d, boolean lock) {
        jdbc.update("""
            INSERT INTO attendance_days (employee_id, work_date, status, label, first_in, last_out, worked_minutes, late, late_waived, regularized,
              missed_checkout, lop_days, leave_days, locked, updated_at)
            VALUES (?, ?, ?::"DayStatus", ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
            ON CONFLICT (employee_id, work_date) DO UPDATE SET status = EXCLUDED.status, label = EXCLUDED.label, first_in = EXCLUDED.first_in,
              last_out = EXCLUDED.last_out, worked_minutes = EXCLUDED.worked_minutes, late = EXCLUDED.late, late_waived = EXCLUDED.late_waived,
              regularized = EXCLUDED.regularized, missed_checkout = EXCLUDED.missed_checkout, lop_days = EXCLUDED.lop_days,
              leave_days = EXCLUDED.leave_days, locked = EXCLUDED.locked, updated_at = now()""",
                employeeId, Date.valueOf(d.date), d.status.name(), d.label, ts(d.firstIn), ts(d.lastOut), d.workedMinutes, d.late || d.lateWaived,
                d.lateWaived, d.regularized, d.missedCheckout, BigDecimal.valueOf(d.lopDays), BigDecimal.valueOf(d.leaveDays), lock);
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
