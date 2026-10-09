package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.ListParams;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.service.PayrollService.PayRow;
import com.jurislpo.jurisone.service.TaxService.DeclMode;
import com.jurislpo.jurisone.service.TaxService.MonthFigures;
import com.jurislpo.jurisone.service.TaxService.Projection;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Tax positions for the open payroll month and the Tax page rows — src/lib/tax/position.ts + rows.ts. */
@Service
public class TaxPositions {

    private final JdbcTemplate jdbc;
    private final PayrollService payroll;
    private final TaxService tax;
    private final SettingsService settings;

    public TaxPositions(JdbcTemplate jdbc, PayrollService payroll, TaxService tax, SettingsService settings) {
        this.jdbc = jdbc;
        this.payroll = payroll;
        this.tax = tax;
        this.settings = settings;
    }

    /** The payroll month tax is projected for: the first month after the last closed run. */
    public String openPayrollMonth() {
        String today = Format.todayIn("Asia/Kolkata").toString().substring(0, 7);
        List<String> last = jdbc.queryForList("SELECT month FROM payroll_runs WHERE status = 'CLOSED' ORDER BY month DESC LIMIT 1", String.class);
        if (last.isEmpty()) return YearMonth.parse(today).minusMonths(1).toString();
        String next = YearMonth.parse(last.get(0)).plusMonths(1).toString();
        return next.compareTo(today) <= 0 ? next : today;
    }

    /** Re-project one payroll row with different declarations / regime (same month figures). */
    public Projection reproject(PayRow row, String month, DeclMode decl, String regime) {
        var p = row.pay();
        return tax.projectTax(row.employeeId(), month, new MonthFigures(p.grossEarned(), p.components().get(0).earned(), p.components().get(1).earned(), p.pf()),
                row.structure().gross(), row.structure().basic(), row.structure().hra(), decl, regime);
    }

    public record Positions(String month, List<PayRow> rows) {}

    /** Tax positions for the open payroll month; {@code ids} limits it to some employees. */
    public Positions taxPositions(List<UUID> ids) {
        String month = openPayrollMonth();
        return new Positions(month, payroll.computeRun(month, ids));
    }

    public record Row(String id, String code, String name, String title, String reg, double inc, double ded, long taxable, long tax, long tds, int pend,
                      boolean overridden) {}

    public record Rows(String month, String fy, List<Row> all, List<Row> rows) {}

    static final List<String> SORTS = List.of("name", "reg", "inc", "taxable", "tax", "tds", "pend");

    /** One row per India payroll employee for the Tax page and its export. */
    public Rows taxRows(Map<String, String> sp) {
        ListParams ls = ListParams.of(sp, SORTS, "name", 200, "asc");
        String reg = ListParams.pick(sp, "reg", List.of("All", "NEW", "OLD"), "All");
        String fy = settings.tax().fy();
        Positions pos = taxPositions(null);
        Map<UUID, Integer> pending = new HashMap<>();
        jdbc.query("SELECT employee_id, count(*) FROM declaration_items WHERE fy = ? AND status = 'PENDING' GROUP BY employee_id",
                rs -> { pending.put((UUID) rs.getObject(1), rs.getInt(2)); }, fy);
        List<Row> all = new ArrayList<>();
        for (PayRow r : pos.rows()) {
            Projection t = r.tax();
            all.add(new Row(r.employeeId().toString(), r.code(), r.name(), r.designation(), t.regime(), t.income(), t.current().std() + t.current().deductions(),
                    t.current().taxable(), t.current().total(), t.monthly(), pending.getOrDefault(r.employeeId(), 0), t.regimeOverridden()));
        }
        String q = ls.q().toLowerCase();
        List<Row> out = new ArrayList<>(all.stream().filter(r -> (q.isEmpty() || (r.name() + " " + r.code()).toLowerCase().contains(q))
                && ("All".equals(reg) || r.reg().equals(reg))).toList());
        Map<String, Function<Row, ? extends Comparable>> keys = Map.of("name", Row::name, "reg", Row::reg, "inc", Row::inc, "taxable", Row::taxable,
                "tax", Row::tax, "tds", Row::tds, "pend", Row::pend);
        out.sort(ls.comparator(keys));
        return new Rows(pos.month(), fy, all, out);
    }
}
