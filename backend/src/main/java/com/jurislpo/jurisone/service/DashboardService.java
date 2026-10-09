package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.service.AttendanceService.AttEmployee;
import com.jurislpo.jurisone.service.AttendanceService.DayView;
import com.jurislpo.jurisone.common.ListParams;
import com.jurislpo.jurisone.engine.AttendanceEngine;
import com.jurislpo.jurisone.engine.AttendanceEngine.Punch;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.engine.Shifts;
import com.jurislpo.jurisone.entity.DayStatus;
import com.jurislpo.jurisone.entity.PunchKind;
import com.jurislpo.jurisone.security.CurrentUser;
import java.sql.Date;
import java.text.Collator;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Home, company overview and My team — src/lib/dashboard.ts, src/lib/team-status.ts and the home/overview/team pages. */
@Service
public class DashboardService {

    /** {@code String.prototype.localeCompare} (Node's default locale). */
    static final Comparator<String> LOCALE = Collator.getInstance(Locale.US)::compare;

    private final JdbcTemplate jdbc;
    private final AttendanceService att;
    private final MyAttendanceService my;
    private final PayrollService payroll;

    public DashboardService(JdbcTemplate jdbc, AttendanceService att, MyAttendanceService my, PayrollService payroll) {
        this.jdbc = jdbc;
        this.att = att;
        this.my = my;
        this.payroll = payroll;
    }

    // ───────────────────────── "right now" status ─────────────────────────

    public record TodayStatus(String label, String tone) {}

    /**
     * "Right now" status for each employee, on their own current work date and time zone:
     * in since … / checked out / on leave / holiday / weekly off / absent / not in yet. Untracked employees are absent from the map.
     */
    public Map<UUID, TodayStatus> todayStatuses(List<UUID> ids, Instant now) {
        Map<UUID, TodayStatus> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        List<AttEmployee> emps = att.loadAttEmployees("e.id = ANY (?)", (Object) ids.toArray(UUID[]::new));
        if (emps.isEmpty()) return out;
        Map<UUID, LocalDate> wds = new HashMap<>();
        for (AttEmployee e : emps) wds.put(e.id(), att.currentWorkDate(e, now));
        LocalDate min = wds.values().stream().min(Comparator.naturalOrder()).orElseThrow();
        LocalDate max = wds.values().stream().max(Comparator.naturalOrder()).orElseThrow();
        Map<UUID, List<DayView>> days = att.attendanceFor(emps, min, max, now);
        for (AttEmployee e : emps) {
            LocalDate wd = wds.get(e.id());
            DayView d = days.get(e.id()).stream().filter(x -> x.r.date.equals(wd)).findFirst().orElseThrow();
            TodayStatus s;
            if (d.r.openSince != null) s = new TodayStatus("In since " + AttendanceService.fmtTime(d.r.openSince, e.tz()), "ok");
            else if (d.r.status == DayStatus.LEAVE) s = new TodayStatus("On leave", "info");
            else if (d.r.status == DayStatus.HOLIDAY) s = new TodayStatus(d.r.label, "gold");
            else if (d.r.status == DayStatus.WEEKLY_OFF) s = new TodayStatus("Weekly off", "neutral");
            else if (!d.punches.isEmpty()) s = new TodayStatus("Checked out", "neutral");
            else if (d.r.status == DayStatus.ABSENT) s = new TodayStatus("Absent", "warn");
            else s = new TodayStatus("Not in yet", "warn");
            out.put(e.id(), s);
        }
        return out;
    }

    // ───────────────────────── payroll state / queues ─────────────────────────

    public record PayrollState(String month, String status, String lastClosed) {}

    /** The payroll month Finance is working on (first open month) and the latest closed one. */
    public PayrollState payrollState() {
        String dflt = payroll.payrollMonths(Format.todayIn("Asia/Kolkata")).dflt();
        List<Map<String, Object>> run = jdbc.queryForList("SELECT status::text AS status, approved_at FROM payroll_runs WHERE month = ?", dflt);
        List<String> last = jdbc.queryForList("SELECT month FROM payroll_runs WHERE status = 'CLOSED' ORDER BY month DESC LIMIT 1", String.class);
        String status = !run.isEmpty() && "CLOSED".equals(run.get(0).get("status")) ? "Closed"
                : !run.isEmpty() && run.get(0).get("approved_at") != null ? "Approved" : "In review";
        return new PayrollState(dflt, status, last.isEmpty() ? null : last.get(0));
    }

