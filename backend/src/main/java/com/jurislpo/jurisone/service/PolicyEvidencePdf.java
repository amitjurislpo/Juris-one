package com.jurislpo.jurisone.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * The policy acceptance evidence as a printable PDF (landscape A4): every acceptance of every version as recorded,
 * with the SHA-256 fingerprints of what was accepted. Standard PDF fonts only, so characters they can't draw are
 * replaced with "?" (the CSV keeps the exact text).
 */
public final class PolicyEvidencePdf {
    private PolicyEvidencePdf() {}

    /** One acceptance: the columns of {@link PolicyService#EVIDENCE}, in order. */
    public static byte[] render(String business, String title, String generatedBy, List<List<Object>> rows) {
        PDRectangle size = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()); // landscape
        float margin = 36, width = size.getWidth() - 2 * margin;
        // version, accepted at, employee id, name, email, IP, browser; the fingerprints go on a second line, in full
        float[] cols = {30, 96, 62, 140, 190, 90, 162};
        String[] heads = {"Ver.", "Accepted at (UTC)", "Employee ID", "Name", "Email", "IP address", "Browser"};
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setTitle("Policy acceptance record: " + title);
            info.setCreator("JurisOne");
            PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA), bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            String generated = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'").format(Instant.now().atOffset(ZoneOffset.UTC));
            int page = 0, i = 0;
            do {
                PDPage p = new PDPage(size);
                doc.addPage(p);
                page++;
                try (PDPageContentStream c = new PDPageContentStream(doc, p)) {
                    float y = size.getHeight() - margin;
                    if (page == 1) {
                        text(c, bold, 15, margin, y, "Policy acceptance record");
                        y -= 18;
                        text(c, font, 10, margin, y, safe(font, title + "  ·  " + business));
                        y -= 13;
                        text(c, font, 8, margin, y, safe(font, rows.size() + " acceptance" + (rows.size() == 1 ? "" : "s") + "  ·  generated " + generated
                                + " by " + generatedBy + "  ·  rows are a permanent record; the fingerprints identify the exact text and PDF accepted"));
                        y -= 18;
                    }
                    // header row
                    float x = margin;
                    for (int k = 0; k < heads.length; k++) {
                        text(c, bold, 7, x, y, heads[k]);
                        x += cols[k];
                    }
                    y -= 4;
                    c.moveTo(margin, y);
                    c.lineTo(margin + width, y);
                    c.setLineWidth(0.5f);
                    c.stroke();
                    y -= 10;
                    while (i < rows.size() && y > margin + 24) {
                        List<Object> r = rows.get(i++);
                        String at = r.get(2) == null ? "" : r.get(2).toString().replace('T', ' ').replaceAll("\\.\\d+Z$", "").replace("Z", "");
                        String[] cells = {str(r.get(1)), at, str(r.get(3)), str(r.get(4)), str(r.get(5)), str(r.get(6)), str(r.get(7))};
                        x = margin;
                        for (int k = 0; k < cells.length; k++) {
                            text(c, font, 7, x, y, fit(font, 7, safe(font, cells[k]), cols[k] - 4));
                            x += cols[k];
                        }
                        y -= 9;
                        String pdf = str(r.get(9));
                        text(c, font, 6, margin + cols[0], y, "Text SHA-256 " + str(r.get(8)) + (pdf.isEmpty() ? "" : "    PDF SHA-256 " + pdf) + "    Record " + str(r.get(10)));
                        y -= 13;
                    }
                    text(c, font, 7, margin, margin - 14, "JurisOne · " + safe(font, title) + " · page " + page);
                }
            } while (i < rows.size());
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    static void text(PDPageContentStream c, PDFont f, float size, float x, float y, String s) throws IOException {
        c.beginText();
        c.setFont(f, size);
        c.newLineAtOffset(x, y);
        c.showText(s);
        c.endText();
    }

    /** Characters the standard font can't draw become "?". */
    static String safe(PDFont f, String s) {
        StringBuilder b = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> {
            String ch = new String(Character.toChars(cp));
            try {
                f.encode(ch);
                b.append(ch);
            } catch (IOException | IllegalArgumentException e) {
                b.append('?');
            }
        });
        return b.toString();
    }

    /** Cuts the text (with "…" as "...") to fit the column. */
    static String fit(PDFont f, float size, String s, float max) throws IOException {
        if (f.getStringWidth(s) / 1000 * size <= max) return s;
        String t = s;
        while (t.length() > 1 && f.getStringWidth(t + "...") / 1000 * size > max) t = t.substring(0, t.length() - 1);
        return t + "...";
    }
}
