package com.jurislpo.jurisone.leave;

import static com.jurislpo.jurisone.engine.Num.round2;

import com.jurislpo.jurisone.attendance.AttendanceService;
import com.jurislpo.jurisone.attendance.AttendanceService.AttEmployee;
import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.common.ListParams;
import com.jurislpo.jurisone.common.UserException;
import com.jurislpo.jurisone.engine.AttendanceEngine;
import com.jurislpo.jurisone.engine.AttendanceEngine.LeavePart;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.engine.LeaveRules;
import com.jurislpo.jurisone.platform.AuditService;
import com.jurislpo.jurisone.security.CurrentUser;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Leave balances, the apply preview, apply / cancel, and the Leave page — src/lib/leave/service.ts + src/app/(app)/leave. */
@Service
public class LeaveService {

    private final JdbcTemplate jdbc;
    private final AttendanceService att;
    private final AuditService audit;

    public LeaveService(JdbcTemplate jdbc, AttendanceService att, AuditService audit) {
        this.jdbc = jdbc;
        this.att = att;
        this.audit = audit;
    }

    public record Balance(String code, String name, double balance, double pending, boolean paid, boolean needsBalance, double annual, Double maxStretch) {}

    /** Ledger balance per leave type, plus days held by pending requests. */
    public List<Balance> balances(UUID employeeId) {
        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        jdbc.query("SELECT leave_type, sum(delta) FROM leave_ledger WHERE employee_id = ? GROUP BY leave_type", rs -> { sums.put(rs.getString(1), rs.getBigDecimal(2)); }, employeeId);
        Map<String, double[]> pending = new LinkedHashMap<>();
        jdbc.query("SELECT leave_type, sum(days), sum(lop_days) FROM leave_requests WHERE employee_id = ? AND status = 'PENDING' GROUP BY leave_type",
                rs -> { pending.put(rs.getString(1), new double[] {rs.getBigDecimal(2).doubleValue(), rs.getBigDecimal(3).doubleValue()}); }, employeeId);
        return jdbc.query("SELECT code, name, paid, needs_balance, annual_quota, max_stretch FROM leave_types WHERE active ORDER BY sort_order ASC", (rs, i) -> {
            String code = rs.getString("code");
            double[] p = pending.get(code);
            BigDecimal ms = rs.getBigDecimal("max_stretch");
            return new Balance(code, rs.getString("name"), round2(sums.containsKey(code) && sums.get(code) != null ? sums.get(code).doubleValue() : 0),
                    p != null ? round2(p[0] - p[1]) : 0, rs.getBoolean("paid"), rs.getBoolean("needs_balance"), rs.getBigDecimal("annual_quota").doubleValue(),
                    ms != null ? ms.doubleValue() : null); // a Prisma Decimal is truthy even when 0
        });
    }

    /** The employee's holidays (own region + everyone) between two dates. */
    public Map<LocalDate, String> holidayMap(String region, LocalDate from, LocalDate to) {
        Map<LocalDate, String> m = new LinkedHashMap<>();
        jdbc.query("SELECT date, name FROM holidays WHERE date >= ? AND date <= ? AND region::text IN ('ALL', ?)",
                rs -> { m.put(rs.getDate(1).toLocalDate(), rs.getString(2)); }, Date.valueOf(from), Date.valueOf(to), region);
        return m;
    }

    public record Skipped(String date, String name) {}

    public record Preview(String from, String to, List<String> dates, List<Skipped> skipped, double days, double available, double paidDays, double lopDays,
                          Double balanceAfter, String error) {
        Preview withError(String e) {
            return new Preview(from, to, dates, skipped, days, available, paidDays, lopDays, balanceAfter, e);
        }
    }

