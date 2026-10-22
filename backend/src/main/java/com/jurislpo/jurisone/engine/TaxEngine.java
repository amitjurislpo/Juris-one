package com.jurislpo.jurisone.engine;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Income-tax engine — pure functions (REQUIREMENTS §5.9).
 *
 * <p>Amounts are RUPEES here, as {@code double} exactly like the TS code (slabs, caps and limits are stored in
 * rupees per financial year); callers convert to/from paise. Tax rules are data: slabs, rebate, cess, surcharge and
 * section caps come from {@code tax_years} and {@code tax_sections}.
 */
public final class TaxEngine {

    private TaxEngine() {}

    public enum Regime { NEW, OLD }

    /** {@code upto == null}: no upper limit. */
    public record Slab(Double upto, double rate) {}

    public record Surcharge(double above, double rate) {}

    /** @param marginalRelief relief just above the rebate limit (new regime) */
    public record TaxYearConfig(Regime regime, double stdDeduction, double rebateLimit, boolean marginalRelief, double cessRate,
                                List<Slab> slabs, List<Surcharge> surcharge) {}

    public record SlabLine(double from, Double to, double rate, double amount) {}

    /**
     * @param gross      income before deductions
     * @param deductions old-regime chapter VI-A etc.
     * @param base       after rebate / marginal relief
     * @param total      annual tax liability, rounded to the rupee
     */
    public record TaxResult(Regime regime, double gross, double standardDeduction, double deductions, long taxable, double slabTax, double rebate,
                            double base, double surcharge, double cess, long total, List<SlabLine> lines) {}

    public record SlabTax(double tax, List<SlabLine> lines) {}

    public static SlabTax slabTax(double taxable, List<Slab> slabs) {
        double previous = 0, tax = 0;
        List<SlabLine> lines = new ArrayList<>();
        for (Slab slab : slabs) {
            double top = slab.upto() == null ? Double.POSITIVE_INFINITY : slab.upto();
            double amount = taxable > previous ? (Math.min(taxable, top) - previous) * slab.rate() : 0;
            lines.add(new SlabLine(previous, slab.upto(), slab.rate(), amount));
            tax += amount;
            if (taxable <= top) break;
            previous = top;
        }
        return new SlabTax(tax, lines);
    }

    private static double surchargeOn(double taxable, double base, TaxYearConfig taxYearConfig) {
        List<Surcharge> tiers = new ArrayList<>(taxYearConfig.surcharge());
        tiers.sort(Comparator.comparingDouble(Surcharge::above));
        tiers.removeIf(t -> !(taxable > t.above()));
        if (tiers.isEmpty()) return 0;
        Surcharge tier = tiers.get(tiers.size() - 1);
        double tierTax = base * tier.rate();
        // marginal relief: tax + surcharge may not exceed tax at the threshold + income above it
        double taxAtTier = slabTax(tier.above(), taxYearConfig.slabs()).tax();
        double prevRate = tiers.size() > 1 ? tiers.get(tiers.size() - 2).rate() : 0;
        double capAtThreshold = taxAtTier * (1 + prevRate) + (taxable - tier.above());
        if (base + tierTax > capAtThreshold) tierTax = Math.max(0, capAtThreshold - base);
        return tierTax;
    }

    /** Annual tax on {@code gross} income after the standard deduction and {@code deductions}. */
    public static TaxResult computeTax(double gross, double deductions, TaxYearConfig taxYearConfig) {
        double standardDeduction = Math.min(taxYearConfig.stdDeduction(), Math.max(0, gross));
        long taxable = Math.max(0, Math.round(gross - standardDeduction - deductions));
        SlabTax slabTax = slabTax(taxable, taxYearConfig.slabs());
        double tax = slabTax.tax();
        double base = tax;
        double rebate = 0;
        if (taxable <= taxYearConfig.rebateLimit()) {
            rebate = tax;
            base = 0;
        } else if (taxYearConfig.marginalRelief() && tax > taxable - taxYearConfig.rebateLimit()) {
            rebate = tax - (taxable - taxYearConfig.rebateLimit());
            base = taxable - taxYearConfig.rebateLimit();
        }
        double surcharge = surchargeOn(taxable, base, taxYearConfig);
        double cess = (base + surcharge) * taxYearConfig.cessRate();
        return new TaxResult(taxYearConfig.regime(), gross, standardDeduction, deductions, taxable, tax, rebate, base, surcharge, cess, Math.round(base + surcharge + cess), slabTax.lines());
    }

    // ───────────────────────── old-regime deductions ─────────────────────────

    public record DeductionCaps(double c80C, double c80CCD1B, double c80DSelf, double c80DParents, double c80DParentsSenior, double c24B, double c80TTA) {}

    /** Declared amounts in rupees (the TS {@code Declared}); starts as NO_DECLARATIONS. */
    public static final class Declared {
        public double rent; public boolean metro; public boolean parentsSenior;
        public double ppf, elss, lic, ssy, tuition, homeLoanPrincipal, nsc;
        public double nps, medSelf, medParents, homeLoanInterest, educationLoanInterest, donations, savingsInterest;

