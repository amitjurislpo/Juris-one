package com.jurislpo.jurisone.repository;

import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.service.LeaveService.HolidayView;
import com.jurislpo.jurisone.service.LeaveService.RequestRow;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Leave types, the leave ledger, leave requests and the holidays they skip. */
@Repository
public class LeaveRepository {

    private final JdbcTemplate jdbc;

    /** Still on probation (or not yet joined): earned leave isn't available. */
    public boolean onProbation(UUID employeeId) {
        return !jdbc.queryForList("SELECT 1 FROM employees WHERE id = ? AND status IN ('PROBATION', 'ONBOARDING')", employeeId).isEmpty();
    }

    public LeaveRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** An active leave type as the balance list shows it ({@code maxStretch} null when not capped). */
    public record ActiveType(String code, String name, boolean paid, boolean needsBalance, double annualQuota, Double maxStretch) {}

    /** Ledger total per leave type of the employee. */
    public Map<String, BigDecimal> ledgerSums(UUID employeeId) {
        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        jdbc.query("SELECT leave_type, sum(delta) FROM leave_ledger WHERE employee_id = ? GROUP BY leave_type", resultSet -> { sums.put(resultSet.getString(1), resultSet.getBigDecimal(2)); }, employeeId);
        return sums;
    }

    /** Per leave type, {days, lop days} held by the employee's pending requests. */
    public Map<String, double[]> pendingDays(UUID employeeId) {
        Map<String, double[]> pending = new LinkedHashMap<>();
        jdbc.query("SELECT leave_type, sum(days), sum(lop_days) FROM leave_requests WHERE employee_id = ? AND status = 'PENDING' GROUP BY leave_type",
                resultSet -> { pending.put(resultSet.getString(1), new double[] {resultSet.getBigDecimal(2).doubleValue(), resultSet.getBigDecimal(3).doubleValue()}); }, employeeId);
        return pending;
    }

    /** Active leave types in display order. */
    public List<ActiveType> activeTypes() {
        return jdbc.query("SELECT code, name, paid, needs_balance, annual_quota, max_stretch FROM leave_types WHERE active ORDER BY sort_order ASC", (resultSet, i) -> {
            String code = resultSet.getString("code");
            BigDecimal maxStretch = resultSet.getBigDecimal("max_stretch");
            return new ActiveType(code, resultSet.getString("name"), resultSet.getBoolean("paid"), resultSet.getBoolean("needs_balance"), resultSet.getBigDecimal("annual_quota").doubleValue(),
                    maxStretch != null ? maxStretch.doubleValue() : null); // a Prisma Decimal is truthy even when 0
        });
    }

    /** The holidays (own region + everyone) from..to inclusive, by date. */
    public Map<LocalDate, String> holidays(String region, LocalDate from, LocalDate to) {
        Map<LocalDate, String> holidays = new LinkedHashMap<>();
        jdbc.query("SELECT date, name FROM holidays WHERE date >= ? AND date <= ? AND region::text IN ('ALL', ?)",
                resultSet -> { holidays.put(resultSet.getDate(1).toLocalDate(), resultSet.getString(2)); }, Date.valueOf(from), Date.valueOf(to), region);
        return holidays;
    }

    /** name, active, paid, needs_balance and max_stretch of a leave type (empty when unknown). */
    public List<Map<String, Object>> findTypeRules(String code) {
        return jdbc.queryForList("SELECT name, active, paid, needs_balance, max_stretch FROM leave_types WHERE code = ?", code);
    }

    /** status, days and lop_days of a request (empty when not found). */
    public List<Map<String, Object>> findRequestHold(UUID requestId) {
        return jdbc.queryForList("SELECT status::text AS status, days, lop_days FROM leave_requests WHERE id = ?", requestId);
    }

    /** The first pending or approved request of the employee overlapping from..to, other than {@code excludeRequestId}. */
    public List<Map<String, Object>> findOverlap(UUID employeeId, LocalDate from, LocalDate to, UUID excludeRequestId) {
        return jdbc.queryForList("""
            SELECT status::text AS status, from_date, to_date FROM leave_requests
            WHERE employee_id = ? AND status IN ('PENDING', 'APPROVED') AND from_date <= ? AND to_date >= ? AND (?::uuid IS NULL OR id <> ?::uuid) LIMIT 1""",
                employeeId, Date.valueOf(to), Date.valueOf(from), excludeRequestId, excludeRequestId);
    }

