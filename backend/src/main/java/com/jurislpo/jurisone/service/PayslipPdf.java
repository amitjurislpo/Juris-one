package com.jurislpo.jurisone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jurislpo.jurisone.engine.Format;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
 * A4 payslip PDF from an immutable snapshot (REQUIREMENTS §5.11) drawn with the
 * following layout: same coordinates in mm from the top, Helvetica / Helvetica-Bold, logo, colours and wording.
 * Standard PDF fonts can't draw "₹", so amounts are written "Rs.".
 */
@Component
public class PayslipPdf {

    static final Color INK = new Color(20, 20, 26), MUTED = new Color(110, 112, 125), FAINT = new Color(130, 132, 145), LINE = new Color(230, 230, 236),
            ACCENT = new Color(110, 86, 207), PANEL = new Color(244, 244, 247), BAD = new Color(207, 58, 64);
    static final float W = 210, M = 16;

    /** The company logo drawn at the top of every payslip (classpath: brand/logo-ink.png). */
    private final byte[] logo;

    public PayslipPdf() {
        try (var logoStream = PayslipPdf.class.getResourceAsStream("/brand/logo-ink.png")) {
            if (logoStream == null) throw new IllegalStateException("brand/logo-ink.png is missing from the classpath");
            this.logo = logoStream.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read brand/logo-ink.png", e);
        }
    }

    static float millimetresToPoints(double millimetres) {
        return (float) (millimetres * 72 / 25.4);
    }

    static String formatRupees(long paise) {
        return "Rs. " + Format.groupIndian(Math.round(paise / 100.0));
    }

    private final class Canvas {
        final PDPageContentStream contentStream;
        final float H;
        final PDFont font, bold;

        Canvas(PDPageContentStream pDPageContentStream, float h, PDFont font, PDFont bold) {
            this.contentStream = pDPageContentStream;
            this.H = h;
            this.font = font;
            this.bold = bold;
        }

        float width(String text, PDFont font, float size) throws IOException {
            return font.getStringWidth(text) / 1000 * size;
        }

        void text(String text, double x, double y, float size, PDFont font, Color color, boolean right) throws IOException {
            float w = width(text, font, size);
            contentStream.beginText();
            contentStream.setFont(font, size);
            contentStream.setNonStrokingColor(color);
            contentStream.newLineAtOffset(millimetresToPoints(x) - (right ? w : 0), H - millimetresToPoints(y));
            contentStream.showText(text);
            contentStream.endText();
        }

        void line(double x1, double y, double x2, Color color, float thickness) throws IOException {
            contentStream.setStrokingColor(color);
            contentStream.setLineWidth(thickness);
            contentStream.moveTo(millimetresToPoints(x1), H - millimetresToPoints(y));
            contentStream.lineTo(millimetresToPoints(x2), H - millimetresToPoints(y));
            contentStream.stroke();
        }

        void rect(double x, double y, double w, double h, Color color) throws IOException {
            contentStream.setNonStrokingColor(color);
            contentStream.addRect(millimetresToPoints(x), H - millimetresToPoints(y + h), millimetresToPoints(w), millimetresToPoints(h));
            contentStream.fill();
        }
    }

    public byte[] render(JsonNode storedSnapshot) {
        JsonNode snapshot = PayrollService.currentSnapshotFormat(storedSnapshot);
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            JsonNode employee = snapshot.get("employee");
            String monthLabel = Format.monthLabel(snapshot.get("month").asText());
            PDDocumentInformation info = document.getDocumentInformation();
            info.setTitle("Payslip " + employee.get("code").asText() + " " + monthLabel);
            info.setAuthor(snapshot.at("/company/name").asText());
            info.setCreator("JurisOne");
            PDPage page = new PDPage(new PDRectangle(millimetresToPoints(210), millimetresToPoints(297)));
            document.addPage(page);
            PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA), bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                Canvas canvas = new Canvas(contentStream, page.getMediaBox().getHeight(), font, bold);
                double y = 16;
                PDImageXObject logoImage = PDImageXObject.createFromByteArray(document, logo, "logo");
                double w = 46, h = (double) logoImage.getHeight() / logoImage.getWidth() * w;
                contentStream.drawImage(logoImage, millimetresToPoints(M), canvas.H - millimetresToPoints(y + h), millimetresToPoints(w), millimetresToPoints(h));
                canvas.text("PAYSLIP", W - M, y + 7, 18, bold, INK, true);
                canvas.text(monthLabel, W - M, y + 13, 11, font, MUTED, true);
                canvas.text(snapshot.at("/company/name").asText() + " · " + snapshot.at("/company/address").asText(), W - M, y + 18.5, 8.5f, font, MUTED, true);
                y += 28;
                canvas.line(M, y, W - M, ACCENT, 2.2f);
                y += 8;

