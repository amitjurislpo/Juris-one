package com.jurislpo.jurisone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jurislpo.jurisone.config.JurisOneProperties;
import com.jurislpo.jurisone.engine.Format;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.stereotype.Component;

/**
 * A4 payslip PDF from an immutable snapshot (REQUIREMENTS §5.11) — a line-for-line port of src/lib/payroll/pdf.ts
 * (pdf-lib): same coordinates in mm from the top, Helvetica / Helvetica-Bold, logo, colours and wording.
 * Standard PDF fonts can't draw "₹", so amounts are written "Rs.".
 */
@Component
public class PayslipPdf {

    static final Color INK = new Color(20, 20, 26), MUTED = new Color(110, 112, 125), FAINT = new Color(130, 132, 145), LINE = new Color(230, 230, 236),
            ACCENT = new Color(110, 86, 207), PANEL = new Color(244, 244, 247), BAD = new Color(207, 58, 64);
    static final float W = 210, M = 16;

    private final Path projectDir;

    public PayslipPdf(JurisOneProperties props) {
        this.projectDir = Path.of(props.projectDir() == null ? "." : props.projectDir());
    }

    static float mm(double v) {
        return (float) (v * 72 / 25.4);
    }

    static String rs(long paise) {
        return "Rs. " + Format.groupIndian(Math.round(paise / 100.0));
    }

    private final class Canvas {
        final PDPageContentStream cs;
        final float H;
        final PDFont font, bold;

        Canvas(PDPageContentStream cs, float h, PDFont font, PDFont bold) {
            this.cs = cs;
            this.H = h;
            this.font = font;
            this.bold = bold;
        }

        float width(String t, PDFont f, float size) throws IOException {
            return f.getStringWidth(t) / 1000 * size;
        }

        void text(String t, double x, double y, float size, PDFont f, Color c, boolean right) throws IOException {
            float w = width(t, f, size);
            cs.beginText();
            cs.setFont(f, size);
            cs.setNonStrokingColor(c);
            cs.newLineAtOffset(mm(x) - (right ? w : 0), H - mm(y));
            cs.showText(t);
            cs.endText();
        }

        void line(double x1, double y, double x2, Color c, float thickness) throws IOException {
            cs.setStrokingColor(c);
            cs.setLineWidth(thickness);
            cs.moveTo(mm(x1), H - mm(y));
            cs.lineTo(mm(x2), H - mm(y));
            cs.stroke();
        }

        void rect(double x, double y, double w, double h, Color c) throws IOException {
            cs.setNonStrokingColor(c);
            cs.addRect(mm(x), H - mm(y + h), mm(w), mm(h));
            cs.fill();
        }
    }

