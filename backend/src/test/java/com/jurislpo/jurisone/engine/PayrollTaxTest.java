package com.jurislpo.jurisone.engine;

import com.jurislpo.jurisone.engine.PayrollEngine;
import com.jurislpo.jurisone.engine.TaxEngine;
import static org.assertj.core.api.Assertions.assertThat;

import com.jurislpo.jurisone.engine.PayrollEngine.PayInput;
import com.jurislpo.jurisone.engine.PayrollEngine.PayResult;
import com.jurislpo.jurisone.engine.PayrollEngine.Structure;
import com.jurislpo.jurisone.engine.TaxEngine.DeductionCaps;
import com.jurislpo.jurisone.engine.TaxEngine.Declared;
import com.jurislpo.jurisone.engine.TaxEngine.Deductions;
import com.jurislpo.jurisone.engine.TaxEngine.Regime;
import com.jurislpo.jurisone.engine.TaxEngine.Slab;
import com.jurislpo.jurisone.engine.TaxEngine.Surcharge;
import com.jurislpo.jurisone.engine.TaxEngine.TaxResult;
import com.jurislpo.jurisone.engine.TaxEngine.TaxYearConfig;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Port of tests/unit/payroll-tax.test.ts (FY 2026-27 fixtures copied verbatim). */
class PayrollTaxTest {

    static final TaxYearConfig NEW = new TaxYearConfig(Regime.NEW, 75000, 1200000, true, 0.04,
            List.of(new Slab(400000d, 0), new Slab(800000d, 0.05), new Slab(1200000d, 0.1), new Slab(1600000d, 0.15), new Slab(2000000d, 0.2),
                    new Slab(2400000d, 0.25), new Slab(null, 0.3)),
            List.of(new Surcharge(5000000, 0.1), new Surcharge(10000000, 0.15), new Surcharge(20000000, 0.25)));
    static final TaxYearConfig OLD = new TaxYearConfig(Regime.OLD, 50000, 500000, false, 0.04,
            List.of(new Slab(250000d, 0), new Slab(500000d, 0.05), new Slab(1000000d, 0.2), new Slab(null, 0.3)),
            List.of(new Surcharge(5000000, 0.1), new Surcharge(10000000, 0.15), new Surcharge(20000000, 0.25), new Surcharge(50000000, 0.37)));
    static final DeductionCaps CAPS = new DeductionCaps(150000, 50000, 25000, 25000, 50000, 200000, 10000);
    static final Structure JC074 = new Structure(7_500_000, 3_000_000, 750_000, 3_750_000); // ₹1,50,000 / month

    @Nested
    @DisplayName("golden case — JC-074 Amit Jaspal, September 2026 (REQUIREMENTS §7)")
    class Golden {
        @Test
        @DisplayName("joined 8 Sep with 1 LOP → 22 of 30 paid days")
        void paidDays() {
            int elig = PayrollEngine.eligibleDays(YearMonth.of(2026, 9), LocalDate.parse("2026-09-08"), null);
            assertThat(elig).isEqualTo(23);
            PayResult p = PayrollEngine.computePay(new PayInput(JC074, 30, elig, 1, 0, 0, 0, 0.12, 1_500_000, 20_000));
            assertThat(p.paidDays()).isEqualTo(22);
            assertThat(p.grossEarned()).isEqualTo(11_000_000); // ₹1,10,000
            assertThat(p.pf()).isEqualTo(180_000); // 12% of ₹15,000
        }

        @Test
        @DisplayName("monthly TDS ≈ ₹14,926 on the new regime with ₹7.5L / ₹38,000 from the previous employer")
        void monthlyTds() {
            int remaining = TaxEngine.financialYearOf("2026-09").remainingInclCurrent();
            assertThat(remaining).isEqualTo(7);
            double juris = 110000 + 150000 * 6; // Sep actual + Oct–Mar projected
            TaxResult t = TaxEngine.computeTax(juris + 750000, 0, NEW);
            assertThat(t.taxable()).isEqualTo(1685000);
            assertThat(t.total()).isEqualTo(142480);
            assertThat(TaxEngine.monthlyTds(t.total(), 38000, remaining)).isEqualTo(14926);
        }
    }

