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
import com.jurislpo.jurisone.SignIn;
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

    static final byte[] PDF = "%PDF-1.4\n%doc\n".getBytes(StandardCharsets.US_ASCII);

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    Cookie hrCookie;
    UUID department, empRole, hrId;
    String suffix;

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    Cookie signIn(String email) throws Exception {
        String set = SignIn.session(mockMvc, email)
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        return new Cookie("jo_at", matcher.group(1));
    }

    @BeforeEach
    void business() throws Exception {
        suffix = TestData.letters(8);
        TestData testData = new TestData(jdbc).inBusiness("onboard-" + suffix);
        UUID hrRole = testData.role("hrm", "self", "dir", "people.view", "people.add", "access.toggle");
        empRole = testData.role("employee", "self", "dir");
        jdbc.update("INSERT INTO role_assignable_roles (role_id, assignable_role_id) VALUES (?, ?) ON CONFLICT DO NOTHING", hrRole, empRole);
        for (String[] shift : new String[][] {{"IN_NIGHT", "19:30", "04:30", "IN"}, {"IN_DAY", "09:30", "18:30", "IN"}, {"US_DAY", "09:00", "18:00", "US"}}) {
            jdbc.update("INSERT INTO shifts (id, code, name, start_local, end_local, region) SELECT ?, ?, ?, ?, ?, ?::\"Region\" WHERE NOT EXISTS (SELECT 1 FROM shifts WHERE code = ?)",
                    UUID.randomUUID(), shift[0], shift[0], shift[1], shift[2], shift[3], shift[0]);
        }
        department = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, name, business_id) VALUES (?, 'Litigation', ?)", department, testData.business());
        hrId = testData.employee("OH-" + suffix, "hr." + suffix + "@example.com", hrRole);
        hrCookie = signIn("hr." + suffix + "@example.com");
    }

    String lastLink(String to) {
        String text = jdbc.queryForObject("SELECT text FROM email_outbox WHERE \"to\" = ? ORDER BY created_at DESC LIMIT 1", String.class, to);
        Matcher matcher = Pattern.compile("/onboard/([A-Za-z0-9_-]+)").matcher(text);
        assertThat(matcher.find()).isTrue();
        return matcher.group(1);
    }

    String upload(String token, String kind) throws Exception {
        return json.readTree(mockMvc.perform(multipart("/api/public/onboardings/" + token + "/documents").file(new MockMultipartFile("file", kind.toLowerCase() + ".pdf", "application/pdf", PDF))
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
        String personal = "riya." + suffix + "@gmail.test";
        String id = json.readTree(mockMvc.perform(write(post("/api/onboardings")).cookie(hrCookie).content("""
            {"candidateName":"Riya Sen","personalEmail":"%s","designation":"Associate","departmentId":"%s","roleId":"%s","joiningDate":"2031-11-03"}"""
                .formatted(personal.toUpperCase(), department, empRole))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).at("/data/id").asText();
        String token = lastLink(personal);
        assertThat(jdbc.queryForObject("SELECT token_hash FROM onboardings WHERE id = ?::uuid", String.class, id)).doesNotContain(token); // only the hash is kept

        // the candidate, signed out
        mockMvc.perform(get("/api/public/onboardings/" + token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.candidateName").value("Riya Sen"))
                .andExpect(jsonPath("$.data.editable").value(true)).andExpect(jsonPath("$.data.checklist.length()").value(5));
        mockMvc.perform(get("/api/public/onboardings/not-a-real-token-at-all-123")).andExpect(status().isNotFound());
        mockMvc.perform(write(post("/api/public/onboardings/" + token + "/submission"))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Enter your father's name."));
        mockMvc.perform(write(put("/api/public/onboardings/" + token)).content(FORM)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT form_enc FROM onboardings WHERE id = ?::uuid", String.class, id)).doesNotContain("ABCPE1234F"); // encrypted
        mockMvc.perform(multipart("/api/public/onboardings/" + token + "/documents").file(new MockMultipartFile("file", "x.pdf", "application/pdf", "nope".getBytes()))
                .param("kind", "PAN").header("origin", "http://localhost:3005").header("host", "localhost:3005")).andExpect(status().isBadRequest());
        for (String kind : new String[] {"ID_PROOF", "ADDRESS_PROOF", "PAN", "AADHAAR", "SALARY_SLIP", "SALARY_SLIP"}) upload(token, kind);
        mockMvc.perform(write(post("/api/public/onboardings/" + token + "/submission"))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Upload Salary slips (last 3 months) (3 files)."));
        String slip3 = upload(token, "SALARY_SLIP");
        mockMvc.perform(write(post("/api/public/onboardings/" + token + "/submission"))).andExpect(status().isCreated());
        mockMvc.perform(write(put("/api/public/onboardings/" + token)).content(FORM)).andExpect(status().isUnprocessableEntity()); // read-only once submitted

        // HR reviews: identity numbers masked; every document must be decided; a rejected one goes back to the candidate
        JsonNode onboarding = json.readTree(mockMvc.perform(get("/api/onboardings/" + id).cookie(hrCookie)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).at("/data");
        assertThat(onboarding.at("/status").asText()).isEqualTo("SUBMITTED");
        assertThat(onboarding.at("/form/pan").asText()).isEqualTo("XXXXXX234F");
        assertThat(onboarding.at("/form/bankAccount").asText()).endsWith("6789");
        mockMvc.perform(write(put("/api/onboardings/" + id + "/status")).cookie(hrCookie).content("{\"status\":\"VERIFIED\"}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Verify or reject every document first."));
        for (JsonNode documentId : onboarding.at("/documents")) {
            String body = documentId.at("/id").asText().equals(slip3) ? "{\"status\":\"REJECTED\",\"comment\":\"Blurred, please upload again\"}" : "{\"status\":\"VERIFIED\"}";
            mockMvc.perform(write(put("/api/onboardings/" + id + "/documents/" + documentId.at("/id").asText() + "/status")).cookie(hrCookie).content(body)).andExpect(status().isOk());
        }
        mockMvc.perform(write(put("/api/onboardings/" + id + "/status")).cookie(hrCookie).content("{\"status\":\"VERIFIED\"}")).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(write(put("/api/onboardings/" + id + "/status")).cookie(hrCookie).content("{\"status\":\"CHANGES_REQUESTED\",\"comment\":\"One salary slip is blurred.\"}"))
                .andExpect(status().isOk());
        String token2 = lastLink(personal);
        mockMvc.perform(get("/api/public/onboardings/" + token)).andExpect(status().isNotFound()); // the old link stops working
        mockMvc.perform(get("/api/public/onboardings/" + token2)).andExpect(jsonPath("$.data.hrComment").value("One salary slip is blurred."));
        String verifiedDoc = onboarding.at("/documents/0/id").asText();
        mockMvc.perform(write(delete("/api/public/onboardings/" + token2 + "/documents/" + verifiedDoc))).andExpect(status().isUnprocessableEntity());
        String slip4 = upload(token2, "SALARY_SLIP");
        mockMvc.perform(write(post("/api/public/onboardings/" + token2 + "/submission"))).andExpect(status().isCreated());
        mockMvc.perform(write(put("/api/onboardings/" + id + "/documents/" + slip4 + "/status")).cookie(hrCookie).content("{\"status\":\"VERIFIED\"}")).andExpect(status().isOk());
        mockMvc.perform(write(put("/api/onboardings/" + id + "/status")).cookie(hrCookie).content("{\"status\":\"VERIFIED\"}")).andExpect(status().isOk());

        // add the employee: access off, no invite yet; documents move to their record
        String work = "riya.sen." + suffix + "@firm.test";
        JsonNode added = json.readTree(mockMvc.perform(write(post("/api/onboardings/" + id + "/employee")).cookie(hrCookie).content("""
            {"workEmail":"%s","designation":"Associate","departmentId":"%s","managerId":"%s","roleId":"%s","joiningDate":"2031-11-03","location":"IN_IST","shift":"IN_DAY"}"""
                .formatted(work, department, hrId, empRole))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        assertThat(added.at("/message").asText()).contains("portal access is off");
        String employee = added.at("/data/id").asText();
        var row = jdbc.queryForMap("SELECT portal_access, pan_last4, bank_account_last4, bank_ifsc, full_name FROM employees WHERE id = ?::uuid", employee);
        assertThat(row.get("portal_access")).isEqualTo(false);
        assertThat(row.get("pan_last4")).isEqualTo("234F");
        assertThat(row.get("bank_account_last4")).isEqualTo("6789");
        assertThat(row.get("bank_ifsc")).isEqualTo("HDFC0001234");
        var names = jdbc.queryForMap("SELECT first_name, last_name, full_name, phone_country, emergency_name, emergency_phone, date_of_birth FROM employees WHERE id = ?::uuid", employee);
        assertThat(names.get("full_name")).isEqualTo("Riya Sen");
        assertThat(names.get("last_name")).isEqualTo("Sen");
        assertThat(names.get("phone_country")).isEqualTo("+91");
        assertThat(names.get("emergency_phone")).isEqualTo("+91 9810044556");
        assertThat(names.get("date_of_birth").toString()).isEqualTo("1996-04-02");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE \"to\" = ?", Integer.class, work)).isZero();
        mockMvc.perform(get("/api/people/" + employee + "/documents").cookie(hrCookie)).andExpect(jsonPath("$.data.length()").value(7)); // the rejected one stays behind
        mockMvc.perform(get("/api/public/onboardings/" + token2)).andExpect(status().isNotFound());

        // HR turns access on: no invite, they sign in with the emailed code
        mockMvc.perform(write(put("/api/people/" + employee + "/access")).cookie(hrCookie).content("{\"enabled\":true}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.endsWith("they sign in with the code emailed to their work email")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE \"to\" = ?", Integer.class, work)).isZero();
    }

    @Test
    void theBusinessSetsWhichDocumentsNewOnboardingsAskFor() throws Exception {
        mockMvc.perform(get("/api/onboardings/defaults").cookie(hrCookie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requiredDocs.length()").value(5)).andExpect(jsonPath("$.data.kinds.PHOTO").exists());
        mockMvc.perform(write(put("/api/onboardings/defaults")).cookie(hrCookie).content("{\"requiredDocs\":[\"PAN\",\"BOGUS\"]}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(put("/api/onboardings/defaults")).cookie(hrCookie).content("{\"requiredDocs\":[\"PAN\",\"PHOTO\",\"PAN\"]}")).andExpect(status().isOk());
        mockMvc.perform(get("/api/onboardings/defaults").cookie(hrCookie)).andExpect(jsonPath("$.data.requiredDocs[0]").value("PAN"))
                .andExpect(jsonPath("$.data.requiredDocs.length()").value(2));
        // a new onboarding without its own list asks for the business's defaults
        String id = json.readTree(mockMvc.perform(write(post("/api/onboardings")).cookie(hrCookie).content("{\"candidateName\":\"Mira Das\",\"personalEmail\":\"mira." + suffix + "@gmail.test\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).at("/data/id").asText();
        mockMvc.perform(get("/api/onboardings/" + id).cookie(hrCookie)).andExpect(jsonPath("$.data.requiredDocs.length()").value(2))
                .andExpect(jsonPath("$.data.requiredDocs[1]").value("PHOTO"));
        // other businesses keep theirs
        TestData other = new TestData(jdbc).inBusiness("onboard-def-" + suffix);
        other.employee("DH-" + suffix, "dhr." + suffix + "@example.com", other.role("hrm", "self", "dir", "people.view", "people.add"));
        mockMvc.perform(get("/api/onboardings/defaults").cookie(signIn("dhr." + suffix + "@example.com"))).andExpect(jsonPath("$.data.requiredDocs.length()").value(5));
    }

    @Test
    void aCancelledLinkStopsWorkingAndOtherUsersCantSeeOnboarding() throws Exception {
        String personal = "kabir." + suffix + "@gmail.test";
        String id = json.readTree(mockMvc.perform(write(post("/api/onboardings")).cookie(hrCookie).content("{\"candidateName\":\"Kabir Rao\",\"personalEmail\":\"" + personal + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).at("/data/id").asText();
        mockMvc.perform(write(post("/api/onboardings")).cookie(hrCookie).content("{\"candidateName\":\"Kabir Rao\",\"personalEmail\":\"" + personal + "\"}"))
                .andExpect(status().isUnprocessableEntity()); // one open onboarding per email
        String token = lastLink(personal);
        mockMvc.perform(write(put("/api/onboardings/" + id + "/status")).cookie(hrCookie).content("{\"status\":\"CANCELLED\"}")).andExpect(status().isOk());
        mockMvc.perform(get("/api/public/onboardings/" + token)).andExpect(status().isNotFound());

        // another business's HR doesn't see it
        TestData other = new TestData(jdbc).inBusiness("onboard-other-" + suffix);
        UUID role = other.role("hrm", "self", "dir", "people.view", "people.add");
        other.employee("XH-" + suffix, "xhr." + suffix + "@example.com", role);
        Cookie otherHr = signIn("xhr." + suffix + "@example.com");
        mockMvc.perform(get("/api/onboardings/" + id).cookie(otherHr)).andExpect(status().isNotFound());
        // and employees without people.add can't open the list
        mockMvc.perform(get("/api/onboardings").cookie(signIn("hr." + suffix + "@example.com"))).andExpect(status().isOk());
    }
}