        /** Sets a declaration item by its key ("rent", "ppf", …); unknown keys are ignored. */
        public void set(String key, double value) {
            switch (key) {
                case "rent" -> rent = value; case "ppf" -> ppf = value; case "elss" -> elss = value; case "lic" -> lic = value; case "ssy" -> ssy = value;
                case "tuition" -> tuition = value; case "hlp" -> homeLoanPrincipal = value; case "nsc" -> nsc = value; case "nps" -> nps = value;
                case "medSelf" -> medSelf = value; case "medParents" -> medParents = value; case "hli" -> homeLoanInterest = value; case "edu" -> educationLoanInterest = value;
                case "don" -> donations = value; case "sav" -> savingsInterest = value;
                default -> { }
            }
        }

        public static boolean isItem(String key) {
            return switch (key) {
                case "rent", "ppf", "elss", "lic", "ssy", "tuition", "hlp", "nsc", "nps", "medSelf", "medParents", "hli", "edu", "don", "sav" -> true;
                default -> false;
            };
        }
    }

    /** A {@code [label, amount]} pair — serialised as a 2-element JSON array like the TS tuple. */
    @JsonFormat(shape = JsonFormat.Shape.ARRAY)
    public record DeductionLine(String label, long amount) {}

    public record Deductions(long total, List<DeductionLine> lines, long hraExemption, long c80C) {}

    /** HRA exemption = least of (HRA received, rent − 10% of Basic, 50% metro / 40% of Basic); caps per section. */
    public static Deductions oldRegimeDeductions(double basicAnnual, double hraAnnual, double epfAnnual, Declared declared, DeductionCaps caps, Map<String, String> labels) {
        Map<String, String> labelsOrEmpty = labels == null ? Map.of() : labels;
        double hra = declared.rent != 0 ? Math.max(0, Math.min(Math.min(hraAnnual, declared.rent - 0.1 * basicAnnual), (declared.metro ? 0.5 : 0.4) * basicAnnual)) : 0;
        double c80C = Math.min(caps.c80C(), epfAnnual + declared.ppf + declared.elss + declared.lic + declared.ssy + declared.tuition + declared.homeLoanPrincipal + declared.nsc);
        double nps = Math.min(caps.c80CCD1B(), declared.nps);
        double medicalDeduction = Math.min(caps.c80DSelf(), declared.medSelf) + Math.min(declared.parentsSenior ? caps.c80DParentsSenior() : caps.c80DParents(), declared.medParents);
        double homeLoanInterestDeduction = Math.min(caps.c24B(), declared.homeLoanInterest);
        double savingsInterestDeduction = Math.min(caps.c80TTA(), declared.savingsInterest);
        Object[][] raw = {
            {labelsOrEmpty.getOrDefault("HRA", "HRA") + " exemption", hra},
            {labelsOrEmpty.getOrDefault("80C", "80C") + " (incl. PF)", c80C},
            {labelsOrEmpty.getOrDefault("80CCD1B", "80CCD(1B)") + " NPS", nps},
            {labelsOrEmpty.getOrDefault("80D_SELF", "80D") + " medical", medicalDeduction},
            {labelsOrEmpty.getOrDefault("24B", "24(b)") + " home loan interest", homeLoanInterestDeduction},
            {labelsOrEmpty.getOrDefault("80E", "80E") + " education loan", declared.educationLoanInterest},
            {labelsOrEmpty.getOrDefault("80G", "80G") + " donations", declared.donations},
            {labelsOrEmpty.getOrDefault("80TTA", "80TTA") + " savings interest", savingsInterestDeduction},
        };
        double sum = 0;
        List<DeductionLine> lines = new ArrayList<>();
        for (Object[] row : raw) {
            double value = (double) row[1];
            if (value > 0) {
                sum += value;
                lines.add(new DeductionLine((String) row[0], Math.round(value)));
            }
        }
        return new Deductions(Math.round(sum), lines, Math.round(hra), Math.round(c80C));
    }

    // ───────────────────────── monthly TDS ─────────────────────────

    public record FinancialYear(String financialYear, int index, int remainingInclCurrent) {}

    /** FY months from April: "2026-09" → index 5; remaining months including the current one. */
    public static FinancialYear financialYearOf(String yearMonth) {
        int year = Integer.parseInt(yearMonth.substring(0, 4)), month = Integer.parseInt(yearMonth.substring(5, 7));
        int start = month >= 4 ? year : year - 1;
        int index = (month - 4 + 12) % 12;
        return new FinancialYear(start + "-" + String.format(java.util.Locale.ROOT, "%02d", (start + 1) % 100), index, 12 - index);
    }

    /**
     * Monthly TDS = (annual tax − TDS already deducted this FY, incl. previous employer) ÷ remaining
     * months including the current one. Never negative.
     */
    public static long monthlyTds(double annualTax, double alreadyDeducted, int remainingInclCurrent) {
        if (remainingInclCurrent <= 0) return 0;
        return Math.max(0, Math.round((annualTax - alreadyDeducted) / remainingInclCurrent));
    }
}
