package com.jurislpo.jurisone.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.TestData;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** HR invites a candidate by link; the candidate fills in details and documents; HR verifies, adds the employee, then enables access. */
class OnboardingApiTest extends IntegrationTest {

    static final String PW = "correct-horse-battery-7";
    static final byte[] PDF = "%PDF-1.4\n%doc\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    Cookie hr;
    UUID dept, empRole, hrId;
    String s;

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
    void business() throws Exception {
        s = TestData.letters(8);
        TestData t = new TestData(jdbc).inBusiness("onboard-" + s);
        UUID hrRole = t.role("hrm", "self", "dir", "people.view", "people.add", "access.toggle");
        empRole = t.role("employee", "self", "dir");
        jdbc.update("INSERT INTO role_assignable_roles (role_id, assignable_role_id) VALUES (?, ?) ON CONFLICT DO NOTHING", hrRole, empRole);
        for (String[] sh : new String[][] {{"IN_NIGHT", "19:30", "04:30", "IN"}, {"IN_DAY", "09:30", "18:30", "IN"}, {"US_DAY", "09:00", "18:00", "US"}}) {
            jdbc.update("INSERT INTO shifts (id, code, name, start_local, end_local, region) SELECT ?, ?, ?, ?, ?, ?::\"Region\" WHERE NOT EXISTS (SELECT 1 FROM shifts WHERE code = ?)",
                    UUID.randomUUID(), sh[0], sh[0], sh[1], sh[2], sh[3], sh[0]);
        }
        dept = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, name, business_id) VALUES (?, 'Litigation', ?)", dept, t.business());
        hrId = t.employee("OH-" + s, "hr." + s + "@example.com", hrRole, PW);
        hr = signIn("hr." + s + "@example.com");
    }

    String lastLink(String to) {
        String text = jdbc.queryForObject("SELECT text FROM email_outbox WHERE \"to\" = ? ORDER BY created_at DESC LIMIT 1", String.class, to);
        Matcher m = Pattern.compile("/onboard/([A-Za-z0-9_-]+)").matcher(text);
        assertThat(m.find()).isTrue();
        return m.group(1);
    }

    String upload(String token, String kind) throws Exception {
        return json.readTree(mvc.perform(multipart("/api/onboarding/" + token + "/documents").file(new MockMultipartFile("file", kind.toLowerCase() + ".pdf", "application/pdf", PDF))
                .param("kind", kind).header("origin", "http://localhost:3005").header("host", "localhost:3005")).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString()).at("/data/id").asText();
    }

    static final String FORM = """
        {"firstName":"Riya","middleName":"","lastName":"Sen","fatherName":"Amit Sen","dateOfBirth":"1996-04-02","gender":"Female","phoneCountry":"+91","phone":"98100 11223",
         "currentAddress":"14 Park Street, Kolkata 700016","permanentAddress":"","pan":"abcpe1234f","aadhaar":"2345 6789 0123","uan":"",
         "bankName":"HDFC Bank","bankAccount":"50100123456789","ifsc":"hdfc0001234","previousEmployer":"Lex & Co","previousDesignation":"Associate",
         "emergencyName":"Amit Sen","emergencyRelation":"Father","emergencyPhoneCountry":"+91","emergencyPhone":"98100 44556"}""";

    @Test
    void fromLinkToEmployee() throws Exception {
        String personal = "riya." + s + "@gmail.test";
        String id = json.readTree(mvc.perform(write(post("/api/onboardings")).cookie(hr).content("""
            {"candidateName":"Riya Sen","personalEmail":"%s","designation":"Associate","departmentId":"%s","roleId":"%s","joiningDate":"2031-11-03"}"""
                .formatted(personal.toUpperCase(), dept, empRole))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).at("/data/id").asText();
        String token = lastLink(personal);
        assertThat(jdbc.queryForObject("SELECT token_hash FROM onboardings WHERE id = ?::uuid", String.class, id)).doesNotContain(token); // only the hash is kept

        // the candidate, signed out
        mvc.perform(get("/api/onboarding/" + token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.candidateName").value("Riya Sen"))
                .andExpect(jsonPath("$.data.editable").value(true)).andExpect(jsonPath("$.data.checklist.length()").value(5));
        mvc.perform(get("/api/onboarding/not-a-real-token-at-all-123")).andExpect(status().isNotFound());
        mvc.perform(write(post("/api/onboarding/" + token + "/submission"))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Enter your father's name."));
        mvc.perform(write(put("/api/onboarding/" + token)).content(FORM)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT form_enc FROM onboardings WHERE id = ?::uuid", String.class, id)).doesNotContain("ABCPE1234F"); // encrypted
        mvc.perform(multipart("/api/onboarding/" + token + "/documents").file(new MockMultipartFile("file", "x.pdf", "application/pdf", "nope".getBytes()))
                .param("kind", "PAN").header("origin", "http://localhost:3005").header("host", "localhost:3005")).andExpect(status().isBadRequest());
        for (String k : new String[] {"ID_PROOF", "ADDRESS_PROOF", "PAN", "AADHAAR", "SALARY_SLIP", "SALARY_SLIP"}) upload(token, k);
        mvc.perform(write(post("/api/onboarding/" + token + "/submission"))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Upload Salary slips (last 3 months) (3 files)."));
        String slip3 = upload(token, "SALARY_SLIP");
        mvc.perform(write(post("/api/onboarding/" + token + "/submission"))).andExpect(status().isCreated());
        mvc.perform(write(put("/api/onboarding/" + token)).content(FORM)).andExpect(status().isUnprocessableEntity()); // read-only once submitted

        // HR reviews: identity numbers masked; every document must be decided; a rejected one goes back to the candidate
        JsonNode d = json.readTree(mvc.perform(get("/api/onboardings/" + id).cookie(hr)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).at("/data");
        assertThat(d.at("/status").asText()).isEqualTo("SUBMITTED");
        assertThat(d.at("/form/pan").asText()).isEqualTo("XXXXXX234F");
        assertThat(d.at("/form/bankAccount").asText()).endsWith("6789");
        mvc.perform(write(put("/api/onboardings/" + id + "/status")).cookie(hr).content("{\"status\":\"VERIFIED\"}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Verify or reject every document first."));
        for (JsonNode doc : d.at("/documents")) {
            String body = doc.at("/id").asText().equals(slip3) ? "{\"status\":\"REJECTED\",\"comment\":\"Blurred, please upload again\"}" : "{\"status\":\"VERIFIED\"}";
            mvc.perform(write(put("/api/onboardings/" + id + "/documents/" + doc.at("/id").asText() + "/status")).cookie(hr).content(body)).andExpect(status().isOk());
        }
        mvc.perform(write(put("/api/onboardings/" + id + "/status")).cookie(hr).content("{\"status\":\"VERIFIED\"}")).andExpect(status().isUnprocessableEntity());
        mvc.perform(write(put("/api/onboardings/" + id + "/status")).cookie(hr).content("{\"status\":\"CHANGES_REQUESTED\",\"comment\":\"One salary slip is blurred.\"}"))
                .andExpect(status().isOk());
        String token2 = lastLink(personal);
        mvc.perform(get("/api/onboarding/" + token)).andExpect(status().isNotFound()); // the old link stops working
        mvc.perform(get("/api/onboarding/" + token2)).andExpect(jsonPath("$.data.hrComment").value("One salary slip is blurred."));
        String verifiedDoc = d.at("/documents/0/id").asText();
        mvc.perform(write(delete("/api/onboarding/" + token2 + "/documents/" + verifiedDoc))).andExpect(status().isUnprocessableEntity());
        String slip4 = upload(token2, "SALARY_SLIP");
        mvc.perform(write(post("/api/onboarding/" + token2 + "/submission"))).andExpect(status().isCreated());
        mvc.perform(write(put("/api/onboardings/" + id + "/documents/" + slip4 + "/status")).cookie(hr).content("{\"status\":\"VERIFIED\"}")).andExpect(status().isOk());
        mvc.perform(write(put("/api/onboardings/" + id + "/status")).cookie(hr).content("{\"status\":\"VERIFIED\"}")).andExpect(status().isOk());

        // add the employee: access off, no invite yet; documents move to their record
        String work = "riya.sen." + s + "@firm.test";
        JsonNode added = json.readTree(mvc.perform(write(post("/api/onboardings/" + id + "/employee")).cookie(hr).content("""
            {"workEmail":"%s","designation":"Associate","departmentId":"%s","managerId":"%s","roleId":"%s","joiningDate":"2031-11-03","location":"IN_IST","shift":"IN_DAY"}"""
                .formatted(work, dept, hrId, empRole))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(added.at("/msg").asText()).contains("portal access is off");
        String emp = added.at("/data/id").asText();
        var e = jdbc.queryForMap("SELECT portal_access, pan_last4, bank_account_last4, bank_ifsc, full_name FROM employees WHERE id = ?::uuid", emp);
        assertThat(e.get("portal_access")).isEqualTo(false);
        assertThat(e.get("pan_last4")).isEqualTo("234F");
        assertThat(e.get("bank_account_last4")).isEqualTo("6789");
        assertThat(e.get("bank_ifsc")).isEqualTo("HDFC0001234");
        var names = jdbc.queryForMap("SELECT first_name, last_name, full_name, phone_country, emergency_name, emergency_phone, date_of_birth FROM employees WHERE id = ?::uuid", emp);
        assertThat(names.get("full_name")).isEqualTo("Riya Sen");
        assertThat(names.get("last_name")).isEqualTo("Sen");
        assertThat(names.get("phone_country")).isEqualTo("+91");
        assertThat(names.get("emergency_phone")).isEqualTo("+91 9810044556");
        assertThat(names.get("date_of_birth").toString()).isEqualTo("1996-04-02");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE \"to\" = ?", Integer.class, work)).isZero();
        mvc.perform(get("/api/people/" + emp + "/documents").cookie(hr)).andExpect(jsonPath("$.data.length()").value(7)); // the rejected one stays behind
        mvc.perform(get("/api/onboarding/" + token2)).andExpect(status().isNotFound());

        // HR turns access on: the invite goes to the work email
        mvc.perform(write(put("/api/people/" + emp + "/access")).cookie(hr).content("{\"on\":true}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.endsWith("invite sent to their work email")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE \"to\" = ?", Integer.class, work)).isEqualTo(1);
    }

    @Test
    void aCancelledLinkStopsWorkingAndOtherUsersCantSeeOnboarding() throws Exception {
        String personal = "kabir." + s + "@gmail.test";
        String id = json.readTree(mvc.perform(write(post("/api/onboardings")).cookie(hr).content("{\"candidateName\":\"Kabir Rao\",\"personalEmail\":\"" + personal + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).at("/data/id").asText();
        mvc.perform(write(post("/api/onboardings")).cookie(hr).content("{\"candidateName\":\"Kabir Rao\",\"personalEmail\":\"" + personal + "\"}"))
                .andExpect(status().isUnprocessableEntity()); // one open onboarding per email
        String token = lastLink(personal);
        mvc.perform(write(put("/api/onboardings/" + id + "/status")).cookie(hr).content("{\"status\":\"CANCELLED\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/onboarding/" + token)).andExpect(status().isNotFound());

        // another business's HR doesn't see it
        TestData other = new TestData(jdbc).inBusiness("onboard-other-" + s);
        UUID role = other.role("hrm", "self", "dir", "people.view", "people.add");
        other.employee("XH-" + s, "xhr." + s + "@example.com", role, PW);
        Cookie otherHr = signIn("xhr." + s + "@example.com");
        mvc.perform(get("/api/onboardings/" + id).cookie(otherHr)).andExpect(status().isNotFound());
        // and employees without people.add can't open the list
        mvc.perform(get("/api/onboardings").cookie(signIn("hr." + s + "@example.com"))).andExpect(status().isOk());
    }
}
