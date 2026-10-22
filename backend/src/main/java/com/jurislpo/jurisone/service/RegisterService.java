package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.service.AttendanceService.AttendanceEmployee;
import com.jurislpo.jurisone.service.AttendanceService.DayView;
import com.jurislpo.jurisone.common.ListParams;
import com.jurislpo.jurisone.engine.AttendanceEngine;
import com.jurislpo.jurisone.engine.AttendanceEngine.MonthSummary;
import com.jurislpo.jurisone.engine.Csv;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.engine.PeopleRules;
import com.jurislpo.jurisone.entity.DayStatus;
import com.jurislpo.jurisone.repository.PeopleQuery;
import com.jurislpo.jurisone.repository.RegisterRepository;
import com.jurislpo.jurisone.security.CurrentUser;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/** Attendance register: daily log and monthly grid, time-zone aware, plus its CSV. att.all. */
@Service
public class RegisterService {

    public record ZoneOption(String value, String label) {}

    public static final List<ZoneOption> ZONES = List.of(new ZoneOption("Asia/Kolkata", "IST · India"), new ZoneOption("America/New_York", "ET · New York"),
            new ZoneOption("America/Chicago", "CT · Chicago"), new ZoneOption("America/Los_Angeles", "PT · Los Angeles"), new ZoneOption("UTC", "UTC"),
            new ZoneOption("local", "Each employee’s local time"));
    public static final List<String> DAY_STATUSES = List.of("All", "Present", "Working", "Not checked in", "Absent", "On leave", "Holiday", "Weekly off", "Half day");
    static final List<String> DAY_SORTS = List.of("name", "location", "status", "checkIn", "checkOut", "hours");
    static final List<String> MONTH_SORTS = List.of("name", "present", "absent", "leave");

    /** registerParams. */
    public record RegisterParams(String view, LocalDate day, String month, String timeZone, LocalDate today, String region, String department, String status, ListParams listParams) {}

    private final AttendanceService attendanceService;
    private final RegisterRepository repo;
    private final AuditService audit;

    public RegisterService(AttendanceService attendance, RegisterRepository repo, AuditService audit) {
        this.attendanceService = attendance;
        this.repo = repo;
        this.audit = audit;
    }

    public static RegisterParams params(Map<String, String> queryParams, String viewerTimeZone) {
        LocalDate today = Format.todayIn("Asia/Kolkata");
        String view = ListParams.pick(queryParams, "view", List.of("day", "month"), "day");
        String dayText = ListParams.text(queryParams, "date", today.toString());
        LocalDate day = dayText.matches("^\\d{4}-\\d{2}-\\d{2}$") && dayText.compareTo(today.toString()) <= 0 ? LocalDate.parse(dayText) : today;
        String yearMonth = ListParams.text(queryParams, "month", today.toString().substring(0, 7));
        if (!yearMonth.matches("^\\d{4}-\\d{2}$") || yearMonth.compareTo(today.toString().substring(0, 7)) > 0) yearMonth = today.toString().substring(0, 7);
        List<String> zones = ZONES.stream().map(ZoneOption::value).toList();
        String timeZone = ListParams.pick(queryParams, "timeZone", zones, zones.contains(viewerTimeZone) ? viewerTimeZone : "Asia/Kolkata");
        ListParams listParams = "day".equals(view) ? ListParams.of(queryParams, DAY_SORTS, "name", 50, "asc") : ListParams.of(queryParams, MONTH_SORTS, "name", 50, "asc");
        return new RegisterParams(view, day, yearMonth, timeZone, today, ListParams.pick(queryParams, "region", List.of("All", "IN", "US"), "All"), ListParams.text(queryParams, "departmentId", "All"),
                ListParams.pick(queryParams, "status", DAY_STATUSES, "All"), listParams);
    }

    private List<AttendanceEmployee> employees(RegisterParams registerParams, LocalDate from, LocalDate to) {
        List<String> conditions = new ArrayList<>(List.of("e.date_of_joining <= ?", "(e.exit_date IS NULL OR e.exit_date >= ?)"));
        List<Object> args = new ArrayList<>(List.of(Date.valueOf(to), Date.valueOf(from)));
        if (!"All".equals(registerParams.region())) {
            conditions.add("e.region = ?::\"Region\"");
            args.add(registerParams.region());
        }
        if (!"All".equals(registerParams.department()) && registerParams.department().matches("^[0-9a-f-]{36}$")) {
            conditions.add("e.department_id = ?::uuid");
            args.add(registerParams.department());
        }
        if (!registerParams.listParams().search().isEmpty()) {
            conditions.add("(e.full_name ILIKE ? OR e.code ILIKE ? OR e.designation ILIKE ?)");
            for (int i = 0; i < 3; i++) args.add(PeopleQuery.like(registerParams.listParams().search()));
        }
        return attendanceService.loadAttendanceEmployees(String.join(" AND ", conditions), args.toArray());
    }

