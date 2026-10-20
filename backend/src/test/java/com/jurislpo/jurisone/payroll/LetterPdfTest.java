package com.jurislpo.jurisone.payroll;

import static org.assertj.core.api.Assertions.assertThat;

import com.jurislpo.jurisone.service.LetterPdf;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/** The letter PDF carries the letter text, and flow onto more pages when needed. */
class LetterPdfTest {

    static String text(byte[] pdf, int pages) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            assertThat(doc.getNumberOfPages()).isEqualTo(pages);
            return new PDFTextStripper().getText(doc);
        }
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