    /**
     * Everything the apply form shows and the server enforces: working days, balance after, LOP if
     * the balance is short, plus the rules (no past dates, max stretch, overlaps, joining/exit).
     */
    public Preview previewRequest(AttEmployee emp, String typeCode, LocalDate fromIn, LocalDate toIn, LeavePart part, UUID excludeRequestId, boolean forDecision) {
        LocalDate from = fromIn, to = toIn;
        if (to.isBefore(from)) {
            LocalDate x = from;
            from = to;
            to = x;
        }
        AttendanceService.Pol policy = att.policy();
        List<Map<String, Object>> types = jdbc.queryForList("SELECT name, active, paid, needs_balance, max_stretch FROM leave_types WHERE code = ?", typeCode);
        Preview base = new Preview(from.toString(), to.toString(), List.of(), List.of(), 0, 0, 0, 0, null, null);
        if (types.isEmpty() || !Boolean.TRUE.equals(types.get(0).get("active"))) return base.withError("Pick a leave type.");
        Map<String, Object> type = types.get(0);
        Map<LocalDate, String> hol = holidayMap(emp.region(), from, to);
        LeaveRules.WorkingDays wd = LeaveRules.workingDays(from, to, policy.engine().weeklyOffs(), hol);
        double days = LeaveRules.requestDays(wd.dates(), wd.dates().size() == 1 ? part : LeavePart.FULL);
        Balance bal = balances(emp.id()).stream().filter(b -> b.code().equals(typeCode)).findFirst().orElseThrow();
        // pending requests already hold part of the balance (except the one being decided)
        double held = bal.pending();
        if (excludeRequestId != null) {
            List<Map<String, Object>> self = jdbc.queryForList("SELECT status::text AS status, days, lop_days FROM leave_requests WHERE id = ?", excludeRequestId);
            if (!self.isEmpty() && "PENDING".equals(self.get(0).get("status"))) {
                held = round2(held - (((BigDecimal) self.get(0).get("days")).doubleValue() - ((BigDecimal) self.get(0).get("lop_days")).doubleValue()));
            }
        }
        double available = round2(bal.balance() - held);
        boolean needsBalance = Boolean.TRUE.equals(type.get("needs_balance"));
        LeaveRules.LopSplit split = LeaveRules.lopSplit(days, available, needsBalance, Boolean.TRUE.equals(type.get("paid")));
        Preview out = new Preview(from.toString(), to.toString(), wd.dates().stream().map(LocalDate::toString).toList(),
                wd.skipped().stream().map(s -> new Skipped(s.date().toString(), s.name())).toList(), days, available, split.paidDays(), split.lopDays(),
                needsBalance ? round2(available - split.paidDays()) : null, null);

        LocalDate today = att.currentWorkDate(emp, Instant.now());
        if (!forDecision && from.isBefore(today)) return out.withError("Leave can't start in the past. For a missed day, request a regularization.");
        if (from.isBefore(emp.doj())) return out.withError("Leave can't start before your joining date.");
        if (emp.exit() != null && to.isAfter(emp.exit())) return out.withError("Leave can't go beyond your last working day.");
        if (ChronoUnit.DAYS.between(from, to) + 1 > 62) return out.withError("Apply for at most two months at a time.");
        if (wd.dates().isEmpty()) return out.withError("Those dates are all weekends or holidays — nothing to apply for.");
        BigDecimal maxStretch = (BigDecimal) type.get("max_stretch");
        if (maxStretch != null && days > maxStretch.doubleValue()) {
            return out.withError(type.get("name") + " can be taken for at most " + num(maxStretch.doubleValue()) + " days at a stretch.");
        }
        if (att.isMonthLocked(from.toString().substring(0, 7), policy, Instant.now())) return out.withError("Attendance for that month is locked.");
        List<Map<String, Object>> overlap = jdbc.queryForList("""
            SELECT status::text AS status, from_date, to_date FROM leave_requests
            WHERE employee_id = ? AND status IN ('PENDING', 'APPROVED') AND from_date <= ? AND to_date >= ? AND (?::uuid IS NULL OR id <> ?::uuid) LIMIT 1""",
                emp.id(), Date.valueOf(to), Date.valueOf(from), excludeRequestId, excludeRequestId);
        if (!overlap.isEmpty()) {
            Map<String, Object> o = overlap.get(0);
            return out.withError("You already have a " + ((String) o.get("status")).toLowerCase() + " request from " + o.get("from_date") + " to " + o.get("to_date") + ".");
        }
        return out;
    }

    /** JS number → text (2 not 2.0). */
    static String num(double d) {
        return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
    }

    /** Records leave used from the balance when a request is approved. Joins the caller's transaction. */
    public void postUsage(UUID requestId, UUID employeeId, String leaveType, double paidDays) {
        if (paidDays <= 0) return;
        Map<String, Object> t = jdbc.queryForMap("SELECT paid, needs_balance FROM leave_types WHERE code = ?", leaveType);
        if (!Boolean.TRUE.equals(t.get("needs_balance")) || !Boolean.TRUE.equals(t.get("paid"))) return;
        jdbc.update("INSERT INTO leave_ledger (id, employee_id, leave_type, delta, reason, ref_id, at) VALUES (?, ?, ?, ?, 'USED', ?, now())",
                UUID.randomUUID(), employeeId, leaveType, BigDecimal.valueOf(-paidDays), requestId.toString());
    }