    /** Daily-log status wording (prototype register). */
    public static String dayStatusLabel(DayView dayView) {
        return switch (dayView.dayResult.status) {
            case PRESENT -> "Present";
            case HALF_DAY -> "Half day";
            case ABSENT -> "Absent";
            case LEAVE -> "On leave";
            case HOLIDAY -> "Holiday";
            case WEEKLY_OFF -> "Weekly off";
            case PENDING -> dayView.dayResult.openSince != null ? "Working" : !dayView.punches.isEmpty() ? "Present" : "Not checked in";
            default -> dayView.dayResult.label == null || dayView.dayResult.label.isEmpty() ? "—" : dayView.dayResult.label;
        };
    }

    /** {@code text}: in the selected zone (+1/−1 when on another calendar day); {@code local}: the employee's own time when different. */
    public record Clock(String text, String local) {}

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a z", Locale.US);

    /** Intl en-US {hour, minute, timeZoneName short} with "GMT+5:30" shown as "IST". */
    static String clockText(Instant instant, String timeZone) {
        return CLOCK.format(instant.atZone(ZoneId.of(timeZone))).replace("GMT+05:30", "IST");
    }

    public static Clock clock(Instant instant, String zone, AttendanceEmployee employee, LocalDate rowDay) {
        if (instant == null) return null;
        String zoneId = "local".equals(zone) ? employee.timeZone() : zone;
        LocalDate day = AttendanceEngine.localParts(instant, zoneId).date();
        String shift = day.equals(rowDay) ? "" : day.isAfter(rowDay) ? " (+1)" : " (−1)";
        return new Clock(clockText(instant, zoneId) + shift, !"local".equals(zone) && !zoneId.equals(employee.timeZone()) ? clockText(instant, employee.timeZone()) : null);
    }

    public record DailyRow(String id, String code, String name, String department, String region, String timeZone, String location, String shiftName, String status,
                           boolean late, Clock checkIn, Clock checkOut, double hours, Instant firstIn, Instant lastOut) {}

    public record DailySummary(int total, int present, int notCheckedIn, int onLeave, int absent) {}

    public record Daily(List<DailyRow> rows, DailySummary summary) {}

    public Daily dailyLog(RegisterParams registerParams) {
        List<AttendanceEmployee> employees = employees(registerParams, registerParams.day(), registerParams.day());
        Map<UUID, List<DayView>> all = attendanceService.attendanceFor(employees, registerParams.day(), registerParams.day(), Instant.now());
        List<DailyRow> rows = new ArrayList<>();
        for (AttendanceEmployee employee : employees) {
            DayView dayView = all.get(employee.id()).get(0);
            rows.add(new DailyRow(employee.id().toString(), employee.code(), employee.name(), employee.department(), employee.region(), employee.timeZone(), PeopleRules.locationOfTimeZone(employee.timeZone()).label(),
                    employee.shift().name(), dayStatusLabel(dayView), dayView.dayResult.late, clock(dayView.dayResult.firstIn, registerParams.timeZone(), employee, registerParams.day()), clock(dayView.dayResult.lastOut, registerParams.timeZone(), employee, registerParams.day()),
                    dayView.dayResult.workedMinutes / 60.0, dayView.dayResult.firstIn, dayView.dayResult.lastOut));
        }
        DailySummary sum = new DailySummary(rows.size(), (int) rows.stream().filter(r -> List.of("Present", "Working", "Half day").contains(r.status())).count(),
                (int) rows.stream().filter(r -> r.status().equals("Not checked in")).count(), (int) rows.stream().filter(r -> r.status().equals("On leave")).count(),
                (int) rows.stream().filter(r -> r.status().equals("Absent")).count());
        List<DailyRow> filtered = new ArrayList<>(rows.stream()
                .filter(r -> "All".equals(registerParams.status()) || r.status().equals(registerParams.status()) || ("Present".equals(registerParams.status()) && r.status().equals("Working"))).toList());
        Map<String, Function<DailyRow, ? extends Comparable>> keys = Map.of(
                "name", DailyRow::name, "location", r -> r.region() + r.timeZone(), "status", DailyRow::status,
                "checkIn", r -> r.firstIn() == null ? 9e15 : (double) r.firstIn().toEpochMilli(),
                "checkOut", r -> r.lastOut() == null ? 9e15 : (double) r.lastOut().toEpochMilli(), "hours", DailyRow::hours);
        filtered.sort(registerParams.listParams().comparator(keys));
        return new Daily(filtered, sum);
    }

