package com.jurislpo.jurisone.engine;

import com.jurislpo.jurisone.engine.PayrollEngine.PayInput;
import com.jurislpo.jurisone.engine.PayrollEngine.PayLine;
import com.jurislpo.jurisone.engine.PayrollEngine.PayResult;
import com.jurislpo.jurisone.engine.PayrollEngine.Structure;
import java.time.LocalDate;
import java.time.Period;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * Full & final settlement maths — pure functions, integer PAISE throughout (REQUIREMENTS §5.8, open question §9.2).
 *
 * <p>Rules (the assumptions are also in README):
 * <pre>
 *   per-day rate        = monthly gross ÷ days in the last working day's month   (the same day count PayrollEngine uses for LOP)
 *   salary              = PayrollEngine.computePay for the last, partial month (eligible days = max(DOJ, 1st) → last working day)
 *   leave encashment    = encashable leave days × per-day rate        (rounded to the rupee)
 *   notice recovery     = shortfall days × per-day rate               (shortfall = required notice − days served, never negative)
 *   gratuity            = 15/26 × last monthly Basic × completed years (only when switched on and years ≥ minimum), capped
 *   TDS                 = supplied by the caller from TaxEngine for the final month's taxable amount
 *   net                 = salary + encashment + gratuity + additions − PF − PT − notice recovery − deductions − TDS   (may be negative)
 * </pre>
 * Everything the engine rounds is rounded to whole rupees, as on a payslip.
 */
public final class FnfEngine {

    private FnfEngine() {}

    /** A labelled amount in paise (Finance's additions and deductions). */
    public record Line(String label, long amount) {}

    /**
     * @param structure         monthly rates of the structure in force on the last working day
     * @param month             month of the last working day
     * @param lopDays           loss-of-pay days of that month up to the last working day (0.5 steps)
     * @param salaryAlreadyPaid payroll for that month is already closed, so its salary is not paid again
     * @param encashableDays    remaining balance of the encashable leave types
     * @param noticeDaysRequired notice this person owed (after HR's shortening or waiver)
     * @param noticeStart       the day notice began counting
     * @param gratuityCap       paise
     */
    public record Input(Structure structure, YearMonth month, LocalDate doj, LocalDate lastWorkingDay, double lopDays, boolean salaryAlreadyPaid,
                        double encashableDays, int noticeDaysRequired, LocalDate noticeStart, boolean gratuityEnabled, int gratuityMinYears,
                        long gratuityCap, List<Line> additions, List<Line> deductions, double pfRate, long pfWageCeiling, long professionalTax) {}

    /** What the tax function is given: the final month's taxable figures, paise. */
    public record TaxBase(long gross, long basic, long hra, long pf) {}

    public record Result(int daysInMonth, int eligibleDays, double lopDays, double paidDays, boolean salaryAlreadyPaid, List<PayLine> components,
                         long salaryEarned, long pf, long pt, long perDayRate, double encashableDays, long leaveEncashment, int serviceYears,
                         boolean gratuityEligible, long gratuity, int noticeRequiredDays, int noticeServedDays, int noticeShortfallDays,
                         long noticeRecovery, List<Line> additions, long additionsTotal, List<Line> deductions, long deductionsTotal, long taxableGross,
                         long tds, long totalEarnings, long totalDeductions, long net) {
        /** False when the employee owes money (deductions exceed what is payable). */
        public boolean payable() { return net >= 0; }
    }

    /** Completed years of service, counting the last working day as a day served (1 Jan 2020 → 31 Dec 2024 is 5 years). */
    public static int completedYears(LocalDate doj, LocalDate lastWorkingDay) {
        if (lastWorkingDay.isBefore(doj)) return 0;
        return Period.between(doj, lastWorkingDay.plusDays(1)).getYears();
    }

    /** Gratuity under the Payment of Gratuity Act formula: 15/26 × last Basic × completed years, rounded to the rupee, capped. */
    public static long gratuity(long monthlyBasic, int completedYears, long cap) {
        long g = Math.round(15.0 * monthlyBasic * completedYears / 26 / 100) * 100;
        return Math.min(g, Math.max(0, cap));
    }

    /** {@code days × monthly gross ÷ days in month}, to the rupee. */
    public static long daysPay(double days, long monthlyGross, int daysInMonth) {
        return Math.round(days * monthlyGross / daysInMonth / 100) * 100;
    }

    /** Days between the day notice started and the last working day (never negative). */
    public static int noticeServed(LocalDate noticeStart, LocalDate lastWorkingDay) {
        if (noticeStart == null) return 0;
        return (int) Math.max(0, ChronoUnit.DAYS.between(noticeStart, lastWorkingDay));
    }

    public static Result compute(Input in, java.util.function.Function<TaxBase, Long> tdsOf) {
        YearMonth m = in.month();
        int D = PayrollEngine.daysInMonth(m);
        int elig = PayrollEngine.eligibleDays(m, in.doj(), in.lastWorkingDay());
        Structure s = in.structure();
        long gross = s.gross();

        PayResult pay = PayrollEngine.computePay(new PayInput(s, D, elig, in.lopDays(), 0, 0, 0, in.pfRate(), in.pfWageCeiling(), in.professionalTax()));
        List<PayLine> components = pay.components();
        long salaryEarned = 0, pf = 0, pt = 0;
        double lop = pay.lopDays(), paid = pay.paidDays();
        if (in.salaryAlreadyPaid()) {
            components = components.stream().map(c -> new PayLine(c.label(), c.rate(), 0)).toList();
        } else {
            salaryEarned = pay.grossEarned();
            pf = pay.pf();
            pt = pay.pt();
        }

        long perDay = Math.round((double) gross / D);
        double leaveDays = Math.max(0, in.encashableDays());
        long encash = daysPay(leaveDays, gross, D);

        int served = noticeServed(in.noticeStart(), in.lastWorkingDay());
        int shortfall = Math.max(0, in.noticeDaysRequired() - served);
        long recovery = daysPay(shortfall, gross, D);

        int years = completedYears(in.doj(), in.lastWorkingDay());
        boolean eligible = in.gratuityEnabled() && years >= in.gratuityMinYears() && years > 0;
        long gratuity = eligible ? gratuity(s.basic(), years, in.gratuityCap()) : 0;

        long additions = in.additions().stream().mapToLong(Line::amount).sum();
        long deductions = in.deductions().stream().mapToLong(Line::amount).sum();

        // taxable in the final month: salary + leave encashment + Finance's additions (gratuity within the statutory cap is exempt)
        long taxable = salaryEarned + encash + additions;
        long basicEarned = components.get(0).earned(), hraEarned = components.get(1).earned();
        long tds = Math.max(0, tdsOf.apply(new TaxBase(taxable, basicEarned, hraEarned, pf)));

        long earnings = salaryEarned + encash + gratuity + additions;
        long totalDeductions = pf + pt + recovery + deductions + tds;
        return new Result(D, elig, lop, paid, in.salaryAlreadyPaid(), components, salaryEarned, pf, pt, perDay, leaveDays, encash, years, eligible, gratuity,
                in.noticeDaysRequired(), served, shortfall, recovery, in.additions(), additions, in.deductions(), deductions, taxable, tds, earnings,
                totalDeductions, earnings - totalDeductions);
    }
}
