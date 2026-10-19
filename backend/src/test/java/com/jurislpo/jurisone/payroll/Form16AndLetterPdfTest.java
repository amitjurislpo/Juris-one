package com.jurislpo.jurisone.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.engine.Form16Engine;
import com.jurislpo.jurisone.engine.TaxEngine;
import com.jurislpo.jurisone.service.Form16Pdf;
import com.jurislpo.jurisone.service.Form16Service;
import com.jurislpo.jurisone.service.LetterPdf;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/** The Form 16 and letter PDFs carry the snapshot's figures and text, and flow onto more pages when needed. */
class Form16AndLetterPdfTest {

    static String text(byte[] pdf, int pages) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(pages);
            return new PDFTextStripper().getText(doc);
        }
    }

    @Test
    void form16ShowsPartAAndPartB() throws Exception {
        List<Form16Engine.MonthIn> months = new ArrayList<>();
        for (String ym : Form16Engine.fyMonths("2026-27")) months.add(new Form16Engine.MonthIn(ym, ym + "-28", 10_000_000, 4_000_000, 2_000_000, 480_000, 20_000, 600_000));
        Form16Engine.Result r = Form16Engine.compute(new Form16Engine.Input("2026-27", "NEW", months, 0, 0, new TaxEngine.Declared(),
                new TaxEngine.TaxYearConfig(TaxEngine.Regime.NEW, 75_000, 700_000, true, 0.04,
                        List.of(new TaxEngine.Slab(300_000.0, 0), new TaxEngine.Slab(700_000.0, 0.05), new TaxEngine.Slab(null, 0.1)), List.of()),
                new TaxEngine.Caps(150_000, 50_000, 25_000, 25_000, 50_000, 200_000, 10_000), Map.of()));
        ObjectMapper m = new ObjectMapper();
        JsonNode snap = m.valueToTree(new Form16Service.Snapshot(1, "2026-27", "2027-28", "2027-04-02",
                new Form16Service.Employer("Juris Consultants LLP", "Noida, Uttar Pradesh", "09AAAAA0000A1Z5", "DELJ12345A", null),
                new Form16Service.EmployeeInfo("JC-074", "Amit Jaspal", "Associate", "Technology", "2025-01-01", null, "234F", null), r));
        byte[] pdf = Form16Pdf.render(snap, "ABCDE1234F");
        String t = text(pdf, pdfPages(pdf));
        assertThat(t).contains("FORM NO. 16", "2026-27", "2027-28", "Juris Consultants LLP", "TAN: DELJ12345A", "PAN: not set", "GSTIN: 09AAAAA0000A1Z5",
                "Amit Jaspal", "PAN: ABCDE1234F", "PART A", "PART B", "Quarter-wise summary", "Q1", "Q4", "Total",
                "Rs. 12,00,000.00", "Rs. 72,000.00", "Rs. 6,000.00", "Total income (taxable)", "Tax payable for the year", "Excess TDS (refundable)",
                "not recorded in JurisOne");
    }

    static int pdfPages(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return doc.getNumberOfPages();
        }
    }

    @Test
    void aLetterFlowsOntoMorePagesAndKeepsItsReference() throws Exception {
        String shortBody = "Dear Asha,\n\nWelcome aboard.\n\nYours sincerely";
        String t = text(LetterPdf.render("Juris Consultants", "Noida", "HRL/2026-27/0001", "20 Oct 2026", "Offer of employment", shortBody, "a".repeat(64)), 1);
        assertThat(t).contains("Juris Consultants", "Ref: HRL/2026-27/0001", "20 Oct 2026", "Subject: Offer of employment", "Dear Asha,", "Welcome aboard.");

        StringBuilder longBody = new StringBuilder();
        for (int i = 0; i < 120; i++) longBody.append("Paragraph ").append(i).append(" of a very long letter that has to wrap around and continue.\n\n");
        byte[] pdf = LetterPdf.render("Juris Consultants", "Noida", "HRL/2026-27/0002", "20 Oct 2026", "Long", longBody.toString(), "b".repeat(64));
        assertThat(pdfPages(pdf)).isGreaterThan(1);
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String all = new PDFTextStripper().getText(doc);
            assertThat(all).contains("Paragraph 0 ", "Paragraph 119 ", "page 2");
        }
    }
}
