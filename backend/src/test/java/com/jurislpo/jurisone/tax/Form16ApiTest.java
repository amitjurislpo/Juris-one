package com.jurislpo.jurisone.tax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.TestData;
import com.jurislpo.jurisone.crypto.PiiCrypto;
import com.jurislpo.jurisone.engine.Form16Engine;
import com.jurislpo.jurisone.service.PolicyService;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Form 16: published per financial year only when all 12 months are closed, immutable once published, readable by its
 * employee and by Finance, never by HR or another business; plus the Form 24Q working file.
 */
class Form16ApiTest extends IntegrationTest {

    static final String PW = "correct-horse-battery-7";
    static final String FY = "2030-31";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PiiCrypto pii;

    TestData data;
    String suffix;
    UUID business, e1, e2;
    Cookie fin, finExec, hr, emp1, emp2;
    String emp1Code;

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder b) {
        return b.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    Cookie signIn(String email) throws Exception {
        String set = mvc.perform(write(post("/api/auth/session")).content("{\"identifier\":\"" + email + "\",\"password\":\"" + PW + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher m = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(m.find()).isTrue();
        return new Cookie("jo_at", m.group(1));
    }

    @BeforeEach
    void setUp() throws Exception {
        suffix = TestData.letters(8);
        data = new TestData(jdbc).inBusiness("f16-" + suffix);
        business = data.business();
        UUID finRole = data.role("fm", "self", "dir", "people.view", "pay.view", "pay.close", "tax.view");
        UUID execRole = data.role("fe", "self", "dir", "pay.view", "tax.view");
        UUID hrRole = data.role("hrm", "self", "dir", "people.view", "people.add", "letters.manage");
        UUID empRole = data.role("employee", "self", "dir");
        data.employee("F-FIN-" + suffix, "fin." + suffix + "@example.com", finRole, PW);
        data.employee("F-EXE-" + suffix, "exec." + suffix + "@example.com", execRole, PW);
        data.employee("F-HR-" + suffix, "hr." + suffix + "@example.com", hrRole, PW);
        emp1Code = "F-E1-" + suffix;
        e1 = data.employee(emp1Code, "e1." + suffix + "@example.com", empRole, PW);
        e2 = data.employee("F-E2-" + suffix, "e2." + suffix + "@example.com", empRole, PW);
        jdbc.update("UPDATE employees SET pan_enc = ?, pan_last4 = ? WHERE id = ?", pii.encrypt("ABCDE1234F"), PiiCrypto.last4("ABCDE1234F"), e1);
        fin = signIn("fin." + suffix + "@example.com");
        finExec = signIn("exec." + suffix + "@example.com");
        hr = signIn("hr." + suffix + "@example.com");
        emp1 = signIn("e1." + suffix + "@example.com");
        emp2 = signIn("e2." + suffix + "@example.com");
        taxTables();
    }

    /** A plain NEW and OLD table for a year nobody else uses (the real ones are seeded data). */
    void taxTables() {
        jdbc.update("""
            INSERT INTO tax_years (fy, regime, std_deduction, rebate_limit, marginal_relief, cess_rate, slabs, surcharge) VALUES
            (?, 'NEW', 75000, 700000, true, 0.04,
             '[{"upto":300000,"rate":0},{"upto":700000,"rate":0.05},{"upto":1000000,"rate":0.1},{"upto":1200000,"rate":0.15},{"upto":1500000,"rate":0.2},{"upto":null,"rate":0.3}]'::jsonb, '[]'::jsonb),
            (?, 'OLD', 50000, 500000, false, 0.04,
             '[{"upto":250000,"rate":0},{"upto":500000,"rate":0.05},{"upto":1000000,"rate":0.2},{"upto":null,"rate":0.3}]'::jsonb, '[]'::jsonb)
            ON CONFLICT DO NOTHING""", FY, FY);
    }

    /** A closed payroll month with a payslip for e1 (and for e2 from Oct 2030): gross Rs 1,00,000 and TDS Rs 6,000 a month. */
    void closeMonth(String ym) throws Exception {
        UUID run = UUID.randomUUID();
        jdbc.update("INSERT INTO payroll_runs (id, business_id, month, status, closed_at, pay_date) VALUES (?, ?, ?, 'CLOSED', now(), ?)", run, business, ym,
                Date.valueOf(LocalDate.parse(ym + "-28")));
        slip(run, e1, ym);
        if (ym.compareTo("2030-10") >= 0) slip(run, e2, ym);
    }

    void slip(UUID run, UUID emp, String ym) throws Exception {
        String snap = json.writeValueAsString(Map.of("version", 1, "ym", ym, "payDate", ym + "-28", "regime", "NEW",
                "components", List.of(Map.of("label", "Basic", "rate", 4_000_000, "earned", 4_000_000), Map.of("label", "House rent allowance", "rate", 2_000_000, "earned", 2_000_000)),
                "grossEarned", 10_000_000, "pf", 480_000, "pt", 20_000, "tds", 600_000));
        jdbc.update("INSERT INTO payslips (run_id, employee_id, snapshot) VALUES (?, ?, ?::jsonb)", run, emp, snap);
    }

    void closeYear(boolean includingMarch) throws Exception {
        for (String ym : Form16Engine.fyMonths(FY)) if (includingMarch || !ym.equals("2031-03")) closeMonth(ym);
    }

    String publish(Cookie c) throws Exception {
        return mvc.perform(write(post("/api/form16/" + FY + "/forms")).cookie(c)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    String formId(UUID employee) {
        return jdbc.queryForObject("SELECT id::text FROM form16_forms WHERE employee_id = ? AND fy = ?", String.class, employee, FY);
    }

    @Test
    void itIsPublishedOnlyWhenAllTwelveMonthsAreClosed() throws Exception {
        closeYear(false);
        mvc.perform(write(post("/api/form16/" + FY + "/forms")).cookie(fin)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.allOf(org.hamcrest.Matchers.containsString("needs all 12 months"),
                        org.hamcrest.Matchers.containsString("Mar 2031"), org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Feb 2031")))));
        mvc.perform(get("/api/form16?fy=" + FY).cookie(fin)).andExpect(status().isOk()).andExpect(jsonPath("$.data.ready").value(false))
                .andExpect(jsonPath("$.data.missing[0]").value("Mar 2031")).andExpect(jsonPath("$.data.rows.length()").value(2));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM form16_forms WHERE fy = ? AND business_id = ?", Integer.class, FY, business)).isZero();

        closeMonth("2031-03");
        mvc.perform(get("/api/form16?fy=" + FY).cookie(fin)).andExpect(jsonPath("$.data.ready").value(true));
        mvc.perform(write(post("/api/form16/" + FY + "/forms")).cookie(fin)).andExpect(status().isCreated()).andExpect(jsonPath("$.data.published").value(2));

        // a bad year is a 400
        mvc.perform(write(post("/api/form16/2030-99/forms")).cookie(fin)).andExpect(status().isBadRequest());
    }

    @Test
    void publishedFormsAreSnapshotsTheEmployeeAndFinanceCanOpen() throws Exception {
        closeYear(true);
        mvc.perform(write(put("/api/form16/deductor")).cookie(fin).content("{\"tan\":\"abcd12345e\",\"pan\":\"AAACJ1234K\"}")).andExpect(status().isOk());
        mvc.perform(write(post("/api/form16/" + FY + "/forms")).cookie(fin)).andExpect(status().isCreated()).andExpect(jsonPath("$.data.published").value(2))
                .andExpect(jsonPath("$.data.alreadyPublished").value(0)).andExpect(jsonPath("$.msg").value("2 Form 16s published for FY " + FY));

        // the employee sees only their own
        mvc.perform(get("/api/form16/mine").cookie(emp1)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].fy").value(FY)).andExpect(jsonPath("$.data[0].tdsTotal").value(7_200_000))
                .andExpect(jsonPath("$.data[0].taxableIncome").value(112_500_000)).andExpect(jsonPath("$.data[0].totalTax").value(7_150_000));
        String id = formId(e1);
        String body = mvc.perform(get("/api/form16/forms/" + id).cookie(emp1)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.snapshot.employee.pan").value("XXXXXX234F")).andExpect(jsonPath("$.data.snapshot.employee.panEnc").doesNotExist())
                .andExpect(jsonPath("$.data.snapshot.employer.tan").value("ABCD12345E")).andExpect(jsonPath("$.data.snapshot.employer.pan").value("AAACJ1234K"))
                .andExpect(jsonPath("$.data.snapshot.employer.name").value("Business f16-" + suffix))
                .andExpect(jsonPath("$.data.snapshot.computed.regime").value("NEW")).andExpect(jsonPath("$.data.snapshot.computed.months.length()").value(12))
                .andExpect(jsonPath("$.data.snapshot.computed.quarters[3].tds").value(1_800_000))
                .andExpect(jsonPath("$.data.snapshot.computed.partB.balance").value(-50_000)).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("ABCDE1234F"); // the full PAN is never in the API data

        // the stored snapshot and PDF match their fingerprints; the PDF is what downloads
        Map<String, Object> row = jdbc.queryForMap("SELECT snapshot, snapshot_sha256, pdf, pdf_sha256 FROM form16_forms WHERE id = ?::uuid", id);
        assertThat(row.get("snapshot_sha256")).isEqualTo(PolicyService.sha256(((String) row.get("snapshot")).getBytes(StandardCharsets.UTF_8)));
        assertThat(row.get("pdf_sha256")).isEqualTo(PolicyService.sha256((byte[]) row.get("pdf")));
        assertThat((String) row.get("snapshot")).doesNotContain("ABCDE1234F");
        byte[] pdf = mvc.perform(get("/api/form16/forms/" + id + "/pdf").cookie(emp1)).andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF)).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
        assertThat(pdf).isEqualTo((byte[]) row.get("pdf"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE employee_id = ? AND text LIKE '%Form 16%'", Integer.class, e1)).isEqualTo(1);

        // e2 joined in October: six months, own form only
        JsonNode e2snap = json.readTree(jdbc.queryForObject("SELECT snapshot FROM form16_forms WHERE employee_id = ?", String.class, e2));
        assertThat(e2snap.at("/computed/months")).hasSize(6);
        assertThat(e2snap.at("/computed/quarters/0/gross").asLong()).isZero();

        // Finance (even without pay.close) sees everyone's; the overview lists both
        mvc.perform(get("/api/form16?fy=" + FY).cookie(finExec)).andExpect(status().isOk()).andExpect(jsonPath("$.data.canPublish").value(false))
                .andExpect(jsonPath("$.data.rows.length()").value(2)).andExpect(jsonPath("$.data.rows[?(@.code == '" + emp1Code + "')].formId").value(id))
                .andExpect(jsonPath("$.data.deductor.tan").value("ABCD12345E"));
        mvc.perform(get("/api/form16/forms/" + id).cookie(finExec)).andExpect(status().isOk());
        mvc.perform(get("/api/form16/forms/" + id + "/pdf").cookie(fin)).andExpect(status().isOk());
    }

    @Test
    void hrAndOtherEmployeesNeverSeeIt() throws Exception {
        closeYear(true);
        publish(fin);
        String id = formId(e1);
        // another employee and HR: 403 on the form and its PDF
        mvc.perform(get("/api/form16/forms/" + id).cookie(emp2)).andExpect(status().isForbidden());
        mvc.perform(get("/api/form16/forms/" + id + "/pdf").cookie(emp2)).andExpect(status().isForbidden());
        mvc.perform(get("/api/form16/forms/" + id).cookie(hr)).andExpect(status().isForbidden());
        mvc.perform(get("/api/form16/forms/" + id + "/pdf").cookie(hr)).andExpect(status().isForbidden());
        // HR has no Finance page, no publishing, no 24Q, no deductor numbers; their own list is empty
        mvc.perform(get("/api/form16?fy=" + FY).cookie(hr)).andExpect(status().isForbidden());
        mvc.perform(write(post("/api/form16/" + FY + "/forms")).cookie(hr)).andExpect(status().isForbidden());
        mvc.perform(get("/api/form16/" + FY + "/24q?quarter=Q1").cookie(hr)).andExpect(status().isForbidden());
        mvc.perform(write(put("/api/form16/deductor")).cookie(hr).content("{\"tan\":\"ABCD12345E\"}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/form16/mine").cookie(hr)).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(0));
        // employees can't publish or open the Finance overview either
        mvc.perform(write(post("/api/form16/" + FY + "/forms")).cookie(emp1)).andExpect(status().isForbidden());
        mvc.perform(get("/api/form16?fy=" + FY).cookie(emp1)).andExpect(status().isForbidden());
        // Finance without pay.close can read but not publish or export
        mvc.perform(write(post("/api/form16/" + FY + "/forms")).cookie(finExec)).andExpect(status().isForbidden());
        // unknown / malformed ids
        mvc.perform(get("/api/form16/forms/" + UUID.randomUUID()).cookie(fin)).andExpect(status().isNotFound());
        mvc.perform(get("/api/form16/forms/not-an-id").cookie(fin)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/form16/mine")).andExpect(status().isUnauthorized());
    }

    @Test
    void publishedFormsAreImmutable() throws Exception {
        closeYear(true);
        publish(fin);
        String id = formId(e1);
        Map<String, Object> before = jdbc.queryForMap("SELECT snapshot_sha256, pdf_sha256, published_at FROM form16_forms WHERE id = ?::uuid", id);

        // the figures change afterwards (a new tax profile with previous-employer income); nothing already published moves
        jdbc.update("INSERT INTO tax_profiles (employee_id, fy, regime, prev_employer_income, prev_employer_tds, updated_at) VALUES (?, ?, 'OLD', 5000000, 100000, now())", e1, FY);
        mvc.perform(write(post("/api/form16/" + FY + "/forms")).cookie(fin)).andExpect(status().isCreated()).andExpect(jsonPath("$.data.published").value(0))
                .andExpect(jsonPath("$.data.alreadyPublished").value(2)).andExpect(jsonPath("$.msg").value("Every Form 16 for FY " + FY + " was already published"));
        assertThat(jdbc.queryForMap("SELECT snapshot_sha256, pdf_sha256, published_at FROM form16_forms WHERE id = ?::uuid", id)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM form16_forms WHERE fy = ? AND business_id = ?", Integer.class, FY, business)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE employee_id = ? AND text LIKE '%Form 16%'", Integer.class, e1)).isEqualTo(1);

        // and the database itself refuses edits and deletes
        assertThatThrownBy(() -> jdbc.update("UPDATE form16_forms SET pdf = ? WHERE id = ?::uuid", new byte[] {1}, id)).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permanent record");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM form16_forms WHERE id = ?::uuid", id)).isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permanent record");
        // there is no edit or delete route
        mvc.perform(write(put("/api/form16/forms/" + id)).cookie(fin).content("{}")).andExpect(status().isMethodNotAllowed());
        mvc.perform(write(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/form16/forms/" + id)).cookie(fin)).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void anotherBusinessSeesNothing() throws Exception {
        closeYear(true);
        publish(fin);
        String id = formId(e1);
        String s2 = TestData.letters(8);
        TestData other = new TestData(jdbc).inBusiness("f16b-" + s2);
        UUID role = other.role("fm", "self", "dir", "tax.view", "pay.close");
        other.employee("G-FIN-" + s2, "fin2." + s2 + "@example.com", role, PW);
        Cookie fin2 = signIn("fin2." + s2 + "@example.com");
        mvc.perform(get("/api/form16/forms/" + id).cookie(fin2)).andExpect(status().isNotFound());
        mvc.perform(get("/api/form16/forms/" + id + "/pdf").cookie(fin2)).andExpect(status().isNotFound());
        mvc.perform(get("/api/form16?fy=" + FY).cookie(fin2)).andExpect(status().isOk()).andExpect(jsonPath("$.data.rows.length()").value(0))
                .andExpect(jsonPath("$.data.ready").value(false));
        mvc.perform(write(post("/api/form16/" + FY + "/forms")).cookie(fin2)).andExpect(status().isUnprocessableEntity()); // its own months aren't closed
        assertThat(jdbc.queryForObject("SELECT count(*) FROM form16_forms WHERE fy = ? AND business_id = ?", Integer.class, FY, business)).isEqualTo(2);
    }

    @Test
    void theForm24QFileIsAWorkingFileForFinanceOnly() throws Exception {
        closeYear(false);
        String csv = mvc.perform(get("/api/form16/" + FY + "/24q?quarter=Q1").cookie(fin)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String[] lines = csv.split("\r\n");
        assertThat(lines[0]).contains("NOT A FILED RETURN").contains("Challan columns");
        assertThat(lines[1]).contains("PAN of employee").contains("BSR code of bank branch").contains("Challan serial number").contains("Date of deposit");
        assertThat(lines).hasSize(5); // note, header, 3 months of the one employee on payroll in Q1
        assertThat(lines[2]).startsWith("﻿Q1,2030-04".substring(1)).contains(emp1Code).contains("ABCDE1234F").contains("100000.00").contains("6000.00").endsWith(",,,,");
        assertThat(lines[4]).contains("2030-06");

        // Q4 needs March (not closed here); a bad quarter is a 400; others can't export
        mvc.perform(get("/api/form16/" + FY + "/24q?quarter=Q4").cookie(fin)).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("Mar 2031")));
        mvc.perform(get("/api/form16/" + FY + "/24q?quarter=Q9").cookie(fin)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/form16/" + FY + "/24q").cookie(fin)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/form16/" + FY + "/24q?quarter=Q1").cookie(finExec)).andExpect(status().isForbidden());
        mvc.perform(get("/api/form16/" + FY + "/24q?quarter=Q1").cookie(emp1)).andExpect(status().isForbidden());
        // reading PANs in full is audited
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'form16.24q_export' AND business_id = ?", Integer.class, business)).isEqualTo(1);
    }

    @Test
    void deductorNumbersAreOptionalAndValidated() throws Exception {
        mvc.perform(get("/api/form16?fy=" + FY).cookie(fin)).andExpect(jsonPath("$.data.deductor.tan").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(write(put("/api/form16/deductor")).cookie(fin).content("{\"tan\":\"12345\",\"pan\":\"\"}")).andExpect(status().isBadRequest());
        mvc.perform(write(put("/api/form16/deductor")).cookie(fin).content("{\"tan\":\"\",\"pan\":\"nope\"}")).andExpect(status().isBadRequest());
        mvc.perform(write(put("/api/form16/deductor")).cookie(finExec).content("{\"tan\":\"ABCD12345E\"}")).andExpect(status().isForbidden());
        mvc.perform(write(put("/api/form16/deductor")).cookie(fin).content("{\"tan\":\"ABCD12345E\",\"pan\":\"\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/form16?fy=" + FY).cookie(fin)).andExpect(jsonPath("$.data.deductor.tan").value("ABCD12345E"))
                .andExpect(jsonPath("$.data.deductor.pan").value(org.hamcrest.Matchers.nullValue()));
        mvc.perform(write(put("/api/form16/deductor")).cookie(fin).content("{\"tan\":\"\",\"pan\":\"\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/form16?fy=" + FY).cookie(fin)).andExpect(jsonPath("$.data.deductor.tan").value(org.hamcrest.Matchers.nullValue()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'form16.deductor_change' AND business_id = ?", Integer.class, business)).isEqualTo(2);
    }
}
