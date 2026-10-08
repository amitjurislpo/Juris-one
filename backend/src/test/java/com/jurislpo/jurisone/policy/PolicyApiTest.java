package com.jurislpo.jurisone.policy;

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

    static final String PW = "correct-horse-battery-7";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    Cookie hr, employee, owner;
    String employeeCode;

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder b) {
        return b.header("origin", "http://localhost:3005").header("host", "localhost:3005").header("user-agent", "JUnit browser")
                .contentType(MediaType.APPLICATION_JSON);
    }

    Cookie signIn(String email) throws Exception {
        String set = mvc.perform(write(post("/api/auth/session")).content("{\"identifier\":\"" + email + "\",\"password\":\"" + PW + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher m = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(m.find()).isTrue();
        return new Cookie("jo_at", m.group(1));
    }

    /** A business of its own: published policies would otherwise stop the other tests' users. */
    @BeforeEach
    void business() throws Exception {
        String s = UUID.randomUUID().toString().substring(0, 6).replaceAll("[0-9]", "x");
        TestData t = new TestData(jdbc).inBusiness("policy-" + s);
        UUID hrRole = t.role("hrm", "self", "dir", "people.view", "policies.manage");
        UUID empRole = t.role("employee", "self", "dir");
        UUID ownerRole = t.role("owner", "dir", "roles.manage", "policies.manage", "business.manage");
        employeeCode = "PE-" + s;
        t.employee("PH-" + s, "hr." + s + "@example.com", hrRole, PW);
        t.employee(employeeCode, "emp." + s + "@example.com", empRole, PW);
        t.employee("PO-" + s, "owner." + s + "@example.com", ownerRole, PW);
        hr = signIn("hr." + s + "@example.com");
        employee = signIn("emp." + s + "@example.com");
        owner = signIn("owner." + s + "@example.com");
    }

    String create(Cookie c, String title, String body) throws Exception {
        return json.readTree(mvc.perform(write(post("/api/policies")).cookie(c).content("{\"title\":\"" + title + "\",\"body\":\"" + body + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).at("/data/id").asText();
    }

    void publish(Cookie c, String id) throws Exception {
        mvc.perform(write(put("/api/policies/" + id + "/status")).cookie(c).content("{\"status\":\"PUBLISHED\"}")).andExpect(status().isOk());
    }

    @Test
    void anEmployeeMustAcceptEachNewVersionBeforeDoingAnythingElse() throws Exception {
        String id = create(hr, "Code of conduct", "Be kind.\\nBe on time.");
        // a draft asks nothing of anyone
        mvc.perform(get("/api/me").cookie(employee)).andExpect(jsonPath("$.data.pendingPolicies").value(0));
        publish(hr, id);

        // published: everything but the policy endpoints answers 428 until accepted
        mvc.perform(get("/api/me").cookie(employee)).andExpect(status().isOk()).andExpect(jsonPath("$.data.pendingPolicies").value(1));
        mvc.perform(get("/api/attendance/me").cookie(employee)).andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("POLICY_ACK_REQUIRED"));
        mvc.perform(get("/api/policies").cookie(hr)).andExpect(status().isPreconditionRequired()); // HR too
        mvc.perform(get("/api/policies").cookie(owner)).andExpect(status().isOk()); // never the owner
        mvc.perform(get("/api/me").cookie(owner)).andExpect(jsonPath("$.data.pendingPolicies").value(0));
        mvc.perform(get("/api/policies/pending").cookie(employee)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].title").value("Code of conduct")).andExpect(jsonPath("$.data[0].version").value(1))
                .andExpect(jsonPath("$.data[0].body").value("Be kind.\nBe on time."));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications n JOIN employees e ON e.id = n.employee_id WHERE e.code = ?", Integer.class, employeeCode))
                .isEqualTo(1);

        // accepting an old version is refused; the current one is recorded once
        mvc.perform(write(post("/api/policies/" + id + "/acknowledgements")).cookie(employee).content("{\"version\":2}")).andExpect(status().isConflict());
        mvc.perform(write(post("/api/policies/" + id + "/acknowledgements")).cookie(employee).content("{\"version\":1}")).andExpect(status().isCreated());
        mvc.perform(write(post("/api/policies/" + id + "/acknowledgements")).cookie(employee).content("{\"version\":1}")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.msg").value("Already accepted"));
        mvc.perform(get("/api/attendance/me").cookie(employee)).andExpect(status().isOk());
        var ack = jdbc.queryForMap("SELECT * FROM policy_acknowledgements WHERE employee_code = ?", employeeCode);
        assertThat(ack.get("policy_title")).isEqualTo("Code of conduct");
        assertThat(ack.get("version")).isEqualTo(1);
        assertThat(ack.get("body_sha256")).isEqualTo(PolicyService.sha256("Be kind.\nBe on time.".getBytes(StandardCharsets.UTF_8)));
        assertThat(ack.get("employee_email")).isEqualTo(((String) ack.get("employee_email")).toLowerCase());
        assertThat(ack.get("user_agent")).isEqualTo("JUnit browser");

        // HR accepts too, then edits and publishes version 2: everyone is asked again
        mvc.perform(write(post("/api/policies/" + id + "/acknowledgements")).cookie(hr).content("{\"version\":1}")).andExpect(status().isCreated());
        mvc.perform(write(put("/api/policies/" + id + "/status")).cookie(hr).content("{\"status\":\"PUBLISHED\"}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Nothing has changed since version 1."));
        mvc.perform(write(put("/api/policies/" + id)).cookie(hr).content("{\"title\":\"Code of conduct\",\"body\":\"Be kind. Be on time. Be honest.\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/attendance/me").cookie(employee)).andExpect(status().isOk()); // a draft change asks nothing
        publish(hr, id);
        mvc.perform(get("/api/attendance/me").cookie(employee)).andExpect(status().isPreconditionRequired());
        mvc.perform(get("/api/policies/mine").cookie(employee)).andExpect(jsonPath("$.data[0].version").value(2))
                .andExpect(jsonPath("$.data[0].acknowledgedAt").doesNotExist());
        mvc.perform(get("/api/policies/" + id).cookie(owner)).andExpect(jsonPath("$.data.versions.length()").value(2))
                .andExpect(jsonPath("$.data.versions[1].body").doesNotExist())
                .andExpect(jsonPath("$.data.people[0].acknowledgedAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.people[0].lastVersion").value(1));

        // reminders and the evidence
        mvc.perform(write(post("/api/policies/" + id + "/reminders")).cookie(owner)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.count").value(2));
        String csv = mvc.perform(get("/api/policies/" + id + "/acknowledgements/export").cookie(owner)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains("Accepted at (UTC)").contains(employeeCode).contains((String) ack.get("body_sha256")).contains("JUnit browser");
    }

    @Test
    void theRecordCannotBeChangedOrDeleted() throws Exception {
        String id = create(owner, "Leave policy", "Apply 3 days ahead.");
        publish(owner, id);
        mvc.perform(write(post("/api/policies/" + id + "/acknowledgements")).cookie(employee).content("{\"version\":1}")).andExpect(status().isCreated());
        assertThatThrownBy(() -> jdbc.update("UPDATE policy_acknowledgements SET employee_name = 'Someone else' WHERE employee_code = ?", employeeCode))
                .rootCause().hasMessageContaining("permanent record");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM policy_acknowledgements WHERE employee_code = ?", employeeCode)).rootCause().hasMessageContaining("permanent record");
        assertThatThrownBy(() -> jdbc.update("UPDATE policy_versions SET body = 'changed' WHERE policy_id = ?::uuid", id)).rootCause().hasMessageContaining("permanent record");
        // a published policy is archived, never deleted
        mvc.perform(write(delete("/api/policies/" + id)).cookie(owner)).andExpect(status().isUnprocessableEntity());
        mvc.perform(write(put("/api/policies/" + id + "/status")).cookie(owner).content("{\"status\":\"ARCHIVED\"}")).andExpect(status().isOk());
        mvc.perform(get("/api/policies/mine").cookie(employee)).andExpect(jsonPath("$.data.length()").value(0));
        // drafts can be deleted
        String draft = create(owner, "Draft only", "Not yet.");
        mvc.perform(write(delete("/api/policies/" + draft)).cookie(owner)).andExpect(status().isOk());
    }

    @Test
    void aPdfCanBeAttachedAndEmployeesReadThePublishedOne() throws Exception {
        String id = create(hr, "IT security", "");
        mvc.perform(write(put("/api/policies/" + id + "/status")).cookie(hr).content("{\"status\":\"PUBLISHED\"}")).andExpect(status().isUnprocessableEntity());
        MockMultipartFile notPdf = new MockMultipartFile("file", "x.pdf", "application/pdf", "hello".getBytes());
        mvc.perform(multipart(HttpMethod.PUT, "/api/policies/" + id + "/file").file(notPdf).cookie(hr).header("origin", "http://localhost:3005").header("host", "localhost:3005"))
                .andExpect(status().isBadRequest());
        byte[] pdf = "%PDF-1.4\n%test\n".getBytes(StandardCharsets.US_ASCII);
        mvc.perform(multipart(HttpMethod.PUT, "/api/policies/" + id + "/file").file(new MockMultipartFile("file", "IT security.pdf", "application/pdf", pdf))
                .cookie(hr).header("origin", "http://localhost:3005").header("host", "localhost:3005")).andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("IT security.pdf"));
        // the draft's PDF is for managers only
        mvc.perform(get("/api/policies/" + id + "/file").cookie(employee)).andExpect(status().isForbidden());
        publish(hr, id);
        mvc.perform(get("/api/policies/" + id + "/versions/1/file").cookie(employee)).andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF)).andExpect(content().bytes(pdf));
        assertThat(jdbc.queryForObject("SELECT file_sha256 FROM policy_versions WHERE policy_id = ?::uuid", String.class, id)).isEqualTo(PolicyService.sha256(pdf));
        // employees can't manage policies
        mvc.perform(write(post("/api/policies/" + id + "/acknowledgements")).cookie(employee).content("{\"version\":1}")).andExpect(status().isCreated());
        mvc.perform(get("/api/policies").cookie(employee)).andExpect(status().isForbidden());
    }
}
