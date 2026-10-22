package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.common.ListParams;
import com.jurislpo.jurisone.engine.Format;
import com.jurislpo.jurisone.repository.TaxPositionsRepository;
import com.jurislpo.jurisone.service.PayrollService.PayRow;
import com.jurislpo.jurisone.service.TaxService.DeclarationMode;
import com.jurislpo.jurisone.service.TaxService.MonthFigures;
import com.jurislpo.jurisone.service.TaxService.Projection;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/** Tax positions for the open payroll month and the Tax page rows. */
@Service
public class TaxPositions {

    private final TaxPositionsRepository repo;
    private final PayrollService payroll;
    private final TaxService tax;
    private final SettingsService settings;

    public TaxPositions(TaxPositionsRepository repo, PayrollService payroll, TaxService tax, SettingsService settings) {
        this.repo = repo;
        this.payroll = payroll;
        this.tax = tax;
        this.settings = settings;
    }

    /** The payroll month tax is projected for: the first month after the last closed run. */
    public String openPayrollMonth() {
        String today = Format.todayIn("Asia/Kolkata").toString().substring(0, 7);
        List<String> last = repo.lastClosedMonth();
        if (last.isEmpty()) return YearMonth.parse(today).minusMonths(1).toString();
        String next = YearMonth.parse(last.get(0)).plusMonths(1).toString();
        return next.compareTo(today) <= 0 ? next : today;
    }

    /** Re-project one payroll row with different declarations / regime (same month figures). */
    public Projection reproject(PayRow row, String month, DeclarationMode declaration, String regime) {
        var pay = row.pay();
        return tax.projectTax(row.employeeId(), month, new MonthFigures(pay.grossEarned(), pay.components().get(0).earned(), pay.components().get(1).earned(), pay.pf()),
                row.structure().gross(), row.structure().basic(), row.structure().hra(), declaration, regime);
    }

    public record Positions(String month, List<PayRow> rows) {}

    /** Tax positions for the open payroll month; {@code ids} limits it to some employees. */
    public Positions taxPositions(List<UUID> ids) {
        String month = openPayrollMonth();
        return new Positions(month, payroll.computeRun(month, ids));
    }

    public record Row(String id, String code, String name, String title, String regime, double income, double deductions, long taxable, long tax, long tds, int pending,
                      boolean overridden) {}

    public record Rows(String month, String financialYear, List<Row> all, List<Row> rows) {}

    static final List<String> SORTS = List.of("name", "regime", "income", "taxable", "tax", "tds", "pending");

    /** One row per India payroll employee for the Tax page and its export. */
    public Rows taxRows(Map<String, String> queryParams) {
        ListParams listParams = ListParams.of(queryParams, SORTS, "name", 200, "asc");
        String regime = ListParams.pick(queryParams, "regime", List.of("All", "NEW", "OLD"), "All");
        String financialYear = settings.tax().financialYear();
        Positions allPositions = taxPositions(null);
        Map<UUID, Integer> pending = repo.pendingDeclarationCounts(financialYear);
        List<Row> all = new ArrayList<>();
        for (PayRow payRow : allPositions.rows()) {
            Projection projection = payRow.tax();
            all.add(new Row(payRow.employeeId().toString(), payRow.code(), payRow.name(), payRow.designation(), projection.regime(), projection.income(), projection.current().standardDeduction() + projection.current().deductions(),
                    projection.current().taxable(), projection.current().total(), projection.monthly(), pending.getOrDefault(payRow.employeeId(), 0), projection.regimeOverridden()));
        }
        String search = listParams.search().toLowerCase();
        List<Row> out = new ArrayList<>(all.stream().filter(row -> (search.isEmpty() || (row.name() + " " + row.code()).toLowerCase().contains(search))
                && ("All".equals(regime) || row.regime().equals(regime))).toList());
        Map<String, Function<Row, ? extends Comparable>> keys = Map.of("name", Row::name, "regime", Row::regime, "income", Row::income, "taxable", Row::taxable,
                "tax", Row::tax, "tds", Row::tds, "pending", Row::pending);
        out.sort(listParams.comparator(keys));
        return new Rows(allPositions.month(), financialYear, all, out);
    }
}
