package com.jurislpo.jurisone.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.jurislpo.jurisone.engine.FnfEngine.Input;
import com.jurislpo.jurisone.engine.FnfEngine.Line;
import com.jurislpo.jurisone.engine.FnfEngine.Result;
import com.jurislpo.jurisone.engine.PayrollEngine.Structure;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Full & final maths: pro-rata last month, leave encashment, notice recovery, gratuity, TDS hook and the net. All amounts are paise. */
@DisplayName("F&F settlement maths")
class FnfEngineTest {

    /** ₹1,00,000 a month: Basic 50,000 · HRA 20,000 · LTA 5,000 · Special 25,000. */
    static final Structure S = PayrollEngine.splitGross(10_000_000);
    static final YearMonth OCT = YearMonth.of(2026, 10);

    /** Tiny builder so each test names only what it changes (defaults: a long-serving employee leaving mid-October). */
    static final class Builder {
        LocalDate doj = LocalDate.of(2020, 1, 1), lwd = LocalDate.of(2026, 10, 15), noticeStart = LocalDate.of(2026, 9, 15);
        double lop = 0, leave = 0;
        boolean paid = false, gratuity = false;
        int notice = 0, minYears = 5;
        long cap = 200_000_000;
        List<Line> add = List.of(), ded = List.of();

        Builder doj(String d) { doj = LocalDate.parse(d); return this; }
        Builder lwd(String d) { lwd = LocalDate.parse(d); return this; }
        Builder noticeStart(String d) { noticeStart = LocalDate.parse(d); return this; }
        Builder notice(int n) { notice = n; return this; }
        Builder lop(double d) { lop = d; return this; }
        Builder leave(double d) { leave = d; return this; }
        Builder gratuity(boolean on) { gratuity = on; return this; }
        Builder cap(long c) { cap = c; return this; }
        Builder alreadyPaid() { paid = true; return this; }
        Builder add(String l, long a) { add = List.of(new Line(l, a)); return this; }
        Builder ded(String l, long a) { ded = List.of(new Line(l, a)); return this; }

        Result run() { return run(t -> 0L); }

        Result run(java.util.function.Function<FnfEngine.TaxBase, Long> tds) {
            return FnfEngine.compute(new Input(S, YearMonth.from(lwd), doj, lwd, lop, paid, leave, notice, noticeStart, gratuity, minYears, cap, add, ded, 0.12,
                    1_500_000, 20_000), tds);
        }
    }

    static Builder f() { return new Builder(); }

    @Test
    @DisplayName("pays the last partial month pro-rata to the last working day")
    void proRata() {
        Result r = f().lwd("2026-10-15").run();
        assertThat(r.daysInMonth()).isEqualTo(31);
        assertThat(r.eligibleDays()).isEqualTo(15);
        // Basic 5,000,000 × 15 ÷ 31, HRA, LTA and Special likewise, each to the rupee
        assertThat(r.components().stream().map(PayrollEngine.PayLine::earned).toList()).containsExactly(2_419_400L, 967_700L, 241_900L, 1_209_700L);
        assertThat(r.salaryEarned()).isEqualTo(4_838_700L);
        assertThat(r.pf()).isEqualTo(180_000); // 12% of the earned Basic, which is over the ₹15,000 wage ceiling
        assertThat(r.pt()).isEqualTo(20_000);
    }

    @Test
    @DisplayName("joined mid-month and leaving the same month counts only the days employed")
    void joinedAndLeftSameMonth() {
        Result r = f().doj("2026-10-10").lwd("2026-10-20").noticeStart("2026-10-10").run();
        assertThat(r.eligibleDays()).isEqualTo(11); // 10th..20th
        assertThat(r.components().get(0).earned()).isEqualTo(Math.round(5_000_000.0 * 11 / 31 / 100) * 100);
        assertThat(r.serviceYears()).isZero();
    }

    @Test
    @DisplayName("leaving on the last day of the month pays the whole month")
    void exitOnMonthEnd() {
        Result r = f().lwd("2026-10-31").run();
        assertThat(r.eligibleDays()).isEqualTo(31);
        assertThat(r.salaryEarned()).isEqualTo(10_000_000L);
        // February (28 days), non-leap
        Result feb = f().lwd("2027-02-28").run();
        assertThat(feb.daysInMonth()).isEqualTo(28);
        assertThat(feb.salaryEarned()).isEqualTo(10_000_000L);
    }

