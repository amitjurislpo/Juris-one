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

    private final AttendanceService attendanceService;
    private final MyAttendanceRepository repo;
    private final AuditService audit;

    public MyAttendanceService(AttendanceService attendance, MyAttendanceRepository repo, AuditService audit) {
        this.attendanceService = attendance;
        this.repo = repo;
        this.audit = audit;
    }

    AttendanceEmployee me(CurrentUser user) {
        List<AttendanceEmployee> employees = attendanceService.loadAttendanceEmployees("e.id = ?", user.id());
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
        AttendanceService.ResolvedAttendancePolicy policy = attendanceService.policy();
        LocalDate workDate = attendanceService.currentWorkDate(employee, now);
        String workDateMonth = workDate.toString().substring(0, 7);
        String minMonth = (policy.trackingStart().isAfter(employee.dateOfJoining()) ? policy.trackingStart() : employee.dateOfJoining()).toString().substring(0, 7);
        String yearMonth = ListParams.text(queryParams, "month", workDateMonth);
        if (!yearMonth.matches("^\\d{4}-\\d{2}$") || yearMonth.compareTo(workDateMonth) > 0) yearMonth = workDateMonth;
        if (yearMonth.compareTo(minMonth) < 0) yearMonth = minMonth;
        LocalDate first = YearMonth.parse(yearMonth).atDay(1), last = YearMonth.parse(yearMonth).atEndOfMonth();

        List<DayView> days = attendanceService.attendanceFor(List.of(employee), first, last, now).get(employee.id());
        List<RegularizationView> regularizations = repo.regularizations(user.id(), first, last);
        List<HolidayView> holidays = upcomingHolidays(employee.region(), workDate, 6);
        MonthSummary monthSummary = AttendanceEngine.summarize(days.stream().filter(d -> !d.dayResult.future).map(d -> d.dayResult).toList(), policy.engine());
        boolean locked = attendanceService.isMonthLocked(yearMonth, policy, now);

        List<CalendarDay> calendarDays = new ArrayList<>();
        for (DayView dayView : days) {
            calendarDays.add(new CalendarDay(dayView.dayResult.date.toString(), dayView.dayResult.status.name(), dayView.dayResult.label, dayView.dayResult.late, dayView.dayResult.lateWaived, dayView.dayResult.workedMinutes / 60.0, dayView.dayResult.date.equals(workDate), dayView.dayResult.future,
                    dayView.dayResult.firstIn == null ? null : AttendanceService.formatTime(dayView.dayResult.firstIn, employee.timeZone()), dayView.dayResult.lastOut == null ? null : AttendanceService.formatTime(dayView.dayResult.lastOut, employee.timeZone()),
                    dayView.dayResult.live, dayView.dayResult.missedCheckout || (!dayView.punches.isEmpty() && !dayView.dayResult.live && dayView.dayResult.lastOut == null), dayView.dayResult.offDayWork, dayView.dayResult.lopDays, dayView.dayResult.leaveDays, dayView.dayResult.regularized));
        }
        String selectedDate = ListParams.text(queryParams, "date", "");
        String initialSel;
        if (selectedDate.startsWith(yearMonth)) initialSel = selectedDate;
        else if (workDate.toString().startsWith(yearMonth)) initialSel = workDate.toString();
        else {
            initialSel = first.toString();
            for (DayView day : days) if (!day.dayResult.future) initialSel = day.dayResult.date.toString();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("present", monthSummary.present);
        summary.put("absent", monthSummary.absent);
        summary.put("leave", monthSummary.leave);
        summary.put("holidays", monthSummary.holidays);
        summary.put("weeklyOffs", monthSummary.weeklyOffs);
        summary.put("workedMinutes", monthSummary.workedMinutes);
        summary.put("lateMarks", monthSummary.lateMarks);
        summary.put("lateLopDays", monthSummary.lateLopDays);
        return new Page(true, yearMonth, minMonth, workDate.toString(), summary, calendarDays, regularizations, initialSel, locked, holidays);
    }

    /** Holidays an employee sees: their own region plus "Everyone". */
    public List<HolidayView> upcomingHolidays(String region, LocalDate from, int take) {
        return repo.upcomingHolidays(region, from, take);
    }

    // ───────────────────────── regularization ─────────────────────────

    public record RegularizationInput(String date, String kind, String proposedIn, String proposedOut, String reason) {}

    public String requestRegularization(CurrentUser user, RegularizationInput input, String ipAddress) {
        if (input.date() == null || !input.date().matches("^\\d{4}-\\d{2}-\\d{2}$")) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid string: must match pattern /^\\d{4}-\\d{2}-\\d{2}$/");
        String kind = Check.oneOf(input.kind(), List.of("ABSENT", "LATE_CHECKIN", "MISSED_CHECKOUT", "HOLIDAY_WORK"));
        String proposedInText = input.proposedIn() == null ? null : input.proposedIn(), proposedOutText = input.proposedOut() == null ? null : input.proposedOut();
        for (String punchTime : new String[] {proposedInText, proposedOutText}) {
            if (punchTime == null || (!punchTime.isEmpty() && !punchTime.matches("^\\d{2}:\\d{2}$"))) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid input");
        }
        String reason = Check.requireString(input.reason()).trim();
        if (reason.length() > 500) throw new UserException(HttpStatus.BAD_REQUEST, "Keep the reason under 500 characters.");

        AttendanceEmployee employee = me(user);
        if (employee == null) throw new UserException(NOT_TRACKED);
        AttendanceService.ResolvedAttendancePolicy policy = attendanceService.policy();
        Instant now = Instant.now();
        LocalDate date = LocalDate.parse(input.date());
        if (date.isAfter(attendanceService.currentWorkDate(employee, now))) throw new UserException("You can't regularize a future day.");
        if (attendanceService.isMonthLocked(input.date().substring(0, 7), policy, now)) throw new UserException("Attendance for this month is locked. Ask Finance to adjust LOP in payroll.");

        DayView day = attendanceService.attendanceFor(List.of(employee), date, date, now).get(employee.id()).get(0);
        boolean fits = switch (kind) {
            case "ABSENT" -> day.dayResult.lopDays > 0 && day.dayResult.status != DayStatus.LEAVE;
            case "LATE_CHECKIN" -> day.dayResult.late;
            case "MISSED_CHECKOUT" -> day.dayResult.missedCheckout || (!day.punches.isEmpty() && !day.dayResult.live && day.dayResult.lastOut == null);
            default -> day.dayResult.offDayWork;
        };
        if (!fits) {
            throw new UserException(switch (kind) {
                case "ABSENT" -> "This day isn't marked as loss of pay.";
                case "LATE_CHECKIN" -> "There's no late mark on this day.";
                case "MISSED_CHECKOUT" -> "There's no missing check-out on this day.";
                default -> "You need 4h+ of work on a holiday or weekly off for a comp-off.";
            });
        }
        List<String> duplicateStatuses = repo.openRegularizationStatus(user.id(), date, kind);
        if (!duplicateStatuses.isEmpty()) throw new UserException("PENDING".equals(duplicateStatuses.get(0)) ? "You already have a pending request for this day." : "This day is already regularized.");

        // Proposed times are local to the employee; a night-shift check-out rolls to the next day.
        Instant proposedIn = proposedInText.isEmpty() ? null : toInstant(employee, date, proposedInText, null);
        Instant after = proposedIn != null ? proposedIn : day.dayResult.firstIn;
        Instant proposedOut = proposedOutText.isEmpty() ? null : toInstant(employee, date, proposedOutText, after);
        if ("MISSED_CHECKOUT".equals(kind) && proposedOut == null) throw new UserException("Enter the time you actually checked out.");

        UUID id = UUID.randomUUID();
        repo.insertRegularization(id, user.id(), date, kind, proposedIn, proposedOut, reason.isEmpty() ? null : reason);
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("date", input.date());
        view.put("kind", kind);
        view.put("proposedIn", proposedIn == null ? null : formatIsoInstant(proposedIn));
        view.put("proposedOut", proposedOut == null ? null : formatIsoInstant(proposedOut));
        audit.log(user.id(), "regularization.request", "regularization", id.toString(), null, view, ipAddress);
        return "Regularization sent — HR, Finance or Admin can approve it";
    }

    /** {@code JSON.stringify(Date)}: millisecond ISO with Z. */
    static String formatIsoInstant(Instant instant) {
        return java.time.format.DateTimeFormatter.ISO_INSTANT.format(instant.truncatedTo(java.time.temporal.ChronoUnit.MILLIS)).replaceAll("(\\d{2}:\\d{2}:\\d{2})Z$", "$1.000Z");
    }

    static Instant toInstant(AttendanceEmployee employee, LocalDate date, String time, Instant after) {
        Instant punchInstant = AttendanceEngine.atLocal(date, time, employee.timeZone());
        if (after != null && !punchInstant.isAfter(after)) punchInstant = AttendanceEngine.atLocal(date.plusDays(1), time, employee.timeZone());
        else if (after == null && time.compareTo("12:00") < 0 && employee.shift().startLocal().compareTo("12:00") >= 0) punchInstant = AttendanceEngine.atLocal(date.plusDays(1), time, employee.timeZone());
        return punchInstant;
    }

    public String cancelRegularization(CurrentUser user, String idRaw) {
        UUID id = Check.uuid(idRaw);
        List<Map<String, Object>> rows = repo.findRegularization(id);
        if (rows.isEmpty() || !user.id().equals(rows.get(0).get("employee_id"))) throw new UserException("Request not found.");
        if (!"PENDING".equals(rows.get(0).get("status"))) throw new UserException("Only pending requests can be withdrawn.");
        repo.cancelRegularization(id);
        audit.log(user.id(), "regularization.cancel", "regularization", id.toString(), Map.of("status", "PENDING"), Map.of("status", "CANCELLED"), null);
        return "Request withdrawn";
    }

    // ───────────────────────── check-in / check-out ─────────────────────────

    static final long MIN_GAP_MS = 30_000;

    public record PunchResult(String message, String kind) {}

    /**
     * {@code expect} is the action the user saw on the button, so a double-click or a stale tab can't record the
     * opposite of what they meant.
     */
    public PunchResult punch(CurrentUser user, String expectRaw, String ipAddress) {
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
        repo.insertWebPunch(user.id(), now, expect, ipAddress);
        audit.log(user.id(), "attendance.punch", "punch", user.id().toString(), null, Map.of("kind", expect, "punchedAt", now.toString()), ipAddress);
        if ("IN".equals(expect)) return new PunchResult("Checked in. Have a productive shift.", "IN");

        LocalDate workDate = AttendanceEngine.workDateOf(now, employee.shift().shiftDefinition(), employee.timeZone());
        List<Punch> punches = repo.punchesSince(user.id(), now.minus(Duration.ofHours(40)))
                .stream().filter(p -> AttendanceEngine.workDateOf(p.punchedAt(), employee.shift().shiftDefinition(), employee.timeZone()).equals(workDate)).toList();
        return new PunchResult("Checked out — " + Format.formatHours(AttendanceEngine.workedMinutes(punches).minutes() / 60.0) + " recorded today", "OUT");
    }
}
