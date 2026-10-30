package com.jurislpo.jurisone.juristrack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.JurisTrackStub;
import com.jurislpo.jurisone.JurisTrackStub.Received;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import com.jurislpo.jurisone.service.JurisTrackRosterService;
import com.jurislpo.jurisone.service.JurisTrackRosterTrigger;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.Cookie;
import java.sql.Date;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The roster of a business with Juris Track goes to JurisTrack: its shape, its token, when it is sent, and what happens when JurisTrack is down. */
class JurisTrackRosterApiTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JurisTrackRosterService roster;
    @Autowired JurisTrackRosterTrigger trigger;
    final ObjectMapper json = new ObjectMapper();

    static int sequence = 100;

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    @BeforeEach
    void setUp() {
        for (String[] shift : new String[][] {{"IN_NIGHT", "19:30", "04:30", "IN"}, {"IN_DAY", "09:30", "18:30", "IN"}, {"US_DAY", "09:00", "18:00", "US"}, {"IN_UNUSED", "12:00", "13:00", "IN"}}) {
            jdbc.update("INSERT INTO shifts (id, code, name, start_local, end_local, region) SELECT ?, ?, ?, ?, ?, ?::\"Region\" WHERE NOT EXISTS (SELECT 1 FROM shifts WHERE code = ?)",
                    UUID.randomUUID(), shift[0], shift[0], shift[1], shift[2], shift[3], shift[0]);
        }
        JurisTrackStub.reset();
        JurisTrackStub.answerWith(200, "application/json", JurisTrackStub.ROSTER_SUMMARY);
    }

    // ───────────────────────── helpers ─────────────────────────

    TestData business(String zone, boolean withModule) {
        TestData data = new TestData(jdbc).inBusiness("jt-roster-" + TestData.letters(8));
        jdbc.update("UPDATE businesses SET time_zone = ? WHERE id = ?", zone, data.business());
        if (withModule) {
            jdbc.update("INSERT INTO business_modules (id, business_id, module, source, starts_on) VALUES (?, ?, 'JURIS_TRACK', 'COMPLIMENTARY', ?)",
                    UUID.randomUUID(), data.business(), Date.valueOf(LocalDate.now().minusDays(1)));
        }
        return data;
    }

    /** An employee with a shift, zone, status and manager (any of them may be null). */
    UUID employee(TestData data, String shiftCode, String zone, String status, UUID manager) {
        String code = "RS-" + (++sequence) + TestData.letters(3).toUpperCase();
        UUID id = data.employee(code, code.toLowerCase() + "@example.com", data.role("jt-roster-employee", "self", "dir"));
        jdbc.update("UPDATE employees SET shift_id = (SELECT id FROM shifts WHERE code = ?), time_zone = ?, region = ?::\"Region\", status = ?::\"EmployeeStatus\", reporting_manager_id = ? WHERE id = ?",
                shiftCode, zone, zone.startsWith("America") ? "US" : "IN", status, manager, id);
        return id;
    }

    JWTClaimsSet claimsOf(Received received) throws Exception {
        String authorization = received.headers().get("authorization");
        assertThat(authorization).startsWith("Bearer ");
        SignedJWT token = SignedJWT.parse(authorization.substring("Bearer ".length()));
        assertThat(token.verify(new RSASSAVerifier(JurisTrackStub.publicKey()))).isTrue();
        return token.getJWTClaimsSet();
    }

    List<Received> pushesOf(UUID business) {
        return JurisTrackStub.rosterRequests().stream().filter(received -> {
            try {
                return business.toString().equals(claimsOf(received).getStringClaim("businessId"));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).toList();
    }

    /** Waits until the business has had {@code count} pushes, then a while longer to show that no more come (the pushes are debounced to 300 ms). */
    List<Received> awaitPushes(UUID business, int count) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (pushesOf(business).size() < count && System.currentTimeMillis() < deadline) Thread.sleep(50);
        Thread.sleep(1000);
        assertThat(pushesOf(business)).hasSize(count);
        return pushesOf(business);
    }

    JsonNode person(JsonNode body, UUID employeeId) {
        for (JsonNode employee : body.get("employees")) if (employee.get("employeeId").asText().equals(employeeId.toString())) return employee;
        return null;
    }

    Cookie signIn(String email) throws Exception {
        String set = SignIn.session(mockMvc, email).andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        return new Cookie("jo_at", matcher.group(1));
    }

    /** HR of the business: may add people and give them the employee role. Returns the cookie and the HR employee id. */
    record Hr(Cookie cookie, UUID id, UUID department, UUID employeeRole) {}

    Hr hr(TestData data) throws Exception {
        UUID hrRole = data.role("hrm", "self", "dir", "people.view", "people.add", "access.toggle");
        UUID employeeRole = data.role("jt-roster-employee", "self", "dir");
        jdbc.update("INSERT INTO role_assignable_roles (role_id, assignable_role_id) VALUES (?, ?) ON CONFLICT DO NOTHING", hrRole, employeeRole);
        String code = "HR-" + (++sequence) + TestData.letters(3).toUpperCase();
        UUID id = data.employee(code, code.toLowerCase() + "@example.com", hrRole);
        UUID department = UUID.randomUUID();
        jdbc.update("INSERT INTO departments (id, name, business_id) VALUES (?, ?, ?)", department, "Ops " + TestData.letters(5), data.business());
        return new Hr(signIn(code.toLowerCase() + "@example.com"), id, department, employeeRole);
    }

    String personBody(Hr hr, String email, String firstName) {
        return """
            {"firstName":"%s","middleName":"","lastName":"Joshi","fatherName":"Prakash Joshi","email":"%s","phoneCountry":"+91","phone":"98765 43210","location":"IN_IST","pan":"","aadhaar":"","uan":"",
             "address":"","designation":"Associate","departmentId":"%s","managerId":"%s","dateOfJoining":"2026-10-01","shift":"IN_DAY","roleId":"%s"}""".formatted(firstName, email, hr.department(), hr.id(), hr.employeeRole());
    }

    // ───────────────────────── the payload ─────────────────────────

    @Test
    void theRosterHasTheShiftsInTheBusinessZoneAndEveryEmployeeWithRoleStatusAndManager() throws Exception {
        TestData data = business("Asia/Dubai", true);
        UUID boss = employee(data, "IN_NIGHT", "Asia/Kolkata", "ACTIVE", null);
        UUID lead = employee(data, "IN_DAY", "Asia/Kolkata", "ACTIVE", boss);
        UUID american = employee(data, "US_DAY", "America/New_York", "PROBATION", boss);
        UUID gone = employee(data, "IN_DAY", "Asia/Kolkata", "EXITED", boss);
        UUID badEmail = employee(data, "IN_DAY", "Asia/Kolkata", "ACTIVE", boss);
        jdbc.update("UPDATE employees SET email = 'not-an-email' WHERE id = ?", badEmail);
        UUID noShift = employee(data, null, "Asia/Kolkata", "ACTIVE", lead);
        jdbc.update("UPDATE employees SET shift_id = NULL WHERE id = ?", noShift);

        Map<String, Object> summary = roster.syncBusiness(data.business());

        assertThat(((Map<?, ?>) summary.get("employees")).get("created")).isEqualTo(2);
        Received call = awaitPushes(data.business(), 1).get(0);
        assertThat(call.method()).isEqualTo("PUT");
        assertThat(call.pathAndQuery()).isEqualTo("/api/v1/integration/employees");
        assertThat(call.headers().get("content-type")).startsWith("application/json");
        assertThat(call.headers().get("x-request-id")).isNotBlank();
        JsonNode body = json.readTree(call.body());
        assertThat(body.fieldNames()).toIterable().containsExactlyInAnyOrder("shifts", "employees"); // no per-date roster

        // shifts: only those somebody works, on the Dubai wall clock; US_DAY follows New York's offset of today
        Set<String> codes = new java.util.TreeSet<>();
        for (JsonNode shift : body.get("shifts")) codes.add(shift.get("code").asText());
        assertThat(codes).containsExactly("IN_DAY", "IN_NIGHT", "US_DAY");
        for (JsonNode shift : body.get("shifts")) {
            assertThat(shift.fieldNames()).toIterable().containsExactlyInAnyOrder("code", "name", "startTime", "endTime", "earlyStartMinutes", "lateEndMinutes");
            assertThat(shift.get("earlyStartMinutes").isNull()).isTrue();
            assertThat(shift.get("lateEndMinutes").isNull()).isTrue();
            if (shift.get("code").asText().equals("IN_NIGHT")) assertThat(shift.get("startTime").asText() + "-" + shift.get("endTime").asText()).isEqualTo("18:00-03:00");
            if (shift.get("code").asText().equals("IN_DAY")) assertThat(shift.get("startTime").asText() + "-" + shift.get("endTime").asText()).isEqualTo("08:00-17:00");
            if (shift.get("code").asText().equals("US_DAY")) {
                LocalDate today = LocalDate.now(ZoneId.of("Asia/Dubai"));
                LocalTime start = today.atTime(9, 0).atZone(ZoneId.of("America/New_York")).withZoneSameInstant(ZoneId.of("Asia/Dubai")).toLocalTime();
                assertThat(shift.get("startTime").asText()).isEqualTo(start.format(DateTimeFormatter.ofPattern("HH:mm")));
                assertThat(shift.get("endTime").asText()).isEqualTo(start.plusHours(9).format(DateTimeFormatter.ofPattern("HH:mm")));
            }
        }

        // employees: everybody with a usable email, past employees as inactive
        assertThat(body.get("employees")).hasSize(5);
        assertThat(person(body, badEmail)).isNull();
        JsonNode bossJson = person(body, boss), leadJson = person(body, lead), americanJson = person(body, american), goneJson = person(body, gone), noShiftJson = person(body, noShift);
        assertThat(bossJson.fieldNames()).toIterable().containsExactlyInAnyOrder("employeeId", "email", "firstName", "lastName", "employeeCode", "role", "managerEmployeeId", "isActive", "shiftCode");
        assertThat(bossJson.get("role").asText()).isEqualTo("MANAGER");
        assertThat(bossJson.get("managerEmployeeId").isNull()).isTrue();
        assertThat(bossJson.get("shiftCode").asText()).isEqualTo("IN_NIGHT");
        assertThat(bossJson.get("isActive").asBoolean()).isTrue();
        assertThat(bossJson.get("firstName").asText()).isEqualTo("Person");
        assertThat(bossJson.get("employeeCode").asText()).startsWith("RS-");
        assertThat(bossJson.get("email").asText()).endsWith("@example.com");
        assertThat(leadJson.get("role").asText()).isEqualTo("MANAGER"); // has a report of their own
        assertThat(leadJson.get("managerEmployeeId").asText()).isEqualTo(boss.toString());
        assertThat(americanJson.get("role").asText()).isEqualTo("EMPLOYEE");
        assertThat(americanJson.get("isActive").asBoolean()).isTrue(); // probation people work
        assertThat(americanJson.get("shiftCode").asText()).isEqualTo("US_DAY");
        assertThat(goneJson.get("isActive").asBoolean()).isFalse();
        assertThat(noShiftJson.get("shiftCode").isNull()).isTrue();
        assertThat(noShiftJson.get("role").asText()).isEqualTo("EMPLOYEE");
    }

    @Test
    void aManagerWhoseOnlyReportHasLeftIsAnEmployeeAgain() throws Exception {
        TestData data = business("Asia/Kolkata", true);
        UUID boss = employee(data, "IN_DAY", "Asia/Kolkata", "ACTIVE", null);
        UUID report = employee(data, "IN_DAY", "Asia/Kolkata", "ACTIVE", boss);
        assertThat(person(json.valueToTree(roster.buildPayload(data.business())), boss).get("role").asText()).isEqualTo("MANAGER");
        jdbc.update("UPDATE employees SET status = 'EXITED' WHERE id = ?", report);
        assertThat(person(json.valueToTree(roster.buildPayload(data.business())), boss).get("role").asText()).isEqualTo("EMPLOYEE");
    }

    @Test
    void theSameShiftFromAnotherZoneGetsItsOwnCode() throws Exception {
        TestData data = business("America/New_York", true);
        UUID eastern = employee(data, "US_DAY", "America/New_York", "ACTIVE", null);
        UUID pacific = employee(data, "US_DAY", "America/Los_Angeles", "ACTIVE", null);
        JsonNode body = json.valueToTree(roster.buildPayload(data.business()));
        assertThat(person(body, eastern).get("shiftCode").asText()).isEqualTo("US_DAY");
        assertThat(person(body, pacific).get("shiftCode").asText()).isEqualTo("US_DAY_US_PT");
        assertThat(body.get("shifts")).hasSize(2);
        for (JsonNode shift : body.get("shifts")) {
            assertThat(shift.get("startTime").asText()).isEqualTo(shift.get("code").asText().equals("US_DAY") ? "09:00" : "12:00");
        }
    }

    // ───────────────────────── the service token ─────────────────────────

    @Test
    void theServiceTokenNamesTheBusinessAndNoPersonAndIsFreshForEveryPush() throws Exception {
        TestData data = business("Asia/Kolkata", true);
        employee(data, "IN_DAY", "Asia/Kolkata", "ACTIVE", null);
        roster.syncBusiness(data.business());
        roster.syncBusiness(data.business());
        List<Received> calls = awaitPushes(data.business(), 2);
        JWTClaimsSet first = claimsOf(calls.get(0)), second = claimsOf(calls.get(1));
        assertThat(first.getIssuer()).isEqualTo("http://localhost:3005");
        assertThat(first.getAudience()).containsExactly("juristrack");
        assertThat(first.getStringClaim("scope")).isEqualTo("employees:sync");
        assertThat(first.getStringClaim("businessId")).isEqualTo(data.business().toString());
        assertThat(first.getStringClaim("businessName")).isEqualTo("Business " + jdbc.queryForObject("SELECT code FROM businesses WHERE id = ?", String.class, data.business()));
        assertThat(first.getClaims()).doesNotContainKeys("sub", "email", "name");
        assertThat(Duration.between(first.getIssueTime().toInstant(), first.getExpirationTime().toInstant())).isPositive().isLessThanOrEqualTo(Duration.ofMinutes(10));
        assertThat(first.getJWTID()).isNotBlank().isNotEqualTo(second.getJWTID());
        assertThat(calls.get(0).headers().get("x-request-id")).isNotEqualTo(calls.get(1).headers().get("x-request-id"));
    }

    @Test
    void theUserTokenCarriesTheEmployeesShiftCode() throws Exception {
        TestData data = business("Asia/Kolkata", true);
        UUID role = data.role("jt-roster-employee", "self", "dir");
        String[][] cases = {{"IN_DAY", "Asia/Kolkata", "IN_DAY"}, {"US_DAY", "America/Los_Angeles", "US_DAY_US_PT"}, {null, "Asia/Kolkata", null}};
        for (String[] shift : cases) {
            UUID id = employee(data, shift[0], shift[1], "ACTIVE", null);
            String email = jdbc.queryForObject("SELECT email FROM employees WHERE id = ?", String.class, id);
            if (shift[0] == null) jdbc.update("UPDATE employees SET shift_id = NULL WHERE id = ?", id);
            JurisTrackStub.reset();
            mockMvc.perform(get("/api/v1/juris-track/me/time").cookie(signIn(email))).andExpect(status().isOk());
            JWTClaimsSet claims = claimsOf(JurisTrackStub.received().get(0));
            if (shift[2] == null) assertThat(claims.getClaims()).doesNotContainKey("shiftCode");
            else assertThat(claims.getStringClaim("shiftCode")).isEqualTo(shift[2]);
            assertThat(claims.getSubject()).isEqualTo(id.toString());
        }
        assertThat(role).isNotNull();
    }

    // ───────────────────────── the triggers ─────────────────────────

    @Test
    void addingEditingAndExitingAnEmployeePushesTheRosterOnceEach() throws Exception {
        TestData data = business("Asia/Kolkata", true);
        Hr hr = hr(data);
        String email = "new." + TestData.letters(6) + "@example.com";

        String created = mockMvc.perform(write(post("/api/v1/people")).cookie(hr.cookie()).content(personBody(hr, email, "Meera"))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = json.readTree(created).at("/data/id").asText();
        JsonNode afterAdd = json.readTree(awaitPushes(data.business(), 1).get(0).body());
        JsonNode added = person(afterAdd, UUID.fromString(id));
        assertThat(added.get("email").asText()).isEqualTo(email);
        assertThat(added.get("firstName").asText()).isEqualTo("Meera");
        assertThat(added.get("managerEmployeeId").asText()).isEqualTo(hr.id().toString());
        assertThat(added.get("shiftCode").asText()).isEqualTo("IN_DAY");
        assertThat(person(afterAdd, hr.id()).get("role").asText()).isEqualTo("MANAGER"); // the new report makes the HR manager a manager

        mockMvc.perform(write(put("/api/v1/people/" + id)).cookie(hr.cookie()).content(personBody(hr, email, "Meeraa"))).andExpect(status().isOk());
        JsonNode afterEdit = json.readTree(awaitPushes(data.business(), 2).get(1).body());
        assertThat(person(afterEdit, UUID.fromString(id)).get("firstName").asText()).isEqualTo("Meeraa");

        mockMvc.perform(write(put("/api/v1/people/" + id + "/status")).cookie(hr.cookie()).content("{\"status\":\"EXITED\",\"exitDate\":\"2026-10-20\"}")).andExpect(status().isOk());
        JsonNode afterExit = json.readTree(awaitPushes(data.business(), 3).get(2).body());
        assertThat(person(afterExit, UUID.fromString(id)).get("isActive").asBoolean()).isFalse();
        assertThat(person(afterExit, hr.id()).get("role").asText()).isEqualTo("EMPLOYEE");
    }

    @Test
    void aBusinessWithoutTheModuleIsNeverPushed() throws Exception {
        TestData data = business("Asia/Kolkata", false);
        Hr hr = hr(data);
        mockMvc.perform(write(post("/api/v1/people")).cookie(hr.cookie()).content(personBody(hr, "plain." + TestData.letters(6) + "@example.com", "Asha"))).andExpect(status().isCreated());
        awaitPushes(data.business(), 0);
    }

    @Test
    void aJurisTrackThatIsDownOrFailingNeverFailsTheChange() throws Exception {
        TestData data = business("Asia/Kolkata", true);
        Hr hr = hr(data);
        JurisTrackStub.hangUp(true);
        String email = "down." + TestData.letters(6) + "@example.com";
        String created = mockMvc.perform(write(post("/api/v1/people")).cookie(hr.cookie()).content(personBody(hr, email, "Asha"))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = json.readTree(created).at("/data/id").asText();
        awaitPushes(data.business(), 1); // it tried, JurisTrack hung up
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employees WHERE id = ?::uuid", Integer.class, id)).isEqualTo(1);

        JurisTrackStub.hangUp(false);
        JurisTrackStub.answerWith(500, "application/json", "{}");
        mockMvc.perform(write(put("/api/v1/people/" + id)).cookie(hr.cookie()).content(personBody(hr, email, "Ashaa"))).andExpect(status().isOk());
        awaitPushes(data.business(), 2);
        assertThat(jdbc.queryForObject("SELECT first_name FROM employees WHERE id = ?::uuid", String.class, id)).isEqualTo("Ashaa");
    }

    @Test
    void theFullResyncPushesEveryBusinessWithTheModule() throws Exception {
        TestData withModule = business("Asia/Kolkata", true), without = business("Asia/Kolkata", false);
        employee(withModule, "IN_DAY", "Asia/Kolkata", "ACTIVE", null);
        employee(without, "IN_DAY", "Asia/Kolkata", "ACTIVE", null);
        JurisTrackStub.reset();
        JurisTrackStub.answerWith(200, "application/json", JurisTrackStub.ROSTER_SUMMARY);
        assertThat(trigger.syncAllBusinesses()).isGreaterThanOrEqualTo(1);
        assertThat(pushesOf(withModule.business())).hasSize(1);
        assertThat(pushesOf(without.business())).isEmpty();
    }

    @Test
    void anEndedSubscriptionStillSyncsSoDeactivationsArrive() throws Exception {
        TestData data = business("Asia/Kolkata", false);
        // the plan with the module ended yesterday: the module stays view-only, the roster is still sent
        UUID plan = UUID.randomUUID();
        jdbc.update("INSERT INTO subscription_plans (id, name, price_paise, period_months, modules) VALUES (?, ?, 0, 12, ARRAY['JURIS_TRACK'])", plan, "Track plan " + TestData.letters(6));
        jdbc.update("DELETE FROM business_subscriptions WHERE business_id = ?", data.business());
        jdbc.update("INSERT INTO business_subscriptions (id, business_id, plan_id, starts_on, ends_on, price_paise) VALUES (?, ?, ?, current_date - 400, current_date - 1, 0)",
                UUID.randomUUID(), data.business(), plan);
        UUID someone = employee(data, "IN_DAY", "Asia/Kolkata", "EXITED", null);
        assertThat(roster.isModuleActive(data.business())).isTrue();
        roster.syncBusiness(data.business());
        assertThat(person(json.readTree(awaitPushes(data.business(), 1).get(0).body()), someone).get("isActive").asBoolean()).isFalse();
    }

    // ───────────────────────── the super admin's endpoint ─────────────────────────

    Cookie superAdmin() throws Exception {
        UUID role = jdbc.query("SELECT id FROM roles WHERE key = 'super_admin' AND business_id IS NULL", resultSet -> resultSet.next() ? (UUID) resultSet.getObject(1) : null);
        if (role == null) {
            role = UUID.randomUUID();
            jdbc.update("INSERT INTO roles (id, key, name, is_builtin, is_locked) VALUES (?, 'super_admin', 'Super admin', true, true)", role);
            jdbc.update("INSERT INTO role_permissions (role_id, permission_key) VALUES (?, 'platform.manage')", role);
        }
        UUID id = UUID.randomUUID();
        String email = "platform." + TestData.letters(6) + "@jurisone.test";
        jdbc.update("""
            INSERT INTO employees (id, business_id, code, full_name, email, designation, role_id, date_of_joining, status, time_zone, region, updated_at)
            VALUES (?, NULL, ?, 'Platform Admin', ?, 'Super Administrator', ?, ?, 'ACTIVE', 'Asia/Kolkata', 'IN', now())""",
                id, "SA-" + id.toString().substring(0, 6), email, role, Date.valueOf(LocalDate.of(2025, 1, 1)));
        jdbc.update("INSERT INTO users (employee_id, is_super_admin) VALUES (?, true)", id);
        return signIn(email);
    }

    @Test
    void theSuperAdminCanSendTheRosterNowAndItIsAudited() throws Exception {
        TestData data = business("Asia/Kolkata", true);
        employee(data, "IN_DAY", "Asia/Kolkata", "ACTIVE", null);
        Cookie admin = superAdmin();

        mockMvc.perform(write(post("/api/v1/platform/businesses/" + data.business() + "/juris-track-syncs")).cookie(admin)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.employees.created").value(2)).andExpect(jsonPath("$.data.shifts.created").value(1));
        awaitPushes(data.business(), 1);
        String after = jdbc.queryForObject("SELECT after::text FROM audit_log WHERE action = 'juristrack.roster_synced' AND entity_id = ?", String.class, data.business().toString());
        assertThat(json.readTree(after).at("/employees/created").asInt()).isEqualTo(2);
    }

    @Test
    void whenJurisTrackIsUnreachableOrRefusesTheEndpointAnswers502() throws Exception {
        TestData data = business("Asia/Kolkata", true);
        Cookie admin = superAdmin();
        JurisTrackStub.hangUp(true);
        mockMvc.perform(write(post("/api/v1/platform/businesses/" + data.business() + "/juris-track-syncs")).cookie(admin)).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("JURISTRACK_UNAVAILABLE"));
        JurisTrackStub.hangUp(false);
        JurisTrackStub.answerWith(400, "application/problem+json", "{\"status\":400}");
        mockMvc.perform(write(post("/api/v1/platform/businesses/" + data.business() + "/juris-track-syncs")).cookie(admin)).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("JURISTRACK_REFUSED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'juristrack.roster_synced' AND entity_id = ?", Integer.class, data.business().toString())).isZero();
    }

    @Test
    void onlyTheSuperAdminCanSendItAndTheBusinessMustExistAndHaveJurisTrack() throws Exception {
        TestData data = business("Asia/Kolkata", true), plain = business("Asia/Kolkata", false);
        Cookie hrCookie = hr(data).cookie();
        mockMvc.perform(write(post("/api/v1/platform/businesses/" + data.business() + "/juris-track-syncs")).cookie(hrCookie)).andExpect(status().isForbidden());
        Cookie admin = superAdmin();
        mockMvc.perform(write(post("/api/v1/platform/businesses/" + UUID.randomUUID() + "/juris-track-syncs")).cookie(admin)).andExpect(status().isNotFound());
        mockMvc.perform(write(post("/api/v1/platform/businesses/" + plain.business() + "/juris-track-syncs")).cookie(admin)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("JURISTRACK_NOT_ACTIVE"));
        assertThat(JurisTrackStub.rosterRequests()).isEmpty();
    }

    @Test
    void grantingTheModuleSendsTheRosterWithoutAnEmployeeChange() throws Exception {
        TestData data = business("Asia/Kolkata", false);
        employee(data, "IN_DAY", "Asia/Kolkata", "ACTIVE", null);
        Cookie admin = superAdmin();
        mockMvc.perform(write(post("/api/v1/platform/businesses/" + data.business() + "/modules")).cookie(admin).content("{\"module\":\"JURIS_TRACK\",\"source\":\"COMPLIMENTARY\"}"))
                .andExpect(status().isCreated());
        JsonNode body = json.readTree(awaitPushes(data.business(), 1).get(0).body());
        assertThat(body.get("employees")).hasSize(1);
    }
}
