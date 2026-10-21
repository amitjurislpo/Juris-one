package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.service.AttendanceService.AttendanceEmployee;
import com.jurislpo.jurisone.service.AttendanceService.DayView;
import com.jurislpo.jurisone.common.ListParams;
import com.jurislpo.jurisone.engine.AttendanceEngine;
import com.jurislpo.jurisone.engine.AttendanceEngine.Punch;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.entity.DayStatus;
import com.jurislpo.jurisone.entity.PunchKind;
import com.jurislpo.jurisone.repository.DashboardRepository;
import com.jurislpo.jurisone.repository.DashboardRepository.CurrentEmployee;
import com.jurislpo.jurisone.repository.DashboardRepository.DepartmentRow;
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

    public DashboardService(DashboardRepository repo, AttendanceService attendance, MyAttendanceService myAttendanceService, PayrollService payroll) {
        this.repo = repo;
        this.att = attendance;
        this.my = myAttendanceService;
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
        List<AttendanceEmployee> employees = att.loadAttendanceEmployees("e.id = ANY (?)", (Object) ids.toArray(UUID[]::new));
        if (employees.isEmpty()) return out;
        Map<UUID, LocalDate> wds = new HashMap<>();
        for (AttendanceEmployee e : employees) wds.put(e.id(), att.currentWorkDate(e, now));
        LocalDate min = wds.values().stream().min(Comparator.naturalOrder()).orElseThrow();
        LocalDate max = wds.values().stream().max(Comparator.naturalOrder()).orElseThrow();
        Map<UUID, List<DayView>> days = att.attendanceFor(employees, min, max, now);
        for (AttendanceEmployee employee : employees) {
            LocalDate workDate = wds.get(employee.id());
            DayView dayView = days.get(employee.id()).stream().filter(x -> x.r.date.equals(workDate)).findFirst().orElseThrow();
            TodayStatus todayStatus;
            if (dayView.r.openSince != null) todayStatus = new TodayStatus("In since " + AttendanceService.formatTime(dayView.r.openSince, employee.timeZone()), "ok");
            else if (dayView.r.status == DayStatus.LEAVE) todayStatus = new TodayStatus("On leave", "info");
            else if (dayView.r.status == DayStatus.HOLIDAY) todayStatus = new TodayStatus(dayView.r.label, "gold");
            else if (dayView.r.status == DayStatus.WEEKLY_OFF) todayStatus = new TodayStatus("Weekly off", "neutral");
            else if (!dayView.punches.isEmpty()) todayStatus = new TodayStatus("Checked out", "neutral");
            else if (dayView.r.status == DayStatus.ABSENT) todayStatus = new TodayStatus("Absent", "warn");
            else todayStatus = new TodayStatus("Not in yet", "warn");
            out.put(employee.id(), todayStatus);
        }
        return out;
    }

    // ───────────────────────── payroll state / queues ─────────────────────────

    public record PayrollState(String month, String status, String lastClosed) {}

    /** The payroll month Finance is working on (first open month) and the latest closed one. */
    public PayrollState payrollState() {
        String defaultValue = payroll.payrollMonths(Format.todayIn("Asia/Kolkata")).defaultMonth();
        List<Map<String, Object>> run = repo.runState(defaultValue);
        List<String> last = repo.lastClosedMonth();
        String status = !run.isEmpty() && "CLOSED".equals(run.get(0).get("status")) ? "Closed"
                : !run.isEmpty() && run.get(0).get("approved_at") != null ? "Approved" : "In review";
        return new PayrollState(defaultValue, status, last.isEmpty() ? null : last.get(0));
    }

    public record Pending(long leave, long regularizations, long proofs) {}

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

    public record DepartmentCount(String name, int count) {}

    /** Company overview for Admin (REQUIREMENTS §5.12). */
    public Map<String, Object> overviewData() {
        List<CurrentEmployee> current = repo.currentEmployees();
        List<DepartmentRow> departments = repo.activeDepartments();
        Pending pending = pendingCounts(null);
        PayrollState pay = payrollState();
        Map<UUID, TodayStatus> statuses = todayStatuses(current.stream().map(CurrentEmployee::id).toList(), Instant.now());
        long inNow = statuses.values().stream().filter(s -> s.label().startsWith("In since")).count();

        List<PayrollService.PayView> rows = payroll.payrollView(pay.month()).rows();
        LocalDate lastDay = java.time.YearMonth.parse(pay.month()).atEndOfMonth();
        long noStructure = "Closed".equals(pay.status()) ? 0 : repo.countIndiaWithoutStructure(lastDay);

        List<DepartmentCount> byDepartment = new ArrayList<>(departments.stream().map(d -> new DepartmentCount(d.name(), (int) current.stream().filter(e -> d.id().equals(e.departmentId())).count()))
                .filter(d -> d.count() > 0).toList());
        byDepartment.sort(Comparator.comparingInt(DepartmentCount::count).reversed().thenComparing(DepartmentCount::name, LOCALE));
        int unassigned = (int) current.stream().filter(e -> e.departmentId() == null).count();
        if (unassigned > 0) byDepartment.add(new DepartmentCount("No department", unassigned));

        Map<String, Object> payOut = new LinkedHashMap<>();
        payOut.put("month", pay.month());
        payOut.put("status", pay.status());
        payOut.put("lastClosed", pay.lastClosed());
        payOut.put("net", rows.stream().mapToLong(PayrollService.PayView::net).sum());
        payOut.put("people", rows.size());
        payOut.put("noStructure", noStructure);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("headcount", current.size());
        out.put("india", current.stream().filter(e -> "IN".equals(e.region())).count());
        out.put("us", current.stream().filter(e -> "US".equals(e.region())).count());
        out.put("accessOff", current.stream().filter(e -> !e.portalAccess()).count());
        out.put("departmentCount", departments.size());
        out.put("checkedInNow", inNow);
        out.put("pending", pending);
        out.put("payroll", payOut);
        out.put("byDepartment", byDepartment);
        return out;
    }

    // ───────────────────────── home ─────────────────────────

    public record MyLeave(String id, String type, String from, String to, double days, String status) {}

    public record TeamCard(String id, String name, String title, TodayStatus status) {}

    /** Permission-driven cards on Home (REQUIREMENTS §5.12). Salary is never shown here. */
    public Map<String, Object> homeCards(CurrentUser user) {
        boolean finance = user.can("pay.view");
        // Finance approves regularizations too, but sees them on its payroll card instead
        boolean approver = user.can("appr.leave") || (user.can("appr.reg") && !finance);
        boolean showMyLeave = !finance && !user.can("appr.reg") && !user.hasReports();

        List<MyLeave> myLeaves = showMyLeave ? repo.recentLeaves(user.id()) : List.of();
        List<Member> team = user.hasReports() ? repo.directReports(user.id()) : List.of();
        Pending pending = approver || finance ? pendingCounts(user.id()) : null;
        PayrollState pay = finance || "IN".equals(user.region()) ? payrollState() : null;
        List<String> slip = "IN".equals(user.region()) ? repo.lastClosedPayslipMonth(user.id()) : List.of();
        Map<UUID, TodayStatus> statuses = team.isEmpty() ? Map.of() : todayStatuses(team.stream().map(Member::id).toList(), Instant.now());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("myLeaves", showMyLeave ? myLeaves : null);
        out.put("team", user.hasReports() ? team.stream().map(m -> new TeamCard(m.id().toString(), m.name(), m.title(),
                statuses.getOrDefault(m.id(), new TodayStatus("Not tracked", "neutral")))).toList() : null);
        if (approver && pending != null) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("leaveToo", user.can("appr.leave"));
            view.put("count", (user.can("appr.leave") ? pending.leave() : 0) + (user.can("appr.reg") ? pending.regularizations() : 0));
            out.put("approvals", view);
        } else out.put("approvals", null);
        if (finance && pay != null && pending != null) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("month", pay.month());
            view.put("status", pay.status());
            view.put("proofs", user.can("proofs.verify") ? pending.proofs() : null);
            view.put("regularizations", pending.regularizations());
            out.put("payroll", view);
        } else out.put("payroll", null);
        if ("IN".equals(user.region()) && pay != null) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("month", slip.isEmpty() ? pay.month() : slip.get(0));
            view.put("ready", !slip.isEmpty());
            out.put("payslip", view);
        } else out.put("payslip", null);
        return out;
    }

    public record PunchLogLine(String time, String label, String detail) {}

    public record HomeDay(String date, String status, String label, int workedMinutes, boolean future, int punches) {}

    /** Intl en-US short time-zone name, with India shown as "IST". */
    static String timeZoneAbbreviation(String timeZone, Instant now) {
        String z = DateTimeFormatter.ofPattern("zzz", Locale.US).format(now.atZone(ZoneId.of(timeZone)));
        return "GMT+5:30".equals(z) ? "IST" : z;
    }

    /** Everything Home needs; the page lays it out (heatmap, KPIs and bars are derived from {@code days} as before). */
    public Map<String, Object> home(CurrentUser user) {
        Instant now = Instant.now();
        AttendanceService.ResolvedAttendancePolicy policy = att.policy();
        List<AttendanceEmployee> found = att.loadAttendanceEmployees("e.id = ?", user.id());
        Map<String, Object> out = new LinkedHashMap<>();
        if (found.isEmpty()) {
            out.put("tracked", false);
            return out;
        }
        AttendanceEmployee employee = found.get(0);
        String timeZone = employee.timeZone();
        LocalDate workDate = att.currentWorkDate(employee, now);
        LocalDate monday = workDate.minusDays((AttendanceEngine.dowOf(workDate) + 6) % 7);
        LocalDate heatStart = monday.minusDays(15 * 7);
        LocalDate monthStart = workDate.withDayOfMonth(1);
        LocalDate from = heatStart.isBefore(monthStart) ? heatStart : monthStart;
        List<DayView> days = att.attendanceFor(List.of(employee), from, monday.plusDays(6), now).get(employee.id());
        DayView today = days.stream().filter(d -> d.r.date.equals(workDate)).findFirst().orElseThrow();

        List<Punch> sorted = new ArrayList<>(today.punches);
        sorted.sort(Comparator.comparing(Punch::at));
        int closed = AttendanceEngine.workedMinutes(today.punches).minutes();
        boolean on = !sorted.isEmpty() && sorted.get(sorted.size() - 1).kind() == PunchKind.IN;
        String start = employee.shift().startLocal(), end = employee.shift().endLocal();
        long s = AttendanceEngine.atLocal(workDate, start, timeZone).toEpochMilli();
        long e = AttendanceEngine.atLocal(end.compareTo(start) <= 0 ? workDate.plusDays(1) : workDate, end, timeZone).toEpochMilli();
        List<String> ticks = new ArrayList<>();
        for (int i = 0; i < 4; i++) ticks.add(AttendanceService.formatTime(Instant.ofEpochMilli(s + (long) Math.floor((e - s) * (double) i / 3)), timeZone));
        String abbr = timeZoneAbbreviation(timeZone, now);
        String shiftText = ("IN_NIGHT".equals(employee.shift().code()) ? "Night" : "Day") + " shift · " + AttendanceService.formatTime(AttendanceEngine.atLocal(workDate, start, timeZone), timeZone)
                + " – " + AttendanceService.formatTime(AttendanceEngine.atLocal(workDate, end, timeZone), timeZone) + " " + abbr;
        List<PunchLogLine> log = sorted.stream().map(p -> new PunchLogLine(AttendanceService.formatTime(p.at(), timeZone), p.kind() == PunchKind.IN ? "Checked in" : "Checked out",
                "WEB".equals(p.source()) ? "Web" : "MOBILE".equals(p.source()) ? "Mobile" : "Regularization")).toList();

        Double leaveAvail = repo.leaveAvailable(user.id());

        out.put("tracked", true);
        out.put("workDate", workDate.toString());
        out.put("checkedIn", on);
        out.put("openSince", today.r.openSince == null ? null : Format.isoTimestamp(today.r.openSince));
        out.put("closedMinutes", closed);
        out.put("shiftText", shiftText);
        out.put("ticks", ticks);
        out.put("targetHours", policy.shiftTargetHours());
        out.put("log", log);
        out.put("days", days.stream().map(d -> new HomeDay(d.r.date.toString(), d.r.status.name(), d.r.label, d.r.workedMinutes, d.r.future, d.punches.size())).toList());
        out.put("leaveAvailable", leaveAvail);
        out.put("holidays", my.upcomingHolidays(employee.region(), workDate, 4));
        out.put("cards", homeCards(user));
        return out;
    }

    // ───────────────────────── my team ─────────────────────────

    public record TeamRow(String id, String code, String fullName, String designation, String shift, TodayStatus status, Long photo) {}

    public record Away(String id, String fullName, List<String> cells) {}

    static final List<String> TEAM_SORTS = List.of("name", "id", "title", "status");

    /** Anyone with active direct reports. Read-only — approvals stay with HR (REQUIREMENTS §5.13). */
    public Map<String, Object> team(CurrentUser user, Map<String, String> queryParams) {
        ListParams listParams = ListParams.of(queryParams, TEAM_SORTS, "name");
        List<TeamMember> team = repo.teamMembers(user.id());
        Map<UUID, TodayStatus> status = todayStatuses(team.stream().map(TeamMember::id).toList(), Instant.now());

        // Who's away · this week (Mon–Fri) in the manager's zone
        LocalDate t = Format.todayIn(user.timeZone());
        LocalDate mon = t.minusDays((AttendanceEngine.dowOf(t) + 6) % 7);
        List<LocalDate> days = new ArrayList<>();
        for (int i = 0; i < 5; i++) days.add(mon.plusDays(i));
        List<TeamLeave> leaves = team.isEmpty() ? List.of() : repo.leavesOverlapping(team.stream().map(TeamMember::id).toArray(UUID[]::new), days.get(0), days.get(4));
        List<Holiday> holidays = repo.holidays(days.get(0), days.get(4));

        String search = listParams.search().toLowerCase();
        List<TeamRow> all = team.stream().map(m -> new TeamRow(m.id().toString(), m.code(), m.fullName(), m.designation(), m.shift(), status.get(m.id()), m.photo())).toList();
        List<TeamRow> rows = new ArrayList<>(all.stream().filter(m -> search.isEmpty() || (m.fullName() + " " + m.code() + " " + m.designation()).toLowerCase().contains(search)).toList());
        Map<String, Function<TeamRow, String>> keys = Map.of("name", TeamRow::fullName, "id", TeamRow::code, "title", TeamRow::designation, "status", r -> r.status().label());
        Comparator<TeamRow> cmp = Comparator.comparing(keys.get(listParams.sort()), LOCALE);
        rows.sort(listParams.asc() ? cmp : cmp.reversed());

        List<Away> away = team.stream().map(m -> new Away(m.id().toString(), m.fullName(), days.stream().map(d -> {
            if (holidays.stream().anyMatch(h -> h.date().equals(d) && ("ALL".equals(h.region()) || h.region().equals(m.region())))) return "hol";
            TeamLeave l = leaves.stream().filter(x -> x.employeeId().equals(m.id()) && !x.from().isAfter(d) && !x.to().isBefore(d)).findFirst().orElse(null);
            return l == null ? "" : "APPROVED".equals(l.status()) ? "lv" : "pend";
        }).toList())).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("size", team.size());
        out.put("counts", Map.of(
                "in", all.stream().filter(m -> m.status() != null && m.status().label().startsWith("In since")).count(),
                "leave", all.stream().filter(m -> m.status() != null && "On leave".equals(m.status().label())).count(),
                "notIn", all.stream().filter(m -> m.status() != null && "Not in yet".equals(m.status().label())).count()));
        out.put("rows", rows);
        out.put("days", days.stream().map(LocalDate::toString).toList());
        out.put("away", away);
        return out;
    }

}