                double[] cols = {M, M + 62, M + 124};
                keyValue(canvas, cols[0], y, "Employee", employee.get("name").asText());
                keyValue(canvas, cols[1], y, "Employee ID", employee.get("code").asText());
                keyValue(canvas, cols[2], y, "Designation", employee.get("designation").asText());
                y += 13;
                keyValue(canvas, cols[0], y, "Department", employee.get("department").asText());
                keyValue(canvas, cols[1], y, "Date of joining", Format.formatDayMonthYear(LocalDate.parse(employee.get("dateOfJoining").asText())));
                keyValue(canvas, cols[2], y, "Tax regime", "OLD".equals(snapshot.get("regime").asText()) ? "Old regime" : "New regime");
                y += 13;
                String pan = employee.hasNonNull("panLast4") ? "XXXXXX" + employee.get("panLast4").asText() : "—";
                String bank = employee.hasNonNull("bankLast4") ? (employee.hasNonNull("bankName") ? employee.get("bankName").asText() : "Bank") + " XXXX" + employee.get("bankLast4").asText() : "—";
                keyValue(canvas, cols[0], y, "PAN", pan);
                keyValue(canvas, cols[1], y, "Bank account", bank);
                keyValue(canvas, cols[2], y, "Pay date", Format.formatDayMonthYear(LocalDate.parse(snapshot.get("payDate").asText())));
                y += 15;

                canvas.rect(M, y, W - 2 * M, 20, PANEL);
                JsonNode attendance = snapshot.get("attendance");
                String[][] attendanceRows = {{"Days in month", formatNumber(attendance.get("daysInMonth"))}, {"Weekly offs", formatNumber(attendance.get("weeklyOffs"))}, {"Holidays", formatNumber(attendance.get("holidays"))},
                    {"Present", formatNumber(attendance.get("present"))}, {"Loss of pay", formatNumber(attendance.get("lopDays"))}, {"Paid days", formatNumber(attendance.get("paidDays"))}};
                for (int i = 0; i < attendanceRows.length; i++) {
                    double x = M + 5 + i * ((W - 2 * M - 10) / 6);
                    canvas.text(attendanceRows[i][0], x, y + 7.5, 7.5f, font, MUTED, false);
                    boolean isHighlighted = attendanceRows[i][0].equals("Loss of pay") && attendance.get("lopDays").asDouble() != 0;
                    canvas.text(attendanceRows[i][1], x, y + 15, 13, bold, isHighlighted ? BAD : INK, false);
                }
                y += 28;

