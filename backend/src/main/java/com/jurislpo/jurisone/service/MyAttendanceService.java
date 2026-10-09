package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.service.AttendanceService.AttEmployee;
import com.jurislpo.jurisone.service.AttendanceService.DayView;
import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.common.ListParams;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.engine.AttendanceEngine;
import com.jurislpo.jurisone.engine.AttendanceEngine.MonthSummary;
import com.jurislpo.jurisone.engine.AttendanceEngine.Punch;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.entity.DayStatus;
import com.jurislpo.jurisone.entity.PunchKind;
import com.jurislpo.jurisone.security.CurrentUser;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** My attendance, regularization requests and check-in/out (REQUIREMENTS §5.4). self. */
@Service
public class MyAttendanceService {

    static final String NOT_TRACKED = "Attendance isn't tracked for your account.";

    private final AttendanceService att;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public MyAttendanceService(AttendanceService att, JdbcTemplate jdbc, AuditService audit) {
        this.att = att;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    AttEmployee me(CurrentUser u) {
        List<AttEmployee> emps = att.loadAttEmployees("e.id = ?", u.id());
        return emps.isEmpty() ? null : emps.get(0);
    }

    // ───────────────────────── page ─────────────────────────

    public record CalDay(String date, String status, String label, boolean late, boolean lateWaived, double hours, boolean today, boolean future,
                         String firstIn, String lastOut, boolean live, boolean missedCheckout, boolean offDayWork, double lopDays, double leaveDays,
                         boolean regularized) {}

    public record RegView(String id, String date, String kind, String status, String by, String reason) {}

    public record HolidayView(String id, String date, String name, String region) {}

    public record Page(boolean tracked, String ym, String minYm, String wd, Map<String, Object> summary, List<CalDay> days, List<RegView> regs,
                       String initialSel, boolean locked, List<HolidayView> holidays) {}

    public Page page(CurrentUser u, Map<String, String> sp) {
        AttEmployee emp = me(u);
        if (emp == null) return new Page(false, null, null, null, null, null, null, null, false, null);
        Instant now = Instant.now();
        AttendanceService.Pol policy = att.policy();
        LocalDate wd = att.currentWorkDate(emp, now);
        String wdYm = wd.toString().substring(0, 7);
        String minYm = (policy.trackingStart().isAfter(emp.doj()) ? policy.trackingStart() : emp.doj()).toString().substring(0, 7);
        String ym = ListParams.str(sp, "m", wdYm);
        if (!ym.matches("^\\d{4}-\\d{2}$") || ym.compareTo(wdYm) > 0) ym = wdYm;
        if (ym.compareTo(minYm) < 0) ym = minYm;
        LocalDate first = YearMonth.parse(ym).atDay(1), last = YearMonth.parse(ym).atEndOfMonth();

        List<DayView> days = att.attendanceFor(List.of(emp), first, last, now).get(emp.id());
        List<RegView> regs = jdbc.query("""
            SELECT r.id::text, r.work_date, r.kind::text, r.status::text, d.full_name AS by, r.reason FROM regularization_requests r
            LEFT JOIN employees d ON d.id = r.decided_by WHERE r.employee_id = ? AND r.work_date >= ? AND r.work_date <= ? ORDER BY r.created_at DESC""",
                (rs, i) -> new RegView(rs.getString(1), rs.getDate(2).toLocalDate().toString(), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6)),
                u.id(), Date.valueOf(first), Date.valueOf(last));
        List<HolidayView> hols = upcomingHolidays(emp.region(), wd, 6);
        MonthSummary s = AttendanceEngine.summarize(days.stream().filter(d -> !d.r.future).map(d -> d.r).toList(), policy.engine());
        boolean locked = att.isMonthLocked(ym, policy, now);

        List<CalDay> cal = new ArrayList<>();
        for (DayView d : days) {
            cal.add(new CalDay(d.r.date.toString(), d.r.status.name(), d.r.label, d.r.late, d.r.lateWaived, d.r.workedMinutes / 60.0, d.r.date.equals(wd), d.r.future,
                    d.r.firstIn == null ? null : AttendanceService.fmtTime(d.r.firstIn, emp.tz()), d.r.lastOut == null ? null : AttendanceService.fmtTime(d.r.lastOut, emp.tz()),
                    d.r.live, d.r.missedCheckout || (!d.punches.isEmpty() && !d.r.live && d.r.lastOut == null), d.r.offDayWork, d.r.lopDays, d.r.leaveDays, d.r.regularized));
        }
        String sel = ListParams.str(sp, "d", "");
        String initialSel;
        if (sel.startsWith(ym)) initialSel = sel;
        else if (wd.toString().startsWith(ym)) initialSel = wd.toString();
        else {
            initialSel = first.toString();
            for (DayView d : days) if (!d.r.future) initialSel = d.r.date.toString();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("present", s.present);
        summary.put("absent", s.absent);
        summary.put("leave", s.leave);
        summary.put("holidays", s.holidays);
        summary.put("weeklyOffs", s.weeklyOffs);
        summary.put("workedMinutes", s.workedMinutes);
        summary.put("lateMarks", s.lateMarks);
        summary.put("lateLopDays", s.lateLopDays);
        return new Page(true, ym, minYm, wd.toString(), summary, cal, regs, initialSel, locked, hols);
    }

    /** Holidays an employee sees: their own region plus "Everyone" (src/lib/holidays.ts). */
    public List<HolidayView> upcomingHolidays(String region, LocalDate from, int take) {
        return jdbc.query("SELECT id::text, date, name, region::text FROM holidays WHERE date >= ? AND region::text IN ('ALL', ?) ORDER BY date ASC LIMIT ?",
                (rs, i) -> new HolidayView(rs.getString(1), rs.getDate(2).toLocalDate().toString(), rs.getString(3), rs.getString(4)), Date.valueOf(from), region, take);
    }

    // ───────────────────────── regularization ─────────────────────────

    public record RegInput(String date, String kind, String proposedIn, String proposedOut, String reason) {}

    public String requestRegularization(CurrentUser u, RegInput f, String ip) {
        if (f.date() == null || !f.date().matches("^\\d{4}-\\d{2}-\\d{2}$")) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid string: must match pattern /^\\d{4}-\\d{2}-\\d{2}$/");
        String kind = Check.oneOf(f.kind(), List.of("ABSENT", "LATE_CHECKIN", "MISSED_CHECKOUT", "HOLIDAY_WORK"));
        String pIn = f.proposedIn() == null ? null : f.proposedIn(), pOut = f.proposedOut() == null ? null : f.proposedOut();
        for (String t : new String[] {pIn, pOut}) {
            if (t == null || (!t.isEmpty() && !t.matches("^\\d{2}:\\d{2}$"))) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input");
        }
        String reason = Check.str(f.reason()).trim();
        if (reason.length() > 500) throw new UserException(HttpStatus.BAD_REQUEST, "Keep the reason under 500 characters.");

        AttEmployee emp = me(u);
        if (emp == null) throw new UserException(NOT_TRACKED);
        AttendanceService.Pol policy = att.policy();
        Instant now = Instant.now();
        LocalDate date = LocalDate.parse(f.date());
        if (date.isAfter(att.currentWorkDate(emp, now))) throw new UserException("You can't regularize a future day.");
        if (att.isMonthLocked(f.date().substring(0, 7), policy, now)) throw new UserException("Attendance for this month is locked. Ask Finance to adjust LOP in payroll.");

        DayView day = att.attendanceFor(List.of(emp), date, date, now).get(emp.id()).get(0);
        boolean fits = switch (kind) {
            case "ABSENT" -> day.r.lopDays > 0 && day.r.status != DayStatus.LEAVE;
            case "LATE_CHECKIN" -> day.r.late;
            case "MISSED_CHECKOUT" -> day.r.missedCheckout || (!day.punches.isEmpty() && !day.r.live && day.r.lastOut == null);
            default -> day.r.offDayWork;
        };
        if (!fits) {
            throw new UserException(switch (kind) {
                case "ABSENT" -> "This day isn't marked as loss of pay.";
                case "LATE_CHECKIN" -> "There's no late mark on this day.";
                case "MISSED_CHECKOUT" -> "There's no missing check-out on this day.";
                default -> "You need 4h+ of work on a holiday or weekly off for a comp-off.";
            });
        }
        List<String> dup = jdbc.queryForList("SELECT status::text FROM regularization_requests WHERE employee_id = ? AND work_date = ? AND kind = ?::\"RegularizationKind\" "
                + "AND status IN ('PENDING', 'APPROVED') LIMIT 1", String.class, u.id(), Date.valueOf(date), kind);
        if (!dup.isEmpty()) throw new UserException("PENDING".equals(dup.get(0)) ? "You already have a pending request for this day." : "This day is already regularized.");

        // Proposed times are local to the employee; a night-shift check-out rolls to the next day.
        Instant proposedIn = pIn.isEmpty() ? null : toInstant(emp, date, pIn, null);
        Instant after = proposedIn != null ? proposedIn : day.r.firstIn;
        Instant proposedOut = pOut.isEmpty() ? null : toInstant(emp, date, pOut, after);
        if ("MISSED_CHECKOUT".equals(kind) && proposedOut == null) throw new UserException("Enter the time you actually checked out.");

        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO regularization_requests (id, employee_id, work_date, kind, proposed_in, proposed_out, reason, status, created_at) "
                + "VALUES (?, ?, ?, ?::\"RegularizationKind\", ?, ?, ?, 'PENDING', now())",
                id, u.id(), Date.valueOf(date), kind, ts(proposedIn), ts(proposedOut), reason.isEmpty() ? null : reason);
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("date", f.date());
        a.put("kind", kind);
        a.put("proposedIn", proposedIn == null ? null : iso(proposedIn));
        a.put("proposedOut", proposedOut == null ? null : iso(proposedOut));
        audit.log(u.id(), "regularization.request", "regularization", id.toString(), null, a, ip);
        return "Regularization sent — HR, Finance or Admin can approve it";
    }

