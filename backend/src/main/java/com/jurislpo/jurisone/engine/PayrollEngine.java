package com.jurislpo.jurisone.engine;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Payroll maths — pure functions, integer PAISE throughout (REQUIREMENTS §5.8). Port of src/lib/payroll/engine.ts.
 *
 * <pre>
 *   earned_component = monthly_rate × paid_days ÷ days_in_month
 *   PF (employee)    = 12% × min(earned Basic, ₹15,000)
 *   net              = gross_earned − PF − PT − TDS − other deductions
 * </pre>
 */
public final class PayrollEngine {

    private PayrollEngine() {}

    /** Monthly rates, paise. */
    public record Structure(long basic, long hra, long lta, long special) {
        public long gross() { return basic + hra + lta + special; }
    }

    /**
     * @param eligibleDays max(DOJ, 1st) → min(exit, month end)
     * @param lopDays      may be fractional (0.5 steps)
     * @param pfWageCeiling paise (₹15,000 → 1_500_000)
     * @param professionalTax paise per month (when anything is earned)
     */
    public record PayInput(Structure structure, int daysInMonth, int eligibleDays, double lopDays, long bonus, long otherDeduction,
                           long tds, double pfRate, long pfWageCeiling, long professionalTax) {}

    public record PayLine(String label, long rate, long earned) {}

    public record PayResult(int daysInMonth, int eligibleDays, double lopDays, double paidDays, List<PayLine> components, long bonus,
                            long grossEarned, long pf, long pt, long tds, long otherDeduction, long totalDeductions, long net) {}

    public static PayResult computePay(PayInput i) {
        double lop = Math.min(Math.max(0, i.lopDays()), i.eligibleDays());
        double paidDays = Math.max(0, i.eligibleDays() - lop);
        Structure s = i.structure();
        List<PayLine> components = List.of(
                new PayLine("Basic", s.basic(), earned(s.basic(), paidDays, i.daysInMonth())),
                new PayLine("House rent allowance", s.hra(), earned(s.hra(), paidDays, i.daysInMonth())),
                new PayLine("Leave travel allowance", s.lta(), earned(s.lta(), paidDays, i.daysInMonth())),
                new PayLine("Special allowance", s.special(), earned(s.special(), paidDays, i.daysInMonth())));
        long grossEarned = components.stream().mapToLong(PayLine::earned).sum() + i.bonus();
        long pf = Math.round(i.pfRate() * Math.min(components.get(0).earned(), i.pfWageCeiling()));
        long pt = grossEarned > 0 ? i.professionalTax() : 0;
        long totalDeductions = pf + pt + i.tds() + i.otherDeduction();
        return new PayResult(i.daysInMonth(), i.eligibleDays(), lop, paidDays, components, i.bonus(), grossEarned, pf, pt, i.tds(),
                i.otherDeduction(), totalDeductions, grossEarned - totalDeductions);
    }

    /** Earned amounts are rounded to whole rupees (100 paise), as on a printed payslip. */
    private static long earned(long rate, double paidDays, int daysInMonth) {
        return Math.round((rate * paidDays) / daysInMonth / 100) * 100;
    }

    /** Days in [max(doj, 1st), min(exit, month end)] — 0 if not employed that month. */
    public static int eligibleDays(YearMonth ym, LocalDate doj, LocalDate exit) {
        LocalDate first = ym.atDay(1), last = ym.atEndOfMonth();
        LocalDate from = doj.isAfter(first) ? doj : first;
        LocalDate to = exit != null && exit.isBefore(last) ? exit : last;
        if (from.isAfter(to)) return 0;
        return (int) ChronoUnit.DAYS.between(from, to) + 1;
    }

    public static int daysInMonth(YearMonth ym) {
        return ym.lengthOfMonth();
    }

    /** "Split" helper: Basic 50% · HRA 20% · LTA 5% · Special = the rest (paise, rupee-rounded). */
    public static Structure splitGross(long target) {
        long basic = rupees(target * 0.5), hra = rupees(target * 0.2), lta = rupees(target * 0.05);
        return new Structure(basic, hra, lta, target - basic - hra - lta);
    }

    private static long rupees(double paise) {
        return Math.round(paise / 100) * 100;
    }

    private static final String[] A = {"", "One", "Two", "Three", "Four", "Five", "Six", "Seven", "Eight", "Nine", "Ten", "Eleven", "Twelve",
        "Thirteen", "Fourteen", "Fifteen", "Sixteen", "Seventeen", "Eighteen", "Nineteen"};
    private static final String[] B = {"", "", "Twenty", "Thirty", "Forty", "Fifty", "Sixty", "Seventy", "Eighty", "Ninety"};

    private static String two(long x) {
        return x < 20 ? A[(int) x] : B[(int) (x / 10)] + (x % 10 != 0 ? " " + A[(int) (x % 10)] : "");
    }

    private static String three(long x) {
        return (x >= 100 ? A[(int) (x / 100)] + " Hundred" + (x % 100 != 0 ? " " : "") : "") + (x % 100 != 0 ? two(x % 100) : "");
    }

    /** Rupees in words, Indian system (for the payslip). */
    public static String rupeesInWords(double amount) {
        long n = Math.round(amount);
        if (n == 0) return "Zero";
        List<String> parts = new ArrayList<>();
        long cr = n / 10_000_000L, lk = (n / 100_000L) % 100, th = (n / 1_000L) % 100, rest = n % 1000;
        if (cr != 0) parts.add((cr >= 100 ? three(cr) : two(cr)) + " Crore");
        if (lk != 0) parts.add(two(lk) + " Lakh");
        if (th != 0) parts.add(two(th) + " Thousand");
        if (rest != 0) parts.add(three(rest));
        return String.join(" ", parts);
    }
}