    @Test
    @DisplayName("takes loss-of-pay days off the paid days, in half-day steps")
    void lop() {
        Result r = f().lwd("2026-10-31").lop(2.5).run();
        assertThat(r.paidDays()).isEqualTo(28.5);
        assertThat(r.components().get(0).earned()).isEqualTo(Math.round(5_000_000 * 28.5 / 31 / 100) * 100);
    }

    @Test
    @DisplayName("encashes leave at monthly gross ÷ days in the month, to the rupee")
    void leaveEncashment() {
        Result r = f().lwd("2026-10-31").leave(10).run();
        assertThat(r.perDayRate()).isEqualTo(322_581); // 10,000,000 ÷ 31
        assertThat(r.leaveEncashment()).isEqualTo(3_225_800L); // 10 days × 10,000,000 ÷ 31 = 32,258.06 → ₹32,258
        assertThat(f().lwd("2026-10-31").leave(0).run().leaveEncashment()).isZero();
        assertThat(f().lwd("2026-10-31").leave(-3).run().leaveEncashment()).isZero();
        assertThat(f().lwd("2026-10-31").leave(1.5).run().leaveEncashment()).isEqualTo(483_900L);
    }

    @Test
    @DisplayName("recovers the notice shortfall and nothing when the notice was served, waived or shortened")
    void noticeRecovery() {
        // 30 days required, notice started 1 Oct, leaving 15 Oct: 14 days served, 16 short
        Result short_ = f().notice(30).noticeStart("2026-10-01").lwd("2026-10-15").run();
        assertThat(short_.noticeServedDays()).isEqualTo(14);
        assertThat(short_.noticeShortfallDays()).isEqualTo(16);
        assertThat(short_.noticeRecovery()).isEqualTo(5_161_300L); // 16 × 10,000,000 ÷ 31 = ₹51,612.90 → ₹51,613
        // served in full
        assertThat(f().notice(30).noticeStart("2026-10-01").lwd("2026-10-31").run().noticeRecovery()).isZero();
        // waived by HR
        assertThat(f().notice(0).noticeStart("2026-10-01").lwd("2026-10-15").run().noticeRecovery()).isZero();
        // shortened to 14 days
        assertThat(f().notice(14).noticeStart("2026-10-01").lwd("2026-10-15").run().noticeRecovery()).isZero();
        // a last day before the notice start never gives a negative served count
        assertThat(f().notice(30).noticeStart("2026-10-20").lwd("2026-10-15").run().noticeServedDays()).isZero();
    }

    @Test
    @DisplayName("gratuity: 15/26 × last Basic × completed years, only from 5 years, only when switched on")
    void gratuity() {
        // 1 Nov 2021 → 30 Sep 2026 is 4 years 11 months: not eligible
        Result fourEleven = f().doj("2021-11-01").lwd("2026-09-30").gratuity(true).run();
        assertThat(fourEleven.serviceYears()).isEqualTo(4);
        assertThat(fourEleven.gratuityEligible()).isFalse();
        assertThat(fourEleven.gratuity()).isZero();
        // 1 Oct 2021 → 30 Sep 2026 is exactly 5 years (the last day counts as served)
        Result five = f().doj("2021-10-01").lwd("2026-09-30").gratuity(true).run();
        assertThat(five.serviceYears()).isEqualTo(5);
        assertThat(five.gratuityEligible()).isTrue();
        assertThat(five.gratuity()).isEqualTo(14_423_100L); // 15/26 × ₹50,000 × 5 = ₹1,44,230.77 → ₹1,44,231
        // 10 completed years: the partial year is dropped
        Result ten = f().doj("2016-03-15").lwd("2026-10-15").gratuity(true).run();
        assertThat(ten.serviceYears()).isEqualTo(10);
        assertThat(ten.gratuity()).isEqualTo(Math.round(15.0 * 5_000_000 * 10 / 26 / 100) * 100);
        // off by default in the policy: nothing, whatever the service
        assertThat(f().doj("2010-01-01").lwd("2026-10-15").gratuity(false).run().gratuity()).isZero();
        // the statutory ceiling
        assertThat(f().doj("1990-01-01").lwd("2026-10-15").gratuity(true).cap(10_000_000).run().gratuity()).isEqualTo(10_000_000L);
    }