    /** {@code JSON.stringify(Date)}: millisecond ISO with Z. */
    static String iso(Instant i) {
        return java.time.format.DateTimeFormatter.ISO_INSTANT.format(i.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)).replaceAll("(\\d{2}:\\d{2}:\\d{2})Z$", "$1.000Z");
    }

    static Instant toInstant(AttEmployee emp, LocalDate date, String hm, Instant after) {
        Instant t = AttendanceEngine.atLocal(date, hm, emp.tz());
        if (after != null && !t.isAfter(after)) t = AttendanceEngine.atLocal(date.plusDays(1), hm, emp.tz());
        else if (after == null && hm.compareTo("12:00") < 0 && emp.shift().startLocal().compareTo("12:00") >= 0) t = AttendanceEngine.atLocal(date.plusDays(1), hm, emp.tz());
        return t;
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    public String cancelRegularization(CurrentUser u, String idRaw) {
        UUID id = Check.uuid(idRaw);
        List<Map<String, Object>> rs = jdbc.queryForList("SELECT employee_id, status::text AS status FROM regularization_requests WHERE id = ?", id);
        if (rs.isEmpty() || !u.id().equals(rs.get(0).get("employee_id"))) throw new UserException("Request not found.");
        if (!"PENDING".equals(rs.get(0).get("status"))) throw new UserException("Only pending requests can be withdrawn.");
        jdbc.update("UPDATE regularization_requests SET status = 'CANCELLED' WHERE id = ?", id);
        audit.log(u.id(), "regularization.cancel", "regularization", id.toString(), Map.of("status", "PENDING"), Map.of("status", "CANCELLED"), null);
        return "Request withdrawn";
    }

    // ───────────────────────── check-in / check-out ─────────────────────────

    static final long MIN_GAP_MS = 30_000;

    public record PunchResult(String msg, String kind) {}

    /**
     * {@code expect} is the action the user saw on the button, so a double-click or a stale tab can't record the
     * opposite of what they meant.
     */
    public PunchResult punch(CurrentUser u, String expectRaw, String ip) {
        String expect = Check.oneOf(expectRaw, List.of("IN", "OUT"));
        AttEmployee emp = me(u);
        if (emp == null) throw new UserException(NOT_TRACKED);
        Instant now = Instant.now();
        LocalDate today = Format.isoInZone(now, emp.tz());
        if (today.isBefore(emp.doj())) throw new UserException("You can check in from your joining date.");

        List<Map<String, Object>> last = jdbc.queryForList("SELECT at, kind::text AS kind FROM punches WHERE employee_id = ? ORDER BY at DESC LIMIT 1", u.id());
        String current = !last.isEmpty() && "IN".equals(last.get(0).get("kind")) ? "IN" : "OUT";
        if ("IN".equals(expect) && "IN".equals(current)) throw new UserException("You're already checked in.");
        if ("OUT".equals(expect) && !"IN".equals(current)) throw new UserException("You're not checked in.");
        if (!last.isEmpty() && now.toEpochMilli() - ((Timestamp) last.get(0).get("at")).toInstant().toEpochMilli() < MIN_GAP_MS) {
            throw new UserException("Just recorded — give it a few seconds.");
        }
        jdbc.update("INSERT INTO punches (id, employee_id, at, kind, source, ip, created_at) VALUES (?, ?, ?, ?::\"PunchKind\", 'WEB', ?, now())",
                UUID.randomUUID(), u.id(), Timestamp.from(now), expect, ip);
        if ("IN".equals(expect)) return new PunchResult("Checked in. Have a productive shift.", "IN");

        LocalDate wd = AttendanceEngine.workDateOf(now, emp.shift().def(), emp.tz());
        List<Punch> ps = jdbc.query("SELECT at, kind::text FROM punches WHERE employee_id = ? AND at >= ? ORDER BY at ASC",
                (rs, i) -> new Punch(rs.getTimestamp(1).toInstant(), PunchKind.valueOf(rs.getString(2))), u.id(), Timestamp.from(now.minus(Duration.ofHours(40))))
                .stream().filter(p -> AttendanceEngine.workDateOf(p.at(), emp.shift().def(), emp.tz()).equals(wd)).toList();
        return new PunchResult("Checked out — " + Format.fmtH(AttendanceEngine.workedMinutes(ps).minutes() / 60.0) + " recorded today", "OUT");
    }
}
