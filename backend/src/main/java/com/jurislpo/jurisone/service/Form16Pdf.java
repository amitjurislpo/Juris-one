package com.jurislpo.jurisone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jurislpo.jurisone.engine.Format;
import java.io.IOException;
import java.time.LocalDate;

/**
 * Form 16 (Part A summary and Part B) as an A4 PDF, from the published snapshot. Standard PDF fonts can't draw the rupee
 * sign, so amounts are written "Rs.". The TDS challan details (BSR code, serial number, deposit date) are not recorded in
 * JurisOne, so Part A here is a summary of what was deducted, not the TRACES certificate.
 */
public final class Form16Pdf {
    private Form16Pdf() {}

    static String rs(long paise) {
        long r = Math.abs(paise);
        String s = Format.groupIndian(r / 100) + "." + String.format(java.util.Locale.ROOT, "%02d", r % 100);
        return (paise < 0 ? "-" : "") + "Rs. " + s;
    }

    private static String or(JsonNode n, String dflt) {
        return n == null || n.isNull() || n.asText().isBlank() ? dflt : n.asText();
    }

    /** @param pan the employee's full PAN ("" when not on record) */
    public static byte[] render(JsonNode s, String pan) {
        JsonNode er = s.get("employer"), ee = s.get("employee"), b = s.at("/computed/partB");
        String fy = s.get("fy").asText();
        try (SimplePdf p = new SimplePdf("Form 16 FY " + fy + " " + ee.get("code").asText(), er.get("name").asText(),
                "Form 16 · " + ee.get("code").asText() + " · FY " + fy)) {
            p.text("FORM NO. 16", SimplePdf.M, 18, true, SimplePdf.INK, false);
            p.text("Financial year " + fy, SimplePdf.W - SimplePdf.M, 10, false, SimplePdf.MUTED, true);
            p.gap(8);
            p.text("Certificate under section 203 of the Income-tax Act, 1961 for tax deducted at source on salary", SimplePdf.M, 9, false, SimplePdf.MUTED, false);
            p.text("Assessment year " + s.get("assessmentYear").asText(), SimplePdf.W - SimplePdf.M, 9, false, SimplePdf.MUTED, true);
            p.gap(5);
            p.rule(SimplePdf.ACCENT, 2f);
            p.gap(5);

            // employer | employee
            double mid = SimplePdf.M + 92;
            p.text("EMPLOYER (DEDUCTOR)", SimplePdf.M, 7.5f, true, SimplePdf.MUTED, false);
            p.text("EMPLOYEE", mid, 7.5f, true, SimplePdf.MUTED, false);
            p.gap(5);
            p.text(or(er.get("name"), ""), SimplePdf.M, 10.5f, true, SimplePdf.INK, false);
            p.text(or(ee.get("name"), ""), mid, 10.5f, true, SimplePdf.INK, false);
            p.gap(5);
            String[] left = {"TAN: " + or(er.get("tan"), "not set"), "PAN: " + or(er.get("pan"), "not set"), "GSTIN: " + or(er.get("gstin"), "not set")};
            String[] right = {"Employee ID: " + ee.get("code").asText(), "PAN: " + (pan == null || pan.isBlank() ? "not on record" : pan),
                "Designation: " + or(ee.get("designation"), "-"), "Department: " + or(ee.get("department"), "-")};
            int rows = Math.max(left.length, right.length);
            for (int i = 0; i < rows; i++) {
                if (i < left.length) p.text(left[i], SimplePdf.M, 9, false, SimplePdf.INK, false);
                if (i < right.length) p.text(right[i], mid, 9, false, SimplePdf.INK, false);
                p.gap(4.6);
            }
            String addr = or(er.get("address"), "");
            if (!addr.isEmpty()) {
                for (String l : p.wrap("Address: " + addr, 86, 9)) {
                    p.text(l, SimplePdf.M, 9, false, SimplePdf.INK, false);
                    p.gap(4.6);
                }
            }
            p.gap(2);
            p.text("Tax regime: " + ("OLD".equals(s.at("/computed/regime").asText()) ? "Old regime" : "New regime"), SimplePdf.M, 9, true, SimplePdf.INK, false);
            p.gap(8);

            // Part A
            heading(p, "PART A  -  Summary of tax deducted at source");
            double[] xs = {SimplePdf.M, SimplePdf.M + 45, SimplePdf.W - SimplePdf.M - 52, SimplePdf.W - SimplePdf.M - 26, SimplePdf.W - SimplePdf.M};
            boolean[] re = {false, false, true, true, true};
            p.row(new String[] {"Month", "Paid on", "Gross paid", "PF", "TDS deducted"}, xs, re, 8, true, SimplePdf.MUTED);
            p.rule(SimplePdf.LINE, 0.6f);
            p.gap(1.2);
            long tg = 0, tt = 0;
            for (JsonNode m : s.at("/computed/months")) {
                p.row(new String[] {Format.ymLabel(m.get("ym").asText()), m.hasNonNull("payDate") ? Format.fmtDY(LocalDate.parse(m.get("payDate").asText())) : "-",
                    rs(m.get("gross").asLong()), rs(m.get("pf").asLong()), rs(m.get("tds").asLong())}, xs, re, 8.5f, false, SimplePdf.INK);
                tg += m.get("gross").asLong();
                tt += m.get("tds").asLong();
            }
            p.rule(SimplePdf.INK, 0.8f);
            p.gap(1.2);
            p.row(new String[] {"Total", "", rs(tg), "", rs(tt)}, xs, re, 8.5f, true, SimplePdf.INK);
            p.gap(5);

            p.ensure(40);
            p.text("Quarter-wise summary", SimplePdf.M, 9, true, SimplePdf.INK, false);
            p.gap(5);
            double[] qx = {SimplePdf.M, SimplePdf.M + 20, SimplePdf.W - SimplePdf.M - 40, SimplePdf.W - SimplePdf.M};
            boolean[] qr = {false, false, true, true};
            p.row(new String[] {"Quarter", "Period", "Amount paid", "TDS deducted"}, qx, qr, 8, true, SimplePdf.MUTED);
            p.rule(SimplePdf.LINE, 0.6f);
            p.gap(1.2);
            for (JsonNode q : s.at("/computed/quarters")) {
                p.row(new String[] {q.get("quarter").asText(), q.get("period").asText(), rs(q.get("gross").asLong()), rs(q.get("tds").asLong())}, qx, qr, 8.5f, false, SimplePdf.INK);
            }
            p.gap(2);
            p.paragraphs("The challan details of the tax deposited (BSR code, challan serial number, date of deposit) are not recorded in JurisOne. "
                    + "This summary shows what was deducted from the salary; the TRACES-generated Part A is the authoritative certificate of deposit.", 7.5f, SimplePdf.MUTED);
            p.gap(5);

            // Part B
            p.ensure(60);
            heading(p, "PART B  -  Computation of income and tax");
            double lx = SimplePdf.M, rx = SimplePdf.W - SimplePdf.M;
            amount(p, "Gross salary (section 17(1)) from this employer", b.get("grossSalary").asLong(), lx, rx, false);
            if (b.get("previousEmployerIncome").asLong() != 0) amount(p, "Income reported by the previous employer", b.get("previousEmployerIncome").asLong(), lx, rx, false);
            amount(p, "Gross total income", b.get("grossTotalIncome").asLong(), lx, rx, true);
            for (JsonNode l : b.get("exemptions")) amount(p, "Less: " + l.get("label").asText(), l.get("amount").asLong(), lx, rx, false);
            amount(p, "Less: Standard deduction (section 16(ia))", b.get("standardDeduction").asLong(), lx, rx, false);
            for (JsonNode l : b.get("deductions")) amount(p, "Less: " + l.get("label").asText(), l.get("amount").asLong(), lx, rx, false);
            amount(p, "Total income (taxable)", b.get("taxableIncome").asLong(), lx, rx, true);
            amount(p, "Tax on total income", b.get("taxOnIncome").asLong(), lx, rx, false);
            if (b.get("rebate").asLong() != 0) amount(p, "Less: Rebate under section 87A", b.get("rebate").asLong(), lx, rx, false);
            if (b.get("surcharge").asLong() != 0) amount(p, "Add: Surcharge", b.get("surcharge").asLong(), lx, rx, false);
            amount(p, "Add: Health and education cess", b.get("cess").asLong(), lx, rx, false);
            amount(p, "Tax payable for the year", b.get("totalTax").asLong(), lx, rx, true);
            amount(p, "Less: TDS deducted by this employer", b.get("tdsCurrentEmployer").asLong(), lx, rx, false);
            if (b.get("tdsPreviousEmployer").asLong() != 0) amount(p, "Less: TDS deducted by the previous employer", b.get("tdsPreviousEmployer").asLong(), lx, rx, false);
            long bal = b.get("balance").asLong();
            amount(p, bal >= 0 ? "Balance tax payable" : "Excess TDS (refundable)", Math.abs(bal), lx, rx, true);
            p.gap(2);
            p.paragraphs("Provident fund contributed: " + rs(b.get("providentFund").asLong()) + ". Professional tax deducted: " + rs(b.get("professionalTax").asLong())
                    + ". Tax is computed with the same tables and rules as the monthly TDS in JurisOne.", 7.5f, SimplePdf.MUTED);
            p.gap(4);
            p.paragraphs("Generated from the closed payroll records of " + s.get("employer").get("name").asText() + " on " + s.get("generatedOn").asText()
                    + ". Content fingerprint (SHA-256 of the data this page was made from) is shown in JurisOne beside the download.", 7.5f, SimplePdf.MUTED);
            return p.finish();
        } catch (IOException e) {
            throw new IllegalStateException("Form 16 PDF", e);
        }
    }

    private static void heading(SimplePdf p, String t) {
        p.ensure(14);
        p.panel(7, SimplePdf.PANEL);
        p.gap(1.6);
        p.text(t, SimplePdf.M + 2, 9, true, SimplePdf.INK, false);
        p.gap(7);
    }

    private static void amount(SimplePdf p, String label, long paise, double lx, double rx, boolean strong) {
        p.ensure(6);
        if (strong) {
            p.rule(SimplePdf.LINE, 0.5f);
            p.gap(1);
        }
        p.text(label, lx + 1, 9, strong, SimplePdf.INK, false);
        p.text(rs(paise), rx - 1, 9, strong, SimplePdf.INK, true);
        p.gap(5);
    }
}
