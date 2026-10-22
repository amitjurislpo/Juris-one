package com.jurislpo.jurisone.payroll;

import com.jurislpo.jurisone.service.PayslipPdf;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.config.JacksonConfig;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/** The payslip PDF carries the snapshot's figures; JSON numbers are written like JavaScript's. */
class PayslipPdfTest {

    static final String SNAPSHOT = """
        {"version":1,"ym":"2026-09","company":{"name":"Juris Consultants","address":"Noida"},
         "employee":{"code":"JC-074","name":"Amit Jaspal","designation":"Associate","department":"Technology","doj":"2026-09-08","panLast4":"234F","uanLast4":null,"bankName":"HDFC","bankLast4":"1234"},
         "regime":"NEW","payDate":"2026-10-01",
         "attendance":{"daysInMonth":30,"eligibleDays":23,"weeklyOffs":6,"holidays":0,"present":16,"lopDays":1,"paidDays":22},
         "components":[{"label":"Basic","rate":7500000,"earned":5500000},{"label":"House rent allowance","rate":3000000,"earned":2200000},
                       {"label":"Leave travel allowance","rate":750000,"earned":550000},{"label":"Special allowance","rate":3750000,"earned":2750000}],
         "bonus":0,"grossEarned":11000000,"pf":180000,"pt":20000,"tds":1492600,"otherDeduction":0,
         "deductions":[{"label":"Provident fund (12% of Basic)","amount":180000},{"label":"Professional tax","amount":20000},{"label":"Income tax (TDS)","amount":1492600}],
         "totalDeductions":1692600,"net":9307400,"netWords":"Ninety Three Thousand Seventy Four","note":null}""";

    @Test
    void rendersTheSnapshot() throws Exception {
        JsonNode snapshot = new ObjectMapper().readTree(SNAPSHOT);
        byte[] pdfBytes = new PayslipPdf().render(snapshot);
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            String text = new PDFTextStripper().getText(document);
            assertThat(document.getNumberOfPages()).isEqualTo(1);
            // the company logo is drawn (it used to be skipped silently when its file was missing)
            int images = 0;
            for (var name : document.getPage(0).getResources().getXObjectNames()) if (document.getPage(0).getResources().isImageXObject(name)) images++;
            assertThat(images).isEqualTo(1);
            assertThat(document.getDocumentInformation().getTitle()).isEqualTo("Payslip JC-074 September 2026");
            assertThat(text).contains("PAYSLIP", "September 2026", "Amit Jaspal", "XXXXXX234F", "HDFC XXXX1234", "8 Sep 2026", "1 Oct 2026",
                    "Rs. 93,074", "Rs. 1,10,000", "Rs. 14,926", "Rupees Ninety Three Thousand Seventy Four only", "TDS is based on the new regime.");
            assertThat(text).contains("22").doesNotContain("22.0");
        }
    }

    @Test
    void jsonNumbersLookLikeJavaScript() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(JacksonConfig.jsNumbers());
        assertThat(mapper.writeValueAsString(Map.of("a", 1.0))).isEqualTo("{\"a\":1}");
        assertThat(mapper.writeValueAsString(Map.of("a", 1.5))).isEqualTo("{\"a\":1.5}");
        assertThat(mapper.writeValueAsString(java.util.List.of(22.0, 0.5))).isEqualTo("[22,0.5]");
        record PaidDays(double paid, Double lop) {}
        assertThat(mapper.writeValueAsString(new PaidDays(22.0, 1.0))).isEqualTo("{\"paid\":22,\"lop\":1}");
    }
}
