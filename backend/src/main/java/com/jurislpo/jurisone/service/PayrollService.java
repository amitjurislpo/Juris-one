package com.jurislpo.jurisone.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.service.AttendanceService.AttendanceEmployee;
import com.jurislpo.jurisone.service.AttendanceService.DayView;
import com.jurislpo.jurisone.common.ListParams;
import com.jurislpo.jurisone.engine.AttendanceEngine;
import com.jurislpo.jurisone.engine.PayrollEngine;
import com.jurislpo.jurisone.engine.PayrollEngine.PayInput;
import com.jurislpo.jurisone.engine.PayrollEngine.PayLine;
import com.jurislpo.jurisone.engine.PayrollEngine.PayResult;
import com.jurislpo.jurisone.engine.PayrollEngine.Structure;
import com.jurislpo.jurisone.repository.PayrollRepository;
import com.jurislpo.jurisone.service.TaxService.MonthFigures;
import com.jurislpo.jurisone.service.TaxService.Projection;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/** Payroll run computation, payslip snapshots and the payroll view. */
@Service
public class PayrollService {

    private final PayrollRepository repo;
    private final AttendanceService att;
    private final SettingsService settings;
    private final TaxService tax;
    private final ObjectMapper json;

    public PayrollService(PayrollRepository repo, AttendanceService attendance, SettingsService settings, TaxService tax, ObjectMapper json) {
        this.repo = repo;
        this.att = attendance;
        this.settings = settings;
        this.tax = tax;
        this.json = json;
    }

    public record PayRow(UUID employeeId, String code, String name, String designation, String department, String dateOfJoining, String regime, Structure structure,
                         PayResult pay, double attendanceLop, boolean adjusted, String note, int weeklyOffs, int holidays, double present, long annualTax,
                         Projection tax) {}

    // ── snapshot (immutable JSON stored on payslips.snapshot) ──
    public record SnapshotCompany(String name, String address) {}

    public record SnapshotEmployee(String code, String name, String designation, String department, @JsonProperty("doj") String dateOfJoining, String panLast4, String uanLast4, String bankName,
                               String bankLast4) {}

    public record SnapshotAttendance(int daysInMonth, int eligibleDays, int weeklyOffs, int holidays, double present, double lopDays, double paidDays) {}

    public record SnapshotLine(String label, long rate, long earned) {}

    public record SnapshotDeduction(String label, long amount) {}

    public record Snapshot(int version, @JsonProperty("ym") String month, SnapshotCompany company, SnapshotEmployee employee, String regime, String payDate, SnapshotAttendance attendance,
                           List<SnapshotLine> components, long bonus, long grossEarned, long pf, long pt, long tds, long otherDeduction, List<SnapshotDeduction> deductions,
                           long totalDeductions, long net, String netWords, String note) {}

    public Snapshot toSnapshot(PayRow row, String yearMonth, Map<String, Object> employee, SettingsService.Company company, String payDate) {
        PayResult payResult = row.pay();
        List<SnapshotLine> comps = new ArrayList<>();
        for (PayLine c : payResult.components()) comps.add(new SnapshotLine(c.label(), c.rate(), c.earned()));
        if (payResult.bonus() != 0) comps.add(new SnapshotLine("Bonus / arrears", 0, payResult.bonus()));
        List<SnapshotDeduction> deductions = new ArrayList<>(List.of(new SnapshotDeduction("Provident fund (12% of Basic)", payResult.pf()),
                new SnapshotDeduction("Professional tax", payResult.pt()), new SnapshotDeduction("Income tax (TDS)", payResult.tds())));
        if (payResult.otherDeduction() != 0) deductions.add(new SnapshotDeduction("Other deduction", payResult.otherDeduction()));
        return new Snapshot(1, yearMonth, new SnapshotCompany(company.name(), company.address()), new SnapshotEmployee(row.code(), row.name(), row.designation(), row.department(),
                row.dateOfJoining(), (String) employee.get("pan_last4"), (String) employee.get("uan_last4"), (String) employee.get("bank_name"), (String) employee.get("bank_account_last4")),
                row.regime(), payDate, new SnapshotAttendance(payResult.daysInMonth(), payResult.eligibleDays(), row.weeklyOffs(), row.holidays(), row.present(), payResult.lopDays(), payResult.paidDays()),
                comps, payResult.bonus(), payResult.grossEarned(), payResult.pf(), payResult.pt(), payResult.tds(), payResult.otherDeduction(), deductions, payResult.totalDeductions(), payResult.net(),
                PayrollEngine.rupeesInWords(payResult.net() / 100.0), row.note());
    }