    /** paid and needs_balance of a leave type. */
    public Map<String, Object> findTypeFlags(String code) {
        return jdbc.queryForMap("SELECT paid, needs_balance FROM leave_types WHERE code = ?", code);
    }

    public void insertUsage(UUID employeeId, String leaveType, BigDecimal delta, String refId) {
        jdbc.update("INSERT INTO leave_ledger (id, employee_id, leave_type, delta, reason, ref_id, at) VALUES (?, ?, ?, ?, 'USED', ?, now())",
                UUID.randomUUID(), employeeId, leaveType, delta, refId);
    }

    public String findTypeName(String code) {
        return jdbc.queryForObject("SELECT name FROM leave_types WHERE code = ?", String.class, code);
    }

    /** Inserts a pending request. */
    public void insertRequest(UUID id, UUID employeeId, String leaveType, Date from, Date to, String part, BigDecimal days, BigDecimal lopDays, String reason) {
        jdbc.update("""
            INSERT INTO leave_requests (id, employee_id, leave_type, from_date, to_date, part, days, lop_days, reason, status, applied_at)
            VALUES (?, ?, ?, ?, ?, ?::"LeavePart", ?, ?, ?, 'PENDING', now())""",
                id, employeeId, leaveType, from, to, part, days, lopDays, reason);
    }

    /** Cancels the request only when it is the employee's and still pending; returns rows changed. */
    public int cancelPending(UUID id, UUID employeeId) {
        return jdbc.update("UPDATE leave_requests SET status = 'CANCELLED' WHERE id = ? AND employee_id = ? AND status = 'PENDING'", id, employeeId);
    }

    /** The employee's requests ({@code status} "All" = every status); applied dates shown in {@code timeZone}. */
    public List<RequestRow> findRequests(UUID employeeId, String status, String timeZone) {
        return jdbc.query("""
            SELECT l.id::text AS id, t.name AS type_name, l.leave_type, l.from_date, l.to_date, l.part::text AS part, l.days, l.lop_days, l.applied_at,
                   l.reason, l.status::text AS status, d.full_name AS decided_by
            FROM leave_requests l JOIN leave_types t ON t.code = l.leave_type LEFT JOIN employees d ON d.id = l.decided_by
            WHERE l.employee_id = ? AND (? = 'All' OR l.status::text = ?)""",
                (resultSet, i) -> new RequestRow(resultSet.getString("id"), resultSet.getString("type_name"), resultSet.getString("leave_type"), resultSet.getDate("from_date").toLocalDate().toString(),
                        resultSet.getDate("to_date").toLocalDate().toString(), resultSet.getString("part"), resultSet.getBigDecimal("days").doubleValue(),
                        resultSet.getBigDecimal("lop_days").doubleValue(), Format.isoInZone(resultSet.getTimestamp("applied_at").toInstant(), timeZone).toString(),
                        resultSet.getString("reason"), resultSet.getString("status"), resultSet.getString("decided_by"), resultSet.getTimestamp("applied_at").toInstant()),
                employeeId, status, status);
    }

    /** Holidays of the region (and everyone) from..to inclusive, by date. */
    public List<HolidayView> findHolidayViews(String region, Date from, Date to) {
        return jdbc.query("SELECT id::text, date, name FROM holidays WHERE region::text IN ('ALL', ?) AND date >= ? AND date <= ? ORDER BY date ASC",
                (resultSet, i) -> new HolidayView(resultSet.getString(1), resultSet.getDate(2).toLocalDate().toString(), resultSet.getString(3)), region, from, to);
    }

    /** Holidays of the region (and everyone) from the given date on: ISO date → name. */
    public Map<String, String> upcomingHolidays(String region, Date from) {
        Map<String, String> upcoming = new LinkedHashMap<>();
        jdbc.query("SELECT date, name FROM holidays WHERE region::text IN ('ALL', ?) AND date >= ?", resultSet -> { upcoming.put(resultSet.getDate(1).toLocalDate().toString(), resultSet.getString(2)); },
                region, from);
        return upcoming;
    }
}
