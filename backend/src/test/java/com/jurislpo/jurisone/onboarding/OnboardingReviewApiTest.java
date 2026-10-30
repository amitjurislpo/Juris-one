package com.jurislpo.jurisone.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** HR's decision on a submitted onboarding and the hard lock: submit, reject with flags, fix, resubmit, approve; who may decide; what a locked employee can still reach. */
class OnboardingReviewApiTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    TestData data;
    String suffix;
    UUID employeeId, hrId, freeId;
    Cookie employee, hr, free;

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    Cookie signIn(String email) throws Exception {
        String set = SignIn.session(mockMvc, email).andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        return new Cookie("jo_at", matcher.group(1));
    }

    @BeforeEach
    void business() throws Exception {
        suffix = TestData.letters(8);
        data = new TestData(jdbc).inBusiness("review-" + suffix);
        employeeId = data.employee("RA-" + suffix, "ra." + suffix + "@example.com", data.role("employee", "self", "dir"));
        jdbc.update("INSERT INTO employee_onboardings (id, business_id, employee_id, status) VALUES (?, ?, ?, 'NOT_STARTED')", UUID.randomUUID(), data.business(), employeeId);
        hrId = data.employee("RH-" + suffix, "rh." + suffix + "@example.com", data.role("hrm", "self", "dir", "people.view", "people.add"));
        freeId = data.employee("RF-" + suffix, "rf." + suffix + "@example.com", data.role("employee", "self", "dir")); // no onboarding row: never locked
        employee = signIn("ra." + suffix + "@example.com");
        hr = signIn("rh." + suffix + "@example.com");
        free = signIn("rf." + suffix + "@example.com");
    }

    // ───────────────────────── helpers ─────────────────────────

    JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    JsonNode mine(Cookie cookie) throws Exception {
        return body(mockMvc.perform(get("/api/v1/onboarding/mine").cookie(cookie)).andExpect(status().isOk())).get("data");
    }

    ResultActions putJson(Cookie cookie, String path, String content) throws Exception {
        return mockMvc.perform(write(put(path)).cookie(cookie).content(content));
    }

    ResultActions postJson(Cookie cookie, String path, String content) throws Exception {
        return mockMvc.perform(write(post(path)).cookie(cookie).content(content));
    }

    ResultActions reject(Cookie cookie, UUID id, String content) throws Exception {
        return postJson(cookie, "/api/v1/people/" + id + "/onboarding/rejections", content);
    }

    static final String REJECTION = """
        {"comment":"Please fix the address proof and the bank details.","flags":[{"section":"ADDRESS","comment":"Proof is blurred"},{"section":"ACCOUNTS","comment":"IFSC does not match the branch"}]}""";

    /** Fills every step and every mandatory file, then submits as the employee. */
    void fillAndSubmit() throws Exception {
        putJson(employee, "/api/v1/onboarding/mine/steps/1", EmployeeOnboardingApiTest.STEP1).andExpect(status().isOk());
        putJson(employee, "/api/v1/onboarding/mine/steps/2", "{\"employments\":[]}").andExpect(status().isOk());
        putJson(employee, "/api/v1/onboarding/mine/steps/3", EmployeeOnboardingApiTest.STEP3).andExpect(status().isOk());
        JsonNode members = body(putJson(employee, "/api/v1/onboarding/mine/steps/4", """
            {"familyMembers":[{"name":"Neha","relationship":"Wife","dateOfBirth":"1992-03-04","gender":"Female","bloodGroup":"A +ve","nationality":"Indian"}]}""").andExpect(status().isOk()))
                .at("/data/familyMembers");
        String wife = members.get(0).get("id").asText();
        putJson(employee, "/api/v1/onboarding/mine/steps/5", "{\"nominations\":{\"epf\":[{\"familyMemberId\":\"" + wife + "\",\"percentage\":100}],\"eps\":null,\"esi\":null,\"gratuity\":[]}}")
                .andExpect(status().isOk());
        for (JsonNode slot : mine(employee).get("attachmentSlots")) {
            if (slot.get("isMandatory").asBoolean()) {
                mockMvc.perform(multipart("/api/v1/onboarding/mine/attachments").file(new MockMultipartFile("file", "proof.pdf", "application/pdf", EmployeeOnboardingApiTest.PDF))
                        .param("slotKey", slot.get("slotKey").asText()).cookie(employee).header("origin", "http://localhost:3005").header("host", "localhost:3005")).andExpect(status().isCreated());
            }
        }
        putJson(employee, "/api/v1/onboarding/mine/steps/6", "{}").andExpect(status().isOk());
        postJson(employee, "/api/v1/onboarding/mine/submission", "{}").andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("SUBMITTED"));
    }

    int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    // ───────────────────────── tests ─────────────────────────

    @Test
    void submitRejectWithFlagsFixResubmitApprove() throws Exception {
        fillAndSubmit();
        assertThat(mine(employee).get("reviewFlags").size()).isZero();
        // HR is told in the app
        assertThat(count("SELECT count(*) FROM notifications WHERE employee_id = ? AND text LIKE '%submitted%'", hrId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM notifications WHERE employee_id = ?", employeeId)).isZero();

        // rejecting: sections must be valid, a comment and at least one flag are needed
        reject(hr, employeeId, "{\"comment\":\"\",\"flags\":[]}").andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.errors.comment").exists()).andExpect(jsonPath("$.errors.flags").exists());
        reject(hr, employeeId, "{\"comment\":\"x\",\"flags\":[{\"section\":\"SALARY\",\"comment\":\"y\"}]}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors['flags[0].section']").value("Unknown section"));
        reject(hr, employeeId, "{\"comment\":\"x\",\"flags\":[{\"section\":\"FAMILY\",\"comment\":\"\"}]}").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errors['flags[0].comment']").exists());
        assertThat(mine(employee).get("status").asText()).isEqualTo("SUBMITTED"); // nothing changed

        reject(hr, employeeId, REJECTION).andExpect(status().isCreated());
        JsonNode afterRejection = mine(employee);
        assertThat(afterRejection.get("status").asText()).isEqualTo("CHANGES_REQUESTED");
        assertThat(afterRejection.get("reviewComment").asText()).isEqualTo("Please fix the address proof and the bank details.");
        assertThat(afterRejection.get("reviewFlags").size()).isEqualTo(2);
        assertThat(afterRejection.at("/reviewFlags/0/section").asText()).isEqualTo("ADDRESS");
        assertThat(afterRejection.at("/reviewFlags/0/comment").asText()).isEqualTo("Proof is blurred");
        assertThat(afterRejection.at("/reviewFlags/1/section").asText()).isEqualTo("ACCOUNTS");
        assertThat(afterRejection.get("reviewedAt").isNull()).isFalse();
        assertThat(afterRejection.get("approvedAt").isNull()).isTrue();
        assertThat(afterRejection.get("isReadOnly").asBoolean()).isFalse();
        // the employee is told in the app and by email, with the flagged sections
        assertThat(count("SELECT count(*) FROM notifications WHERE employee_id = ? AND text LIKE 'Your onboarding needs changes%'", employeeId)).isEqualTo(1);
        String mailText = jdbc.queryForObject("SELECT text FROM email_outbox WHERE \"to\" = ? AND subject = 'Changes needed in your onboarding'", String.class, "ra." + suffix + "@example.com");
        assertThat(mailText).contains("Address: Proof is blurred").contains("Accounts & statutory: IFSC does not match the branch").contains("Please fix the address proof");
        // HR sees the same review in the onboarding view
        mockMvc.perform(get("/api/v1/people/" + employeeId + "/onboarding").cookie(hr)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CHANGES_REQUESTED"))
                .andExpect(jsonPath("$.data.reviewFlags.length()").value(2));

        // only a submitted onboarding can be decided
        reject(hr, employeeId, REJECTION).andExpect(status().isConflict());
        postJson(hr, "/api/v1/people/" + employeeId + "/onboarding/approvals", "{}").andExpect(status().isConflict());

        // the employee edits (still locked out of the portal, but the form is editable) and submits again: the flags are cleared
        putJson(employee, "/api/v1/onboarding/mine/steps/3", EmployeeOnboardingApiTest.STEP3).andExpect(status().isOk());
        assertThat(mine(employee).get("status").asText()).isEqualTo("CHANGES_REQUESTED");
        // the flag of the corrected section (Accounts = step 3) goes as soon as its step is saved; the other one stays until the next submission
        JsonNode whileEditing = mine(employee);
        assertThat(whileEditing.get("reviewFlags").size()).isEqualTo(1);
        assertThat(whileEditing.at("/reviewFlags/0/section").asText()).isEqualTo("ADDRESS");
        assertThat(whileEditing.get("reviewComment").asText()).isEqualTo("Please fix the address proof and the bank details.");
        postJson(employee, "/api/v1/onboarding/mine/submission", "{}").andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.reviewFlags.length()").value(0)).andExpect(jsonPath("$.data.reviewComment").doesNotExist());
        assertThat(count("SELECT count(*) FROM onboarding_review_flags WHERE onboarding_id = (SELECT id FROM employee_onboardings WHERE employee_id = ?)", employeeId)).isZero();
        assertThat(count("SELECT count(*) FROM notifications WHERE employee_id = ? AND text LIKE '%submitted%'", hrId)).isEqualTo(2);

        // still locked while waiting
        mockMvc.perform(get("/api/v1/profile").cookie(employee)).andExpect(status().isForbidden());

        postJson(hr, "/api/v1/people/" + employeeId + "/onboarding/approvals", "{}").andExpect(status().isCreated());
        JsonNode approved = mine(employee);
        assertThat(approved.get("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.get("approvedAt").isNull()).isFalse();
        assertThat(approved.get("isReadOnly").asBoolean()).isTrue();
        assertThat(count("SELECT count(*) FROM notifications WHERE employee_id = ? AND text LIKE 'Your onboarding was approved%'", employeeId)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM email_outbox WHERE \"to\" = ? AND subject = 'Onboarding approved'", "ra." + suffix + "@example.com")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit_log WHERE action IN ('onboarding.changes_requested', 'onboarding.approved') AND actor_id = ?", hrId)).isEqualTo(2);

        // approved: the form is final (409) and the portal is open
        putJson(employee, "/api/v1/onboarding/mine/steps/1", EmployeeOnboardingApiTest.STEP1).andExpect(status().isConflict());
        postJson(employee, "/api/v1/onboarding/mine/submission", "{}").andExpect(status().isConflict());
        mockMvc.perform(get("/api/v1/profile").cookie(employee)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/shell").cookie(employee)).andExpect(jsonPath("$.data.onboarding.status").value("APPROVED")).andExpect(jsonPath("$.data.onboarding.isRequired").value(false));
        postJson(hr, "/api/v1/people/" + employeeId + "/onboarding/approvals", "{}").andExpect(status().isConflict());
    }

    @Test
    void aLockedEmployeeReachesOnlyTheirOnboardingAndTheSession() throws Exception {
        for (String status : new String[] {"NOT_STARTED", "IN_PROGRESS", "SUBMITTED", "CHANGES_REQUESTED"}) {
            jdbc.update("UPDATE employee_onboardings SET status = ? WHERE employee_id = ?", status, employeeId);
            mockMvc.perform(get("/api/v1/leave").cookie(employee)).andExpect(status().isForbidden()).andExpect(jsonPath("$.type").value("onboarding-pending"))
                    .andExpect(jsonPath("$.title").value("onboarding-pending")).andExpect(jsonPath("$.status").value(403)).andExpect(jsonPath("$.code").value("onboarding-pending"))
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("onboarding")));
            postJson(employee, "/api/v1/people", "{}").andExpect(status().isForbidden()).andExpect(jsonPath("$.type").value("onboarding-pending"));
            mockMvc.perform(get("/api/v1/profile").cookie(employee)).andExpect(status().isForbidden()).andExpect(jsonPath("$.type").value("onboarding-pending"));
            mockMvc.perform(write(patch("/api/v1/notifications")).cookie(employee).content("{\"read\":true}")).andExpect(status().isForbidden());
            mockMvc.perform(get("/api/v1/me").cookie(employee)).andExpect(status().isOk()).andExpect(jsonPath("$.data.onboarding.status").value(status)).andExpect(jsonPath("$.data.onboarding.isRequired").value(true));
            mockMvc.perform(get("/api/v1/shell").cookie(employee)).andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/onboarding/mine").cookie(employee)).andExpect(status().isOk());
            mockMvc.perform(get("/api/v1/notifications").cookie(employee)).andExpect(status().isOk());
        }
        // not locked: approved, not required, no onboarding row at all, the HR user
        jdbc.update("UPDATE employee_onboardings SET status = 'NOT_REQUIRED' WHERE employee_id = ?", employeeId);
        mockMvc.perform(get("/api/v1/profile").cookie(employee)).andExpect(status().isOk());
        jdbc.update("UPDATE employee_onboardings SET status = 'APPROVED' WHERE employee_id = ?", employeeId);
        mockMvc.perform(get("/api/v1/profile").cookie(employee)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/profile").cookie(free)).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/profile").cookie(hr)).andExpect(status().isOk());
        // a signed-out request is still 401, not 403
        mockMvc.perform(get("/api/v1/leave")).andExpect(status().isUnauthorized());
    }

    @Test
    void anEmployeeStaysLockedUntilApprovedAndOnlyASubmittedOnboardingCanBeDecided() throws Exception {
        for (String status : new String[] {"NOT_STARTED", "IN_PROGRESS", "CHANGES_REQUESTED", "APPROVED"}) {
            jdbc.update("UPDATE employee_onboardings SET status = ? WHERE employee_id = ?", status, employeeId);
            postJson(hr, "/api/v1/people/" + employeeId + "/onboarding/approvals", "{}").andExpect(status().isConflict());
            reject(hr, employeeId, REJECTION).andExpect(status().isConflict());
        }
        jdbc.update("UPDATE employee_onboardings SET status = 'NOT_REQUIRED' WHERE employee_id = ?", employeeId);
        postJson(hr, "/api/v1/people/" + employeeId + "/onboarding/approvals", "{}").andExpect(status().isNotFound());
        reject(hr, employeeId, REJECTION).andExpect(status().isNotFound());
        postJson(hr, "/api/v1/people/" + freeId + "/onboarding/approvals", "{}").andExpect(status().isNotFound()); // no onboarding row
        assertThat(count("SELECT count(*) FROM employee_onboardings WHERE employee_id = ? AND status <> 'NOT_REQUIRED'", employeeId)).isZero();
    }

    @Test
    void onlyPeopleAddCanDecideAndAnotherBusinessSeesNothing() throws Exception {
        jdbc.update("UPDATE employee_onboardings SET status = 'SUBMITTED' WHERE employee_id = ?", employeeId);
        // the employee themselves, an employee without people.add, and HR with only people.view
        postJson(employee, "/api/v1/people/" + employeeId + "/onboarding/approvals", "{}").andExpect(status().isForbidden());
        postJson(free, "/api/v1/people/" + employeeId + "/onboarding/approvals", "{}").andExpect(status().isForbidden());
        reject(free, employeeId, REJECTION).andExpect(status().isForbidden());
        data.employee("RV-" + suffix, "rv." + suffix + "@example.com", data.role("viewer", "self", "people.view"));
        Cookie viewer = signIn("rv." + suffix + "@example.com");
        postJson(viewer, "/api/v1/people/" + employeeId + "/onboarding/approvals", "{}").andExpect(status().isForbidden());
        reject(viewer, employeeId, REJECTION).andExpect(status().isForbidden());
        postJson(hr, "/api/v1/people/" + employeeId + "/onboarding/approvals", "{}").andExpect(status().isCreated()); // HR with people.add is allowed
        assertThat(count("SELECT count(*) FROM employee_onboardings WHERE employee_id = ? AND status = 'APPROVED'", employeeId)).isEqualTo(1);

        // HR of another business cannot see this employee
        jdbc.update("UPDATE employee_onboardings SET status = 'SUBMITTED' WHERE employee_id = ?", employeeId);
        TestData stranger = new TestData(jdbc).inBusiness("review-other-" + suffix);
        stranger.employee("SH-" + suffix, "sh." + suffix + "@example.com", stranger.role("hrm", "self", "people.view", "people.add"));
        Cookie strangerHr = signIn("sh." + suffix + "@example.com");
        postJson(strangerHr, "/api/v1/people/" + employeeId + "/onboarding/approvals", "{}").andExpect(status().isNotFound());
        reject(strangerHr, employeeId, REJECTION).andExpect(status().isNotFound());
        assertThat(count("SELECT count(*) FROM employee_onboardings WHERE employee_id = ? AND status = 'SUBMITTED'", employeeId)).isEqualTo(1);
    }
}