    // ── live computation ──

    /** Live computation for an open run (optionally for some employees only). */
    public List<PayRow> computeRun(String yearMonth, List<UUID> ids) {
        YearMonth m = YearMonth.parse(yearMonth);
        LocalDate first = m.atDay(1), last = m.atEndOfMonth();
        List<Map<String, Object>> runs = repo.runIds(yearMonth);
        Map<UUID, Map<String, Object>> adjustments = new HashMap<>();
        if (!runs.isEmpty()) {
            for (Map<String, Object> a : repo.adjustments(runs.get(0).get("id"))) {
                adjustments.put((UUID) a.get("employee_id"), a);
            }
        }
        SettingsService.PayrollPolicy pay = settings.payroll();
        AttendanceService.ResolvedAttendancePolicy policy = att.policy();
        String idFilter = ids == null ? "" : " AND e.id = ANY (?)";
        List<Object> args = new ArrayList<>(List.of(Date.valueOf(last), Date.valueOf(first), Date.valueOf(last)));
        if (ids != null) args.add(ids.toArray(UUID[]::new));
        // India employees on payroll that month (structure on the last day, employed some day of it)
        List<Map<String, Object>> employees = repo.payrollEmployees(idFilter, args.toArray());
        List<UUID> employeeIds = employees.stream().map(e -> (UUID) e.get("id")).toList();
        List<AttendanceEmployee> attEmps = employeeIds.isEmpty() ? List.of() : att.loadAttendanceEmployees("e.id = ANY (?)", (Object) employeeIds.toArray(UUID[]::new));
        Instant now = Instant.ofEpochMilli(Math.max(System.currentTimeMillis(), last.atTime(23, 59, 59).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() + 2 * 86400_000L));
        Map<UUID, List<DayView>> days = att.attendanceFor(attEmps, first, last, now);
        List<LocalDate> holidays = repo.indiaHolidays(first, last);
        int D = m.lengthOfMonth();
        List<PayRow> rows = new ArrayList<>();
        for (Map<String, Object> employee : employees) {
            UUID id = (UUID) employee.get("id");
            LocalDate dateOfJoining = ((Date) employee.get("date_of_joining")).toLocalDate();
            LocalDate exit = employee.get("exit_date") == null ? null : ((Date) employee.get("exit_date")).toLocalDate();
            int elig = PayrollEngine.eligibleDays(m, dateOfJoining, exit);
            if (elig == 0) continue;
            Map<String, Object> row = repo.structureOn(id, last);
            Structure structure = new Structure(((Number) row.get("basic")).longValue(), ((Number) row.get("hra")).longValue(), ((Number) row.get("lta")).longValue(),
                    ((Number) row.get("special")).longValue());
            LocalDate from = dateOfJoining.isAfter(first) ? dateOfJoining : first, to = exit != null && exit.isBefore(last) ? exit : last;
            int wo = 0, hol = 0;
            for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1)) {
                if (policy.engine().weeklyOffs().contains(AttendanceEngine.dowOf(date))) wo++;
                else if (holidays.contains(date)) hol++;
            }
            List<DayView> dayViews = days.get(id);
            double attendanceLop = 0;
            if (dayViews != null) {
                LocalDate f0 = from, t0 = to;
                attendanceLop = AttendanceEngine.summarize(dayViews.stream().filter(x -> !x.r.date.isBefore(f0) && !x.r.date.isAfter(t0)).map(x -> x.r).toList(), policy.engine()).lopDays;
            }
            Map<String, Object> adj = adjustments.get(id);
            double lop = adj != null && adj.get("lop_days") != null ? ((BigDecimal) adj.get("lop_days")).doubleValue() : attendanceLop;
            long bonus = adj != null ? ((Number) adj.get("bonus")).longValue() : 0, other = adj != null ? ((Number) adj.get("other_deduction")).longValue() : 0;
            long pfCeiling = Math.round(pay.pfWageCeiling() * 100), pt = Math.round(pay.professionalTax().monthly() * 100);
            PayResult pre = PayrollEngine.computePay(new PayInput(structure, D, elig, lop, bonus, other, 0, pay.pfRate(), pfCeiling, pt));
            Projection projection = tax.projectTax(id, yearMonth, new MonthFigures(pre.grossEarned(), pre.components().get(0).earned(), pre.components().get(1).earned(), pre.pf()),
                    structure.gross(), structure.basic(), structure.hra(), null, null);
            PayResult result = PayrollEngine.computePay(new PayInput(structure, D, elig, lop, bonus, other, projection.monthly() * 100, pay.pfRate(), pfCeiling, pt));
            boolean adjusted = adj != null && (adj.get("lop_days") != null || bonus > 0 || other > 0);
            rows.add(new PayRow(id, (String) employee.get("code"), (String) employee.get("full_name"), (String) employee.get("designation"), employee.get("dept") == null ? "—" : (String) employee.get("dept"),
                    dateOfJoining.toString(), projection.regime(), structure, result, attendanceLop, adjusted, adj == null ? null : (String) adj.get("note"), wo, hol,
                    Math.max(0, elig - wo - hol - result.lopDays()), projection.current().total(), projection));
        }
        return rows;
    }

    /** Snapshot rows of a closed run (closed payslips never change). */
    public List<Map.Entry<UUID, JsonNode>> closedRows(String yearMonth) {
        List<Map.Entry<UUID, JsonNode>> out = new ArrayList<>();
        for (Map.Entry<UUID, String> entry : repo.payslipSnapshots(yearMonth)) {
            try {
                out.add(Map.entry(entry.getKey(), json.readTree(entry.getValue())));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
        return out;
    }

    public record RunStatus(Map<String, Object> run, boolean attendanceLocked) {}

    public RunStatus runStatus(String yearMonth) {
        List<Map<String, Object>> runs = repo.findRun(yearMonth);
        return new RunStatus(runs.isEmpty() ? null : runs.get(0), att.isMonthLocked(yearMonth, att.policy(), Instant.now()));
    }

    // ── payroll view (page + exports) ──

    public record PayView(String employeeId, String code, String name, String department, String regime, long monthlyGross, double lop, double attendanceLop,
                          double paid, int days, long bonus, long earned, long pfPt, long tds, long other, long net, boolean edited, String note) {}

    public record View(Map<String, Object> run, boolean closed, boolean attendanceLocked, List<PayView> rows) {}

    public View payrollView(String month) {
        RunStatus rs = runStatus(month);
        boolean closed = rs.run() != null && "CLOSED".equals(rs.run().get("status"));
        List<PayView> rows = new ArrayList<>();
        if (closed) {
            for (Map.Entry<UUID, JsonNode> entry : closedRows(month)) {
                JsonNode s = entry.getValue();
                long gross = 0;
                for (JsonNode c : s.get("components")) if (c.get("rate").asLong() != 0) gross += c.get("rate").asLong();
                JsonNode node = s.get("attendance");
                long bonus = s.get("bonus").asLong(), other = s.get("otherDeduction").asLong();
                rows.add(new PayView(entry.getKey().toString(), s.at("/employee/code").asText(), s.at("/employee/name").asText(), s.at("/employee/department").asText(),
                        s.get("regime").asText(), gross, node.get("lopDays").asDouble(), node.get("lopDays").asDouble(), node.get("paidDays").asDouble(), node.get("daysInMonth").asInt(),
                        bonus, s.get("grossEarned").asLong(), s.get("pf").asLong() + s.get("pt").asLong(), s.get("tds").asLong(), other, s.get("net").asLong(),
                        bonus > 0 || other > 0 || (s.hasNonNull("note") && !s.get("note").asText().isEmpty()), s.hasNonNull("note") ? s.get("note").asText() : null));
            }
        } else {
            for (PayRow payRow : computeRun(month, null)) {
                PayResult payResult = payRow.pay();
                rows.add(new PayView(payRow.employeeId().toString(), payRow.code(), payRow.name(), payRow.department(), payRow.regime(), payRow.structure().gross(), payResult.lopDays(), payRow.attendanceLop(),
                        payResult.paidDays(), payResult.daysInMonth(), payResult.bonus(), payResult.grossEarned(), payResult.pf() + payResult.pt(), payResult.tds(), payResult.otherDeduction(), payResult.net(),
                        payRow.adjusted() || (payRow.note() != null && !payRow.note().isEmpty()), payRow.note()));
            }
        }
        return new View(rs.run(), closed, rs.attendanceLocked(), rows);
    }

    static final List<String> SORTS = List.of("name", "gross", "lop", "paid", "earned", "tds", "net");
    static final List<String> FILTERS = List.of("All", "LOP", "Edited", "NEW", "OLD");

    public static List<PayView> filter(List<PayView> rows, Map<String, String> queryParams) {
        ListParams listParams = ListParams.of(queryParams, SORTS, "name", 100, "asc");
        String flt = ListParams.pick(queryParams, "filter", FILTERS, "All");
        String search = listParams.search().toLowerCase();
        List<PayView> out = new ArrayList<>(rows.stream().filter(r -> (search.isEmpty() || (r.name() + " " + r.code() + " " + r.department()).toLowerCase().contains(search))
                && ("All".equals(flt) || ("LOP".equals(flt) ? r.lop() > 0 : "Edited".equals(flt) ? r.edited() : r.regime().equals(flt)))).toList());
        Map<String, Function<PayView, ? extends Comparable>> keys = Map.of("name", PayView::name, "gross", PayView::monthlyGross, "lop", PayView::lop,
                "paid", PayView::paid, "earned", PayView::earned, "tds", PayView::tds, "net", PayView::net);
        out.sort(listParams.comparator(keys));
        return out;
    }

    public record Months(List<String> months, String defaultMonth) {}

    /** Months shown in the switcher: closed runs + the first open month after them (or last month). */
    public Months payrollMonths(LocalDate today) {
        List<Map<String, Object>> runs = repo.runMonths();
        String cur = today.toString().substring(0, 7);
        String previous = YearMonth.parse(cur).minusMonths(1).toString();
        String lastClosed = null;
        for (Map<String, Object> r : runs) if ("CLOSED".equals(r.get("status"))) lastClosed = (String) r.get("month");
        String open = lastClosed != null ? YearMonth.parse(lastClosed).plusMonths(1).toString() : previous;
        TreeSet<String> set = new TreeSet<>();
        for (Map<String, Object> r : runs) set.add((String) r.get("month"));
        set.add(open.compareTo(cur) <= 0 ? open : previous);
        List<String> all = new ArrayList<>(set);
        return new Months(all.subList(Math.max(0, all.size() - 6), all.size()), open.compareTo(cur) <= 0 ? open : previous);
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> view = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) view.put((String) kv[i], kv[i + 1]);
        return view;
    }
}
