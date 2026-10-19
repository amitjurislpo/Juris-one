package com.jurislpo.jurisone.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jurislpo.jurisone.engine.Format;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

/**
 * A4 full & final settlement statement from the frozen snapshot — the same look as the payslip (Helvetica, mm from the
 * top, "Rs." because the standard fonts have no rupee sign). Long statements continue on further pages.
 */
@Component
public class SettlementPdf {

    static final Color INK = PayslipPdf.INK, MUTED = PayslipPdf.MUTED, FAINT = PayslipPdf.FAINT, LINE = PayslipPdf.LINE, ACCENT = PayslipPdf.ACCENT,
            PANEL = PayslipPdf.PANEL, BAD = PayslipPdf.BAD;
    static final float W = 210, M = 16, BOTTOM = 282;

    private static float mm(double v) {
        return PayslipPdf.mm(v);
    }

    private static String rs(long paise) {
        return (paise < 0 ? "-" : "") + PayslipPdf.rs(Math.abs(paise));
    }

    /** Draws onto the current page and starts a new one when a row would run off the bottom. */
    private static final class Page implements AutoCloseable {
        final PDDocument doc;
        final PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA), bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        PDPageContentStream cs;
        float h;
        double y;

        Page(PDDocument doc) throws IOException {
            this.doc = doc;
            next();
        }

        void next() throws IOException {
            if (cs != null) cs.close();
            PDPage page = new PDPage(new PDRectangle(mm(210), mm(297)));
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            h = page.getMediaBox().getHeight();
            y = 16;
        }

        void ensure(double need) throws IOException {
            if (y + need > BOTTOM) next();
        }

        void text(String t, double x, double yy, float size, PDFont f, Color c, boolean right) throws IOException {
            float w = f.getStringWidth(t) / 1000 * size;
            cs.beginText();
            cs.setFont(f, size);
            cs.setNonStrokingColor(c);
            cs.newLineAtOffset(mm(x) - (right ? w : 0), h - mm(yy));
            cs.showText(t);
            cs.endText();
        }

        void line(double yy, Color c, float thickness) throws IOException {
            cs.setStrokingColor(c);
            cs.setLineWidth(thickness);
            cs.moveTo(mm(M), h - mm(yy));
            cs.lineTo(mm(W - M), h - mm(yy));
            cs.stroke();
        }

        void rect(double yy, double hh, Color c) throws IOException {
            cs.setNonStrokingColor(c);
            cs.addRect(mm(M), h - mm(yy + hh), mm(W - 2 * M), mm(hh));
            cs.fill();
        }

        /** A label on the left and an amount on the right. */
        void row(String label, String note, long paise, boolean strong) throws IOException {
            ensure(8);
            text(label, M, y, 9.5f, strong ? bold : font, INK, false);
            if (note != null && !note.isEmpty()) text(note, W - M - 38, y, 8, font, MUTED, true);
            text(rs(paise), W - M, y, 9.5f, strong ? bold : font, paise < 0 ? BAD : INK, true);
            line(y + 2.5, LINE, 0.6f);
            y += 8;
        }

        void head(String t) throws IOException {
            ensure(14);
            y += 3;
            text(t, M, y, 8, bold, MUTED, false);
            line(y + 2, INK, 0.8f);
            y += 8;
        }

