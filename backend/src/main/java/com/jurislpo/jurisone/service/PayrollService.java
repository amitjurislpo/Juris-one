package com.jurislpo.jurisone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.service.AttendanceService.AttEmployee;
import com.jurislpo.jurisone.service.AttendanceService.DayView;
import com.jurislpo.jurisone.common.ListParams;
import com.jurislpo.jurisone.engine.AttendanceEngine;
import com.jurislpo.jurisone.engine.PayrollEngine;
import com.jurislpo.jurisone.engine.PayrollEngine.PayInput;
import com.jurislpo.jurisone.engine.PayrollEngine.PayLine;
import com.jurislpo.jurisone.engine.PayrollEngine.PayResult;
import com.jurislpo.jurisone.engine.PayrollEngine.Structure;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Payroll run computation, payslip snapshots and the payroll view — src/lib/payroll/service.ts + view.ts. */
@Service
public class PayrollService {

    private final JdbcTemplate jdbc;
    private final AttendanceService att;
    private final SettingsService settings;
    private final TaxService tax;
    private final ObjectMapper json;

    public PayrollService(JdbcTemplate jdbc, AttendanceService att, SettingsService settings, TaxService tax, ObjectMapper json) {
        this.jdbc = jdbc;
        this.att = att;
        this.settings = settings;
        this.tax = tax;
        this.json = json;
    }

    public record PayRow(UUID employeeId, String code, String name, String designation, String department, String doj, String regime, Structure structure,
                         PayResult pay, double attendanceLop, boolean adjusted, String note, int weeklyOffs, int holidays, double present, long annualTax,
                         Projection tax) {}

    // ── snapshot (immutable JSON stored on payslips.snapshot) ──
    public record SnapCompany(String name, String address) {}

    public record SnapEmployee(String code, String name, String designation, String department, String doj, String panLast4, String uanLast4, String bankName,
                               String bankLast4) {}

    public record SnapAttendance(int daysInMonth, int eligibleDays, int weeklyOffs, int holidays, double present, double lopDays, double paidDays) {}

    public record SnapLine(String label, long rate, long earned) {}

    public record SnapDeduction(String label, long amount) {}

    public record Snapshot(int version, String ym, SnapCompany company, SnapEmployee employee, String regime, String payDate, SnapAttendance attendance,
                           List<SnapLine> components, long bonus, long grossEarned, long pf, long pt, long tds, long otherDeduction, List<SnapDeduction> deductions,
                           long totalDeductions, long net, String netWords, String note) {}

    public Snapshot toSnapshot(PayRow row, String ym, Map<String, Object> emp, SettingsService.Company company, String payDate) {
        PayResult p = row.pay();
        List<SnapLine> comps = new ArrayList<>();
        for (PayLine c : p.components()) comps.add(new SnapLine(c.label(), c.rate(), c.earned()));
        if (p.bonus() != 0) comps.add(new SnapLine("Bonus / arrears", 0, p.bonus()));
        List<SnapDeduction> deductions = new ArrayList<>(List.of(new SnapDeduction("Provident fund (12% of Basic)", p.pf()),
                new SnapDeduction("Professional tax", p.pt()), new SnapDeduction("Income tax (TDS)", p.tds())));
        if (p.otherDeduction() != 0) deductions.add(new SnapDeduction("Other deduction", p.otherDeduction()));
        return new Snapshot(1, ym, new SnapCompany(company.name(), company.address()), new SnapEmployee(row.code(), row.name(), row.designation(), row.department(),
                row.doj(), (String) emp.get("pan_last4"), (String) emp.get("uan_last4"), (String) emp.get("bank_name"), (String) emp.get("bank_account_last4")),
                row.regime(), payDate, new SnapAttendance(p.daysInMonth(), p.eligibleDays(), row.weeklyOffs(), row.holidays(), row.present(), p.lopDays(), p.paidDays()),
                comps, p.bonus(), p.grossEarned(), p.pf(), p.pt(), p.tds(), p.otherDeduction(), deductions, p.totalDeductions(), p.net(),
                PayrollEngine.rupeesInWords(p.net() / 100.0), row.note());
    }

    // ── live computation ──

