package com.jurislpo.jurisone.engine;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Payroll maths — pure functions, integer PAISE throughout (REQUIREMENTS §5.8).
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

    public static PayResult computePay(PayInput payInput) {
        double lop = Math.min(Math.max(0, payInput.lopDays()), payInput.eligibleDays());
        double paidDays = Math.max(0, payInput.eligibleDays() - lop);
        Structure structure = payInput.structure();
        List<PayLine> components = List.of(
                new PayLine("Basic", structure.basic(), earned(structure.basic(), paidDays, payInput.daysInMonth())),
                new PayLine("House rent allowance", structure.hra(), earned(structure.hra(), paidDays, payInput.daysInMonth())),
                new PayLine("Leave travel allowance", structure.lta(), earned(structure.lta(), paidDays, payInput.daysInMonth())),
                new PayLine("Special allowance", structure.special(), earned(structure.special(), paidDays, payInput.daysInMonth())));
        long grossEarned = components.stream().mapToLong(PayLine::earned).sum() + payInput.bonus();
        long pf = Math.round(payInput.pfRate() * Math.min(components.get(0).earned(), payInput.pfWageCeiling()));
        long professionalTax = grossEarned > 0 ? payInput.professionalTax() : 0;
        long totalDeductions = pf + professionalTax + payInput.tds() + payInput.otherDeduction();
        return new PayResult(payInput.daysInMonth(), payInput.eligibleDays(), lop, paidDays, components, payInput.bonus(), grossEarned, pf, professionalTax, payInput.tds(),
                payInput.otherDeduction(), totalDeductions, grossEarned - totalDeductions);
    }

    /** Earned amounts are rounded to whole rupees (100 paise), as on a printed payslip. */
    private static long earned(long rate, double paidDays, int daysInMonth) {
        return Math.round((rate * paidDays) / daysInMonth / 100) * 100;
    }

    /** Days in [max(doj, 1st), min(exit, month end)] — 0 if not employed that month. */
    public static int eligibleDays(YearMonth yearMonth, LocalDate dateOfJoining, LocalDate exit) {
        LocalDate first = yearMonth.atDay(1), last = yearMonth.atEndOfMonth();
        LocalDate from = dateOfJoining.isAfter(first) ? dateOfJoining : first;
        LocalDate to = exit != null && exit.isBefore(last) ? exit : last;
        if (from.isAfter(to)) return 0;
        return (int) ChronoUnit.DAYS.between(from, to) + 1;
    }

    public static int daysInMonth(YearMonth yearMonth) {
        return yearMonth.lengthOfMonth();
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

    private static String twoDigits(long number) {
        return number < 20 ? A[(int) number] : B[(int) (number / 10)] + (number % 10 != 0 ? " " + A[(int) (number % 10)] : "");
    }

    private static String three(long number) {
        return (number >= 100 ? A[(int) (number / 100)] + " Hundred" + (number % 100 != 0 ? " " : "") : "") + (number % 100 != 0 ? twoDigits(number % 100) : "");
    }

    /** Rupees in words, Indian system (for the payslip). */
    public static String rupeesInWords(double amount) {
        long rounded = Math.round(amount);
        if (rounded == 0) return "Zero";
        List<String> parts = new ArrayList<>();
        long crore = rounded / 10_000_000L, lakh = (rounded / 100_000L) % 100, thousand = (rounded / 1_000L) % 100, rest = rounded % 1000;
        if (crore != 0) parts.add((crore >= 100 ? three(crore) : twoDigits(crore)) + " Crore");
        if (lakh != 0) parts.add(twoDigits(lakh) + " Lakh");
        if (thousand != 0) parts.add(twoDigits(thousand) + " Thousand");
        if (rest != 0) parts.add(three(rest));
        return String.join(" ", parts);
    }
}