                double half = (W - 2 * M - 8) / 2, earningsX = M, deductionsX = M + half + 8;
                head(canvas, earningsX, y, half, "EARNINGS", "RATE", "EARNED");
                head(canvas, deductionsX, y, half, "DEDUCTIONS", "", "AMOUNT");
                double earningsY = y + 8, deductionsY = y + 8;
                for (JsonNode node : snapshot.get("components")) {
                    canvas.text(node.get("label").asText(), earningsX, earningsY, 9.5f, font, INK, false);
                    if (node.get("rate").asLong() != 0) canvas.text(formatRupees(node.get("rate").asLong()), earningsX + half - 30, earningsY, 9.5f, font, MUTED, true);
                    canvas.text(formatRupees(node.get("earned").asLong()), earningsX + half, earningsY, 9.5f, font, INK, true);
                    canvas.line(earningsX, earningsY + 2.5, earningsX + half, LINE, 0.6f);
                    earningsY += 8;
                }
                for (JsonNode node : snapshot.get("deductions")) {
                    canvas.text(node.get("label").asText(), deductionsX, deductionsY, 9.5f, font, INK, false);
                    canvas.text(formatRupees(node.get("amount").asLong()), deductionsX + half, deductionsY, 9.5f, font, INK, true);
                    canvas.line(deductionsX, deductionsY + 2.5, deductionsX + half, LINE, 0.6f);
                    deductionsY += 8;
                }
                double totalsY = Math.max(earningsY, deductionsY) + 2;
                canvas.text("Gross earnings", earningsX, totalsY, 9.5f, bold, INK, false);
                canvas.text(formatRupees(snapshot.get("grossEarned").asLong()), earningsX + half, totalsY, 9.5f, bold, INK, true);
                canvas.text("Total deductions", deductionsX, totalsY, 9.5f, bold, INK, false);
                canvas.text(formatRupees(snapshot.get("totalDeductions").asLong()), deductionsX + half, totalsY, 9.5f, bold, INK, true);

                y = totalsY + 10;
                canvas.rect(M, y, W - 2 * M, 24, ACCENT);
                canvas.text("NET PAY", M + 6, y + 8, 8.5f, font, Color.WHITE, false);
                wrap(canvas, "Rupees " + snapshot.get("netWords").asText() + " only", M + 6, y + 15.5, 120, font, 9, Color.WHITE);
                canvas.text(formatRupees(snapshot.get("net").asLong()), W - M - 6, y + 15, 20, bold, Color.WHITE, true);

                y += 34;
                List<String> notes = new ArrayList<>(List.of(
                        "Earned = monthly rate x paid days / days in month. PF is 12% of earned Basic (wage ceiling Rs. 15,000).",
                        "TDS is based on the " + ("OLD".equals(snapshot.get("regime").asText()) ? "old regime with verified investment proofs." : "new regime.")));
                if (snapshot.hasNonNull("note") && !snapshot.get("note").asText().isEmpty()) notes.add("Note: " + snapshot.get("note").asText());
                notes.add("This is a system-generated payslip and does not require a signature.");
                for (int i = 0; i < notes.size(); i++) canvas.text(notes.get(i), M, y + i * 5, 8, font, MUTED, false);
            }
            document.save(out);
            return out.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("payslip PDF", exception);
        }
    }

    /** JS String(number): 22 not 22.0. */
    static String formatNumber(JsonNode node) {
        double amount = node.asDouble();
        return amount == Math.rint(amount) ? Long.toString((long) amount) : Double.toString(amount);
    }

    private void keyValue(Canvas canvas, double x, double y, String key, String value) throws IOException {
        canvas.text(key.toUpperCase(), x, y, 7.5f, canvas.font, FAINT, false);
        canvas.text(value, x, y + 5, 10.5f, canvas.bold, INK, false);
    }

    private void head(Canvas canvas, double x, double y, double half, String title, String secondColumn, String thirdColumn) throws IOException {
        canvas.text(title, x, y, 8, canvas.bold, MUTED, false);
        if (!secondColumn.isEmpty()) canvas.text(secondColumn, x + half - 30, y, 8, canvas.bold, MUTED, true);
        canvas.text(thirdColumn, x + half, y, 8, canvas.bold, MUTED, true);
        canvas.line(x, y + 2, x + half, INK, 0.8f);
    }

    private void wrap(Canvas canvas, String text, double xmm, double ymm, double wmm, PDFont font, float size, Color color) throws IOException {
        String line = "";
        int row = 0;
        for (String word : text.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (canvas.width(next, font, size) > millimetresToPoints(wmm) && !line.isEmpty()) {
                canvas.text(line, xmm, ymm + row * 4.2, size, font, color, false);
                line = word;
                row++;
            } else {
                line = next;
            }
        }
        if (!line.isEmpty()) canvas.text(line, xmm, ymm + row * 4.2, size, font, color, false);
    }
}
