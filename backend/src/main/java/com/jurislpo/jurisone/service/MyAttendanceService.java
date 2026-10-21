package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.service.AttendanceService.AttendanceEmployee;
import com.jurislpo.jurisone.service.AttendanceService.DayView;
import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.common.ListParams;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.engine.AttendanceEngine;
import com.jurislpo.jurisone.engine.AttendanceEngine.MonthSummary;
import com.jurislpo.jurisone.engine.AttendanceEngine.Punch;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.entity.DayStatus;
import com.jurislpo.jurisone.repository.MyAttendanceRepository;
import com.jurislpo.jurisone.security.CurrentUser;
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
import org.springframework.stereotype.Service;

/** My attendance, regularization requests and check-in/out (REQUIREMENTS §5.4). self. */
@Service
public class MyAttendanceService {

    static final String NOT_TRACKED = "Attendance isn't tracked for your account.";

    private final AttendanceService att;
    private final MyAttendanceRepository repo;
    private final AuditService audit;

    public MyAttendanceService(AttendanceService attendance, MyAttendanceRepository repo, AuditService audit) {
        this.att = attendance;
        this.repo = repo;
        this.audit = audit;
    }

    AttendanceEmployee me(CurrentUser user) {
        List<AttendanceEmployee> employees = att.loadAttendanceEmployees("e.id = ?", user.id());
        return employees.isEmpty() ? null : employees.get(0);
    }

    // ───────────────────────── page ─────────────────────────

    public record CalendarDay(String date, String status, String label, boolean late, boolean lateWaived, double hours, boolean today, boolean future,
                         String firstIn, String lastOut, boolean live, boolean missedCheckout, boolean offDayWork, double lopDays, double leaveDays,
                         boolean regularized) {}

    public record RegularizationView(String id, String date, String kind, String status, String decidedBy, String reason) {}

    public record HolidayView(String id, String date, String name, String region) {}

    public record Page(boolean tracked, String month, String minMonth, String workDate, Map<String, Object> summary, List<CalendarDay> days, List<RegularizationView> regularizations,
                       String selectedDate, boolean locked, List<HolidayView> holidays) {}

    public Page page(CurrentUser user, Map<String, String> queryParams) {
        AttendanceEmployee employee = me(user);
        if (employee == null) return new Page(false, null, null, null, null, null, null, null, false, null);
        Instant now = Instant.now();
        AttendanceService.ResolvedAttendancePolicy policy = att.policy();
        LocalDate workDate = att.currentWorkDate(employee, now);
        String workDateMonth = workDate.toString().substring(0, 7);
        String minMonth = (policy.trackingStart().isAfter(employee.dateOfJoining()) ? policy.trackingStart() : employee.dateOfJoining()).toString().substring(0, 7);
        String yearMonth = ListParams.str(queryParams, "month", workDateMonth);
        if (!yearMonth.matches("^\\d{4}-\\d{2}$") || yearMonth.compareTo(workDateMonth) > 0) yearMonth = workDateMonth;
        if (yearMonth.compareTo(minMonth) < 0) yearMonth = minMonth;
        LocalDate first = YearMonth.parse(yearMonth).atDay(1), last = YearMonth.parse(yearMonth).atEndOfMonth();

        List<DayView> days = att.attendanceFor(List.of(employee), first, last, now).get(employee.id());
        List<RegularizationView> regularizations = repo.regularizations(user.id(), first, last);
        List<HolidayView> holidays = upcomingHolidays(employee.region(), workDate, 6);
        MonthSummary s = AttendanceEngine.summarize(days.stream().filter(d -> !d.r.future).map(d -> d.r).toList(), policy.engine());
        boolean locked = att.isMonthLocked(yearMonth, policy, now);

        List<CalendarDay> cal = new ArrayList<>();
        for (DayView dayView : days) {
            cal.add(new CalendarDay(dayView.r.date.toString(), dayView.r.status.name(), dayView.r.label, dayView.r.late, dayView.r.lateWaived, dayView.r.workedMinutes / 60.0, dayView.r.date.equals(workDate), dayView.r.future,
                    dayView.r.firstIn == null ? null : AttendanceService.formatTime(dayView.r.firstIn, employee.timeZone()), dayView.r.lastOut == null ? null : AttendanceService.formatTime(dayView.r.lastOut, employee.timeZone()),
                    dayView.r.live, dayView.r.missedCheckout || (!dayView.punches.isEmpty() && !dayView.r.live && dayView.r.lastOut == null), dayView.r.offDayWork, dayView.r.lopDays, dayView.r.leaveDays, dayView.r.regularized));
        }
        String sel = ListParams.str(queryParams, "date", "");
        String initialSel;
        if (sel.startsWith(yearMonth)) initialSel = sel;
        else if (workDate.toString().startsWith(yearMonth)) initialSel = workDate.toString();
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
        return new Page(true, yearMonth, minMonth, workDate.toString(), summary, cal, regularizations, initialSel, locked, holidays);
    }

