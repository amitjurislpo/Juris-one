package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.Check;
import com.jurislpo.jurisone.common.ListParams;
import com.jurislpo.jurisone.engine.AttendanceEngine;
import com.jurislpo.jurisone.engine.AttendanceEngine.ShiftWindow;
import com.jurislpo.jurisone.engine.Csv;
import com.jurislpo.jurisone.entity.DayStatus;
import com.jurislpo.jurisone.exception.UserException;
import com.jurislpo.jurisone.repository.MyAttendanceRepository;
import com.jurislpo.jurisone.security.CurrentUser;
import com.jurislpo.jurisone.service.AttendanceService.AttendanceEmployee;
import com.jurislpo.jurisone.service.AttendanceService.DayView;
import com.jurislpo.jurisone.service.MyAttendanceService.RegularizationView;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The Attendance page's summary for a period (a week for the List and Tabular views, a month for the Calendar view): one row per day,
 * the footer counters, and the CSV / PDF of the same rows. Own data only (the signed-in employee), like {@code /attendance/mine}.
 */
@Service
public class AttendanceSummaryService {

    public static final List<String> VIEWS = List.of("list", "tabular", "calendar");
    /** Filter values of the toolbar: a day status, or "WEEKEND" for a weekly off. */
    public static final List<String> STATUS_FILTERS = List.of("PRESENT", "ABSENT", "HALF_DAY", "LEAVE", "HOLIDAY", "WEEKEND");
    static final int MAX_DAYS = 62;
    static final LocalDate EARLIEST = LocalDate.of(2000, 1, 1);
    static final List<String> EXPORT_HEADER = List.of("Date", "Day", "Shift", "Check-in", "Check-out", "Hours", "Status", "Late by (min)", "Early by (min)");

    private final AttendanceService attendanceService;
    private final MyAttendanceService myAttendance;
    private final MyAttendanceRepository repo;

    public AttendanceSummaryService(AttendanceService attendanceService, MyAttendanceService myAttendance, MyAttendanceRepository repo) {
        this.attendanceService = attendanceService;
        this.myAttendance = myAttendance;
        this.repo = repo;
    }

    public record SummaryDay(String date, String weekday, String status, String label, String shiftName, String firstIn, String lastOut, double hours,
                             int lateMinutes, int earlyMinutes, boolean late, boolean missedCheckout, boolean today, boolean future, boolean live,
                             boolean regularized, String regularizationStatus) {}

    /** Footer counters. Days are fractions (a half day counts 0.5); {@code hours} is the same list in hours. */
    public record Counters(double payableDays, double present, double onDuty, double paidLeave, double holidays, double weekends, double unpaidLeave,
                           double absent, int lateCount, Map<String, Double> hours) {}

    public record ShiftCard(String name, String start, String end) {}

    public record Summary(boolean tracked, String view, String from, String to, String previousFrom, String nextFrom, String workDate, ShiftCard shift,
                          Counters counters, List<SummaryDay> days) {}

    public record Export(String filename, String contentType, byte[] body) {}

    record Period(LocalDate from, LocalDate to, String view, String status) {}

    // ───────────────────────── query ─────────────────────────

    private Period period(Map<String, String> queryParams, LocalDate workDate) {
        String view = Check.oneOf(ListParams.text(queryParams, "view", "list"), VIEWS);
        String status = ListParams.text(queryParams, "status", "");
        if (!status.isEmpty() && !STATUS_FILTERS.contains(status)) throw new UserException(HttpStatus.BAD_REQUEST, "Invalid status filter.");
        String fromText = ListParams.text(queryParams, "from", ""), toText = ListParams.text(queryParams, "to", "");
        LocalDate from, to;
        if (fromText.isEmpty() && toText.isEmpty()) {
            if (view.equals("calendar")) {
                from = workDate.withDayOfMonth(1);
                to = workDate.withDayOfMonth(workDate.lengthOfMonth());
            } else {
                from = workDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                to = from.plusDays(6);
            }
        } else {
            from = parseDate(fromText);
            to = parseDate(toText);
        }
        if (from.isAfter(to)) throw new UserException(HttpStatus.BAD_REQUEST, "The start date must not be after the end date.");
        if (ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) throw new UserException(HttpStatus.BAD_REQUEST, "Choose a period of at most " + MAX_DAYS + " days.");
        if (from.isBefore(EARLIEST) || to.isAfter(workDate.plusYears(1))) throw new UserException(HttpStatus.BAD_REQUEST, "That period is out of range.");
        return new Period(from, to, view, status);
    }