    @Test
    @DisplayName("hands the final month's taxable amount to the tax function and deducts what it returns")
    void tdsHook() {
        AtomicReference<FnfEngine.TaxBase> seen = new AtomicReference<>();
        Result r = f().lwd("2026-10-31").leave(10).add("Bonus", 1_000_000).gratuity(true).doj("2010-01-01").run(t -> {
            seen.set(t);
            return 1_234_500L;
        });
        // salary + leave encashment + additions; gratuity is not taxed
        assertThat(seen.get().gross()).isEqualTo(10_000_000L + 3_225_800L + 1_000_000L);
        assertThat(seen.get().basic()).isEqualTo(5_000_000L);
        assertThat(seen.get().hra()).isEqualTo(2_000_000L);
        assertThat(seen.get().pf()).isEqualTo(r.pf());
        assertThat(r.taxableGross()).isEqualTo(seen.get().gross());
        assertThat(r.tds()).isEqualTo(1_234_500L);
        // a negative answer from the tax function is treated as nothing
        assertThat(f().run(t -> -5L).tds()).isZero();
    }

    @Test
    @DisplayName("net = earnings − PF − PT − notice recovery − deductions − TDS")
    void net() {
        Result r = f().lwd("2026-10-15").notice(30).noticeStart("2026-10-01").leave(5).add("Bonus", 500_000).ded("Laptop damage", 300_000).run(t -> 100_000L);
        long expectedEarnings = r.salaryEarned() + r.leaveEncashment() + r.gratuity() + 500_000;
        long expectedDeductions = r.pf() + r.pt() + r.noticeRecovery() + 300_000 + 100_000;
        assertThat(r.totalEarnings()).isEqualTo(expectedEarnings);
        assertThat(r.totalDeductions()).isEqualTo(expectedDeductions);
        assertThat(r.net()).isEqualTo(expectedEarnings - expectedDeductions);
        assertThat(r.payable()).isTrue();
        assertThat(r.additionsTotal()).isEqualTo(500_000);
        assertThat(r.deductionsTotal()).isEqualTo(300_000);
    }

    @Test
    @DisplayName("a net that is negative is kept (the employee owes the company), not clipped to zero")
    void negativeNet() {
        Result r = f().lwd("2026-10-05").ded("Advance outstanding", 50_000_000L).run();
        assertThat(r.net()).isNegative();
        assertThat(r.payable()).isFalse();
        assertThat(r.net()).isEqualTo(r.totalEarnings() - r.totalDeductions());
    }

    @Test
    @DisplayName("when the month's payroll already paid the salary, only the other items are settled")
    void salaryAlreadyPaid() {
        Result r = f().lwd("2026-10-31").alreadyPaid().leave(2).run();
        assertThat(r.salaryAlreadyPaid()).isTrue();
        assertThat(r.salaryEarned()).isZero();
        assertThat(r.pf()).isZero();
        assertThat(r.pt()).isZero();
        assertThat(r.components().stream().mapToLong(PayrollEngine.PayLine::earned).sum()).isZero();
        assertThat(r.leaveEncashment()).isEqualTo(645_200L); // 2 × 10,000,000 ÷ 31 = ₹6,451.61 → ₹6,452
        assertThat(r.net()).isEqualTo(645_200L);
    }

    @Test
    @DisplayName("completedYears counts the last day as served")
    void completedYears() {
        assertThat(FnfEngine.completedYears(LocalDate.of(2021, 10, 1), LocalDate.of(2026, 9, 30))).isEqualTo(5);
        assertThat(FnfEngine.completedYears(LocalDate.of(2021, 10, 1), LocalDate.of(2026, 9, 29))).isEqualTo(4);
        assertThat(FnfEngine.completedYears(LocalDate.of(2024, 2, 29), LocalDate.of(2025, 2, 27))).isZero();
        assertThat(FnfEngine.completedYears(LocalDate.of(2026, 1, 1), LocalDate.of(2025, 1, 1))).isZero();
    }
}
