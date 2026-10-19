package com.jurislpo.jurisone.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jurislpo.jurisone.engine.Form16Engine.Input;
import com.jurislpo.jurisone.engine.Form16Engine.MonthIn;
import com.jurislpo.jurisone.engine.Form16Engine.Result;
import com.jurislpo.jurisone.engine.TaxEngine.Caps;
import com.jurislpo.jurisone.engine.TaxEngine.Declared;
import com.jurislpo.jurisone.engine.TaxEngine.Regime;
import com.jurislpo.jurisone.engine.TaxEngine.Slab;
import com.jurislpo.jurisone.engine.TaxEngine.TaxYearConfig;
import com.jurislpo.jurisone.exception.UserException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Form 16 aggregation")
class Form16EngineTest {

    static final TaxYearConfig NEW = new TaxYearConfig(Regime.NEW, 75_000, 700_000, true, 0.04,
            List.of(new Slab(300_000.0, 0), new Slab(700_000.0, 0.05), new Slab(1_000_000.0, 0.10), new Slab(1_200_000.0, 0.15), new Slab(1_500_000.0, 0.20), new Slab(null, 0.30)),
            List.of());
    static final TaxYearConfig OLD = new TaxYearConfig(Regime.OLD, 50_000, 500_000, false, 0.04,
            List.of(new Slab(250_000.0, 0), new Slab(500_000.0, 0.05), new Slab(1_000_000.0, 0.20), new Slab(null, 0.30)), List.of());
    static final Caps CAPS = new Caps(150_000, 50_000, 25_000, 25_000, 50_000, 200_000, 10_000);

    /** Every month of the FY with the same figures (paise): gross Rs 1,00,000, Basic 40,000, HRA 20,000, PF 4,800, PT 200. */
    static List<MonthIn> year(long gross, long tds) {
        List<MonthIn> out = new ArrayList<>();
        for (String ym : Form16Engine.fyMonths("2026-27")) out.add(new MonthIn(ym, ym + "-28", gross, 4_000_000, 2_000_000, 480_000, 20_000, tds));
        return out;
    }

    static Input input(String regime, List<MonthIn> months, long prevIncome, long prevTds, Declared d) {
        return new Input("2026-27", regime, months, prevIncome, prevTds, d, "OLD".equals(regime) ? OLD : NEW, CAPS, Map.of());
    }

    @Test
    @DisplayName("new regime: totals, standard deduction, tax with cess, balance after TDS")
    void newRegime() {
        Result r = Form16Engine.compute(input("NEW", year(10_000_000, 600_000), 0, 0, new Declared()));
        Form16Engine.PartB b = r.partB();
        assertThat(r.fy()).isEqualTo("2026-27");
        assertThat(r.assessmentYear()).isEqualTo("2027-28");
        assertThat(r.regime()).isEqualTo("NEW");
        assertThat(b.grossSalary()).isEqualTo(120_000_000L);
        assertThat(b.standardDeduction()).isEqualTo(7_500_000L);
        assertThat(b.exemptions()).isEmpty();
        assertThat(b.deductions()).isEmpty();
        assertThat(b.taxableIncome()).isEqualTo(112_500_000L);       // 12,00,000 - 75,000
        assertThat(b.taxOnIncome()).isEqualTo(6_875_000L);           // 20,000 + 30,000 + 18,750
        assertThat(b.cess()).isEqualTo(275_000L);
        assertThat(b.totalTax()).isEqualTo(7_150_000L);              // 71,500
        assertThat(b.tdsCurrentEmployer()).isEqualTo(7_200_000L);
        assertThat(b.tdsTotal()).isEqualTo(7_200_000L);
        assertThat(b.balance()).isEqualTo(-50_000L);                 // Rs 500 deducted too much
        assertThat(b.providentFund()).isEqualTo(5_760_000L);
        assertThat(b.professionalTax()).isEqualTo(240_000L);
    }

    @Test
    @DisplayName("months and quarters add up: TDS per month, and per quarter Apr-Jun, Jul-Sep, Oct-Dec, Jan-Mar")
    void quarters() {
        Result r = Form16Engine.compute(input("NEW", year(10_000_000, 600_000), 0, 0, new Declared()));
        assertThat(r.months()).hasSize(12);
        assertThat(r.months().get(0).ym()).isEqualTo("2026-04");
        assertThat(r.months().get(11).ym()).isEqualTo("2027-03");
        assertThat(r.quarters()).extracting(Form16Engine.QuarterRow::quarter).containsExactly("Q1", "Q2", "Q3", "Q4");
        assertThat(r.quarters()).allSatisfy(q -> {
            assertThat(q.gross()).isEqualTo(30_000_000L);
            assertThat(q.tds()).isEqualTo(1_800_000L);
        });
        assertThat(r.quarters().get(0).period()).isEqualTo("Apr 2026 – Jun 2026");
        assertThat(r.quarters().get(3).period()).isEqualTo("Jan 2027 – Mar 2027");
        assertThat(r.quarters().stream().mapToLong(Form16Engine.QuarterRow::tds).sum()).isEqualTo(r.months().stream().mapToLong(Form16Engine.MonthRow::tds).sum());
    }

