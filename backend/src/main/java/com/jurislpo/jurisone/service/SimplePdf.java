package com.jurislpo.jurisone.service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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

/**
 * A small top-down A4 writer (standard Helvetica, millimetres from the top-left) that starts a new page when the next
 * block doesn't fit. Characters the standard fonts can't draw are replaced with "?" like in the other PDFs.
 */
final class SimplePdf implements AutoCloseable {

    static final Color INK = new Color(20, 20, 26), MUTED = new Color(110, 112, 125), LINE = new Color(215, 215, 224), ACCENT = new Color(110, 86, 207),
            PANEL = new Color(244, 244, 247);
    static final double W = 210, H = 297, M = 18, BOTTOM = 18;

    final PDDocument doc = new PDDocument();
    final PDFont font = new PDType1Font(Standard14Fonts.FontName.HELVETICA), bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    private PDPageContentStream cs;
    private final String footer;
    private int pages;
    double y;

    SimplePdf(String title, String author, String footer) {
        PDDocumentInformation info = doc.getDocumentInformation();
        info.setTitle(title);
        info.setAuthor(author);
        info.setCreator("JurisOne");
        this.footer = footer;
        newPage();
    }

    static float mm(double v) {
        return (float) (v * 72 / 25.4);
    }

    private void newPage() {
        try {
            if (cs != null) {
                footer();
                cs.close();
            }
            PDPage page = new PDPage(new PDRectangle(mm(W), mm(H)));
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            pages++;
            y = M;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void footer() throws IOException {
        raw(PolicyEvidencePdf.safe(font, footer) + "  ·  page " + pages, M, H - 10, 7, font, MUTED, false);
    }

    /** Starts a new page unless {@code h} mm still fit. */
    void ensure(double h) {
        if (y + h > H - BOTTOM) newPage();
    }

    void gap(double mm) {
        y += mm;
    }

    float width(String t, PDFont font, float size) {
        try {
            return font.getStringWidth(PolicyEvidencePdf.safe(font, t)) / 1000 * size;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void raw(String t, double x, double yMm, float size, PDFont font, Color color, boolean right) throws IOException {
        float w = font.getStringWidth(t) / 1000 * size;
        cs.beginText();
        cs.setFont(font, size);
        cs.setNonStrokingColor(color);
        cs.newLineAtOffset(mm(x) - (right ? w : 0), mm(H - yMm));
        cs.showText(t);
        cs.endText();
    }

    /** One line at the current y (does not move y). {@code x} is the left edge, or the right edge when {@code right}. */
    void text(String t, double x, float size, boolean isBold, Color color, boolean right) {
        try {
            raw(PolicyEvidencePdf.safe(isBold ? bold : font, t), x, y + size * 0.3528 * 0.8, size, isBold ? bold : font, color, right);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A line of text that moves y down by its height. */
    void line(String t, float size, boolean isBold, Color color) {
        double h = size * 0.3528 * 1.45;
        ensure(h);
        text(t, M, size, isBold, color, false);
        y += h;
    }

    /** Word-wrapped paragraph(s); "\n" keeps line breaks, a blank line leaves a gap. */
    void paragraphs(String body, float size, Color color) {
        double h = size * 0.3528 * 1.5;
        for (String para : body.replace("\r\n", "\n").split("\n", -1)) {
            if (para.isBlank()) {
                y += h * 0.6;
                continue;
            }
            for (String l : wrap(para, W - 2 * M, size)) {
                ensure(h);
                text(l, M, size, false, color, false);
                y += h;
            }
        }
    }

    List<String> wrap(String para, double widthMm, float size) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : para.split(" ")) {
            String next = cur.length() == 0 ? word : cur + " " + word;
            if (width(next, font, size) > mm(widthMm) && cur.length() > 0) {
                out.add(cur.toString());
                cur = new StringBuilder(word);
            } else {
                cur = new StringBuilder(next);
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    void rule(Color color, float thickness) {
        try {
            cs.setStrokingColor(color);
            cs.setLineWidth(thickness);
            cs.moveTo(mm(M), mm(H - y));
            cs.lineTo(mm(W - M), mm(H - y));
            cs.stroke();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    void panel(double h, Color color) {
        try {
            cs.setNonStrokingColor(color);
            cs.addRect(mm(M), mm(H - y - h), mm(W - 2 * M), mm(h));
            cs.fill();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A table row: cells at the given left x positions (mm); {@code rightEdge[i]} true = x is the cell's right edge. */
    void row(String[] cells, double[] xs, boolean[] rightEdge, float size, boolean isBold, Color color) {
        double h = size * 0.3528 * 1.7;
        ensure(h);
        for (int i = 0; i < cells.length; i++) text(cells[i], xs[i], size, isBold, color, rightEdge[i]);
        y += h;
    }

    byte[] finish() {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            footer();
            cs.close();
            cs = null;
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() throws IOException {
        if (cs != null) cs.close();
        doc.close();
    }
}