    public record Cell(String code, String tone) {}

    static final Map<DayStatus, String[]> CODE = Map.of(DayStatus.PRESENT, new String[] {"P", "ok"}, DayStatus.HALF_DAY, new String[] {"½", "warn"},
            DayStatus.ABSENT, new String[] {"A", "bad"}, DayStatus.LEAVE, new String[] {"L", "info"}, DayStatus.HOLIDAY, new String[] {"H", "gold"},
            DayStatus.WEEKLY_OFF, new String[] {"", "off"});

    public static Cell cellCode(DayView dayView) {
        if (dayView.dayResult.future) return new Cell("", "none");
        if (dayView.dayResult.status == DayStatus.PENDING) return !dayView.punches.isEmpty() ? new Cell("P", "ok") : dayView.dayResult.live ? new Cell("–", "warn") : new Cell("", "none");
        String[] codes = CODE.get(dayView.dayResult.status);
        return codes != null ? new Cell(codes[0], codes[1]) : new Cell("", "none");
    }

    public record GridDay(String date, int day, String weekday, boolean weeklyOff) {}

    /** {@code days}: per-cell tooltip data (date, label, worked minutes). */
    public record DayTip(String date, String label, int workedMinutes) {}

    public record GridRow(String id, String code, String name, String department, String region, List<Cell> cells, List<DayTip> days, double present, double absent, double leave) {}

    public record Monthly(List<GridRow> rows, List<GridDay> days, String from, String to) {}

