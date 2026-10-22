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
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDDocumentInformation info = document.getDocumentInformation();
            info.setTitle("Policy acceptance record: " + title);
            info.setCreator("JurisOne");
            PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA), bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            String generated = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm 'UTC'").format(Instant.now().atOffset(ZoneOffset.UTC));
            int page = 0, i = 0;
            do {
                PDPage pDPage = new PDPage(size);
                document.addPage(pDPage);
                page++;
                try (PDPageContentStream contentStream = new PDPageContentStream(document, pDPage)) {
                    float y = size.getHeight() - margin;
                    if (page == 1) {
                        text(contentStream, bold, 15, margin, y, "Policy acceptance record");
                        y -= 18;
                        text(contentStream, font, 10, margin, y, safe(font, title + "  ·  " + business));
                        y -= 13;
                        text(contentStream, font, 8, margin, y, safe(font, rows.size() + " acceptance" + (rows.size() == 1 ? "" : "s") + "  ·  generated " + generated
                                + " by " + generatedBy + "  ·  rows are a permanent record; the fingerprints identify the exact text and PDF accepted"));
                        y -= 18;
                    }
                    // header row
                    float x = margin;
                    for (int k = 0; k < heads.length; k++) {
                        text(contentStream, bold, 7, x, y, heads[k]);
                        x += cols[k];
                    }
                    y -= 4;
                    contentStream.moveTo(margin, y);
                    contentStream.lineTo(margin + width, y);
                    contentStream.setLineWidth(0.5f);
                    contentStream.stroke();
                    y -= 10;
                    while (i < rows.size() && y > margin + 24) {
                        List<Object> row = rows.get(i++);
                        String acknowledgedAt = row.get(2) == null ? "" : row.get(2).toString().replace('T', ' ').replaceAll("\\.\\d+Z$", "").replace("Z", "");
                        String[] cells = {textOrEmpty(row.get(1)), acknowledgedAt, textOrEmpty(row.get(3)), textOrEmpty(row.get(4)), textOrEmpty(row.get(5)), textOrEmpty(row.get(6)), textOrEmpty(row.get(7))};
                        x = margin;
                        for (int k = 0; k < cells.length; k++) {
                            text(contentStream, font, 7, x, y, fit(font, 7, safe(font, cells[k]), cols[k] - 4));
                            x += cols[k];
                        }
                        y -= 9;
                        String pdfSha = textOrEmpty(row.get(9));
                        text(contentStream, font, 6, margin + cols[0], y, "Text SHA-256 " + textOrEmpty(row.get(8)) + (pdfSha.isEmpty() ? "" : "    PDF SHA-256 " + pdfSha) + "    Record " + textOrEmpty(row.get(10)));
                        y -= 13;
                    }
                    text(contentStream, font, 7, margin, margin - 14, "JurisOne · " + safe(font, title) + " · page " + page);
                }
            } while (i < rows.size());
            document.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String textOrEmpty(Object value) {
        return value == null ? "" : value.toString();
    }

    static void text(PDPageContentStream pDPageContentStream, PDFont font, float size, float x, float y, String text) throws IOException {
        pDPageContentStream.beginText();
        pDPageContentStream.setFont(font, size);
        pDPageContentStream.newLineAtOffset(x, y);
        pDPageContentStream.showText(text);
        pDPageContentStream.endText();
    }

    /** Characters the standard font can't draw become "?". */
    static String safe(PDFont font, String text) {
        StringBuilder builder = new StringBuilder(text.length());
        text.codePoints().forEach(codePoint -> {
            String character = new String(Character.toChars(codePoint));
            try {
                font.encode(character);
                builder.append(character);
            } catch (IOException | IllegalArgumentException e) {
                builder.append('?');
            }
        });
        return builder.toString();
    }

    /** Cuts the text (with "…" as "...") to fit the column. */
    static String fit(PDFont font, float size, String text, float max) throws IOException {
        if (font.getStringWidth(text) / 1000 * size <= max) return text;
        String remaining = text;
        while (remaining.length() > 1 && font.getStringWidth(remaining + "...") / 1000 * size > max) remaining = remaining.substring(0, remaining.length() - 1);
        return remaining + "...";
    }
}
