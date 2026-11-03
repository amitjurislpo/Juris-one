package com.jurislpo.jurisone.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The slim Add employee form, the employee-code series, and "Initiate onboarding" for several employees at once. */
class AddEmployeeAndInitiationApiTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;

    TestData data;
    String suffix, prefix;
    UUID hrRoleId, employeeRoleId, adminRoleId;
    Cookie hr, plain, admin;

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
        data = new TestData(jdbc).inBusiness("slim-add-" + suffix);
        prefix = jdbc.queryForObject("SELECT employee_prefix FROM businesses WHERE id = ?", String.class, data.business());
        if (jdbc.queryForObject("SELECT count(*) FROM shifts WHERE code = 'IN_NIGHT'", Integer.class) == 0) {
            jdbc.update("INSERT INTO shifts (id, code, name, start_local, end_local, region) VALUES (?, 'IN_NIGHT', 'Night', '19:30', '04:30', 'IN'::\"Region\")", UUID.randomUUID());
        }
        employeeRoleId = data.role("employee", "self", "dir");
        hrRoleId = data.role("hrm", "self", "dir", "people.view", "people.add");
        adminRoleId = data.role("owner", "self", "dir", "people.view", "people.add", "roles.manage");
        jdbc.update("INSERT INTO role_assignable_roles (role_id, assignable_role_id) VALUES (?, ?) ON CONFLICT DO NOTHING", hrRoleId, employeeRoleId);
        jdbc.update("INSERT INTO role_assignable_roles (role_id, assignable_role_id) VALUES (?, ?) ON CONFLICT DO NOTHING", adminRoleId, employeeRoleId);
        data.employee("HR-" + suffix, "hr." + suffix + "@example.com", hrRoleId);
        data.employee("PL-" + suffix, "pl." + suffix + "@example.com", employeeRoleId);
        data.employee("AD-" + suffix, "ad." + suffix + "@example.com", adminRoleId);
        hr = signIn("hr." + suffix + "@example.com");
        plain = signIn("pl." + suffix + "@example.com");
        admin = signIn("ad." + suffix + "@example.com");
    }

    JsonNode body(ResultActions actions) throws Exception {
        return json.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    String slim(String firstName, String email, String extra) {
        return "{\"firstName\":\"" + firstName + "\",\"lastName\":\"Joshi\",\"email\":\"" + email + "\",\"phoneCountry\":\"+91\",\"phone\":\"98765 43210\",\"paymentType\":\"BANK_TRANSFER\""
                + (extra.isEmpty() ? "" : "," + extra) + "}";
    }

    ResultActions add(Cookie cookie, String content) throws Exception {
        return mockMvc.perform(write(post("/api/v1/people")).cookie(cookie).content(content));
    }

    String email(String name) {
        return name + "." + suffix + "@example.com";
    }

    UUID addOk(String name) throws Exception {
        return UUID.fromString(body(add(hr, slim("Meera", email(name), "")).andExpect(status().isCreated())).at("/data/id").asText());
    }

    String codeOf(UUID id) {
        return jdbc.queryForObject("SELECT code FROM employees WHERE id = ?", String.class, id);
    }

    void onboardingRow(UUID employeeId, String status) {
        jdbc.update("INSERT INTO employee_onboardings (id, business_id, employee_id, status) VALUES (?, ?, ?, ?) ON CONFLICT (employee_id) DO UPDATE SET status = EXCLUDED.status",
                UUID.randomUUID(), data.business(), employeeId, status);
    }

    // ───────────────────────── Add employee ─────────────────────────

    @Test
    void addsAnEmployeeWithOnlyTheFiveMandatoryFieldsAndAnAutomaticCode() throws Exception {
        UUID id = addOk("first");
        assertThat(codeOf(id)).isEqualTo(prefix + "01");
        assertThat(codeOf(addOk("second"))).isEqualTo(prefix + "02");
        assertThat(jdbc.queryForMap("SELECT payment_type, employment_type, designation, department_id::text AS department FROM employees WHERE id = ?", id))
                .containsEntry("payment_type", "BANK_TRANSFER").containsEntry("employment_type", null).containsEntry("designation", "").containsEntry("department", null);
        assertThat(jdbc.queryForObject("SELECT r.key FROM employees e JOIN roles r ON r.id = e.role_id WHERE e.id = ?", String.class, id)).isEqualTo("employee");
        assertThat(jdbc.queryForObject("SELECT status FROM employee_onboardings WHERE employee_id = ?", String.class, id)).isEqualTo("NOT_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'employee.create' AND entity_id = ?", Integer.class, id.toString())).isEqualTo(1);
    }

    @Test
    void storesTheOptionalAttributes() throws Exception {
        UUID id = UUID.fromString(body(add(hr, slim("Asha", email("attributes"), "\"employmentType\":\"CONTRACT\",\"gender\":\"FEMALE\"")).andExpect(status().isCreated())).at("/data/id").asText());
        assertThat(jdbc.queryForMap("SELECT employment_type, gender FROM employees WHERE id = ?", id)).containsEntry("employment_type", "CONTRACT").containsEntry("gender", "FEMALE");
    }

    @Test
    void refusesWhenAMandatoryFieldIsMissingOrHostile() throws Exception {
        add(hr, "{\"firstName\":\"A\",\"lastName\":\"B\",\"email\":\"" + email("nopay") + "\",\"phoneCountry\":\"+91\",\"phone\":\"98765 43210\"}").andExpect(status().isBadRequest());
        add(hr, slim("Asha", email("badpay"), "\"paymentType\":\"CRYPTO\"").replace("\"paymentType\":\"BANK_TRANSFER\",", "")).andExpect(status().isBadRequest());
        add(hr, slim("Asha", email("badtype"), "\"employmentType\":\"x'; DROP TABLE employees;--\"")).andExpect(status().isBadRequest());
        add(hr, slim("Asha", email("badgender"), "\"gender\":\"<script>\"")).andExpect(status().isBadRequest());
        add(hr, slim("Asha", "not-an-email", "")).andExpect(status().isBadRequest());
        add(hr, slim("Asha", email("nophone"), "").replace("98765 43210", "")).andExpect(status().isUnprocessableEntity());
        add(hr, slim("A".repeat(500), email("long"), "")).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employees WHERE email LIKE ?", Integer.class, "%." + suffix + "@example.com")).isEqualTo(3); // only the three seeded people
    }

    @Test
    void anEmployeeWithoutThePermissionCannotAdd() throws Exception {
        add(plain, slim("Asha", email("denied"), "")).andExpect(status().isForbidden());
    }

    @Test
    void theAutomaticCodeGrowsPastNinetyNine() throws Exception {
        data.employee(prefix + "99", email("ninetynine"), employeeRoleId);
        assertThat(codeOf(addOk("hundred"))).isEqualTo(prefix + "100");
        assertThat(codeOf(addOk("hundredone"))).isEqualTo(prefix + "101");
    }

    @Test
    void aTypedCodeIsUsedWhenFreeAndRefusedWhenTakenOrMalformed() throws Exception {
        UUID id = UUID.fromString(body(add(hr, slim("Asha", email("custom"), "\"employeeCode\":\"" + prefix + "77\"")).andExpect(status().isCreated())).at("/data/id").asText());
        assertThat(codeOf(id)).isEqualTo(prefix + "77");
        add(hr, slim("Ravi", email("clash"), "\"employeeCode\":\"" + prefix + "77\"")).andExpect(status().isConflict());
        for (String bad : List.of("JC-12", "A;DROP", "../etc", "ABCDEFGHIJKL1", "AB1234567", "12")) {
            add(hr, slim("Ravi", email("bad" + bad.length()), "\"employeeCode\":\"" + bad + "\"")).andExpect(status().isBadRequest());
        }
        // the typed code does not disturb the automatic series
        assertThat(codeOf(addOk("auto"))).isEqualTo(prefix + "78");
    }

    @Test
    void aTypedCodeIsUniquePerBusinessOnly() throws Exception {
        TestData other = data.inBusiness("slim-other-" + suffix);
        other.employee("SAME" + suffix.substring(0, 3).toUpperCase().replace("X", "Y") + "1", email("othertenant"), other.role("employee", "self"));
        // the same code in another business is free for this one
        String code = jdbc.queryForObject("SELECT code FROM employees WHERE email = ?", String.class, email("othertenant"));
        add(hr, slim("Asha", email("sametenant"), "\"employeeCode\":\"" + code + "\"")).andExpect(status().isCreated());
    }

    @Test
    void manualSeriesRequiresACodeAndOnlyTheAdminChangesTheSetting() throws Exception {
        mockMvc.perform(write(put("/api/v1/settings/employee-code-series")).cookie(hr).content("{\"mode\":\"MANUAL\"}")).andExpect(status().isForbidden());
        mockMvc.perform(write(put("/api/v1/settings/employee-code-series")).cookie(admin).content("{\"mode\":\"WEIRD\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(put("/api/v1/settings/employee-code-series")).cookie(admin).content("{\"mode\":\"MANUAL\"}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT code_series_mode FROM businesses WHERE id = ?", String.class, data.business())).isEqualTo("MANUAL");
        add(hr, slim("Asha", email("nocode"), "")).andExpect(status().isBadRequest());
        add(hr, slim("Asha", email("withcode"), "\"employeeCode\":\"" + prefix + "05\"")).andExpect(status().isCreated());
        JsonNode options = body(mockMvc.perform(get("/api/v1/people/form-options").cookie(hr)).andExpect(status().isOk()));
        assertThat(options.at("/data/codeSeriesMode").asText()).isEqualTo("MANUAL");
        mockMvc.perform(write(put("/api/v1/settings/employee-code-series")).cookie(admin).content("{\"mode\":\"AUTO\"}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'business.code_series'", Integer.class)).isGreaterThanOrEqualTo(2);
    }

    // ───────────────────────── Initiate onboarding ─────────────────────────

    ResultActions initiate(Cookie cookie, String content) throws Exception {
        return mockMvc.perform(write(post("/api/v1/onboarding/invitations")).cookie(cookie).content(content));
    }

    String ids(UUID... employeeIds) {
        return String.join(",", java.util.Arrays.stream(employeeIds).map(id -> "\"" + id + "\"").toList());
    }

    @Test
    void initiatesSeveralEmployeesAtOnceAndRefusesTheOnesThatCannotGo() throws Exception {
        UUID fresh = addOk("fresh"), second = addOk("second"), approved = addOk("approved"), submitted = addOk("submitted"), exited = addOk("exited");
        onboardingRow(approved, "APPROVED");
        onboardingRow(submitted, "SUBMITTED");
        jdbc.update("UPDATE employees SET status = 'EXITED', exit_date = current_date WHERE id = ?", exited);
        JsonNode result = body(initiate(hr, "{\"employeeIds\":[" + ids(fresh, second, approved, submitted, exited) + "],\"filledBy\":\"EMPLOYEE\"}").andExpect(status().isCreated())).get("data");
        assertThat(result.get("initiated").asInt()).isEqualTo(2);
        assertThat(result.get("refused").asInt()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT status FROM employee_onboardings WHERE employee_id = ?", String.class, fresh)).isEqualTo("NOT_STARTED");
        assertThat(jdbc.queryForObject("SELECT filled_by FROM employee_onboardings WHERE employee_id = ?", String.class, fresh)).isEqualTo("EMPLOYEE");
        assertThat(jdbc.queryForObject("SELECT status FROM employee_onboardings WHERE employee_id = ?", String.class, approved)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT status FROM employee_onboardings WHERE employee_id = ?", String.class, submitted)).isEqualTo("SUBMITTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'onboarding.initiated'", Integer.class)).isGreaterThanOrEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'onboarding.initiation_refused'", Integer.class)).isGreaterThanOrEqualTo(3);
    }

    @Test
    void hrFilledModeSendsNoMailAndHrCanThenFillOnTheirBehalf() throws Exception {
        UUID id = addOk("hrfills");
        int mailsBefore = jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE \"to\" = ?", Integer.class, email("hrfills"));
        initiate(hr, "{\"employeeIds\":[" + ids(id) + "],\"filledBy\":\"HR\"}").andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT filled_by FROM employee_onboardings WHERE employee_id = ?", String.class, id)).isEqualTo("HR");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE \"to\" = ?", Integer.class, email("hrfills"))).isEqualTo(mailsBefore);
        // HR's endpoints for the wizard now work for this employee (before initiating they answered 404)
        mockMvc.perform(write(put("/api/v1/people/" + id + "/onboarding/current-step")).cookie(hr).content("{\"step\":2}")).andExpect(status().isOk());
    }

    @Test
    void employeeFilledModeEmailsTheInvitation() throws Exception {
        UUID id = addOk("mailed");
        initiate(hr, "{\"employeeIds\":[" + ids(id) + "]}").andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE \"to\" = ? AND subject LIKE 'Welcome aboard%'", Integer.class, email("mailed"))).isEqualTo(1);
    }

    @Test
    void refusesOtherBusinessesEmployeesAndHostileInput() throws Exception {
        TestData other = data.inBusiness("slim-foreign-" + suffix);
        UUID foreign = other.employee("FO" + suffix.substring(0, 3).toUpperCase() + "1", email("foreign"), other.role("employee", "self"));
        jdbc.update("INSERT INTO employee_onboardings (id, business_id, employee_id, status) VALUES (?, ?, ?, 'NOT_REQUIRED')", UUID.randomUUID(), other.business(), foreign);
        JsonNode result = body(initiate(hr, "{\"employeeIds\":[" + ids(foreign) + "],\"filledBy\":\"EMPLOYEE\"}").andExpect(status().isCreated())).get("data");
        assertThat(result.get("initiated").asInt()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM employee_onboardings WHERE employee_id = ?", String.class, foreign)).isEqualTo("NOT_REQUIRED");

        initiate(hr, "{\"employeeIds\":[],\"filledBy\":\"HR\"}").andExpect(status().isBadRequest());
        initiate(hr, "{\"filledBy\":\"HR\"}").andExpect(status().isBadRequest());
        initiate(hr, "{\"employeeIds\":[\"1' OR '1'='1\"],\"filledBy\":\"HR\"}").andExpect(status().isBadRequest());
        initiate(hr, "{\"employeeIds\":[" + ids(UUID.randomUUID()) + "],\"filledBy\":\"ROOT\"}").andExpect(status().isBadRequest());
        String tooMany = String.join(",", java.util.Collections.nCopies(101, "\"" + UUID.randomUUID() + "\""));
        initiate(hr, "{\"employeeIds\":[" + tooMany + "],\"filledBy\":\"HR\"}").andExpect(status().isBadRequest());
    }

    @Test
    void onlyPeopleWithTheAddPermissionMayInitiate() throws Exception {
        UUID id = addOk("denied");
        initiate(plain, "{\"employeeIds\":[" + ids(id) + "]}").andExpect(status().isForbidden());
        mockMvc.perform(write(post("/api/v1/onboarding/invitations")).content("{}")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT status FROM employee_onboardings WHERE employee_id = ?", String.class, id)).isEqualTo("NOT_REQUIRED");
    }
}
