package com.jurislpo.jurisone.attendance;

import com.jurislpo.jurisone.attendance.AttendanceService.AttEmployee;
import com.jurislpo.jurisone.attendance.AttendanceService.DayView;
import com.jurislpo.jurisone.common.ListParams;
import com.jurislpo.jurisone.engine.AttendanceEngine;
import com.jurislpo.jurisone.engine.AttendanceEngine.MonthSummary;
import com.jurislpo.jurisone.engine.Csv;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.engine.PeopleRules;
import com.jurislpo.jurisone.entity.DayStatus;
import com.jurislpo.jurisone.people.PeopleQuery;
import com.jurislpo.jurisone.platform.AuditService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Attendance register: daily log and monthly grid, time-zone aware, plus its CSV — src/lib/attendance/register.ts. att.all. */
@Service
public class RegisterService {

    public record Zone(String v, String l) {}

    public static final List<Zone> ZONES = List.of(new Zone("Asia/Kolkata", "IST · India"), new Zone("America/New_York", "ET · New York"),
            new Zone("America/Chicago", "CT · Chicago"), new Zone("America/Los_Angeles", "PT · Los Angeles"), new Zone("UTC", "UTC"),
            new Zone("local", "Each employee’s local time"));
    public static final List<String> DAY_STATUSES = List.of("All", "Present", "Working", "Not checked in", "Absent", "On leave", "Holiday", "Weekly off", "Half day");
    static final List<String> DAY_SORTS = List.of("name", "loc", "st", "inn", "out", "h");
    static final List<String> MONTH_SORTS = List.of("name", "P", "A", "L");

    /** registerParams. */
    public record Params(String view, LocalDate day, String ym, String tz, LocalDate today, String region, String dept, String st, ListParams ls) {}

