package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.service.AttendanceService.AttEmployee;
import com.jurislpo.jurisone.service.AttendanceService.DayView;
import com.jurislpo.jurisone.common.ListParams;
import com.jurislpo.jurisone.engine.AttendanceEngine;
import com.jurislpo.jurisone.engine.AttendanceEngine.Punch;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.entity.DayStatus;
import com.jurislpo.jurisone.entity.PunchKind;
import com.jurislpo.jurisone.repository.DashboardRepository;
import com.jurislpo.jurisone.repository.DashboardRepository.CurrentEmployee;
import com.jurislpo.jurisone.repository.DashboardRepository.Dept;
import com.jurislpo.jurisone.repository.DashboardRepository.Holiday;
import com.jurislpo.jurisone.repository.DashboardRepository.Member;
import com.jurislpo.jurisone.repository.DashboardRepository.TeamLeave;
import com.jurislpo.jurisone.repository.DashboardRepository.TeamMember;
import com.jurislpo.jurisone.security.CurrentUser;
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
import org.springframework.stereotype.Service;

/** Home, company overview and My team. */
@Service
public class DashboardService {

    /** {@code String.prototype.localeCompare} (Node's default locale). */
    static final Comparator<String> LOCALE = Collator.getInstance(Locale.US)::compare;

    private final DashboardRepository repo;
    private final AttendanceService att;
    private final MyAttendanceService my;
    private final PayrollService payroll;

    public DashboardService(DashboardRepository repo, AttendanceService att, MyAttendanceService my, PayrollService payroll) {
        this.repo = repo;
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
        List<Map<String, Object>> run = repo.runState(dflt);
        List<String> last = repo.lastClosedMonth();
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
                repo.countPending("leave_requests", notMine, args),
                repo.countPending("regularization_requests", notMine, args),
                repo.countPending("declaration_items", notMine, args));
    }

    // ───────────────────────── overview ─────────────────────────

    public record DeptCount(String name, int n) {}

    /** Company overview for Admin (REQUIREMENTS §5.12). */
    public Map<String, Object> overviewData() {
        List<CurrentEmployee> current = repo.currentEmployees();
        List<Dept> depts = repo.activeDepartments();
        Pending pending = pendingCounts(null);
        PayrollState pay = payrollState();
        Map<UUID, TodayStatus> statuses = todayStatuses(current.stream().map(CurrentEmployee::id).toList(), Instant.now());
        long inNow = statuses.values().stream().filter(s -> s.label().startsWith("In since")).count();

        List<PayrollService.PayView> rows = payroll.payrollView(pay.month()).rows();
        LocalDate lastDay = java.time.YearMonth.parse(pay.month()).atEndOfMonth();
        long noStructure = "Closed".equals(pay.status()) ? 0 : repo.countIndiaWithoutStructure(lastDay);

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

        List<MyLeave> myLeaves = showMyLeave ? repo.recentLeaves(u.id()) : List.of();
        List<Member> team = u.hasReports() ? repo.directReports(u.id()) : List.of();
        Pending pending = approver || finance ? pendingCounts(u.id()) : null;
        PayrollState pay = finance || "IN".equals(u.region()) ? payrollState() : null;
        List<String> slip = "IN".equals(u.region()) ? repo.lastClosedPayslipMonth(u.id()) : List.of();
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

        Double leaveAvail = repo.leaveAvailable(u.id());

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

    public record TeamRow(String id, String code, String fullName, String designation, String shift, TodayStatus st, Long photo) {}

    public record Away(String id, String fullName, List<String> cells) {}

    static final List<String> TEAM_SORTS = List.of("name", "id", "title", "st");

    /** Anyone with active direct reports. Read-only — approvals stay with HR (REQUIREMENTS §5.13). */
    public Map<String, Object> team(CurrentUser u, Map<String, String> sp) {
        ListParams ls = ListParams.of(sp, TEAM_SORTS, "name");
        List<TeamMember> team = repo.teamMembers(u.id());
        Map<UUID, TodayStatus> status = todayStatuses(team.stream().map(TeamMember::id).toList(), Instant.now());

        // Who's away · this week (Mon–Fri) in the manager's zone
        LocalDate t = Format.todayIn(u.timeZone());
        LocalDate mon = t.minusDays((AttendanceEngine.dowOf(t) + 6) % 7);
        List<LocalDate> days = new ArrayList<>();
        for (int i = 0; i < 5; i++) days.add(mon.plusDays(i));
        List<TeamLeave> leaves = team.isEmpty() ? List.of() : repo.leavesOverlapping(team.stream().map(TeamMember::id).toArray(UUID[]::new), days.get(0), days.get(4));
        List<Holiday> holidays = repo.holidays(days.get(0), days.get(4));

        String q = ls.q().toLowerCase();
        List<TeamRow> all = team.stream().map(m -> new TeamRow(m.id().toString(), m.code(), m.fullName(), m.designation(), m.shift(), status.get(m.id()), m.photo())).toList();
        List<TeamRow> rows = new ArrayList<>(all.stream().filter(m -> q.isEmpty() || (m.fullName() + " " + m.code() + " " + m.designation()).toLowerCase().contains(q)).toList());
        Map<String, Function<TeamRow, String>> keys = Map.of("name", TeamRow::fullName, "id", TeamRow::code, "title", TeamRow::designation, "st", r -> r.st().label());
        Comparator<TeamRow> cmp = Comparator.comparing(keys.get(ls.sort()), LOCALE);
        rows.sort(ls.asc() ? cmp : cmp.reversed());

        List<Away> away = team.stream().map(m -> new Away(m.id().toString(), m.fullName(), days.stream().map(d -> {
            if (holidays.stream().anyMatch(h -> h.date().equals(d) && ("ALL".equals(h.region()) || h.region().equals(m.region())))) return "hol";
            TeamLeave l = leaves.stream().filter(x -> x.employeeId().equals(m.id()) && !x.from().isAfter(d) && !x.to().isBefore(d)).findFirst().orElse(null);
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