    public byte[] render(JsonNode s) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            JsonNode e = s.get("employee");
            String ymLabel = Format.ymLabel(s.get("ym").asText());
            PDDocumentInformation info = doc.getDocumentInformation();
            info.setTitle("Payslip " + e.get("code").asText() + " " + ymLabel);
            info.setAuthor(s.at("/company/name").asText());
            info.setCreator("JurisOne");
            PDPage page = new PDPage(new PDRectangle(mm(210), mm(297)));
            doc.addPage(page);
            PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA), bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                Canvas c = new Canvas(cs, page.getMediaBox().getHeight(), font, bold);
                double y = 16;
                try {
                    Path logo = projectDir.resolve("public/brand/logo-ink.png");
                    PDImageXObject img = PDImageXObject.createFromByteArray(doc, Files.readAllBytes(logo), "logo");
                    double w = 46, h = (double) img.getHeight() / img.getWidth() * w;
                    cs.drawImage(img, mm(M), c.H - mm(y + h), mm(w), mm(h));
                } catch (IOException | RuntimeException ignored) {
                    // logo is decorative
                }
                c.text("PAYSLIP", W - M, y + 7, 18, bold, INK, true);
                c.text(ymLabel, W - M, y + 13, 11, font, MUTED, true);
                c.text(s.at("/company/name").asText() + " · " + s.at("/company/address").asText(), W - M, y + 18.5, 8.5f, font, MUTED, true);
                y += 28;
                c.line(M, y, W - M, ACCENT, 2.2f);
                y += 8;

                double[] cols = {M, M + 62, M + 124};
                kv(c, cols[0], y, "Employee", e.get("name").asText());
                kv(c, cols[1], y, "Employee ID", e.get("code").asText());
                kv(c, cols[2], y, "Designation", e.get("designation").asText());
                y += 13;
                kv(c, cols[0], y, "Department", e.get("department").asText());
                kv(c, cols[1], y, "Date of joining", Format.fmtDY(LocalDate.parse(e.get("doj").asText())));
                kv(c, cols[2], y, "Tax regime", "OLD".equals(s.get("regime").asText()) ? "Old regime" : "New regime");
                y += 13;
                String pan = e.hasNonNull("panLast4") ? "XXXXXX" + e.get("panLast4").asText() : "—";
                String bank = e.hasNonNull("bankLast4") ? (e.hasNonNull("bankName") ? e.get("bankName").asText() : "Bank") + " XXXX" + e.get("bankLast4").asText() : "—";
                kv(c, cols[0], y, "PAN", pan);
                kv(c, cols[1], y, "Bank account", bank);
                kv(c, cols[2], y, "Pay date", Format.fmtDY(LocalDate.parse(s.get("payDate").asText())));
                y += 15;

                c.rect(M, y, W - 2 * M, 20, PANEL);
                JsonNode a = s.get("attendance");
                String[][] att = {{"Days in month", num(a.get("daysInMonth"))}, {"Weekly offs", num(a.get("weeklyOffs"))}, {"Holidays", num(a.get("holidays"))},
                    {"Present", num(a.get("present"))}, {"Loss of pay", num(a.get("lopDays"))}, {"Paid days", num(a.get("paidDays"))}};
                for (int i = 0; i < att.length; i++) {
                    double x = M + 5 + i * ((W - 2 * M - 10) / 6);
                    c.text(att[i][0], x, y + 7.5, 7.5f, font, MUTED, false);
                    boolean red = att[i][0].equals("Loss of pay") && a.get("lopDays").asDouble() != 0;
                    c.text(att[i][1], x, y + 15, 13, bold, red ? BAD : INK, false);
                }
                y += 28;

                double half = (W - 2 * M - 8) / 2, xE = M, xD = M + half + 8;
                head(c, xE, y, half, "EARNINGS", "RATE", "EARNED");
                head(c, xD, y, half, "DEDUCTIONS", "", "AMOUNT");
                double ye = y + 8, yd = y + 8;
                for (JsonNode l : s.get("components")) {
                    c.text(l.get("label").asText(), xE, ye, 9.5f, font, INK, false);
                    if (l.get("rate").asLong() != 0) c.text(rs(l.get("rate").asLong()), xE + half - 30, ye, 9.5f, font, MUTED, true);
                    c.text(rs(l.get("earned").asLong()), xE + half, ye, 9.5f, font, INK, true);
                    c.line(xE, ye + 2.5, xE + half, LINE, 0.6f);
                    ye += 8;
                }
                for (JsonNode d : s.get("deductions")) {
                    c.text(d.get("label").asText(), xD, yd, 9.5f, font, INK, false);
                    c.text(rs(d.get("amount").asLong()), xD + half, yd, 9.5f, font, INK, true);
                    c.line(xD, yd + 2.5, xD + half, LINE, 0.6f);
                    yd += 8;
                }
                double yt = Math.max(ye, yd) + 2;
                c.text("Gross earnings", xE, yt, 9.5f, bold, INK, false);
                c.text(rs(s.get("grossEarned").asLong()), xE + half, yt, 9.5f, bold, INK, true);
                c.text("Total deductions", xD, yt, 9.5f, bold, INK, false);
                c.text(rs(s.get("totalDeductions").asLong()), xD + half, yt, 9.5f, bold, INK, true);

                y = yt + 10;
                c.rect(M, y, W - 2 * M, 24, ACCENT);
                c.text("NET PAY", M + 6, y + 8, 8.5f, font, Color.WHITE, false);
                wrap(c, "Rupees " + s.get("netWords").asText() + " only", M + 6, y + 15.5, 120, font, 9, Color.WHITE);
                c.text(rs(s.get("net").asLong()), W - M - 6, y + 15, 20, bold, Color.WHITE, true);

                y += 34;
                List<String> notes = new ArrayList<>(List.of(
                        "Earned = monthly rate x paid days / days in month. PF is 12% of earned Basic (wage ceiling Rs. 15,000).",
                        "TDS is based on the " + ("OLD".equals(s.get("regime").asText()) ? "old regime with verified investment proofs." : "new regime.")));
                if (s.hasNonNull("note") && !s.get("note").asText().isEmpty()) notes.add("Note: " + s.get("note").asText());
                notes.add("This is a system-generated payslip and does not require a signature.");
                for (int i = 0; i < notes.size(); i++) c.text(notes.get(i), M, y + i * 5, 8, font, MUTED, false);
            }
            doc.save(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("payslip PDF", ex);
        }
    }

    /** JS String(number): 22 not 22.0. */
    static String num(JsonNode n) {
        double d = n.asDouble();
        return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
    }

    private void kv(Canvas c, double x, double y, String k, String v) throws IOException {
        c.text(k.toUpperCase(), x, y, 7.5f, c.font, FAINT, false);
        c.text(v, x, y + 5, 10.5f, c.bold, INK, false);
    }

    private void head(Canvas c, double x, double y, double half, String t, String c2, String c3) throws IOException {
        c.text(t, x, y, 8, c.bold, MUTED, false);
        if (!c2.isEmpty()) c.text(c2, x + half - 30, y, 8, c.bold, MUTED, true);
        c.text(c3, x + half, y, 8, c.bold, MUTED, true);
        c.line(x, y + 2, x + half, INK, 0.8f);
    }

    private void wrap(Canvas c, String t, double xmm, double ymm, double wmm, PDFont f, float size, Color color) throws IOException {
        String line = "";
        int row = 0;
        for (String w : t.split(" ")) {
            String next = line.isEmpty() ? w : line + " " + w;
            if (c.width(next, f, size) > mm(wmm) && !line.isEmpty()) {
                c.text(line, xmm, ymm + row * 4.2, size, f, color, false);
                line = w;
                row++;
            } else {
                line = next;
            }
        }
        if (!line.isEmpty()) c.text(line, xmm, ymm + row * 4.2, size, f, color, false);
    }
}
