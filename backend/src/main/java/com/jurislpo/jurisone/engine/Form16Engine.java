package com.jurislpo.jurisone.engine;

import com.jurislpo.jurisone.engine.TaxEngine.Caps;
import com.jurislpo.jurisone.engine.TaxEngine.Declared;
import com.jurislpo.jurisone.engine.TaxEngine.Deductions;
import com.jurislpo.jurisone.engine.TaxEngine.TaxResult;
import com.jurislpo.jurisone.engine.TaxEngine.TaxYearConfig;
import com.jurislpo.jurisone.exception.UserException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * Form 16 aggregation (REQUIREMENTS §5.9): one employee's closed payslips of a financial year, plus their tax profile,
 * become the month-wise and quarter-wise TDS summary (Part A) and the computation of tax (Part B), using the same
 * {@link TaxEngine} the monthly TDS uses. Pure functions; every amount in the result is integer paise (the tax engine
 * itself works in whole rupees).
 */
public final class Form16Engine {
    private Form16Engine() {}

    /** One closed payslip, in paise: gross earned (incl. bonus), Basic and HRA earned, PF, professional tax and TDS deducted. */
    public record MonthIn(String ym, String payDate, long gross, long basic, long hra, long pf, long pt, long tds) {}

    /**
     * @param regime       "NEW" or "OLD" — the regime the employee finally chose for the year
     * @param config       the tax tables of that regime for the year
     * @param declared     approved declarations (rupees); only used on the old regime
     */
    public record Input(String fy, String regime, List<MonthIn> months, long prevIncome, long prevTds, Declared declared, TaxYearConfig config,
                        Caps caps, Map<String, String> labels) {}

    public record MonthRow(String ym, String payDate, long gross, long pf, long pt, long tds) {}

    public record QuarterRow(String quarter, String period, long gross, long tds) {}

    public record Line(String label, long amount) {}

    /** Part B. {@code balance} is tax payable (positive) or refundable (negative) after all TDS. */
    public record PartB(long grossSalary, long previousEmployerIncome, long grossTotalIncome, List<Line> exemptions, long totalExemptions,
                        long standardDeduction, List<Line> deductions, long totalDeductions, long taxableIncome, long taxOnIncome, long rebate,
                        long surcharge, long cess, long totalTax, long tdsCurrentEmployer, long tdsPreviousEmployer, long tdsTotal, long balance,
                        long providentFund, long professionalTax) {}

    public record Result(String fy, String assessmentYear, String regime, List<MonthRow> months, List<QuarterRow> quarters, PartB partB) {}

    // ───────────────────────── financial-year helpers ─────────────────────────

    /** "2026-27" (the second part must follow the first) → its start year; 400 otherwise. */
    public static int startYear(String fy) {
        if (fy == null || !fy.matches("^\\d{4}-\\d{2}$")) throw new UserException(HttpStatus.BAD_REQUEST, "Financial year looks like 2026-27.");
        int y = Integer.parseInt(fy.substring(0, 4));
        if ((y + 1) % 100 != Integer.parseInt(fy.substring(5))) throw new UserException(HttpStatus.BAD_REQUEST, "Financial year looks like 2026-27.");
        return y;
    }