    private final AttendanceService att;
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public RegisterService(AttendanceService att, JdbcTemplate jdbc, AuditService audit) {
        this.att = att;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public static Params params(Map<String, String> sp, String viewerTz) {
        LocalDate today = Format.todayIn("Asia/Kolkata");
        String view = ListParams.pick(sp, "view", List.of("day", "month"), "day");
        String dayS = ListParams.str(sp, "day", today.toString());
        LocalDate day = dayS.matches("^\\d{4}-\\d{2}-\\d{2}$") && dayS.compareTo(today.toString()) <= 0 ? LocalDate.parse(dayS) : today;
        String ym = ListParams.str(sp, "ym", today.toString().substring(0, 7));
        if (!ym.matches("^\\d{4}-\\d{2}$") || ym.compareTo(today.toString().substring(0, 7)) > 0) ym = today.toString().substring(0, 7);
        List<String> zones = ZONES.stream().map(Zone::v).toList();
        String tz = ListParams.pick(sp, "tz", zones, zones.contains(viewerTz) ? viewerTz : "Asia/Kolkata");
        ListParams ls = "day".equals(view) ? ListParams.of(sp, DAY_SORTS, "name", 50, "asc") : ListParams.of(sp, MONTH_SORTS, "name", 50, "asc");
        return new Params(view, day, ym, tz, today, ListParams.pick(sp, "region", List.of("All", "IN", "US"), "All"), ListParams.str(sp, "dept", "All"),
                ListParams.pick(sp, "st", DAY_STATUSES, "All"), ls);
    }

    private List<AttEmployee> employees(Params p, LocalDate from, LocalDate to) {
        List<String> and = new ArrayList<>(List.of("e.date_of_joining <= ?", "(e.exit_date IS NULL OR e.exit_date >= ?)"));
        List<Object> args = new ArrayList<>(List.of(Date.valueOf(to), Date.valueOf(from)));
        if (!"All".equals(p.region())) {
            and.add("e.region = ?::\"Region\"");
            args.add(p.region());
        }
        if (!"All".equals(p.dept()) && p.dept().matches("^[0-9a-f-]{36}$")) {
            and.add("e.department_id = ?::uuid");
            args.add(p.dept());
        }
        if (!p.ls().q().isEmpty()) {
            and.add("(e.full_name ILIKE ? OR e.code ILIKE ? OR e.designation ILIKE ?)");
            for (int i = 0; i < 3; i++) args.add(PeopleQuery.like(p.ls().q()));
        }
        return att.loadAttEmployees(String.join(" AND ", and), args.toArray());
    }

    /** Daily-log status wording (prototype register). */
    public static String dayStatusLabel(DayView v) {
        return switch (v.r.status) {
            case PRESENT -> "Present";
            case HALF_DAY -> "Half day";
            case ABSENT -> "Absent";
            case LEAVE -> "On leave";
            case HOLIDAY -> "Holiday";
            case WEEKLY_OFF -> "Weekly off";
            case PENDING -> v.r.openSince != null ? "Working" : !v.punches.isEmpty() ? "Present" : "Not checked in";
            default -> v.r.label == null || v.r.label.isEmpty() ? "—" : v.r.label;
        };
    }

    /** {@code text}: in the selected zone (+1/−1 when on another calendar day); {@code local}: the employee's own time when different. */
    public record Clock(String text, String local) {}

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a z", Locale.US);

    /** Intl en-US {hour, minute, timeZoneName short} with "GMT+5:30" shown as "IST". */
    static String clockText(Instant at, String tz) {
        return CLOCK.format(at.atZone(ZoneId.of(tz))).replace("GMT+05:30", "IST");
    }

    public static Clock clock(Instant at, String zone, AttEmployee e, LocalDate rowDay) {
        if (at == null) return null;
        String z = "local".equals(zone) ? e.tz() : zone;
        LocalDate day = AttendanceEngine.localParts(at, z).date();
        String shift = day.equals(rowDay) ? "" : day.isAfter(rowDay) ? " (+1)" : " (−1)";
        return new Clock(clockText(at, z) + shift, !"local".equals(zone) && !z.equals(e.tz()) ? clockText(at, e.tz()) : null);
    }

    public record DailyRow(String id, String code, String name, String department, String region, String tz, String loc, String shiftName, String st,
                           boolean late, Clock inn, Clock out, double h, Instant firstIn, Instant lastOut) {}

    public record Sum(int t, int p, int n, int l, int a) {}

    public record Daily(List<DailyRow> rows, Sum sum) {}

    public Daily dailyLog(Params p) {
        List<AttEmployee> emps = employees(p, p.day(), p.day());
        Map<UUID, List<DayView>> all = att.attendanceFor(emps, p.day(), p.day(), Instant.now());
        List<DailyRow> rows = new ArrayList<>();
        for (AttEmployee e : emps) {
            DayView d = all.get(e.id()).get(0);
            rows.add(new DailyRow(e.id().toString(), e.code(), e.name(), e.department(), e.region(), e.tz(), PeopleRules.locationOfTz(e.tz()).l(),
                    e.shift().name(), dayStatusLabel(d), d.r.late, clock(d.r.firstIn, p.tz(), e, p.day()), clock(d.r.lastOut, p.tz(), e, p.day()),
                    d.r.workedMinutes / 60.0, d.r.firstIn, d.r.lastOut));
        }
        Sum sum = new Sum(rows.size(), (int) rows.stream().filter(r -> List.of("Present", "Working", "Half day").contains(r.st())).count(),
                (int) rows.stream().filter(r -> r.st().equals("Not checked in")).count(), (int) rows.stream().filter(r -> r.st().equals("On leave")).count(),
                (int) rows.stream().filter(r -> r.st().equals("Absent")).count());
        List<DailyRow> filtered = new ArrayList<>(rows.stream()
                .filter(r -> "All".equals(p.st()) || r.st().equals(p.st()) || ("Present".equals(p.st()) && r.st().equals("Working"))).toList());
        Map<String, Function<DailyRow, ? extends Comparable>> keys = Map.of(
                "name", DailyRow::name, "loc", r -> r.region() + r.tz(), "st", DailyRow::st,
                "inn", r -> r.firstIn() == null ? 9e15 : (double) r.firstIn().toEpochMilli(),
                "out", r -> r.lastOut() == null ? 9e15 : (double) r.lastOut().toEpochMilli(), "h", DailyRow::h);
        filtered.sort(p.ls().comparator(keys));
        return new Daily(filtered, sum);
    }

    public record Cell(String c, String cls) {}

    static final Map<DayStatus, String[]> CODE = Map.of(DayStatus.PRESENT, new String[] {"P", "ok"}, DayStatus.HALF_DAY, new String[] {"½", "warn"},
            DayStatus.ABSENT, new String[] {"A", "bad"}, DayStatus.LEAVE, new String[] {"L", "info"}, DayStatus.HOLIDAY, new String[] {"H", "gold"},
            DayStatus.WEEKLY_OFF, new String[] {"", "off"});

    public static Cell cellCode(DayView d) {
        if (d.r.future) return new Cell("", "none");
        if (d.r.status == DayStatus.PENDING) return !d.punches.isEmpty() ? new Cell("P", "ok") : d.r.live ? new Cell("–", "warn") : new Cell("", "none");
        String[] c = CODE.get(d.r.status);
        return c != null ? new Cell(c[0], c[1]) : new Cell("", "none");
    }

    public record GridDay(String k, int d, String dow, boolean wk) {}

    /** {@code days}: per-cell tooltip data (date, label, worked minutes). */
    public record DayTip(String date, String label, int workedMinutes) {}

    public record GridRow(String id, String code, String name, String department, String region, List<Cell> cells, List<DayTip> days, double P, double A, double L) {}

    public record Monthly(List<GridRow> rows, List<GridDay> days, String from, String to) {}

    public Monthly monthlyGrid(Params p) {
        AttendanceService.Pol policy = att.policy();
        YearMonth ym = YearMonth.parse(p.ym());
        LocalDate from = ym.atDay(1), to = ym.atEndOfMonth();
        List<AttEmployee> emps = employees(p, from, to);
        Map<UUID, List<DayView>> all = att.attendanceFor(emps, from, to, Instant.now());
        List<GridDay> days = new ArrayList<>();
        for (LocalDate k = from; !k.isAfter(to); k = k.plusDays(1)) {
            int dow = AttendanceEngine.dowOf(k);
            days.add(new GridDay(k.toString(), k.getDayOfMonth(), String.valueOf("SMTWTFS".charAt(dow)), policy.engine().weeklyOffs().contains(dow)));
        }
        List<GridRow> rows = new ArrayList<>();
        for (AttEmployee e : emps) {
            List<DayView> ds = all.get(e.id());
            MonthSummary s = AttendanceEngine.summarize(ds.stream().filter(x -> !x.r.future).map(x -> x.r).toList(), policy.engine());
            rows.add(new GridRow(e.id().toString(), e.code(), e.name(), e.department(), e.region(), ds.stream().map(RegisterService::cellCode).toList(),
                    ds.stream().map(x -> new DayTip(x.r.date.toString(), x.r.label, x.r.workedMinutes)).toList(), s.present, s.absent, s.leave));
        }
        Map<String, Function<GridRow, ? extends Comparable>> keys = Map.of("name", GridRow::name, "P", GridRow::P, "A", GridRow::A, "L", GridRow::L);
        rows.sort(p.ls().comparator(keys));
        return new Monthly(rows, days, from.toString(), to.toString());
    }

    public static List<String> months(LocalDate trackingStart, LocalDate today) {
        List<String> out = new ArrayList<>();
        for (YearMonth ym = YearMonth.from(trackingStart); !ym.isAfter(YearMonth.from(today)); ym = ym.plusMonths(1)) out.add(ym.toString());
        java.util.Collections.reverse(out);
        return out;
    }

    // ── page + export ──

    public record Dept(String id, String name) {}

    public Map<String, Object> page(CurrentUser u, Map<String, String> sp) {
        Params p = params(sp, u.timeZone());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("view", p.view());
        out.put("day", p.day().toString());
        out.put("ym", p.ym());
        out.put("tz", p.tz());
        out.put("today", p.today().toString());
        out.put("page", p.ls().page());
        out.put("take", p.ls().take());
        out.put("depts", jdbc.query("SELECT id::text, name FROM departments WHERE archived_at IS NULL ORDER BY name ASC", (rs, i) -> new Dept(rs.getString(1), rs.getString(2))));
        out.put("zones", ZONES);
        out.put("statuses", DAY_STATUSES);
        if ("day".equals(p.view())) {
            Daily d = dailyLog(p);
            out.put("total", d.rows().size());
            out.put("rows", d.rows().subList(Math.min(p.ls().skip(), d.rows().size()), Math.min(p.ls().skip() + p.ls().take(), d.rows().size())));
            out.put("sum", d.sum());
        } else {
            Monthly m = monthlyGrid(p);
            out.put("total", m.rows().size());
            out.put("rows", m.rows().subList(Math.min(p.ls().skip(), m.rows().size()), Math.min(p.ls().skip() + p.ls().take(), m.rows().size())));
            out.put("days", m.days());
            out.put("months", months(att.policy().trackingStart(), p.today()));
        }
        return out;
    }

    public record Export(String filename, String body) {}

    public Export export(CurrentUser u, Map<String, String> sp) {
        Params p = params(sp, u.timeZone());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("view", p.view());
        after.put("day", p.day().toString());
        after.put("ym", p.ym());
        after.put("filters", sp);
        audit.log(u.id(), "attendance.export", "attendance", null, null, after, null);
        if ("day".equals(p.view())) {
            Daily d = dailyLog(p);
            String tzName = "local".equals(p.tz()) ? "employee local" : p.tz();
            List<List<Object>> rows = new ArrayList<>();
            for (DailyRow r : d.rows()) {
                rows.add(List.of(p.day().toString(), r.code(), r.name(), r.department(), "IN".equals(r.region()) ? "India" : "US", r.shiftName(), r.st(),
                        r.late() ? "Yes" : "", r.inn() == null ? "" : r.inn().text(), r.out() == null ? "" : r.out().text(),
                        r.inn() == null ? "" : r.inn().local() != null ? r.inn().local() : r.inn().text(),
                        r.out() == null ? "" : r.out().local() != null ? r.out().local() : r.out().text(),
                        r.h() != 0 ? String.format(Locale.ROOT, "%.2f", r.h()) : ""));
            }
            return new Export("jurisone-attendance-" + p.day() + ".csv", Csv.toCsv(List.of("Date", "Employee ID", "Name", "Department", "Team", "Shift", "Status", "Late",
                    "Check-in (" + tzName + ")", "Check-out (" + tzName + ")", "Check-in (local)", "Check-out (local)", "Worked hours"), rows));
        }
        Monthly m = monthlyGrid(p);
        List<String> header = new ArrayList<>(List.of("Employee ID", "Name", "Department", "Team"));
        m.days().forEach(d -> header.add(d.k()));
        header.addAll(List.of("Present", "Absent / LOP", "Leave"));
        List<List<Object>> rows = new ArrayList<>();
        for (GridRow r : m.rows()) {
            List<Object> row = new ArrayList<>(List.of(r.code(), r.name(), r.department(), "IN".equals(r.region()) ? "India" : "US"));
            for (int i = 0; i < r.cells().size(); i++) {
                String c = r.cells().get(i).c();
                row.add(!c.isEmpty() ? c : m.days().get(i).wk() ? "WO" : "");
            }
            row.add(r.P());
            row.add(r.A());
            row.add(r.L());
            rows.add(row);
        }
        return new Export("jurisone-attendance-" + p.ym() + ".csv", Csv.toCsv(header, rows));
    }
}