    /** Holidays an employee sees: their own region plus "Everyone". */
    public List<HolidayView> upcomingHolidays(String region, LocalDate from, int take) {
        return repo.upcomingHolidays(region, from, take);
    }

    // ───────────────────────── regularization ─────────────────────────

    public record RegularizationInput(String date, String kind, String proposedIn, String proposedOut, String reason) {}

    public String requestRegularization(CurrentUser user, RegularizationInput input, String ip) {
        if (input.date() == null || !input.date().matches("^\\d{4}-\\d{2}-\\d{2}$")) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid string: must match pattern /^\\d{4}-\\d{2}-\\d{2}$/");
        String kind = Check.oneOf(input.kind(), List.of("ABSENT", "LATE_CHECKIN", "MISSED_CHECKOUT", "HOLIDAY_WORK"));
        String pIn = input.proposedIn() == null ? null : input.proposedIn(), pOut = input.proposedOut() == null ? null : input.proposedOut();
        for (String t : new String[] {pIn, pOut}) {
            if (t == null || (!t.isEmpty() && !t.matches("^\\d{2}:\\d{2}$"))) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input");
        }
        String reason = Check.str(input.reason()).trim();
        if (reason.length() > 500) throw new UserException(HttpStatus.BAD_REQUEST, "Keep the reason under 500 characters.");

        AttendanceEmployee employee = me(user);
        if (employee == null) throw new UserException(NOT_TRACKED);
        AttendanceService.ResolvedAttendancePolicy policy = att.policy();
        Instant now = Instant.now();
        LocalDate date = LocalDate.parse(input.date());
        if (date.isAfter(att.currentWorkDate(employee, now))) throw new UserException("You can't regularize a future day.");
        if (att.isMonthLocked(input.date().substring(0, 7), policy, now)) throw new UserException("Attendance for this month is locked. Ask Finance to adjust LOP in payroll.");

        DayView day = att.attendanceFor(List.of(employee), date, date, now).get(employee.id()).get(0);
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
        List<String> dup = repo.openRegularizationStatus(user.id(), date, kind);
        if (!dup.isEmpty()) throw new UserException("PENDING".equals(dup.get(0)) ? "You already have a pending request for this day." : "This day is already regularized.");

        // Proposed times are local to the employee; a night-shift check-out rolls to the next day.
        Instant proposedIn = pIn.isEmpty() ? null : toInstant(employee, date, pIn, null);
        Instant after = proposedIn != null ? proposedIn : day.r.firstIn;
        Instant proposedOut = pOut.isEmpty() ? null : toInstant(employee, date, pOut, after);
        if ("MISSED_CHECKOUT".equals(kind) && proposedOut == null) throw new UserException("Enter the time you actually checked out.");

        UUID id = UUID.randomUUID();
        repo.insertRegularization(id, user.id(), date, kind, proposedIn, proposedOut, reason.isEmpty() ? null : reason);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("date", input.date());
        view.put("kind", kind);
        view.put("proposedIn", proposedIn == null ? null : iso(proposedIn));
        view.put("proposedOut", proposedOut == null ? null : iso(proposedOut));
        audit.log(user.id(), "regularization.request", "regularization", id.toString(), null, view, ip);
        return "Regularization sent — HR, Finance or Admin can approve it";
    }