    /** Live computation for an open run (optionally for some employees only). */
    public List<PayRow> computeRun(String ym, List<UUID> ids) {
        YearMonth m = YearMonth.parse(ym);
        LocalDate first = m.atDay(1), last = m.atEndOfMonth();
        List<Map<String, Object>> runs = jdbc.queryForList("SELECT id FROM payroll_runs WHERE month = ?", ym);
        Map<UUID, Map<String, Object>> adjustments = new HashMap<>();
        if (!runs.isEmpty()) {
            for (Map<String, Object> a : jdbc.queryForList("SELECT * FROM payroll_adjustments WHERE run_id = ?", runs.get(0).get("id"))) {
                adjustments.put((UUID) a.get("employee_id"), a);
            }
        }
        SettingsService.PayrollPolicy pay = settings.payroll();
        AttendanceService.Pol policy = att.policy();
        String idFilter = ids == null ? "" : " AND e.id = ANY (?)";
        List<Object> args = new ArrayList<>(List.of(Date.valueOf(last), Date.valueOf(first), Date.valueOf(last)));
        if (ids != null) args.add(ids.toArray(UUID[]::new));
        // India employees on payroll that month (structure on the last day, employed some day of it)
        List<Map<String, Object>> emps = jdbc.queryForList("""
            SELECT e.id, e.code, e.full_name, e.designation, e.date_of_joining, e.exit_date, d.name AS dept
            FROM employees e LEFT JOIN departments d ON d.id = e.department_id
            WHERE e.region = 'IN' AND e.date_of_joining <= ? AND (e.exit_date IS NULL OR e.exit_date >= ?)
              AND EXISTS (SELECT 1 FROM salary_structures s WHERE s.employee_id = e.id AND s.effective_from <= ?)""" + idFilter + " ORDER BY e.full_name ASC",
                args.toArray());
        List<UUID> empIds = emps.stream().map(e -> (UUID) e.get("id")).toList();
        List<AttEmployee> attEmps = empIds.isEmpty() ? List.of() : att.loadAttEmployees("e.id = ANY (?)", (Object) empIds.toArray(UUID[]::new));
        Instant now = Instant.ofEpochMilli(Math.max(System.currentTimeMillis(), last.atTime(23, 59, 59).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() + 2 * 86400_000L));
        Map<UUID, List<DayView>> days = att.attendanceFor(attEmps, first, last, now);
        List<LocalDate> holidays = jdbc.query("SELECT date FROM holidays WHERE date >= ? AND date <= ? AND region::text IN ('IN', 'ALL')",
                (rs, i) -> rs.getDate(1).toLocalDate(), Date.valueOf(first), Date.valueOf(last));
        int D = m.lengthOfMonth();
        List<PayRow> rows = new ArrayList<>();
        for (Map<String, Object> e : emps) {
            UUID id = (UUID) e.get("id");
            LocalDate doj = ((Date) e.get("date_of_joining")).toLocalDate();
            LocalDate exit = e.get("exit_date") == null ? null : ((Date) e.get("exit_date")).toLocalDate();
            int elig = PayrollEngine.eligibleDays(m, doj, exit);
            if (elig == 0) continue;
            Map<String, Object> st = jdbc.queryForMap("SELECT basic, hra, lta, special FROM salary_structures WHERE employee_id = ? AND effective_from <= ? ORDER BY effective_from DESC LIMIT 1",
                    id, Date.valueOf(last));
            Structure structure = new Structure(((Number) st.get("basic")).longValue(), ((Number) st.get("hra")).longValue(), ((Number) st.get("lta")).longValue(),
                    ((Number) st.get("special")).longValue());
            LocalDate from = doj.isAfter(first) ? doj : first, to = exit != null && exit.isBefore(last) ? exit : last;
            int wo = 0, hol = 0;
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                if (policy.engine().weeklyOffs().contains(AttendanceEngine.dowOf(d))) wo++;
                else if (holidays.contains(d)) hol++;
            }
            List<DayView> ds = days.get(id);
            double attendanceLop = 0;
            if (ds != null) {
                LocalDate f0 = from, t0 = to;
                attendanceLop = AttendanceEngine.summarize(ds.stream().filter(x -> !x.r.date.isBefore(f0) && !x.r.date.isAfter(t0)).map(x -> x.r).toList(), policy.engine()).lopDays;
            }
            Map<String, Object> adj = adjustments.get(id);
            double lop = adj != null && adj.get("lop_days") != null ? ((BigDecimal) adj.get("lop_days")).doubleValue() : attendanceLop;
            long bonus = adj != null ? ((Number) adj.get("bonus")).longValue() : 0, other = adj != null ? ((Number) adj.get("other_deduction")).longValue() : 0;
            long pfCeiling = Math.round(pay.pfWageCeiling() * 100), pt = Math.round(pay.professionalTax().monthly() * 100);
            PayResult pre = PayrollEngine.computePay(new PayInput(structure, D, elig, lop, bonus, other, 0, pay.pfRate(), pfCeiling, pt));
            Projection t = tax.projectTax(id, ym, new MonthFigures(pre.grossEarned(), pre.components().get(0).earned(), pre.components().get(1).earned(), pre.pf()),
                    structure.gross(), structure.basic(), structure.hra(), null, null);
            PayResult result = PayrollEngine.computePay(new PayInput(structure, D, elig, lop, bonus, other, t.monthly() * 100, pay.pfRate(), pfCeiling, pt));
            boolean adjusted = adj != null && (adj.get("lop_days") != null || bonus > 0 || other > 0);
            rows.add(new PayRow(id, (String) e.get("code"), (String) e.get("full_name"), (String) e.get("designation"), e.get("dept") == null ? "—" : (String) e.get("dept"),
                    doj.toString(), t.regime(), structure, result, attendanceLop, adjusted, adj == null ? null : (String) adj.get("note"), wo, hol,
                    Math.max(0, elig - wo - hol - result.lopDays()), t.current().total(), t));
        }
        return rows;
    }

    /** Snapshot rows of a closed run (closed payslips never change). */
    public List<Map.Entry<UUID, JsonNode>> closedRows(String ym) {
        return jdbc.query("SELECT p.employee_id, p.snapshot::text FROM payslips p JOIN payroll_runs r ON r.id = p.run_id WHERE r.month = ?", (rs, i) -> {
            try {
                return Map.entry((UUID) rs.getObject(1), json.readTree(rs.getString(2)));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }, ym);
    }

    public record RunStatus(Map<String, Object> run, boolean attendanceLocked) {}

    public RunStatus runStatus(String ym) {
        List<Map<String, Object>> runs = jdbc.queryForList("SELECT id, month, status::text AS status, approved_at, closed_at, pay_date, attendance_locked_at, approved_by FROM payroll_runs WHERE month = ?", ym);
        return new RunStatus(runs.isEmpty() ? null : runs.get(0), att.isMonthLocked(ym, att.policy(), Instant.now()));
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
            for (Map.Entry<UUID, JsonNode> en : closedRows(month)) {
                JsonNode s = en.getValue();
                long gross = 0;
                for (JsonNode c : s.get("components")) if (c.get("rate").asLong() != 0) gross += c.get("rate").asLong();
                JsonNode a = s.get("attendance");
                long bonus = s.get("bonus").asLong(), other = s.get("otherDeduction").asLong();
                rows.add(new PayView(en.getKey().toString(), s.at("/employee/code").asText(), s.at("/employee/name").asText(), s.at("/employee/department").asText(),
                        s.get("regime").asText(), gross, a.get("lopDays").asDouble(), a.get("lopDays").asDouble(), a.get("paidDays").asDouble(), a.get("daysInMonth").asInt(),
                        bonus, s.get("grossEarned").asLong(), s.get("pf").asLong() + s.get("pt").asLong(), s.get("tds").asLong(), other, s.get("net").asLong(),
                        bonus > 0 || other > 0 || (s.hasNonNull("note") && !s.get("note").asText().isEmpty()), s.hasNonNull("note") ? s.get("note").asText() : null));
            }
        } else {
            for (PayRow r : computeRun(month, null)) {
                PayResult p = r.pay();
                rows.add(new PayView(r.employeeId().toString(), r.code(), r.name(), r.department(), r.regime(), r.structure().gross(), p.lopDays(), r.attendanceLop(),
                        p.paidDays(), p.daysInMonth(), p.bonus(), p.grossEarned(), p.pf() + p.pt(), p.tds(), p.otherDeduction(), p.net(),
                        r.adjusted() || (r.note() != null && !r.note().isEmpty()), r.note()));
            }
        }
        return new View(rs.run(), closed, rs.attendanceLocked(), rows);
    }

    static final List<String> SORTS = List.of("name", "gross", "lop", "paid", "earned", "tds", "net");
    static final List<String> FILTERS = List.of("All", "LOP", "Edited", "NEW", "OLD");

    public static List<PayView> filter(List<PayView> rows, Map<String, String> sp) {
        ListParams ls = ListParams.of(sp, SORTS, "name", 100, "asc");
        String flt = ListParams.pick(sp, "show", FILTERS, "All");
        String q = ls.q().toLowerCase();
        List<PayView> out = new ArrayList<>(rows.stream().filter(r -> (q.isEmpty() || (r.name() + " " + r.code() + " " + r.department()).toLowerCase().contains(q))
                && ("All".equals(flt) || ("LOP".equals(flt) ? r.lop() > 0 : "Edited".equals(flt) ? r.edited() : r.regime().equals(flt)))).toList());
        Map<String, Function<PayView, ? extends Comparable>> keys = Map.of("name", PayView::name, "gross", PayView::monthlyGross, "lop", PayView::lop,
                "paid", PayView::paid, "earned", PayView::earned, "tds", PayView::tds, "net", PayView::net);
        out.sort(ls.comparator(keys));
        return out;
    }

    public record Months(List<String> months, String dflt) {}

    /** Months shown in the switcher: closed runs + the first open month after them (or last month). */
    public Months payrollMonths(LocalDate today) {
        List<Map<String, Object>> runs = jdbc.queryForList("SELECT month, status::text AS status FROM payroll_runs ORDER BY month ASC");
        String cur = today.toString().substring(0, 7);
        String prev = YearMonth.parse(cur).minusMonths(1).toString();
        String lastClosed = null;
        for (Map<String, Object> r : runs) if ("CLOSED".equals(r.get("status"))) lastClosed = (String) r.get("month");
        String open = lastClosed != null ? YearMonth.parse(lastClosed).plusMonths(1).toString() : prev;
        TreeSet<String> set = new TreeSet<>();
        for (Map<String, Object> r : runs) set.add((String) r.get("month"));
        set.add(open.compareTo(cur) <= 0 ? open : prev);
        List<String> all = new ArrayList<>(set);
        return new Months(all.subList(Math.max(0, all.size() - 6), all.size()), open.compareTo(cur) <= 0 ? open : prev);
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }
}