    private static LocalDate parseDate(String text) {
        try {
            if (text.matches("^\\d{4}-\\d{2}-\\d{2}$")) return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            // falls through to the same message
        }
        throw new UserException(HttpStatus.BAD_REQUEST, "Dates must look like YYYY-MM-DD.");
    }

    public Summary summary(CurrentUser user, Map<String, String> queryParams) {
        AttendanceEmployee employee = myAttendance.me(user);
        if (employee == null) return new Summary(false, null, null, null, null, null, null, null, null, List.of());
        Instant now = Instant.now();
        LocalDate workDate = attendanceService.currentWorkDate(employee, now);
        return build(user, employee, period(queryParams, workDate), workDate, now);
    }

    private Summary build(CurrentUser user, AttendanceEmployee employee, Period period, LocalDate workDate, Instant now) {
        AttendanceService.ResolvedAttendancePolicy policy = attendanceService.policy();
        List<DayView> views = attendanceService.attendanceFor(List.of(employee), period.from(), period.to(), now).get(employee.id());
        Map<String, String> regularizationByDate = new LinkedHashMap<>();
        for (RegularizationView regularization : repo.regularizations(user.id(), period.from(), period.to())) { // newest first: keep the newest per day
            regularizationByDate.putIfAbsent(regularization.date(), regularization.status());
        }
        double target = policy.shiftTargetHours();
        double present = 0, onDuty = 0, paidLeave = 0, holidays = 0, weekends = 0, unpaidLeave = 0, absent = 0, presentHours = 0;
        int lateCount = 0;
        List<SummaryDay> days = new ArrayList<>();
        for (DayView view : views) {
            AttendanceEngine.DayResult day = view.dayResult;
            if (!day.future) {
                double dayPresent = day.status == DayStatus.PRESENT ? 1 : day.status == DayStatus.HALF_DAY ? 0.5
                        : day.status == DayStatus.LEAVE && day.workedMinutes >= policy.engine().halfDayHours() * 60 ? 0.5 : 0;
                // An On Duty day is a present day of its own kind: its part is counted under "On duty", not again under "Present".
                onDuty += day.onDutyDays;
                present += Math.max(0, dayPresent - day.onDutyDays);
                if (day.status == DayStatus.HOLIDAY) holidays += 1;
                if (day.status == DayStatus.WEEKLY_OFF) weekends += 1;
                paidLeave += day.leaveDays;
                if (day.status == DayStatus.LEAVE) unpaidLeave += day.lopDays;
                else absent += day.lopDays;
                if (day.late) lateCount += 1;
                if ((day.status == DayStatus.PRESENT || day.status == DayStatus.HALF_DAY || day.status == DayStatus.LEAVE) && day.onDutyDays < 1) presentHours += day.workedMinutes / 60.0;
            }
            if (!matches(day.status, period.status())) continue;
            days.add(row(employee, day, view, workDate, regularizationByDate.get(day.date.toString())));
        }
        Map<String, Double> hours = new LinkedHashMap<>();
        hours.put("payableDays", round(presentHours + (onDuty + paidLeave + holidays + weekends) * target));
        hours.put("present", round(presentHours));
        hours.put("onDuty", round(onDuty * target));
        hours.put("paidLeave", round(paidLeave * target));
        hours.put("holidays", round(holidays * target));
        hours.put("weekends", round(weekends * target));
        hours.put("unpaidLeave", round(unpaidLeave * target));
        hours.put("absent", round(absent * target));
        Counters counters = new Counters(present + onDuty + paidLeave + holidays + weekends, present, onDuty, paidLeave, holidays, weekends, unpaidLeave, absent, lateCount, hours);
        long span = ChronoUnit.DAYS.between(period.from(), period.to()) + 1;
        boolean isMonth = period.view().equals("calendar");
        String previousFrom = isMonth ? period.from().minusMonths(1).withDayOfMonth(1).toString() : period.from().minusDays(span).toString();
        String nextFrom = isMonth ? period.from().plusMonths(1).withDayOfMonth(1).toString() : period.from().plusDays(span).toString();
        AttendanceService.Shift shift = employee.shift();
        return new Summary(true, period.view(), period.from().toString(), period.to().toString(), previousFrom, nextFrom, workDate.toString(),
                new ShiftCard(shift.name(), shift.startLocal(), shift.endLocal()), counters, days);
    }

    private static double round(double value) {
        return Math.round(value * 100) / 100.0;
    }

    private static boolean matches(DayStatus status, String filter) {
        return switch (filter) {
            case "" -> true;
            case "WEEKEND" -> status == DayStatus.WEEKLY_OFF;
            default -> status.name().equals(filter);
        };
    }