    public record Pending(long leave, long reg, long proofs) {}

    /** Pending approval queues, everyone's (Overview) or excluding the viewer's own (Home). */
    public Pending pendingCounts(UUID excludeId) {
        String notMine = excludeId == null ? "" : " AND employee_id <> ?";
        Object[] args = excludeId == null ? new Object[0] : new Object[] {excludeId};
        return new Pending(
                jdbc.queryForObject("SELECT count(*) FROM leave_requests WHERE status = 'PENDING'" + notMine, Long.class, args),
                jdbc.queryForObject("SELECT count(*) FROM regularization_requests WHERE status = 'PENDING'" + notMine, Long.class, args),
                jdbc.queryForObject("SELECT count(*) FROM declaration_items WHERE status = 'PENDING'" + notMine, Long.class, args));
    }

    // ───────────────────────── overview ─────────────────────────

    public record DeptCount(String name, int n) {}

    /** Company overview for Admin (REQUIREMENTS §5.12). */
    public Map<String, Object> overviewData() {
        record Cur(UUID id, String region, boolean portalAccess, UUID departmentId) {}
        List<Cur> current = jdbc.query("SELECT id, region::text, portal_access, department_id FROM employees WHERE status <> 'EXITED'",
                (rs, i) -> new Cur((UUID) rs.getObject(1), rs.getString(2), rs.getBoolean(3), (UUID) rs.getObject(4)));
        record Dept(UUID id, String name) {}
        List<Dept> depts = jdbc.query("SELECT id, name FROM departments WHERE archived_at IS NULL ORDER BY name ASC", (rs, i) -> new Dept((UUID) rs.getObject(1), rs.getString(2)));
        Pending pending = pendingCounts(null);
        PayrollState pay = payrollState();
        Map<UUID, TodayStatus> statuses = todayStatuses(current.stream().map(Cur::id).toList(), Instant.now());
        long inNow = statuses.values().stream().filter(s -> s.label().startsWith("In since")).count();

        List<PayrollService.PayView> rows = payroll.payrollView(pay.month()).rows();
        LocalDate lastDay = java.time.YearMonth.parse(pay.month()).atEndOfMonth();
        long noStructure = "Closed".equals(pay.status()) ? 0 : jdbc.queryForObject("""
            SELECT count(*) FROM employees e WHERE e.region = 'IN' AND e.status <> 'EXITED' AND e.date_of_joining <= ?
              AND NOT EXISTS (SELECT 1 FROM salary_structures s WHERE s.employee_id = e.id AND s.effective_from <= ?)""", Long.class, Date.valueOf(lastDay), Date.valueOf(lastDay));

        List<DeptCount> byDept = new ArrayList<>(depts.stream().map(d -> new DeptCount(d.name(), (int) current.stream().filter(e -> d.id().equals(e.departmentId())).count()))
                .filter(d -> d.n() > 0).toList());
        byDept.sort(Comparator.comparingInt(DeptCount::n).reversed().thenComparing(DeptCount::name, LOCALE));
        int unassigned = (int) current.stream().filter(e -> e.departmentId() == null).count();
        if (unassigned > 0) byDept.add(new DeptCount("No department", unassigned));

        Map<String, Object> payOut = new LinkedHashMap<>();
        payOut.put("month", pay.month());
        payOut.put("status", pay.status());
        payOut.put("lastClosed", pay.lastClosed());
        payOut.put("net", rows.stream().mapToLong(PayrollService.PayView::net).sum());
        payOut.put("people", rows.size());
        payOut.put("noStructure", noStructure);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("head", current.size());
        out.put("india", current.stream().filter(e -> "IN".equals(e.region())).count());
        out.put("us", current.stream().filter(e -> "US".equals(e.region())).count());
        out.put("accessOff", current.stream().filter(e -> !e.portalAccess()).count());
        out.put("deptCount", depts.size());
        out.put("inNow", inNow);
        out.put("pending", pending);
        out.put("payroll", payOut);
        out.put("byDept", byDept);
        return out;
    }