    /** {@code JSON.stringify(Date)}: millisecond ISO with Z. */
    static String iso(Instant i) {
        return java.time.format.DateTimeFormatter.ISO_INSTANT.format(i.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)).replaceAll("(\\d{2}:\\d{2}:\\d{2})Z$", "$1.000Z");
    }

    static Instant toInstant(AttendanceEmployee employee, LocalDate date, String time, Instant after) {
        Instant t = AttendanceEngine.atLocal(date, time, employee.timeZone());
        if (after != null && !t.isAfter(after)) t = AttendanceEngine.atLocal(date.plusDays(1), time, employee.timeZone());
        else if (after == null && time.compareTo("12:00") < 0 && employee.shift().startLocal().compareTo("12:00") >= 0) t = AttendanceEngine.atLocal(date.plusDays(1), time, employee.timeZone());
        return t;
    }

    public String cancelRegularization(CurrentUser user, String idRaw) {
        UUID id = Check.uuid(idRaw);
        List<Map<String, Object>> rs = repo.findRegularization(id);
        if (rs.isEmpty() || !user.id().equals(rs.get(0).get("employee_id"))) throw new UserException("Request not found.");
        if (!"PENDING".equals(rs.get(0).get("status"))) throw new UserException("Only pending requests can be withdrawn.");
        repo.cancelRegularization(id);
        audit.log(user.id(), "regularization.cancel", "regularization", id.toString(), Map.of("status", "PENDING"), Map.of("status", "CANCELLED"), null);
        return "Request withdrawn";
    }

    // ───────────────────────── check-in / check-out ─────────────────────────

    static final long MIN_GAP_MS = 30_000;

    public record PunchResult(String msg, String kind) {}

    /**
     * {@code expect} is the action the user saw on the button, so a double-click or a stale tab can't record the
     * opposite of what they meant.
     */
    public PunchResult punch(CurrentUser user, String expectRaw, String ip) {
        String expect = Check.oneOf(expectRaw, List.of("IN", "OUT"));
        AttendanceEmployee employee = me(user);
        if (employee == null) throw new UserException(NOT_TRACKED);
        Instant now = Instant.now();
        LocalDate today = Format.isoInZone(now, employee.timeZone());
        if (today.isBefore(employee.dateOfJoining())) throw new UserException("You can check in from your joining date.");

        List<Map<String, Object>> last = repo.lastPunch(user.id());
        String current = !last.isEmpty() && "IN".equals(last.get(0).get("kind")) ? "IN" : "OUT";
        if ("IN".equals(expect) && "IN".equals(current)) throw new UserException("You're already checked in.");
        if ("OUT".equals(expect) && !"IN".equals(current)) throw new UserException("You're not checked in.");
        if (!last.isEmpty() && now.toEpochMilli() - ((Timestamp) last.get(0).get("at")).toInstant().toEpochMilli() < MIN_GAP_MS) {
            throw new UserException("Just recorded — give it a few seconds.");
        }
        repo.insertWebPunch(user.id(), now, expect, ip);
        audit.log(user.id(), "attendance.punch", "punch", user.id().toString(), null, Map.of("kind", expect, "at", now.toString()), ip);
        if ("IN".equals(expect)) return new PunchResult("Checked in. Have a productive shift.", "IN");

        LocalDate workDate = AttendanceEngine.workDateOf(now, employee.shift().def(), employee.timeZone());
        List<Punch> punches = repo.punchesSince(user.id(), now.minus(Duration.ofHours(40)))
                .stream().filter(p -> AttendanceEngine.workDateOf(p.at(), employee.shift().def(), employee.timeZone()).equals(workDate)).toList();
        return new PunchResult("Checked out — " + Format.formatHours(AttendanceEngine.workedMinutes(punches).minutes() / 60.0) + " recorded today", "OUT");
    }
}