    public Monthly monthlyGrid(RegisterParams registerParams) {
        AttendanceService.ResolvedAttendancePolicy policy = attendanceService.policy();
        YearMonth yearMonth = YearMonth.parse(registerParams.month());
        LocalDate from = yearMonth.atDay(1), to = yearMonth.atEndOfMonth();
        List<AttendanceEmployee> employees = employees(registerParams, from, to);
        Map<UUID, List<DayView>> all = attendanceService.attendanceFor(employees, from, to, Instant.now());
        List<GridDay> days = new ArrayList<>();
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            int dayOfWeek = AttendanceEngine.dowOf(day);
            days.add(new GridDay(day.toString(), day.getDayOfMonth(), String.valueOf("SMTWTFS".charAt(dayOfWeek)), policy.engine().weeklyOffs().contains(dayOfWeek)));
        }
        List<GridRow> rows = new ArrayList<>();
        for (AttendanceEmployee employee : employees) {
            List<DayView> dayViews = all.get(employee.id());
            MonthSummary summary = AttendanceEngine.summarize(dayViews.stream().filter(x -> !x.dayResult.future).map(x -> x.dayResult).toList(), policy.engine());
            rows.add(new GridRow(employee.id().toString(), employee.code(), employee.name(), employee.department(), employee.region(), dayViews.stream().map(RegisterService::cellCode).toList(),
                    dayViews.stream().map(x -> new DayTip(x.dayResult.date.toString(), x.dayResult.label, x.dayResult.workedMinutes)).toList(), summary.present, summary.absent, summary.leave));
        }
        Map<String, Function<GridRow, ? extends Comparable>> keys = Map.of("name", GridRow::name, "present", GridRow::present, "absent", GridRow::absent, "leave", GridRow::leave);
        rows.sort(registerParams.listParams().comparator(keys));
        return new Monthly(rows, days, from.toString(), to.toString());
    }

    public static List<String> months(LocalDate trackingStart, LocalDate today) {
        List<String> out = new ArrayList<>();
        for (YearMonth yearMonth = YearMonth.from(trackingStart); !yearMonth.isAfter(YearMonth.from(today)); yearMonth = yearMonth.plusMonths(1)) out.add(yearMonth.toString());
        java.util.Collections.reverse(out);
        return out;
    }

    // ── page + export ──

    public record DepartmentSummary(String id, String name) {}

    public Map<String, Object> page(CurrentUser user, Map<String, String> queryParams) {
        RegisterParams registerParams = params(queryParams, user.timeZone());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("view", registerParams.view());
        out.put("date", registerParams.day().toString());
        out.put("month", registerParams.month());
        out.put("timeZone", registerParams.timeZone());
        out.put("today", registerParams.today().toString());
        out.put("page", registerParams.listParams().page());
        out.put("take", registerParams.listParams().take());
        out.put("departments", repo.activeDepartments());
        out.put("zones", ZONES);
        out.put("statuses", DAY_STATUSES);
        if ("day".equals(registerParams.view())) {
            Daily daily = dailyLog(registerParams);
            out.put("total", daily.rows().size());
            out.put("rows", daily.rows().subList(Math.min(registerParams.listParams().skip(), daily.rows().size()), Math.min(registerParams.listParams().skip() + registerParams.listParams().take(), daily.rows().size())));
            out.put("summary", daily.summary());
        } else {
            Monthly monthly = monthlyGrid(registerParams);
            out.put("total", monthly.rows().size());
            out.put("rows", monthly.rows().subList(Math.min(registerParams.listParams().skip(), monthly.rows().size()), Math.min(registerParams.listParams().skip() + registerParams.listParams().take(), monthly.rows().size())));
            out.put("days", monthly.days());
            out.put("months", months(attendanceService.policy().trackingStart(), registerParams.today()));
        }
        return out;
    }

    public record Export(String filename, String body) {}

    public Export export(CurrentUser user, Map<String, String> queryParams) {
        RegisterParams registerParams = params(queryParams, user.timeZone());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("view", registerParams.view());
        after.put("date", registerParams.day().toString());
        after.put("month", registerParams.month());
        after.put("filters", queryParams);
        audit.log(user.id(), "attendance.export", "attendance", null, null, after, null);
        if ("day".equals(registerParams.view())) {
            Daily daily = dailyLog(registerParams);
            String tzName = "local".equals(registerParams.timeZone()) ? "employee local" : registerParams.timeZone();
            List<List<Object>> rows = new ArrayList<>();
            for (DailyRow dailyRow : daily.rows()) {
                rows.add(List.of(registerParams.day().toString(), dailyRow.code(), dailyRow.name(), dailyRow.department(), "IN".equals(dailyRow.region()) ? "India" : "US", dailyRow.shiftName(), dailyRow.status(),
                        dailyRow.late() ? "Yes" : "", dailyRow.checkIn() == null ? "" : dailyRow.checkIn().text(), dailyRow.checkOut() == null ? "" : dailyRow.checkOut().text(),
                        dailyRow.checkIn() == null ? "" : dailyRow.checkIn().local() != null ? dailyRow.checkIn().local() : dailyRow.checkIn().text(),
                        dailyRow.checkOut() == null ? "" : dailyRow.checkOut().local() != null ? dailyRow.checkOut().local() : dailyRow.checkOut().text(),
                        dailyRow.hours() != 0 ? String.format(Locale.ROOT, "%.2f", dailyRow.hours()) : ""));
            }
            return new Export("jurisone-attendance-" + registerParams.day() + ".csv", Csv.toCsv(List.of("Date", "Employee ID", "Name", "Department", "Team", "Shift", "Status", "Late",
                    "Check-in (" + tzName + ")", "Check-out (" + tzName + ")", "Check-in (local)", "Check-out (local)", "Worked hours"), rows));
        }
        Monthly monthly = monthlyGrid(registerParams);
        List<String> header = new ArrayList<>(List.of("Employee ID", "Name", "Department", "Team"));
        monthly.days().forEach(day -> header.add(day.date()));
        header.addAll(List.of("Present", "Absent / LOP", "Leave"));
        List<List<Object>> rows = new ArrayList<>();
        for (GridRow gridRow : monthly.rows()) {
            List<Object> row = new ArrayList<>(List.of(gridRow.code(), gridRow.name(), gridRow.department(), "IN".equals(gridRow.region()) ? "India" : "US"));
            for (int i = 0; i < gridRow.cells().size(); i++) {
                String code = gridRow.cells().get(i).code();
                row.add(!code.isEmpty() ? code : monthly.days().get(i).weeklyOff() ? "WO" : "");
            }
            row.add(gridRow.present());
            row.add(gridRow.absent());
            row.add(gridRow.leave());
            rows.add(row);
        }
        return new Export("jurisone-attendance-" + registerParams.month() + ".csv", Csv.toCsv(header, rows));
    }
}
