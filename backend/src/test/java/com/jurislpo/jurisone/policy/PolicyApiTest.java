package com.jurislpo.jurisone.policy;

import com.jurislpo.jurisone.service.PolicyService;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Policies are versioned, must be accepted before anything else, and every acceptance is a permanent record. */
class PolicyApiTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    Cookie hrCookie, employee, owner;
    String employeeCode;

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005").header("user-agent", "JUnit browser")
                .contentType(MediaType.APPLICATION_JSON);
    }

    Cookie signIn(String email) throws Exception {
        String set = SignIn.session(mockMvc, email)
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        return new Cookie("jo_at", matcher.group(1));
    }

    /** A business of its own: published policies would otherwise stop the other tests' users. */
    @BeforeEach
    void business() throws Exception {
        String suffix = TestData.letters(8);
        TestData testData = new TestData(jdbc).inBusiness("policy-" + suffix);
        UUID hrRole = testData.role("hrm", "self", "dir", "people.view", "policies.manage");
        UUID empRole = testData.role("employee", "self", "dir");
        UUID ownerRole = testData.role("owner", "dir", "roles.manage", "policies.manage", "business.manage");
        employeeCode = "PE-" + suffix;
        testData.employee("PH-" + suffix, "hr." + suffix + "@example.com", hrRole);
        testData.employee(employeeCode, "emp." + suffix + "@example.com", empRole);
        testData.employee("PO-" + suffix, "owner." + suffix + "@example.com", ownerRole);
        hrCookie = signIn("hr." + suffix + "@example.com");
        employee = signIn("emp." + suffix + "@example.com");
        owner = signIn("owner." + suffix + "@example.com");
    }

    String create(Cookie cookie, String title, String body) throws Exception {
        return json.readTree(mockMvc.perform(write(post("/api/v1/policies")).cookie(cookie).content("{\"title\":\"" + title + "\",\"body\":\"" + body + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).at("/data/id").asText();
    }

    void publish(Cookie cookie, String id) throws Exception {
        mockMvc.perform(write(put("/api/v1/policies/" + id + "/status")).cookie(cookie).content("{\"status\":\"PUBLISHED\"}")).andExpect(status().isOk());
    }

    @Test
    void anEmployeeMustAcceptEachNewVersionBeforeDoingAnythingElse() throws Exception {
        String id = create(hrCookie, "Code of conduct", "Be kind.\\nBe on time.");
        // a draft asks nothing of anyone
        mockMvc.perform(get("/api/v1/me").cookie(employee)).andExpect(jsonPath("$.data.pendingPolicies").value(0));
        publish(hrCookie, id);

        // published: everything but the policy endpoints answers 428 until accepted
        mockMvc.perform(get("/api/v1/me").cookie(employee)).andExpect(status().isOk()).andExpect(jsonPath("$.data.pendingPolicies").value(1));
        mockMvc.perform(get("/api/v1/attendance/mine").cookie(employee)).andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("POLICY_ACK_REQUIRED"));
        mockMvc.perform(get("/api/v1/policies").cookie(hrCookie)).andExpect(status().isPreconditionRequired()); // HR too
        mockMvc.perform(get("/api/v1/policies").cookie(owner)).andExpect(status().isOk()); // never the owner
        mockMvc.perform(get("/api/v1/me").cookie(owner)).andExpect(jsonPath("$.data.pendingPolicies").value(0));
        mockMvc.perform(get("/api/v1/policies/pending").cookie(employee)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].title").value("Code of conduct")).andExpect(jsonPath("$.data[0].version").value(1))
                .andExpect(jsonPath("$.data[0].body").value("Be kind.\nBe on time."));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications n JOIN employees e ON e.id = n.employee_id WHERE e.code = ?", Integer.class, employeeCode))
                .isEqualTo(1);

        // accepting an old version is refused; the current one is recorded once
        mockMvc.perform(write(post("/api/v1/policies/" + id + "/acknowledgements")).cookie(employee).content("{\"version\":2}")).andExpect(status().isConflict());
        mockMvc.perform(write(post("/api/v1/policies/" + id + "/acknowledgements")).cookie(employee).content("{\"version\":1}")).andExpect(status().isCreated());
        mockMvc.perform(write(post("/api/v1/policies/" + id + "/acknowledgements")).cookie(employee).content("{\"version\":1}")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Already accepted"));
        mockMvc.perform(get("/api/v1/attendance/mine").cookie(employee)).andExpect(status().isOk());
        var acknowledgement = jdbc.queryForMap("SELECT * FROM policy_acknowledgements WHERE employee_code = ?", employeeCode);
        assertThat(acknowledgement.get("policy_title")).isEqualTo("Code of conduct");
        assertThat(acknowledgement.get("version")).isEqualTo(1);
        assertThat(acknowledgement.get("body_sha256")).isEqualTo(PolicyService.sha256("Be kind.\nBe on time.".getBytes(StandardCharsets.UTF_8)));
        assertThat(acknowledgement.get("employee_email")).isEqualTo(((String) acknowledgement.get("employee_email")).toLowerCase());
        assertThat(acknowledgement.get("user_agent")).isEqualTo("JUnit browser");

        // HR accepts too, then edits and publishes version 2: everyone is asked again
        mockMvc.perform(write(post("/api/v1/policies/" + id + "/acknowledgements")).cookie(hrCookie).content("{\"version\":1}")).andExpect(status().isCreated());
        mockMvc.perform(write(put("/api/v1/policies/" + id + "/status")).cookie(hrCookie).content("{\"status\":\"PUBLISHED\"}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Nothing has changed since version 1."));
        mockMvc.perform(write(put("/api/v1/policies/" + id)).cookie(hrCookie).content("{\"title\":\"Code of conduct\",\"body\":\"Be kind. Be on time. Be honest.\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/attendance/mine").cookie(employee)).andExpect(status().isOk()); // a draft change asks nothing
        publish(hrCookie, id);
        mockMvc.perform(get("/api/v1/attendance/mine").cookie(employee)).andExpect(status().isPreconditionRequired());
        mockMvc.perform(get("/api/v1/policies/mine").cookie(employee)).andExpect(jsonPath("$.data[0].version").value(2))
                .andExpect(jsonPath("$.data[0].acknowledgedAt").doesNotExist());
        mockMvc.perform(get("/api/v1/policies/" + id).cookie(owner)).andExpect(jsonPath("$.data.versions.length()").value(2))
                .andExpect(jsonPath("$.data.versions[1].body").doesNotExist())
                .andExpect(jsonPath("$.data.people[0].acknowledgedAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.people[0].lastVersion").value(1));

        // reminders and the evidence
        mockMvc.perform(write(post("/api/v1/policies/" + id + "/reminders")).cookie(owner)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.count").value(2));
        String csv = mockMvc.perform(get("/api/v1/policies/" + id + "/acknowledgements/export").cookie(owner)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains("Accepted at (UTC)").contains(employeeCode).contains((String) acknowledgement.get("body_sha256")).contains("JUnit browser");
        // the same evidence as a printable PDF, with the full fingerprints
        byte[] pdfBytes = mockMvc.perform(get("/api/v1/policies/" + id + "/acknowledgements/export?format=pdf").cookie(owner)).andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF)).andReturn().getResponse().getContentAsByteArray();
        try (org.apache.pdfbox.pdmodel.PDDocument document = org.apache.pdfbox.Loader.loadPDF(pdfBytes)) {
            String text = new org.apache.pdfbox.text.PDFTextStripper().getText(document);
            assertThat(text).contains("Policy acceptance record").contains("Code of conduct").contains(employeeCode).contains((String) acknowledgement.get("body_sha256"));
        }
    }

    @Test
    void theRecordCannotBeChangedOrDeleted() throws Exception {
        String id = create(owner, "Leave policy", "Apply 3 days ahead.");
        publish(owner, id);
        mockMvc.perform(write(post("/api/v1/policies/" + id + "/acknowledgements")).cookie(employee).content("{\"version\":1}")).andExpect(status().isCreated());
        assertThatThrownBy(() -> jdbc.update("UPDATE policy_acknowledgements SET employee_name = 'Someone else' WHERE employee_code = ?", employeeCode))
                .rootCause().hasMessageContaining("permanent record");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM policy_acknowledgements WHERE employee_code = ?", employeeCode)).rootCause().hasMessageContaining("permanent record");
        assertThatThrownBy(() -> jdbc.update("UPDATE policy_versions SET body = 'changed' WHERE policy_id = ?::uuid", id)).rootCause().hasMessageContaining("permanent record");
        // a published policy is archived, never deleted
        mockMvc.perform(write(delete("/api/v1/policies/" + id)).cookie(owner)).andExpect(status().isUnprocessableEntity());
        mockMvc.perform(write(put("/api/v1/policies/" + id + "/status")).cookie(owner).content("{\"status\":\"ARCHIVED\"}")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/policies/mine").cookie(employee)).andExpect(jsonPath("$.data.length()").value(0));
        // drafts can be deleted
        String draft = create(owner, "Draft only", "Not yet.");
        mockMvc.perform(write(delete("/api/v1/policies/" + draft)).cookie(owner)).andExpect(status().isOk());
    }

    @Test
    void aPdfCanBeAttachedAndEmployeesReadThePublishedOne() throws Exception {
        String id = create(hrCookie, "IT security", "");
        mockMvc.perform(write(put("/api/v1/policies/" + id + "/status")).cookie(hrCookie).content("{\"status\":\"PUBLISHED\"}")).andExpect(status().isUnprocessableEntity());
        MockMultipartFile notPdf = new MockMultipartFile("file", "x.pdf", "application/pdf", "hello".getBytes());
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/policies/" + id + "/file").file(notPdf).cookie(hrCookie).header("origin", "http://localhost:3005").header("host", "localhost:3005"))
                .andExpect(status().isBadRequest());
        byte[] pdfBytes = "%PDF-1.4\n%test\n".getBytes(StandardCharsets.US_ASCII);
        mockMvc.perform(multipart(HttpMethod.PUT, "/api/v1/policies/" + id + "/file").file(new MockMultipartFile("file", "IT security.pdf", "application/pdf", pdfBytes))
                .cookie(hrCookie).header("origin", "http://localhost:3005").header("host", "localhost:3005")).andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("IT security.pdf"));
        // the draft's PDF is for managers only
        mockMvc.perform(get("/api/v1/policies/" + id + "/file").cookie(employee)).andExpect(status().isForbidden());
        publish(hrCookie, id);
        mockMvc.perform(get("/api/v1/policies/" + id + "/versions/1/file").cookie(employee)).andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF)).andExpect(content().bytes(pdfBytes));
        assertThat(jdbc.queryForObject("SELECT file_sha256 FROM policy_versions WHERE policy_id = ?::uuid", String.class, id)).isEqualTo(PolicyService.sha256(pdfBytes));
        // employees can't manage policies
        mockMvc.perform(write(post("/api/v1/policies/" + id + "/acknowledgements")).cookie(employee).content("{\"version\":1}")).andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/policies").cookie(employee)).andExpect(status().isForbidden());
    }
}