    /** The 12 months April..March of the year, as "2026-04" … "2027-03". */
    public static List<String> fyMonths(String fy) {
        int y = startYear(fy);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            int m = (3 + i) % 12 + 1;
            out.add(String.format(Locale.ROOT, "%04d-%02d", m >= 4 ? y : y + 1, m));
        }
        return out;
    }

    /** "2026-27" → "2027-28". */
    public static String assessmentYear(String fy) {
        int y = startYear(fy) + 1;
        return y + "-" + String.format(Locale.ROOT, "%02d", (y + 1) % 100);
    }

    /** "Q1" (Apr–Jun) … "Q4" (Jan–Mar) of a month. */
    public static String quarterOf(String ym) {
        int m = Integer.parseInt(ym.substring(5, 7));
        return "Q" + ((m - 4 + 12) % 12 / 3 + 1);
    }

    public static List<String> quarterMonths(String fy, String quarter) {
        List<String> all = fyMonths(fy);
        int q = switch (quarter == null ? "" : quarter) {
            case "Q1" -> 0;
            case "Q2" -> 1;
            case "Q3" -> 2;
            case "Q4" -> 3;
            default -> throw new UserException(HttpStatus.BAD_REQUEST, "Choose a quarter: Q1, Q2, Q3 or Q4.");
        };
        return all.subList(q * 3, q * 3 + 3);
    }

    static String period(String fy, String quarter) {
        List<String> m = quarterMonths(fy, quarter);
        return Format.MON3.get(Integer.parseInt(m.get(0).substring(5)) - 1) + " " + m.get(0).substring(0, 4) + " – "
                + Format.MON3.get(Integer.parseInt(m.get(2).substring(5)) - 1) + " " + m.get(2).substring(0, 4);
    }

    // ───────────────────────── computation ─────────────────────────

    private static double rupees(long paise) {
        return paise / 100.0;
    }

    private static long paise(double rupees) {
        return Math.round(rupees * 100);
    }

    public static Result compute(Input in) {
        List<String> valid = fyMonths(in.fy());
        List<MonthIn> months = in.months().stream().sorted(java.util.Comparator.comparing(MonthIn::ym)).toList();
        for (MonthIn m : months) if (!valid.contains(m.ym())) throw new IllegalArgumentException("Month " + m.ym() + " is not in FY " + in.fy());

        long gross = 0, basic = 0, hra = 0, pf = 0, pt = 0, tds = 0;
        List<MonthRow> rows = new ArrayList<>();
        for (MonthIn m : months) {
            gross += m.gross();
            basic += m.basic();
            hra += m.hra();
            pf += m.pf();
            pt += m.pt();
            tds += m.tds();
            rows.add(new MonthRow(m.ym(), m.payDate(), m.gross(), m.pf(), m.pt(), m.tds()));
        }
        List<QuarterRow> quarters = new ArrayList<>();
        for (String q : List.of("Q1", "Q2", "Q3", "Q4")) {
            long qg = months.stream().filter(m -> quarterOf(m.ym()).equals(q)).mapToLong(MonthIn::gross).sum();
            long qt = months.stream().filter(m -> quarterOf(m.ym()).equals(q)).mapToLong(MonthIn::tds).sum();
            quarters.add(new QuarterRow(q, period(in.fy(), q), qg, qt));
        }

        boolean old = "OLD".equals(in.regime());
        double income = rupees(gross + in.prevIncome());
        Deductions ded = old ? TaxEngine.oldRegimeDeductions(rupees(basic), rupees(hra), rupees(pf), in.declared(), in.caps(), in.labels()) : null;
        TaxResult t = TaxEngine.computeTax(income, ded == null ? 0 : ded.total(), in.config());

        List<Line> exemptions = new ArrayList<>(), via = new ArrayList<>();
        if (ded != null) {
            for (TaxEngine.Line l : ded.lines()) (l.label().endsWith(" exemption") ? exemptions : via).add(new Line(l.label(), l.amount() * 100));
        }
        long totalEx = exemptions.stream().mapToLong(Line::amount).sum(), totalVia = via.stream().mapToLong(Line::amount).sum();
        long std = paise(t.std());
        long taxable = t.taxable() * 100;
        long tdsTotal = tds + in.prevTds();
        long totalTax = t.total() * 100;
        PartB b = new PartB(gross, in.prevIncome(), gross + in.prevIncome(), exemptions, totalEx, std, via, totalVia, taxable, paise(t.slabTax()), paise(t.rebate()),
                paise(t.surcharge()), paise(t.cess()), totalTax, tds, in.prevTds(), tdsTotal, totalTax - tdsTotal, pf, pt);
        return new Result(in.fy(), assessmentYear(in.fy()), old ? "OLD" : "NEW", rows, quarters, b);
    }
}