        @Override
        public void close() throws IOException {
            if (cs != null) cs.close();
            cs = null;
        }
    }

    private static String days(double d) {
        return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
    }

    public byte[] render(JsonNode s) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream(); Page p = new Page(doc)) {
            JsonNode e = s.get("employee"), x = s.get("exit"), r = s.get("result");
            doc.getDocumentInformation().setTitle("Full & final settlement " + e.get("code").asText());
            doc.getDocumentInformation().setAuthor(s.at("/company/name").asText());
            doc.getDocumentInformation().setCreator("JurisOne");

            p.text("FULL & FINAL SETTLEMENT", W - M, p.y + 7, 16, p.bold, INK, true);
            p.text(Format.ymLabel(s.get("month").asText()), W - M, p.y + 13, 11, p.font, MUTED, true);
            p.text(s.at("/company/name").asText() + " · " + s.at("/company/address").asText(), W - M, p.y + 18.5, 8.5f, p.font, MUTED, true);
            p.y += 28;
            p.line(p.y, ACCENT, 2.2f);
            p.y += 8;

            double[] cols = {M, M + 62, M + 124};
            kv(p, cols[0], "Employee", e.get("name").asText());
            kv(p, cols[1], "Employee ID", e.get("code").asText());
            kv(p, cols[2], "Designation", e.get("designation").asText());
            p.y += 13;
            kv(p, cols[0], "Department", e.get("department").asText());
            kv(p, cols[1], "Date of joining", Format.fmtDY(LocalDate.parse(e.get("doj").asText())));
            kv(p, cols[2], "Last working day", Format.fmtDY(LocalDate.parse(x.get("lastWorkingDay").asText())));
            p.y += 13;
            String bank = e.hasNonNull("bankLast4") ? (e.hasNonNull("bankName") ? e.get("bankName").asText() : "Bank") + " XXXX" + e.get("bankLast4").asText() : "—";
            kv(p, cols[0], "Reason for exit", switch (x.get("kind").asText()) { case "RESIGNATION" -> "Resignation"; case "TERMINATION" -> "Termination"; default -> "Other"; });
            kv(p, cols[1], "Service", r.get("serviceYears").asInt() + " completed year" + (r.get("serviceYears").asInt() == 1 ? "" : "s"));
            kv(p, cols[2], "Bank account", bank);
            p.y += 14;

            p.rect(p.y, 18, PANEL);
            String[][] att = {{"Days in month", days(r.get("daysInMonth").asDouble())}, {"Days paid", days(r.get("eligibleDays").asDouble())},
                {"Loss of pay", days(r.get("lopDays").asDouble())}, {"Paid days", days(r.get("paidDays").asDouble())}};
            for (int i = 0; i < att.length; i++) {
                double ax = M + 5 + i * ((W - 2 * M - 10) / 4);
                p.text(att[i][0], ax, p.y + 7, 7.5f, p.font, MUTED, false);
                p.text(att[i][1], ax, p.y + 14, 12, p.bold, "Loss of pay".equals(att[i][0]) && r.get("lopDays").asDouble() != 0 ? BAD : INK, false);
            }
            p.y += 24;

            p.head("PAYABLE TO THE EMPLOYEE");
            if (r.get("salaryAlreadyPaid").asBoolean()) {
                p.row("Salary for the month", "already paid through payroll", 0, false);
            } else {
                for (JsonNode c : r.get("components")) {
                    if (c.get("rate").asLong() != 0) p.row(c.get("label").asText(), "rate " + rs(c.get("rate").asLong()), c.get("earned").asLong(), false);
                }
            }
            if (r.get("leaveEncashment").asLong() != 0 || r.get("encashableDays").asDouble() != 0) {
                StringBuilder lv = new StringBuilder();
                for (JsonNode l : s.get("leave")) lv.append(lv.length() == 0 ? "" : ", ").append(l.get("type").asText()).append(' ').append(days(l.get("days").asDouble()));
                p.row("Leave encashment", lv.length() == 0 ? days(r.get("encashableDays").asDouble()) + " days" : lv + " days", r.get("leaveEncashment").asLong(), false);
            }
            if (r.get("gratuity").asLong() != 0) p.row("Gratuity", "15/26 x last Basic x " + r.get("serviceYears").asInt() + " years", r.get("gratuity").asLong(), false);
            for (JsonNode l : r.get("additions")) p.row(l.get("label").asText(), null, l.get("amount").asLong(), false);
            p.row("Total earnings", null, r.get("totalEarnings").asLong(), true);

            p.head("DEDUCTIONS");
            if (r.get("pf").asLong() != 0) p.row("Provident fund (12% of Basic)", null, r.get("pf").asLong(), false);
            if (r.get("pt").asLong() != 0) p.row("Professional tax", null, r.get("pt").asLong(), false);
            if (r.get("noticeRecovery").asLong() != 0) {
                p.row("Notice period recovery", r.get("noticeShortfallDays").asInt() + " of " + r.get("noticeRequiredDays").asInt() + " days short", r.get("noticeRecovery").asLong(), false);
            }
            for (JsonNode l : r.get("deductions")) p.row(l.get("label").asText(), null, l.get("amount").asLong(), false);
            p.row("Income tax (TDS)", null, r.get("tds").asLong(), false);
            p.row("Total deductions", null, r.get("totalDeductions").asLong(), true);

            p.ensure(40);
            p.y += 4;
            long net = r.get("net").asLong();
            p.rect(p.y, 24, net < 0 ? BAD : ACCENT);
            p.text(net < 0 ? "RECOVERABLE FROM THE EMPLOYEE" : "NET PAYABLE", M + 6, p.y + 8, 8.5f, p.font, Color.WHITE, false);
            p.text("Rupees " + s.get("netWords").asText() + " only", M + 6, p.y + 15.5, 9, p.font, Color.WHITE, false);
            p.text(rs(Math.abs(net)), W - M - 6, p.y + 15, 20, p.bold, Color.WHITE, true);
            p.y += 34;

            p.ensure(30);
            java.util.List<String> notes = new java.util.ArrayList<>();
            notes.add("Per-day rate = monthly gross / days in the last month. Notice served: " + r.get("noticeServedDays").asInt() + " day(s) of " + r.get("noticeRequiredDays").asInt()
                    + (x.hasNonNull("noticeReason") ? " (" + x.get("noticeReason").asText() + ")" : "") + ".");
            notes.add("TDS is the remaining tax for the financial year (" + ("OLD".equals(s.get("regime").asText()) ? "old" : "new") + " regime), deducted in this settlement.");
            if (s.hasNonNull("note") && !s.get("note").asText().isEmpty()) notes.add("Note: " + s.get("note").asText());
            notes.add("Approved by " + s.get("approvedBy").asText() + " on " + Format.fmtDY(LocalDate.parse(s.get("approvedOn").asText())) + ". System-generated; no signature needed.");
            for (String n : notes) {
                p.ensure(6);
                p.text(n, M, p.y, 8, p.font, MUTED, false);
                p.y += 5;
            }
            p.close();
            doc.save(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("settlement PDF", ex);
        }
    }

    private static void kv(Page p, double x, String k, String v) throws IOException {
        p.text(k.toUpperCase(), x, p.y, 7.5f, p.font, FAINT, false);
        p.text(v, x, p.y + 5, 10.5f, p.bold, INK, false);
    }
}
