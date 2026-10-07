package com.jurislpo.jurisone.engine;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Income-tax engine — pure functions (REQUIREMENTS §5.9). Port of src/lib/tax/engine.ts.
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
    public record TaxResult(Regime regime, double gross, double std, double deductions, long taxable, double slabTax, double rebate,
                            double base, double surcharge, double cess, long total, List<SlabLine> lines) {}

    public record SlabTax(double tax, List<SlabLine> lines) {}

    public static SlabTax slabTax(double taxable, List<Slab> slabs) {
        double prev = 0, tax = 0;
        List<SlabLine> lines = new ArrayList<>();
        for (Slab s : slabs) {
            double top = s.upto() == null ? Double.POSITIVE_INFINITY : s.upto();
            double amt = taxable > prev ? (Math.min(taxable, top) - prev) * s.rate() : 0;
            lines.add(new SlabLine(prev, s.upto(), s.rate(), amt));
            tax += amt;
            if (taxable <= top) break;
            prev = top;
        }
        return new SlabTax(tax, lines);
    }

    private static double surchargeOn(double taxable, double base, TaxYearConfig c) {
        List<Surcharge> tiers = new ArrayList<>(c.surcharge());
        tiers.sort(Comparator.comparingDouble(Surcharge::above));
        tiers.removeIf(t -> !(taxable > t.above()));
        if (tiers.isEmpty()) return 0;
        Surcharge tier = tiers.get(tiers.size() - 1);
        double s = base * tier.rate();
        // marginal relief: tax + surcharge may not exceed tax at the threshold + income above it
        double atT = slabTax(tier.above(), c.slabs()).tax();
        double prevRate = tiers.size() > 1 ? tiers.get(tiers.size() - 2).rate() : 0;
        double capAtThreshold = atT * (1 + prevRate) + (taxable - tier.above());
        if (base + s > capAtThreshold) s = Math.max(0, capAtThreshold - base);
        return s;
    }

    /** Annual tax on {@code gross} income after the standard deduction and {@code deductions}. */
    public static TaxResult computeTax(double gross, double deductions, TaxYearConfig c) {
        double std = Math.min(c.stdDeduction(), Math.max(0, gross));
        long taxable = Math.max(0, Math.round(gross - std - deductions));
        SlabTax st = slabTax(taxable, c.slabs());
        double tax = st.tax();
        double base = tax;
        double rebate = 0;
        if (taxable <= c.rebateLimit()) {
            rebate = tax;
            base = 0;
        } else if (c.marginalRelief() && tax > taxable - c.rebateLimit()) {
            rebate = tax - (taxable - c.rebateLimit());
            base = taxable - c.rebateLimit();
        }
        double surcharge = surchargeOn(taxable, base, c);
        double cess = (base + surcharge) * c.cessRate();
        return new TaxResult(c.regime(), gross, std, deductions, taxable, tax, rebate, base, surcharge, cess, Math.round(base + surcharge + cess), st.lines());
    }

    // ───────────────────────── old-regime deductions ─────────────────────────

    public record Caps(double c80C, double c80CCD1B, double c80DSelf, double c80DParents, double c80DParentsSenior, double c24B, double c80TTA) {}

    /** Declared amounts in rupees (the TS {@code Declared}); starts as NO_DECLARATIONS. */
    public static final class Declared {
        public double rent; public boolean metro; public boolean parentsSenior;
        public double ppf, elss, lic, ssy, tuition, hlp, nsc;
        public double nps, medSelf, medParents, hli, edu, don, sav;

        /** Sets a declaration item by its key ("rent", "ppf", … as in src/lib/tax/catalogue.ts); unknown keys are ignored. */
        public void set(String key, double v) {
            switch (key) {
                case "rent" -> rent = v; case "ppf" -> ppf = v; case "elss" -> elss = v; case "lic" -> lic = v; case "ssy" -> ssy = v;
                case "tuition" -> tuition = v; case "hlp" -> hlp = v; case "nsc" -> nsc = v; case "nps" -> nps = v;
                case "medSelf" -> medSelf = v; case "medParents" -> medParents = v; case "hli" -> hli = v; case "edu" -> edu = v;
                case "don" -> don = v; case "sav" -> sav = v;
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
    public record Line(String label, long amount) {}

    public record Deductions(long total, List<Line> lines, long hraExemption, long c80C) {}

    /** HRA exemption = least of (HRA received, rent − 10% of Basic, 50% metro / 40% of Basic); caps per section. */
    public static Deductions oldRegimeDeductions(double basicAnnual, double hraAnnual, double epfAnnual, Declared d, Caps caps, Map<String, String> labels) {
        Map<String, String> lb = labels == null ? Map.of() : labels;
        double hra = d.rent != 0 ? Math.max(0, Math.min(Math.min(hraAnnual, d.rent - 0.1 * basicAnnual), (d.metro ? 0.5 : 0.4) * basicAnnual)) : 0;
        double c80C = Math.min(caps.c80C(), epfAnnual + d.ppf + d.elss + d.lic + d.ssy + d.tuition + d.hlp + d.nsc);
        double nps = Math.min(caps.c80CCD1B(), d.nps);
        double med = Math.min(caps.c80DSelf(), d.medSelf) + Math.min(d.parentsSenior ? caps.c80DParentsSenior() : caps.c80DParents(), d.medParents);
        double hli = Math.min(caps.c24B(), d.hli);
        double sav = Math.min(caps.c80TTA(), d.sav);
        Object[][] raw = {
            {lb.getOrDefault("HRA", "HRA") + " exemption", hra},
            {lb.getOrDefault("80C", "80C") + " (incl. PF)", c80C},
            {lb.getOrDefault("80CCD1B", "80CCD(1B)") + " NPS", nps},
            {lb.getOrDefault("80D_SELF", "80D") + " medical", med},
            {lb.getOrDefault("24B", "24(b)") + " home loan interest", hli},
            {lb.getOrDefault("80E", "80E") + " education loan", d.edu},
            {lb.getOrDefault("80G", "80G") + " donations", d.don},
            {lb.getOrDefault("80TTA", "80TTA") + " savings interest", sav},
        };
        double sum = 0;
        List<Line> lines = new ArrayList<>();
        for (Object[] r : raw) {
            double v = (double) r[1];
            if (v > 0) {
                sum += v;
                lines.add(new Line((String) r[0], Math.round(v)));
            }
        }
        return new Deductions(Math.round(sum), lines, Math.round(hra), Math.round(c80C));
    }

    // ───────────────────────── monthly TDS ─────────────────────────

    public record Fy(String fy, int index, int remainingInclCurrent) {}

    /** FY months from April: "2026-09" → index 5; remaining months including the current one. */
    public static Fy fyOf(String ym) {
        int y = Integer.parseInt(ym.substring(0, 4)), m = Integer.parseInt(ym.substring(5, 7));
        int start = m >= 4 ? y : y - 1;
        int index = (m - 4 + 12) % 12;
        return new Fy(start + "-" + String.format(java.util.Locale.ROOT, "%02d", (start + 1) % 100), index, 12 - index);
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