    // ───────────────────────── apply / cancel ─────────────────────────

    public record ApplyInput(String type, String from, String to, String part, String reason) {}

    record Parsed(String type, LocalDate from, LocalDate to, LeavePart part, String reason) {}

    static Parsed parse(ApplyInput i) {
        String type = i.type();
        if (type == null || type.isEmpty()) throw new UserException(HttpStatus.BAD_REQUEST, "Too small: expected string to have >=1 characters");
        if (type.length() > 10) throw new UserException(HttpStatus.BAD_REQUEST, "Too big: expected string to have <=10 characters");
        Check.isoDate(i.from(), "Pick your dates on the calendar.");
        Check.isoDate(i.to(), "Pick your dates on the calendar.");
        LeavePart part = LeavePart.valueOf(Check.oneOf(i.part(), List.of("FULL", "FIRST_HALF", "SECOND_HALF")));
        String reason = i.reason() == null ? "" : i.reason().trim();
        if (reason.length() > 500) throw new UserException(HttpStatus.BAD_REQUEST, "Keep the reason under 500 characters.");
        return new Parsed(type, LocalDate.parse(i.from()), LocalDate.parse(i.to()), part, reason);
    }

    AttEmployee me(CurrentUser u) {
        List<AttEmployee> e = att.loadAttEmployees("e.id = ?", u.id());
        if (e.isEmpty()) throw new UserException("Leave isn't tracked for your account.");
        return e.get(0);
    }

    /** Live numbers for the apply form — same rules the server enforces on submit. */
    public Preview preview(CurrentUser u, ApplyInput i) {
        AttEmployee emp = me(u);
        Parsed f = parse(i);
        return previewRequest(emp, f.type(), f.from(), f.to(), f.part(), null, false);
    }

