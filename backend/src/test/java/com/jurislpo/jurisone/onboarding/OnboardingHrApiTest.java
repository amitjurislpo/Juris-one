package com.jurislpo.jurisone.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

/** HR starts onboardings, lists them and fills the wizard on the employee's behalf (the employee's own side is in EmployeeOnboardingApiTest). */
class OnboardingHrApiTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    TestData data;
    String suffix;
    UUID departmentId, managerId, hrId, hrRoleId, employeeRoleId;
    Cookie hr, plain;

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
        data = new TestData(jdbc).inBusiness("onboard-hr-" + suffix);
        if (jdbc.queryForObject("SELECT count(*) FROM shifts WHERE code = 'IN_NIGHT'", Integer.class) == 0) {
            jdbc.update("INSERT INTO shifts (id, code, name, start_local, end_local, region) VALUES (?, 'IN_NIGHT', 'Night', '19:30', '04:30', 'IN'::\"Region\")", UUID.randomUUID());
        }
        employeeRoleId = data.role("employee", "self", "dir");
        hrRoleId = data.role("hrm", "self", "dir", "people.view", "people.add");
        jdbc.update("INSERT INTO role_assignable_roles (role_id, assignable_role_id) VALUES (?, ?) ON CONFLICT DO NOTHING", hrRoleId, employeeRoleId);
        departmentId = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, name, business_id) VALUES (?, ?, ?)", departmentId, "Legal " + suffix, data.business());
        managerId = data.employee("MG-" + suffix, "mg." + suffix + "@example.com", employeeRoleId);
        hrId = data.employee("HR-" + suffix, "hr." + suffix + "@example.com", hrRoleId);
        data.employee("PL-" + suffix, "pl." + suffix + "@example.com", employeeRoleId);
        hr = signIn("hr." + suffix + "@example.com");
        plain = signIn("pl." + suffix + "@example.com");
    }

    String startBody(String email) {
        return """
            {"firstName":"Meera","lastName":"Joshi","workEmail":"%s","phoneCountry":"+91","phone":"98765 43210","location":"IN_IST","designation":"Associate",
             "departmentId":"%s","managerId":"%s","dateOfJoining":"2031-10-12","roleId":"%s"}""".formatted(email, departmentId, managerId, employeeRoleId);
    }

    JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    String start(String name) throws Exception {
        return body(mockMvc.perform(write(post("/api/v1/onboardings")).cookie(hr).content(startBody(name + "." + suffix + "@example.com"))).andExpect(status().isCreated())).at("/data/employeeId").asText();
    }

    ResultActions putJson(Cookie cookie, String path, String content) throws Exception {
        return mockMvc.perform(write(put(path)).cookie(cookie).content(content));
    }

    JsonNode saveOk(String employeeId, int step, String content) throws Exception {
        return body(putJson(hr, "/api/v1/people/" + employeeId + "/onboarding/steps/" + step, content).andExpect(status().isOk())).get("data");
    }

    ResultActions upload(Cookie cookie, String employeeId, String slotKey) throws Exception {
        return mockMvc.perform(multipart("/api/v1/people/" + employeeId + "/onboarding/attachments").file(new MockMultipartFile("file", "proof.pdf", "application/pdf", EmployeeOnboardingApiTest.PDF))
                .param("slotKey", slotKey).cookie(cookie).header("origin", "http://localhost:3005").header("host", "localhost:3005"));
    }

    String family() {
        return """
            {"familyMembers":[{"name":"Neha","relationship":"Wife","dateOfBirth":"1992-03-04","gender":"Female","bloodGroup":"A +ve","nationality":"Indian","isMinor":false,"hasMentalIllness":false}]}""";
    }

    /** Steps 1 to 5 through HR's endpoints. */
    void fillSteps(String employeeId) throws Exception {
        saveOk(employeeId, 1, EmployeeOnboardingApiTest.STEP1);
        saveOk(employeeId, 2, "{\"employments\":[{\"companyName\":\"Lex & Co\",\"designation\":\"Associate\",\"lastDrawnSalary\":50000.50,\"fromDate\":\"2013-01-01\",\"toDate\":\"2016-01-01\"}]}");
        saveOk(employeeId, 3, EmployeeOnboardingApiTest.STEP3);
        String memberId = saveOk(employeeId, 4, family()).at("/familyMembers/0/id").asText();
        saveOk(employeeId, 5, "{\"nominations\":{\"epf\":[{\"familyMemberId\":\"" + memberId + "\",\"percentage\":100}],\"eps\":{\"familyMemberId\":\"" + memberId + "\"},\"esi\":null,\"gratuity\":[]}}");
    }

    // ───────────────────────── starting ─────────────────────────

    @Test
    void hrStartsAnOnboardingAndTheInvitationIsQueued() throws Exception {
        String email = "meera." + suffix + "@example.com";
        JsonNode created = body(mockMvc.perform(write(post("/api/v1/onboardings")).cookie(hr).content(startBody(email.toUpperCase()))).andExpect(status().isCreated()));
        String employeeId = created.at("/data/employeeId").asText();
        assertThat(employeeId).isNotBlank();

        assertThat(jdbc.queryForObject("SELECT portal_access FROM employees WHERE id = ?::uuid", Boolean.class, employeeId)).isTrue();
        assertThat(jdbc.queryForObject("SELECT email FROM employees WHERE id = ?::uuid", String.class, employeeId)).isEqualTo(email);
        assertThat(jdbc.queryForObject("SELECT father_name FROM employees WHERE id = ?::uuid", String.class, employeeId)).isNull(); // the employee gives it in the wizard
        assertThat(jdbc.queryForObject("SELECT status FROM employee_onboardings WHERE employee_id = ?::uuid", String.class, employeeId)).isEqualTo("NOT_STARTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE \"to\" = ? AND subject LIKE 'Welcome aboard%'", Integer.class, email)).isEqualTo(1);
        String text = jdbc.queryForObject("SELECT text FROM email_outbox WHERE \"to\" = ? AND subject LIKE 'Welcome aboard%'", String.class, email);
        assertThat(text).contains("Hi Meera,").contains("Glad to have you onboard!").contains("Let's Get Started: http://localhost:3005/login").contains(email).contains("HR Team");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'onboarding.created' AND actor_id = ?", Integer.class, hrId)).isEqualTo(1);

        // the new employee signs in and the wizard is theirs
        Cookie newcomer = signIn(email);
        mockMvc.perform(get("/api/v1/onboarding/mine").cookie(newcomer)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("NOT_STARTED"))
                .andExpect(jsonPath("$.data.header.employeeCode").value(jdbc.queryForObject("SELECT code FROM employees WHERE id = ?::uuid", String.class, employeeId)));

        // same errors as add-employee; nothing is created or mailed twice
        mockMvc.perform(write(post("/api/v1/onboardings")).cookie(hr).content(startBody(email))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.endsWith("already uses that email.")));
        mockMvc.perform(write(post("/api/v1/onboardings")).cookie(hr).content(startBody("nope"))).andExpect(status().is4xxClientError())
                .andExpect(jsonPath("$.detail").value("Enter a valid email"));
        mockMvc.perform(write(post("/api/v1/onboardings")).cookie(hr).content(startBody("other." + suffix + "@example.com").replace("\"lastName\":\"Joshi\"", "\"lastName\":\"\"")))
                .andExpect(status().is4xxClientError()).andExpect(jsonPath("$.detail").value("Last name is required"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE \"to\" = ? AND subject LIKE 'Welcome aboard%'", Integer.class, email)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employees WHERE email = ?", Integer.class, email)).isEqualTo(1);
    }

    @Test
    void onlyPeopleAddMayStartListOrFill() throws Exception {
        String employeeId = start("a");
        mockMvc.perform(write(post("/api/v1/onboardings")).cookie(plain).content(startBody("x." + suffix + "@example.com"))).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/onboardings").cookie(plain)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/onboardings")).andExpect(status().isUnauthorized());
        String base = "/api/v1/people/" + employeeId + "/onboarding";
        putJson(plain, base + "/steps/1", EmployeeOnboardingApiTest.STEP1).andExpect(status().isForbidden());
        putJson(plain, base + "/current-step", "{\"step\":2}").andExpect(status().isForbidden());
        upload(plain, employeeId, "bank").andExpect(status().isForbidden());
        mockMvc.perform(write(delete(base + "/attachments/" + UUID.randomUUID())).cookie(plain)).andExpect(status().isForbidden());
        mockMvc.perform(get(base + "/secrets").cookie(plain)).andExpect(status().isForbidden());
        mockMvc.perform(write(post(base + "/submission")).cookie(plain)).andExpect(status().isForbidden());
        mockMvc.perform(write(put(base + "/steps/1")).content("{}")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT status FROM employee_onboardings WHERE employee_id = ?::uuid", String.class, employeeId)).isEqualTo("NOT_STARTED");
    }

    // ───────────────────────── on the employee's behalf ─────────────────────────

    @Test
    void hrFillsAllSixStepsOnBehalfAndSubmits() throws Exception {
        String employeeId = start("b");
        String base = "/api/v1/people/" + employeeId + "/onboarding";

        JsonNode first = saveOk(employeeId, 1, EmployeeOnboardingApiTest.STEP1);
        assertThat(first.get("status").asText()).isEqualTo("IN_PROGRESS");
        assertThat(first.get("currentStep").asInt()).isEqualTo(2);
        assertThat(first.at("/header/name").asText()).isEqualTo("Meera Joshi");
        fillSteps(employeeId);
        JsonNode moved = body(putJson(hr, base + "/current-step", "{\"step\":3}").andExpect(status().isOk())).get("data");
        assertThat(moved.get("currentStep").asInt()).isEqualTo(3);
        putJson(hr, base + "/current-step", "{\"step\":9}").andExpect(status().isBadRequest());

        // numbers are masked in the read view and complete in the secrets (no-store, audited)
        JsonNode view = body(mockMvc.perform(get(base).cookie(hr)).andExpect(status().isOk())).get("data");
        assertThat(view.at("/statutory/pan").asText()).isEqualTo("XXXXXXXX234F");
        mockMvc.perform(get(base + "/secrets").cookie(hr)).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.pan").value("ABCDE1234F")).andExpect(jsonPath("$.data.bankAccountNumber").value("50100123456789")).andExpect(jsonPath("$.data.aadhaar").value("234567890123"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'onboarding.secrets_read' AND actor_id = ?", Integer.class, hrId)).isEqualTo(1);

        // not submittable until every mandatory file is there
        JsonNode errors = body(mockMvc.perform(write(post(base + "/submission")).cookie(hr)).andExpect(status().isUnprocessableEntity())).get("errors");
        assertThat(errors.has("attachments.address_permanent")).isTrue();
        for (JsonNode slot : view.get("attachmentSlots")) {
            if (slot.get("isMandatory").asBoolean()) upload(hr, employeeId, slot.get("slotKey").asText()).andExpect(status().isCreated());
        }
        JsonNode afterUpload = body(mockMvc.perform(get(base).cookie(hr)).andExpect(status().isOk())).get("data");
        String fileId = afterUpload.at("/attachmentSlots/0/files/0/id").asText();
        assertThat(fileId).isNotBlank();
        mockMvc.perform(get(base + "/attachments/" + fileId + "/file").cookie(hr)).andExpect(status().isOk());
        mockMvc.perform(write(delete(base + "/attachments/" + fileId)).cookie(hr)).andExpect(status().isOk());
        mockMvc.perform(write(delete(base + "/attachments/" + fileId)).cookie(hr)).andExpect(status().isNotFound());
        upload(hr, employeeId, afterUpload.at("/attachmentSlots/0/slotKey").asText()).andExpect(status().isCreated());
        assertThat(saveOk(employeeId, 6, "{}").at("/stepStatus/6").asText()).isEqualTo("COMPLETE");

        mockMvc.perform(write(post(base + "/submission")).cookie(hr)).andExpect(status().isCreated()).andExpect(jsonPath("$.data.status").value("SUBMITTED"));
        assertThat(jdbc.queryForObject("SELECT status FROM employee_onboardings WHERE employee_id = ?::uuid", String.class, employeeId)).isEqualTo("SUBMITTED");
        assertThat(jdbc.queryForObject("SELECT submitted_at IS NOT NULL FROM employee_onboardings WHERE employee_id = ?::uuid", Boolean.class, employeeId)).isTrue();

        // every write was audited with the HR actor, naming the employee
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE actor_id = ? AND action IN ('onboarding.step_saved', 'onboarding.attachment_add', 'onboarding.attachment_remove', 'onboarding.submitted')",
                Integer.class, hrId)).isGreaterThanOrEqualTo(14);
        assertThat(jdbc.queryForObject("SELECT after::text FROM audit_log WHERE action = 'onboarding.submitted' AND actor_id = ?", String.class, hrId)).contains(employeeId);

        // HR may still correct a submitted onboarding: the status stays SUBMITTED
        assertThat(saveOk(employeeId, 1, EmployeeOnboardingApiTest.STEP1).get("status").asText()).isEqualTo("SUBMITTED");
    }

    @Test
    void invalidPayloadsAnswerTheSameErrorsAsTheEmployeeSide() throws Exception {
        String employeeId = start("c");
        JsonNode errors = body(putJson(hr, "/api/v1/people/" + employeeId + "/onboarding/steps/1", "{}").andExpect(status().isUnprocessableEntity())).get("errors");
        assertThat(errors.has("personal")).isTrue();
        assertThat(errors.has("permanentAddress")).isTrue();
        assertThat(errors.has("education")).isTrue();
        // the same body on the employee's own endpoint gives the same map
        Cookie newcomer = signIn("c." + suffix + "@example.com");
        JsonNode own = body(putJson(newcomer, "/api/v1/onboarding/mine/steps/1", "{}").andExpect(status().isUnprocessableEntity())).get("errors");
        assertThat(own).isEqualTo(errors);

        String invalid = EmployeeOnboardingApiTest.STEP1.replace("\"pin\":\"110001\"", "\"pin\":\"11\"").replace("\"toYear\":2012", "\"toYear\":2005");
        errors = body(putJson(hr, "/api/v1/people/" + employeeId + "/onboarding/steps/1", invalid).andExpect(status().isUnprocessableEntity())).get("errors");
        assertThat(errors.get("permanentAddress.pin").asText()).contains("6 digits");
        assertThat(errors.get("education[0].toYear").asText()).contains("cannot be before");
        putJson(hr, "/api/v1/people/" + employeeId + "/onboarding/steps/7", "{}").andExpect(status().isBadRequest());
        mockMvc.perform(write(post("/api/v1/people/" + employeeId + "/onboarding/submission")).cookie(hr)).andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("SELECT status FROM employee_onboardings WHERE employee_id = ?::uuid", String.class, employeeId)).isEqualTo("NOT_STARTED"); // nothing saved
        upload(hr, employeeId, "not-a-slot").andExpect(status().isUnprocessableEntity());
    }

    @Test
    void approvedOrNotRequiredOrForeignOnboardingsAreRefused() throws Exception {
        String employeeId = start("d");
        String base = "/api/v1/people/" + employeeId + "/onboarding";
        saveOk(employeeId, 1, EmployeeOnboardingApiTest.STEP1);
        upload(hr, employeeId, "address_permanent").andExpect(status().isCreated());

        // APPROVED: read only
        jdbc.update("UPDATE employee_onboardings SET status = 'APPROVED' WHERE employee_id = ?::uuid", employeeId);
        putJson(hr, base + "/steps/1", EmployeeOnboardingApiTest.STEP1).andExpect(status().isConflict());
        putJson(hr, base + "/current-step", "{\"step\":2}").andExpect(status().isConflict());
        upload(hr, employeeId, "bank").andExpect(status().isConflict());
        mockMvc.perform(write(post(base + "/submission")).cookie(hr)).andExpect(status().isConflict());
        mockMvc.perform(get(base + "/secrets").cookie(hr)).andExpect(status().isConflict());
        mockMvc.perform(write(delete(base + "/attachments/" + UUID.randomUUID())).cookie(hr)).andExpect(status().isConflict());
        mockMvc.perform(get(base).cookie(hr)).andExpect(status().isOk());

        // NOT_REQUIRED: not found
        jdbc.update("UPDATE employee_onboardings SET status = 'NOT_REQUIRED' WHERE employee_id = ?::uuid", employeeId);
        putJson(hr, base + "/steps/1", EmployeeOnboardingApiTest.STEP1).andExpect(status().isNotFound());
        mockMvc.perform(get(base + "/secrets").cookie(hr)).andExpect(status().isNotFound());
        mockMvc.perform(write(post(base + "/submission")).cookie(hr)).andExpect(status().isNotFound());

        // another business's employee is invisible
        TestData stranger = new TestData(jdbc).inBusiness("onboard-other-" + suffix);
        UUID strangerId = stranger.employee("ST-" + suffix, "st." + suffix + "@example.com", stranger.role("employee", "self"));
        jdbc.update("INSERT INTO employee_onboardings (id, business_id, employee_id, status) VALUES (?, ?, ?, 'NOT_STARTED')", UUID.randomUUID(), stranger.business(), strangerId);
        String foreign = "/api/v1/people/" + strangerId + "/onboarding";
        putJson(hr, foreign + "/steps/1", EmployeeOnboardingApiTest.STEP1).andExpect(status().isNotFound());
        putJson(hr, foreign + "/current-step", "{\"step\":2}").andExpect(status().isNotFound());
        upload(hr, strangerId.toString(), "bank").andExpect(status().isNotFound());
        mockMvc.perform(get(foreign + "/secrets").cookie(hr)).andExpect(status().isNotFound());
        mockMvc.perform(write(post(foreign + "/submission")).cookie(hr)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/onboardings?search=ST-" + suffix).cookie(hr)).andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        assertThat(jdbc.queryForObject("SELECT status FROM employee_onboardings WHERE employee_id = ?", String.class, strangerId)).isEqualTo("NOT_STARTED");
    }

    // ───────────────────────── the list ─────────────────────────

    @Test
    void theListFiltersSearchesAndPagesNewestFirst() throws Exception {
        String first = start("one");
        String second = start("two");
        String third = start("three");
        jdbc.update("UPDATE employee_onboardings SET created_at = now() - interval '3 days' WHERE employee_id = ?::uuid", first);
        jdbc.update("UPDATE employee_onboardings SET created_at = now() - interval '2 days' WHERE employee_id = ?::uuid", second);
        jdbc.update("UPDATE employee_onboardings SET status = 'SUBMITTED', submitted_at = now(), current_step = 6 WHERE employee_id = ?::uuid", second);
        jdbc.update("UPDATE employee_onboardings SET status = 'APPROVED' WHERE employee_id = ?::uuid", third);
        // an old employee without the wizard never shows
        UUID old = data.employee("OLD-" + suffix, "old." + suffix + "@example.com", employeeRoleId);
        jdbc.update("INSERT INTO employee_onboardings (id, business_id, employee_id, status) VALUES (?, ?, ?, 'NOT_REQUIRED')", UUID.randomUUID(), data.business(), old);

        JsonNode all = body(mockMvc.perform(get("/api/v1/onboardings?search=" + suffix).cookie(hr)).andExpect(status().isOk())).get("data");
        assertThat(all.get("total").asInt()).isEqualTo(3);
        assertThat(all.at("/rows/0/employeeId").asText()).isEqualTo(third);
        assertThat(all.at("/rows/1/employeeId").asText()).isEqualTo(second);
        assertThat(all.at("/rows/2/employeeId").asText()).isEqualTo(first);
        JsonNode row = all.at("/rows/1");
        assertThat(row.get("name").asText()).isEqualTo("Meera Joshi");
        assertThat(row.get("email").asText()).isEqualTo("two." + suffix + "@example.com");
        assertThat(row.get("designation").asText()).isEqualTo("Associate");
        assertThat(row.get("department").asText()).isEqualTo("Legal " + suffix);
        assertThat(row.get("employeeCode").asText()).isNotBlank();
        assertThat(row.get("status").asText()).isEqualTo("SUBMITTED");
        assertThat(row.get("submittedAt").asText()).isNotBlank();
        assertThat(row.get("currentStep").asInt()).isEqualTo(6);
        assertThat(all.at("/rows/2/submittedAt").isNull()).isTrue();
        assertThat(all.get("page").asInt()).isEqualTo(1);

        mockMvc.perform(get("/api/v1/onboardings?status=SUBMITTED&search=" + suffix).cookie(hr)).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.rows[0].employeeId").value(second));
        mockMvc.perform(get("/api/v1/onboardings?status=NOT_STARTED&search=" + suffix).cookie(hr)).andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.rows[0].employeeId").value(first));
        mockMvc.perform(get("/api/v1/onboardings?status=APPROVED&search=" + suffix).cookie(hr)).andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/v1/onboardings?status=NOT_REQUIRED").cookie(hr)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/onboardings?search=old." + suffix).cookie(hr)).andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/v1/onboardings?search=" + suffix + "&pageSize=2&page=2").cookie(hr)).andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.rows.length()").value(1)).andExpect(jsonPath("$.data.rows[0].employeeId").value(first)).andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.take").value(2));
    }
}