    private SummaryDay row(AttendanceEmployee employee, AttendanceEngine.DayResult day, DayView view, LocalDate workDate, String regularizationStatus) {
        int lateMinutes = 0, earlyMinutes = 0;
        if (day.status == DayStatus.PRESENT || day.status == DayStatus.HALF_DAY) {
            ShiftWindow window = AttendanceEngine.shiftWindow(day.date, employee.shift().shiftDefinition(), employee.timeZone());
            if (day.firstIn != null && day.firstIn.isAfter(window.graceUntil())) lateMinutes = (int) Duration.between(window.start(), day.firstIn).toMinutes();
            if (day.lastOut != null && !day.live && day.lastOut.isBefore(window.end())) earlyMinutes = (int) Duration.between(day.lastOut, window.end()).toMinutes();
        }
        boolean missedCheckout = day.missedCheckout || (!view.punches.isEmpty() && !day.live && day.lastOut == null);
        return new SummaryDay(day.date.toString(), day.date.getDayOfWeek().name(), day.status.name(), day.label, employee.shift().name(),
                day.firstIn == null ? null : AttendanceService.formatTime(day.firstIn, employee.timeZone()),
                day.lastOut == null ? null : AttendanceService.formatTime(day.lastOut, employee.timeZone()),
                day.workedMinutes / 60.0, lateMinutes, earlyMinutes, day.late, missedCheckout, day.date.equals(workDate), day.future, day.live, day.regularized, regularizationStatus);
    }

    // ───────────────────────── export ─────────────────────────

    private static String duration(double hours) {
        int minutes = (int) Math.round(hours * 60);
        return minutes == 0 ? "" : (minutes / 60) + ":" + String.format("%02d", minutes % 60);
    }

    private static List<String> exportRow(SummaryDay day) {
        String weekday = day.weekday().charAt(0) + day.weekday().substring(1).toLowerCase();
        return List.of(day.date(), weekday, day.shiftName(), day.firstIn() == null ? "" : day.firstIn(), day.lastOut() == null ? "" : day.lastOut(), duration(day.hours()),
                day.future() ? "" : day.label(), day.lateMinutes() > 0 ? String.valueOf(day.lateMinutes()) : "", day.earlyMinutes() > 0 ? String.valueOf(day.earlyMinutes()) : "");
    }

    /** The same rows as the page, as CSV or PDF ({@code format=csv|pdf}). */
    public Export export(CurrentUser user, Map<String, String> queryParams) {
        String format = Check.oneOf(ListParams.text(queryParams, "format", "csv"), List.of("csv", "pdf"));
        Summary summary = summary(user, queryParams);
        if (!summary.tracked()) throw new UserException(MyAttendanceService.NOT_TRACKED);
        String name = "jurisone-attendance-" + summary.from() + "-to-" + summary.to();
        if (format.equals("csv")) {
            List<List<String>> rows = summary.days().stream().map(AttendanceSummaryService::exportRow).toList();
            return new Export(name + ".csv", "text/csv; charset=UTF-8", Csv.toCsv(EXPORT_HEADER, rows).getBytes(StandardCharsets.UTF_8));
        }
        try (SimplePdf pdf = new SimplePdf("Attendance " + summary.from() + " to " + summary.to(), "JurisOne", "JurisOne attendance")) {
            Counters counters = summary.counters();
            pdf.line("Attendance " + summary.from() + " to " + summary.to(), 14, true, SimplePdf.INK);
            pdf.line("Payable days " + counters.payableDays() + " | Present " + counters.present() + " | Paid leave " + counters.paidLeave() + " | Holidays " + counters.holidays()
                    + " | Weekends " + counters.weekends() + " | Absent " + counters.absent() + " | Late " + counters.lateCount(), 8, false, SimplePdf.MUTED);
            pdf.gap(4);
            double[] columnX = {SimplePdf.M, 44, 68, 92, 118, 138, 170};
            boolean[] rightEdge = new boolean[7];
            pdf.row(new String[]{"Date", "Shift", "Check-in", "Check-out", "Hours", "Status", "Late/Early"}, columnX, rightEdge, 8, true, SimplePdf.INK);
            for (SummaryDay day : summary.days()) {
                String lateEarly = (day.lateMinutes() > 0 ? "Late " + day.lateMinutes() + "m " : "") + (day.earlyMinutes() > 0 ? "Early " + day.earlyMinutes() + "m" : "");
                pdf.row(new String[]{day.date(), day.shiftName(), day.firstIn() == null ? "" : day.firstIn(), day.lastOut() == null ? "" : day.lastOut(), duration(day.hours()),
                        day.future() ? "" : day.label(), lateEarly.trim()}, columnX, rightEdge, 8, false, SimplePdf.INK);
            }
            return new Export(name + ".pdf", "application/pdf", pdf.finish());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