    public String apply(CurrentUser u, ApplyInput i, String ip) {
        AttEmployee emp = me(u);
        Parsed f = parse(i);
        Preview p = previewRequest(emp, f.type(), f.from(), f.to(), f.part(), null, false);
        if (p.error() != null) throw new UserException(p.error());
        LeavePart part = p.dates().size() == 1 ? f.part() : LeavePart.FULL;
        String typeName = jdbc.queryForObject("SELECT name FROM leave_types WHERE code = ?", String.class, f.type());
        UUID id = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO leave_requests (id, employee_id, leave_type, from_date, to_date, part, days, lop_days, reason, status, applied_at)
            VALUES (?, ?, ?, ?, ?, ?::"LeavePart", ?, ?, ?, 'PENDING', now())""",
                id, u.id(), f.type(), Date.valueOf(p.from()), Date.valueOf(p.to()), part.name(), BigDecimal.valueOf(p.days()), BigDecimal.valueOf(p.lopDays()),
                f.reason().isEmpty() ? null : f.reason());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("type", f.type());
        after.put("from", p.from());
        after.put("to", p.to());
        after.put("part", part.name());
        after.put("days", p.days());
        after.put("lopDays", p.lopDays());
        audit.log(u.id(), "leave.apply", "leave_request", id.toString(), null, after, ip);
        String span = p.from().equals(p.to()) ? Format.fmtD(LocalDate.parse(p.from())) : Format.fmtD(LocalDate.parse(p.from())) + " – " + Format.fmtD(LocalDate.parse(p.to()));
        return typeName + " for " + num(p.days()) + " day" + (p.days() == 1 ? "" : "s") + " (" + span + ") sent to HR"
                + (p.lopDays() != 0 ? " · " + num(p.lopDays()) + " day(s) unpaid" : "");
    }

    public String cancel(CurrentUser u, String idRaw) {
        UUID id = Check.uuid(idRaw);
        // atomic: only a still-pending request of mine can be cancelled
        int n = jdbc.update("UPDATE leave_requests SET status = 'CANCELLED' WHERE id = ? AND employee_id = ? AND status = 'PENDING'", id, u.id());
        if (n == 0) throw new UserException("Only your pending requests can be cancelled.");
        audit.log(u.id(), "leave.cancel", "leave_request", id.toString(), Map.of("status", "PENDING"), Map.of("status", "CANCELLED"), null);
        return "Leave request cancelled";
    }

    // ───────────────────────── page ─────────────────────────

    static final List<String> STATUS = List.of("All", "PENDING", "APPROVED", "DECLINED", "CANCELLED");
    static final List<String> SORTS = List.of("type", "from", "days", "applied", "status");

    public record ReqRow(String id, String typeName, String leaveType, String from, String to, String part, double days, double lopDays, String applied,
                         String reason, String status, String decidedBy, Instant appliedAt) {}

    public record TypeOption(String code, String name, boolean paid, Double available) {}

    public record HolidayView(String id, String date, String name) {}

    public Map<String, Object> page(CurrentUser u, Map<String, String> sp) {
        String tab = ListParams.pick(sp, "tab", List.of("requests", "holidays"), "requests");
        String st = ListParams.pick(sp, "st", STATUS, "All");
        ListParams ls = ListParams.of(sp, SORTS, "applied", 50, "desc");
        List<AttEmployee> emps = att.loadAttEmployees("e.id = ?", u.id());
        AttEmployee emp = emps.isEmpty() ? null : emps.get(0);
        AttendanceService.Pol policy = att.policy();
        Instant now = Instant.now();
        LocalDate today = emp != null ? att.currentWorkDate(emp, now) : Format.isoInZone(now, u.timeZone());
        int year = today.getYear();

        List<Balance> bals = balances(u.id());
        List<ReqRow> reqs = new ArrayList<>(jdbc.query("""
            SELECT l.id::text AS id, t.name AS type_name, l.leave_type, l.from_date, l.to_date, l.part::text AS part, l.days, l.lop_days, l.applied_at,
                   l.reason, l.status::text AS status, d.full_name AS decided_by
            FROM leave_requests l JOIN leave_types t ON t.code = l.leave_type LEFT JOIN employees d ON d.id = l.decided_by
            WHERE l.employee_id = ? AND (? = 'All' OR l.status::text = ?)""",
                (rs, i) -> new ReqRow(rs.getString("id"), rs.getString("type_name"), rs.getString("leave_type"), rs.getDate("from_date").toLocalDate().toString(),
                        rs.getDate("to_date").toLocalDate().toString(), rs.getString("part"), rs.getBigDecimal("days").doubleValue(),
                        rs.getBigDecimal("lop_days").doubleValue(), Format.isoInZone(rs.getTimestamp("applied_at").toInstant(), u.timeZone()).toString(),
                        rs.getString("reason"), rs.getString("status"), rs.getString("decided_by"), rs.getTimestamp("applied_at").toInstant()),
                u.id(), st, st));
        Map<String, Function<ReqRow, ? extends Comparable>> keys = Map.of("type", ReqRow::typeName, "from", ReqRow::from, "days", ReqRow::days,
                "applied", r -> r.appliedAt().toEpochMilli(), "status", ReqRow::status);
        reqs.sort(ls.comparator(keys));

        double lopYear = 0;
        if (emp != null) {
            LocalDate start = LocalDate.of(year, 1, 1);
            if (policy.trackingStart().isAfter(start)) start = policy.trackingStart();
            if (emp.doj().isAfter(start)) start = emp.doj();
            lopYear = AttendanceEngine.summarize(att.attendanceFor(List.of(emp), start, today, now).get(emp.id()).stream().filter(x -> !x.r.future).map(x -> x.r).toList(),
                    policy.engine()).lopDays;
        }
        List<HolidayView> hols = jdbc.query("SELECT id::text, date, name FROM holidays WHERE region::text IN ('ALL', ?) AND date >= ? AND date <= ? ORDER BY date ASC",
                (rs, i) -> new HolidayView(rs.getString(1), rs.getDate(2).toLocalDate().toString(), rs.getString(3)), u.region(),
                Date.valueOf(LocalDate.of(year, 1, 1)), Date.valueOf(LocalDate.of(year, 12, 31)));
        Map<String, String> upcoming = new LinkedHashMap<>();
        jdbc.query("SELECT date, name FROM holidays WHERE region::text IN ('ALL', ?) AND date >= ?", rs -> { upcoming.put(rs.getDate(1).toLocalDate().toString(), rs.getString(2)); },
                u.region(), Date.valueOf(today));
        List<TypeOption> types = bals.stream().map(b -> new TypeOption(b.code(), b.name(), b.paid(), b.needsBalance() ? round2(b.balance() - b.pending()) : null)).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tab", tab);
        out.put("st", st);
        out.put("today", today.toString());
        out.put("year", String.valueOf(year));
        out.put("canApply", emp != null);
        out.put("balances", bals);
        out.put("lopYear", lopYear);
        out.put("requests", reqs);
        out.put("holidays", hols);
        out.put("holidayMap", upcoming);
        out.put("types", types);
        out.put("weeklyOffs", policy.engine().weeklyOffs().stream().sorted().toList());
        return out;
    }

    static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}