    // ───────────────────────── home ─────────────────────────

    public record MyLeave(String id, String type, String from, String to, double days, String status) {}

    public record TeamCard(String id, String name, String title, TodayStatus st) {}

    /** Permission-driven cards on Home (REQUIREMENTS §5.12). Salary is never shown here. */
    public Map<String, Object> homeCards(CurrentUser u) {
        boolean finance = u.can("pay.view");
        // Finance approves regularizations too, but sees them on its payroll card instead
        boolean approver = u.can("appr.leave") || (u.can("appr.reg") && !finance);
        boolean showMyLeave = !finance && !u.can("appr.reg") && !u.hasReports();

        List<MyLeave> myLeaves = showMyLeave ? jdbc.query("""
            SELECT l.id::text, t.name, l.from_date, l.to_date, l.days, l.status::text FROM leave_requests l JOIN leave_types t ON t.code = l.leave_type
            WHERE l.employee_id = ? ORDER BY l.applied_at DESC LIMIT 3""",
                (rs, i) -> new MyLeave(rs.getString(1), rs.getString(2), rs.getDate(3).toLocalDate().toString(), rs.getDate(4).toLocalDate().toString(),
                        rs.getBigDecimal(5).doubleValue(), rs.getString(6)), u.id()) : List.of();
        record Member(UUID id, String name, String title) {}
        List<Member> team = u.hasReports() ? jdbc.query("SELECT id, full_name, designation FROM employees WHERE reporting_manager_id = ? AND status <> 'EXITED' ORDER BY full_name ASC",
                (rs, i) -> new Member((UUID) rs.getObject(1), rs.getString(2), rs.getString(3)), u.id()) : List.of();
        Pending pending = approver || finance ? pendingCounts(u.id()) : null;
        PayrollState pay = finance || "IN".equals(u.region()) ? payrollState() : null;
        List<String> slip = "IN".equals(u.region()) ? jdbc.queryForList("""
            SELECT r.month FROM payslips p JOIN payroll_runs r ON r.id = p.run_id WHERE p.employee_id = ? AND r.status = 'CLOSED' ORDER BY r.month DESC LIMIT 1""",
                String.class, u.id()) : List.of();
        Map<UUID, TodayStatus> statuses = team.isEmpty() ? Map.of() : todayStatuses(team.stream().map(Member::id).toList(), Instant.now());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("myLeaves", showMyLeave ? myLeaves : null);
        out.put("team", u.hasReports() ? team.stream().map(m -> new TeamCard(m.id().toString(), m.name(), m.title(),
                statuses.getOrDefault(m.id(), new TodayStatus("Not tracked", "neutral")))).toList() : null);
        if (approver && pending != null) {
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("leaveToo", u.can("appr.leave"));
            a.put("count", (u.can("appr.leave") ? pending.leave() : 0) + (u.can("appr.reg") ? pending.reg() : 0));
            out.put("approvals", a);
        } else out.put("approvals", null);
        if (finance && pay != null && pending != null) {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("month", pay.month());
            p.put("status", pay.status());
            p.put("proofs", u.can("proofs.verify") ? pending.proofs() : null);
            p.put("regs", pending.reg());
            out.put("payroll", p);
        } else out.put("payroll", null);
        if ("IN".equals(u.region()) && pay != null) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("month", slip.isEmpty() ? pay.month() : slip.get(0));
            s.put("ready", !slip.isEmpty());
            out.put("payslip", s);
        } else out.put("payslip", null);
        return out;
    }

    public record LogLine(String t, String l, String d) {}

    public record HomeDay(String date, String status, String label, int workedMinutes, boolean future, int punches) {}

    /** Intl en-US short time-zone name, with India shown as "IST". */
    static String tzAbbr(String tz, Instant now) {
        String z = DateTimeFormatter.ofPattern("zzz", Locale.US).format(now.atZone(ZoneId.of(tz)));
        return "GMT+5:30".equals(z) ? "IST" : z;
    }

    /** Everything Home needs; the page lays it out (heatmap, KPIs and bars are derived from {@code days} as before). */
    public Map<String, Object> home(CurrentUser u) {
        Instant now = Instant.now();
        AttendanceService.Pol policy = att.policy();
        List<AttEmployee> found = att.loadAttEmployees("e.id = ?", u.id());
        Map<String, Object> out = new LinkedHashMap<>();
        if (found.isEmpty()) {
            out.put("tracked", false);
            return out;
        }
        AttEmployee emp = found.get(0);
        String tz = emp.tz();
        LocalDate wd = att.currentWorkDate(emp, now);
        LocalDate monday = wd.minusDays((AttendanceEngine.dowOf(wd) + 6) % 7);
        LocalDate heatStart = monday.minusDays(15 * 7);
        LocalDate monthStart = wd.withDayOfMonth(1);
        LocalDate from = heatStart.isBefore(monthStart) ? heatStart : monthStart;
        List<DayView> days = att.attendanceFor(List.of(emp), from, monday.plusDays(6), now).get(emp.id());
        DayView today = days.stream().filter(d -> d.r.date.equals(wd)).findFirst().orElseThrow();

        List<Punch> sorted = new ArrayList<>(today.punches);
        sorted.sort(Comparator.comparing(Punch::at));
        int closed = AttendanceEngine.workedMinutes(today.punches).minutes();
        boolean on = !sorted.isEmpty() && sorted.get(sorted.size() - 1).kind() == PunchKind.IN;
        String start = emp.shift().startLocal(), end = emp.shift().endLocal();
        long s = AttendanceEngine.atLocal(wd, start, tz).toEpochMilli();
        long e = AttendanceEngine.atLocal(end.compareTo(start) <= 0 ? wd.plusDays(1) : wd, end, tz).toEpochMilli();
        List<String> ticks = new ArrayList<>();
        for (int i = 0; i < 4; i++) ticks.add(AttendanceService.fmtTime(Instant.ofEpochMilli(s + (long) Math.floor((e - s) * (double) i / 3)), tz));
        String abbr = tzAbbr(tz, now);
        String shiftText = ("IN_NIGHT".equals(emp.shift().code()) ? "Night" : "Day") + " shift · " + AttendanceService.fmtTime(AttendanceEngine.atLocal(wd, start, tz), tz)
                + " – " + AttendanceService.fmtTime(AttendanceEngine.atLocal(wd, end, tz), tz) + " " + abbr;
        List<LogLine> log = sorted.stream().map(p -> new LogLine(AttendanceService.fmtTime(p.at(), tz), p.kind() == PunchKind.IN ? "Checked in" : "Checked out",
                "WEB".equals(p.source()) ? "Web" : "MOBILE".equals(p.source()) ? "Mobile" : "Regularization")).toList();

        Double leaveAvail = jdbc.queryForObject("SELECT COALESCE(sum(delta), 0)::float8 FROM leave_ledger WHERE employee_id = ? AND leave_type IN ('CL', 'EL')", Double.class, u.id());

        out.put("tracked", true);
        out.put("wd", wd.toString());
        out.put("on", on);
        out.put("openSince", today.r.openSince == null ? null : Format.jsIso(today.r.openSince));
        out.put("closedMinutes", closed);
        out.put("shiftText", shiftText);
        out.put("ticks", ticks);
        out.put("targetHours", policy.shiftTargetHours());
        out.put("log", log);
        out.put("days", days.stream().map(d -> new HomeDay(d.r.date.toString(), d.r.status.name(), d.r.label, d.r.workedMinutes, d.r.future, d.punches.size())).toList());
        out.put("leaveAvail", leaveAvail);
        out.put("hols", my.upcomingHolidays(emp.region(), wd, 4));
        out.put("cards", homeCards(u));
        return out;
    }

    // ───────────────────────── my team ─────────────────────────

    public record TeamRow(String id, String code, String fullName, String designation, String shift, TodayStatus st) {}

    public record Away(String id, String fullName, List<String> cells) {}

    static final List<String> TEAM_SORTS = List.of("name", "id", "title", "st");

    /** Anyone with active direct reports. Read-only — approvals stay with HR (REQUIREMENTS §5.13). */
    public Map<String, Object> team(CurrentUser u, Map<String, String> sp) {
        ListParams ls = ListParams.of(sp, TEAM_SORTS, "name");
        record M(UUID id, String code, String fullName, String designation, String region, String shift) {}
        List<M> team = jdbc.query("""
            SELECT e.id, e.code, e.full_name, e.designation, e.region::text, s.code AS s_code, s.start_local, s.end_local
            FROM employees e LEFT JOIN shifts s ON s.id = e.shift_id WHERE e.reporting_manager_id = ? AND e.status <> 'EXITED'""",
                (rs, i) -> new M((UUID) rs.getObject(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        Shifts.label(rs.getString(6), rs.getString(7), rs.getString(8))), u.id());
        Map<UUID, TodayStatus> status = todayStatuses(team.stream().map(M::id).toList(), Instant.now());

        // Who's away · this week (Mon–Fri) in the manager's zone
        LocalDate t = Format.todayIn(u.timeZone());
        LocalDate mon = t.minusDays((AttendanceEngine.dowOf(t) + 6) % 7);
        List<LocalDate> days = new ArrayList<>();
        for (int i = 0; i < 5; i++) days.add(mon.plusDays(i));
        record L(UUID employeeId, LocalDate from, LocalDate to, String status) {}
        List<L> leaves = team.isEmpty() ? List.of() : jdbc.query("""
            SELECT employee_id, from_date, to_date, status::text FROM leave_requests
            WHERE employee_id = ANY (?) AND status IN ('APPROVED', 'PENDING') AND from_date <= ? AND to_date >= ?""",
                (rs, i) -> new L((UUID) rs.getObject(1), rs.getDate(2).toLocalDate(), rs.getDate(3).toLocalDate(), rs.getString(4)),
                team.stream().map(M::id).toArray(UUID[]::new), Date.valueOf(days.get(4)), Date.valueOf(days.get(0)));
        record H(LocalDate date, String region) {}
        List<H> holidays = jdbc.query("SELECT date, region::text FROM holidays WHERE date >= ? AND date <= ?",
                (rs, i) -> new H(rs.getDate(1).toLocalDate(), rs.getString(2)), Date.valueOf(days.get(0)), Date.valueOf(days.get(4)));

        String q = ls.q().toLowerCase();
        List<TeamRow> all = team.stream().map(m -> new TeamRow(m.id().toString(), m.code(), m.fullName(), m.designation(), m.shift(), status.get(m.id()))).toList();
        List<TeamRow> rows = new ArrayList<>(all.stream().filter(m -> q.isEmpty() || (m.fullName() + " " + m.code() + " " + m.designation()).toLowerCase().contains(q)).toList());
        Map<String, Function<TeamRow, String>> keys = Map.of("name", TeamRow::fullName, "id", TeamRow::code, "title", TeamRow::designation, "st", r -> r.st().label());
        Comparator<TeamRow> cmp = Comparator.comparing(keys.get(ls.sort()), LOCALE);
        rows.sort(ls.asc() ? cmp : cmp.reversed());

        List<Away> away = team.stream().map(m -> new Away(m.id().toString(), m.fullName(), days.stream().map(d -> {
            if (holidays.stream().anyMatch(h -> h.date().equals(d) && ("ALL".equals(h.region()) || h.region().equals(m.region())))) return "hol";
            L l = leaves.stream().filter(x -> x.employeeId().equals(m.id()) && !x.from().isAfter(d) && !x.to().isBefore(d)).findFirst().orElse(null);
            return l == null ? "" : "APPROVED".equals(l.status()) ? "lv" : "pend";
        }).toList())).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("size", team.size());
        out.put("counts", Map.of(
                "in", all.stream().filter(m -> m.st() != null && m.st().label().startsWith("In since")).count(),
                "leave", all.stream().filter(m -> m.st() != null && "On leave".equals(m.st().label())).count(),
                "notIn", all.stream().filter(m -> m.st() != null && "Not in yet".equals(m.st().label())).count()));
        out.put("rows", rows);
        out.put("days", days.stream().map(LocalDate::toString).toList());
        out.put("away", away);
        return out;
    }

}