    @Test
    @DisplayName("old regime: HRA exemption and Chapter VI-A deductions come from the declarations, PF counts under 80C")
    void oldRegime() {
        Declared d = new Declared();
        d.rent = 240_000;
        d.ppf = 50_000;
        Result r = Form16Engine.compute(input("OLD", year(10_000_000, 700_000), 0, 0, d));
        Form16Engine.PartB b = r.partB();
        assertThat(b.exemptions()).hasSize(1);
        assertThat(b.exemptions().get(0).label()).endsWith(" exemption");
        assertThat(b.totalExemptions()).isEqualTo(19_200_000L);      // least of 2,40,000 HRA, 2,40,000 - 10% basic, 40% of basic = 1,92,000
        assertThat(b.deductions()).hasSize(1);
        assertThat(b.totalDeductions()).isEqualTo(10_760_000L);      // PF 57,600 + PPF 50,000
        assertThat(b.standardDeduction()).isEqualTo(5_000_000L);
        assertThat(b.taxableIncome()).isEqualTo(85_040_000L);        // 12,00,000 - 50,000 - 1,92,000 - 1,07,600
        assertThat(b.totalTax()).isEqualTo(8_588_300L);              // 82,580 + 4% cess
        assertThat(b.balance()).isEqualTo(8_588_300L - 12 * 700_000L);
    }

    @Test
    @DisplayName("income and TDS from a previous employer are added")
    void previousEmployer() {
        Result r = Form16Engine.compute(input("NEW", year(10_000_000, 600_000), 10_000_000, 500_000, new Declared()));
        Form16Engine.PartB b = r.partB();
        assertThat(b.previousEmployerIncome()).isEqualTo(10_000_000L);
        assertThat(b.grossTotalIncome()).isEqualTo(130_000_000L);
        assertThat(b.taxableIncome()).isEqualTo(122_500_000L);
        assertThat(b.totalTax()).isEqualTo(8_840_000L);              // 85,000 + 4% cess
        assertThat(b.tdsPreviousEmployer()).isEqualTo(500_000L);
        assertThat(b.tdsTotal()).isEqualTo(7_700_000L);
        assertThat(b.balance()).isEqualTo(1_140_000L);
    }

    @Test
    @DisplayName("income under the rebate limit pays no tax: the rebate cancels it")
    void rebate() {
        List<MonthIn> months = year(4_166_667, 0);                   // about Rs 5,00,000 a year
        Result r = Form16Engine.compute(input("NEW", months, 0, 0, new Declared()));
        assertThat(r.partB().rebate()).isEqualTo(r.partB().taxOnIncome()).isPositive();
        assertThat(r.partB().totalTax()).isZero();
        assertThat(r.partB().balance()).isZero();
    }

    @Test
    @DisplayName("a part-year employee: only the months worked, other quarters are empty")
    void partYear() {
        List<MonthIn> months = year(10_000_000, 600_000).subList(6, 9); // Oct-Dec
        Result r = Form16Engine.compute(input("NEW", months, 0, 0, new Declared()));
        assertThat(r.months()).hasSize(3);
        assertThat(r.quarters().get(0).gross()).isZero();
        assertThat(r.quarters().get(2).gross()).isEqualTo(30_000_000L);
        assertThat(r.partB().grossSalary()).isEqualTo(30_000_000L);
    }

    @Test
    @DisplayName("financial-year helpers")
    void helpers() {
        assertThat(Form16Engine.fyMonths("2026-27")).hasSize(12).startsWith("2026-04", "2026-05").endsWith("2027-02", "2027-03");
        assertThat(Form16Engine.fyMonths("2099-00").get(11)).isEqualTo("2100-03");
        assertThat(Form16Engine.quarterOf("2026-04")).isEqualTo("Q1");
        assertThat(Form16Engine.quarterOf("2026-12")).isEqualTo("Q3");
        assertThat(Form16Engine.quarterOf("2027-03")).isEqualTo("Q4");
        assertThat(Form16Engine.quarterMonths("2026-27", "Q2")).containsExactly("2026-07", "2026-08", "2026-09");
        assertThatThrownBy(() -> Form16Engine.fyMonths("2026-28")).isInstanceOf(UserException.class);
        assertThatThrownBy(() -> Form16Engine.fyMonths("2026")).isInstanceOf(UserException.class);
        assertThatThrownBy(() -> Form16Engine.quarterMonths("2026-27", "Q5")).isInstanceOf(UserException.class);
        assertThatThrownBy(() -> Form16Engine.compute(input("NEW", List.of(new MonthIn("2025-12", null, 1, 0, 0, 0, 0, 0)), 0, 0, new Declared())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