    @Nested
    @DisplayName("tax engine")
    class Tax {
        @Test
        @DisplayName("new regime: full rebate up to ₹12L taxable, marginal relief just above")
        void newRegimeRebate() {
            assertThat(TaxEngine.computeTax(1275000, 0, NEW).total()).isZero(); // 12L taxable
            TaxResult just = TaxEngine.computeTax(1285000, 0, NEW); // 12.1L taxable
            assertThat(just.base()).isEqualTo(10000); // relief caps tax at income above 12L
            assertThat(TaxEngine.computeTax(1675000, 0, NEW).total()).isEqualTo(Math.round(120000 * 1.04));
        }

        @Test
        @DisplayName("old regime: rebate up to ₹5L, slabs above")
        void oldRegime() {
            assertThat(TaxEngine.computeTax(550000, 0, OLD).total()).isZero();
            assertThat(TaxEngine.computeTax(1050000, 0, OLD).total()).isEqualTo(Math.round((12500 + 100000) * 1.04));
        }

        @Test
        @DisplayName("surcharge above ₹50L with marginal relief")
        void surcharge() {
            TaxResult t = TaxEngine.computeTax(5_085_000, 0, NEW); // 50.1L taxable
            TaxResult at50 = TaxEngine.computeTax(5_075_000, 0, NEW);
            assertThat(t.base() + t.surcharge()).isLessThanOrEqualTo(at50.base() + 10000 + 1);
        }

        @Test
        @DisplayName("old-regime deductions: HRA least-of, 80C cap incl. PF, 80D parents")
        void deductions() {
            Declared declaration = new Declared();
            declaration.rent = 180000;
            declaration.metro = true;
            declaration.ppf = 80000;
            declaration.lic = 25000;
            declaration.medSelf = 18000;
            Deductions d = TaxEngine.oldRegimeDeductions(432000, 172800, 51840, declaration, CAPS, null);
            assertThat(d.hraExemption()).isEqualTo(Math.round(Math.min(172800, Math.min(180000 - 43200, 216000))));
            assertThat(d.c80C()).isEqualTo(150000);
            assertThat(d.total()).isEqualTo(136800 + 150000 + 18000);
        }

        @Test
        @DisplayName("monthly TDS is never negative")
        void neverNegative() {
            assertThat(TaxEngine.monthlyTds(10000, 50000, 3)).isZero();
        }
    }

    @Nested
    @DisplayName("payroll helpers")
    class Helpers {
        @Test
        @DisplayName("split: Basic 50 · HRA 20 · LTA 5 · Special rest")
        void split() {
            assertThat(PayrollEngine.splitGross(15_000_000)).isEqualTo(JC074);
        }

        @Test
        @DisplayName("exit mid-month limits eligible days")
        void exit() {
            assertThat(PayrollEngine.eligibleDays(YearMonth.of(2026, 8), LocalDate.parse("2023-02-01"), LocalDate.parse("2026-08-31"))).isEqualTo(31);
            assertThat(PayrollEngine.eligibleDays(YearMonth.of(2026, 9), LocalDate.parse("2023-02-01"), LocalDate.parse("2026-08-31"))).isZero();
            assertThat(PayrollEngine.eligibleDays(YearMonth.of(2026, 8), LocalDate.parse("2023-02-01"), LocalDate.parse("2026-08-15"))).isEqualTo(15);
        }

        @Test
        @DisplayName("net pay in words (Indian system)")
        void words() {
            assertThat(PayrollEngine.rupeesInWords(105274)).isEqualTo("One Lakh Five Thousand Two Hundred Seventy Four");
            assertThat(PayrollEngine.rupeesInWords(12000000)).isEqualTo("One Crore Twenty Lakh");
        }
    }
}
