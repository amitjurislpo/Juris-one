package com.jurislpo.jurisone.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import jakarta.servlet.http.Cookie;
import java.sql.Date;
import java.time.Instant;
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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The audit trail: every write request and sensitive read leaves a row (whatever its outcome), page views too, and the log is append-only. */
class AuditTrailApiTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    TestData data;
    UUID ownerRole, empRole;
    String suffix, code;

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    @BeforeEach
    void setUp() {
        code = "audit" + TestData.letters(6); // its own business: the roles and rows here must not leak into other tests
        data = new TestData(jdbc).inBusiness(code);
        suffix = UUID.randomUUID().toString().substring(0, 6);
        ownerRole = data.role("owner", "dir", "people.view", "people.add", "dept.manage", "roles.manage", "access.toggle");
        empRole = data.role("employee", "self", "dir");
    }

    Cookie cookieOf(MvcResult result) {
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(result.getResponse().getHeader("Set-Cookie"));
        assertThat(matcher.find()).isTrue();
        return new Cookie("jo_at", matcher.group(1));
    }

    record Person(UUID id, String email, Cookie cookie) {}

    Person person(TestData testData, UUID role) throws Exception {
        String code = "AU-" + UUID.randomUUID().toString().substring(0, 8);
        String email = code.toLowerCase() + "@example.com";
        UUID id = testData.employee(code, email, role);
        return new Person(id, email, cookieOf(SignIn.session(mockMvc, email).andExpect(status().isCreated()).andReturn()));
    }

    Person superAdmin() throws Exception {
        UUID role = jdbc.query("SELECT id FROM roles WHERE key = 'super_admin' AND business_id IS NULL", resultSet -> resultSet.next() ? (UUID) resultSet.getObject(1) : null);
        if (role == null) {
            role = UUID.randomUUID();
            jdbc.update("INSERT INTO roles (id, key, name, is_builtin, is_locked) VALUES (?, 'super_admin', 'Super admin', true, true)", role);
            jdbc.update("INSERT INTO role_permissions (role_id, permission_key) VALUES (?, 'platform.manage')", role);
        }
        UUID id = UUID.randomUUID();
        String email = "platform." + suffix + "@jurisone.test";
        jdbc.update("""
            INSERT INTO employees (id, business_id, code, full_name, email, designation, role_id, date_of_joining, status, time_zone, region, updated_at)
            VALUES (?, NULL, ?, 'Platform Admin', ?, 'Super Administrator', ?, ?, 'ACTIVE', 'Asia/Kolkata', 'IN', now())""",
                id, "SA-" + suffix, email, role, Date.valueOf(LocalDate.of(2025, 1, 1)));
        jdbc.update("INSERT INTO users (employee_id, is_super_admin) VALUES (?, true)", id);
        return new Person(id, email, cookieOf(SignIn.session(mockMvc, email).andExpect(status().isCreated()).andReturn()));
    }

    List<Map<String, Object>> rows(String where, Object... args) {
        return jdbc.queryForList("SELECT * FROM audit_log WHERE " + where + " ORDER BY at", args);
    }

    String dept() {
        return "{\"name\":\"Dept " + UUID.randomUUID().toString().substring(0, 8) + "\"}";
    }

    /** The database clock: audit rows are stamped by the database, so comparing with the JVM clock can be off by a few milliseconds. */
    /** The same clock the audit filter stamps its rows with (the JVM's), so "rows since now" never depends on drift between the JVM and the database container. */
    Instant startOfTest() {
        return Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }

    @Test
    void everyWriteLeavesARowWhateverTheOutcome() throws Exception {
        Person owner = person(data, ownerRole), employee = person(data, empRole);

        // success: the row names the actor and business, how it ended, how long it took, and its id is in the response header
        MvcResult result = mockMvc.perform(write(post("/api/v1/departments")).cookie(owner.cookie()).content(dept())).andReturn();
        assertThat(result.getResponse().getStatus()).isBetween(200, 201);
        String departmentRequestId = result.getResponse().getHeader("X-Request-Id");
        Map<String, Object> row = rows("request_id = ?", departmentRequestId).get(0);
        assertThat(row.get("action")).isEqualTo("request.POST");
        assertThat(row.get("entity")).isEqualTo("api");
        assertThat(row.get("path")).isEqualTo("/api/v1/departments");
        assertThat(row.get("method")).isEqualTo("POST");
        assertThat(row.get("status")).isEqualTo(result.getResponse().getStatus());
        assertThat(row.get("actor_id")).isEqualTo(owner.id());
        assertThat(row.get("business_id")).isEqualTo(data.business());
        assertThat((Integer) row.get("duration_ms")).isGreaterThanOrEqualTo(0);
        assertThat(row.get("at")).isNotNull();

        // a business rule refuses it (the service rolls back): still recorded
        MvcResult refused = mockMvc.perform(write(post("/api/v1/departments")).cookie(owner.cookie()).content("{\"name\":\"\"}")).andReturn();
        int refusedStatus = refused.getResponse().getStatus();
        assertThat(refusedStatus).isGreaterThanOrEqualTo(400);
        // no permission: 403
        MvcResult forbidden = mockMvc.perform(write(post("/api/v1/departments")).cookie(employee.cookie()).content(dept())).andReturn();
        assertThat(forbidden.getResponse().getStatus()).isEqualTo(403);
        // not signed in: 401, no actor
        MvcResult unauthenticated = mockMvc.perform(write(post("/api/v1/departments")).content(dept())).andReturn();
        assertThat(unauthenticated.getResponse().getStatus()).isEqualTo(401);
        // a request from another origin is turned away before any controller: recorded too
        MvcResult otherOrigin = mockMvc.perform(post("/api/v1/departments").header("origin", "http://evil.example").header("host", "localhost:3005")
                .contentType(MediaType.APPLICATION_JSON).cookie(owner.cookie()).content(dept())).andReturn();
        assertThat(otherOrigin.getResponse().getStatus()).isEqualTo(403);

        // looked up by the request ids the API handed back, so the check does not depend on clocks or on other tests' rows
        List<MvcResult> requests = List.of(result, refused, forbidden, unauthenticated, otherOrigin);
        List<Map<String, Object>> posts = new java.util.ArrayList<>();
        for (MvcResult request : requests) {
            String requestId = request.getResponse().getHeader("X-Request-Id");
            assertThat(requestId).as("every response carries its request id").isNotNull();
            List<Map<String, Object>> recorded = jdbc.queryForList("SELECT status, actor_id FROM audit_log WHERE request_id = ?", requestId);
            assertThat(recorded).as("one audit row for request " + requestId).hasSize(1);
            posts.add(recorded.get(0));
        }
        assertThat(posts.stream().map(row2 -> row2.get("status")).toList()).containsExactly(result.getResponse().getStatus(), refusedStatus, 403, 401, 403);
        assertThat(posts.get(3).get("actor_id")).isNull();
        assertThat(posts.get(2).get("actor_id")).isEqualTo(employee.id());
    }

    @Test
    void anEndedSubscriptionsRefusalIsRecordedToo() throws Exception {
        TestData ended = data.inBusiness("audit" + TestData.letters(6));
        UUID roleId = ended.role("owner", "dir", "dept.manage", "roles.manage");
        Person owner = person(ended, roleId); // sign in while the subscription is still valid
        jdbc.update("UPDATE business_subscriptions SET starts_on = current_date - 400, ends_on = current_date - 10 WHERE business_id = ?", ended.business());
        MvcResult result = mockMvc.perform(write(post("/api/v1/departments")).cookie(owner.cookie()).content(dept())).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(402);
        Map<String, Object> row = rows("request_id = ?", result.getResponse().getHeader("X-Request-Id")).get(0);
        assertThat(row.get("status")).isEqualTo(402);
        assertThat(row.get("business_id")).isEqualTo(ended.business());
        assertThat(row.get("actor_id")).isEqualTo(owner.id());
    }

    @Test
    void onlySensitiveReadsAreRecorded() throws Exception {
        Person owner = person(data, ownerRole);
        Instant startedAt = startOfTest();
        mockMvc.perform(get("/api/v1/me").cookie(owner.cookie())).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/departments").cookie(owner.cookie()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE method = 'GET' AND at >= ? AND path IN ('/api/v1/me', '/api/v1/departments')",
                Integer.class, java.sql.Timestamp.from(startedAt))).isZero();
        mockMvc.perform(get("/api/v1/people/" + owner.id()).cookie(owner.cookie())); // recorded whether it is answered or refused
        mockMvc.perform(get("/api/v1/people/" + owner.id() + "/contact").cookie(owner.cookie()));
        List<Map<String, Object>> reads = jdbc.queryForList("SELECT path, action, actor_id FROM audit_log WHERE method = 'GET' AND at >= ? ORDER BY at", java.sql.Timestamp.from(startedAt));
        assertThat(reads.stream().map(x -> x.get("path")).toList()).containsExactly("/api/v1/people/" + owner.id(), "/api/v1/people/" + owner.id() + "/contact");
        assertThat(reads.get(0).get("action")).isEqualTo("request.GET");
        assertThat(reads.get(0).get("actor_id")).isEqualTo(owner.id());
    }

    @Test
    void theLogIsAppendOnly() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO audit_log (id, action, entity, business_id) VALUES (?, 'test.row', 'test', NULL)", id);
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_log SET action = 'changed' WHERE id = ?", id)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_log WHERE id = ?", id)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_log SET at = now() - interval '1 day' WHERE id = ?", id)).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT action FROM audit_log WHERE id = ?", String.class, id)).isEqualTo("test.row");
        // the one allowed change: a row without a business gets one (and deleting an employee clears the actor reference)
        jdbc.update("UPDATE audit_log SET business_id = ? WHERE id = ?", data.business(), id);
        assertThat(jdbc.queryForObject("SELECT business_id FROM audit_log WHERE id = ?", UUID.class, id)).isEqualTo(data.business());
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_log SET business_id = NULL WHERE id = ?", id)).isInstanceOf(DataAccessException.class);
    }

    @Test
    void pageViewsAreRecordedForAnySignedInUser() throws Exception {
        Person employee = person(data, empRole);
        Instant startedAt = startOfTest();
        mockMvc.perform(write(post("/api/v1/audit/page-views")).cookie(employee.cookie()).content("{\"path\":\"/leave?apply=1#x\"}")).andExpect(status().isCreated());
        List<Map<String, Object>> views = jdbc.queryForList("SELECT * FROM audit_log WHERE action = 'page.view' AND actor_id = ? AND at >= ?", employee.id(), java.sql.Timestamp.from(startedAt));
        assertThat(views).hasSize(1);
        assertThat(views.get(0).get("entity")).isEqualTo("page");
        assertThat(views.get(0).get("entity_id")).isEqualTo("/leave");
        assertThat(views.get(0).get("business_id")).isEqualTo(data.business());
        // not a request row on top of it, and bad input is refused
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE path = '/api/v1/audit/page-views'", Integer.class)).isZero();
        mockMvc.perform(write(post("/api/v1/audit/page-views")).cookie(employee.cookie()).content("{\"path\":\"leave\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(post("/api/v1/audit/page-views")).content("{\"path\":\"/leave\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void signInStepsAreRecordedWithoutSecrets() throws Exception {
        Person employee = person(data, empRole); // signing in already happened once
        Instant startedAt = startOfTest();
        mockMvc.perform(write(post("/api/v1/auth/otp-requests")).content("{\"email\":\"" + employee.email() + "\"}")).andExpect(status().isCreated());
        mockMvc.perform(write(post("/api/v1/auth/session")).content("{\"identifier\":\"" + employee.email() + "\",\"otp\":\"123456\"}")).andExpect(status().isUnauthorized());
        mockMvc.perform(write(post("/api/v1/auth/session")).content("{\"identifier\":\"nobody@example.com\",\"otp\":\"123456\"}")).andExpect(status().isUnauthorized());
        java.sql.Timestamp since = java.sql.Timestamp.from(startedAt.minusSeconds(2));
        List<String> mine = jdbc.queryForList("SELECT action FROM audit_log WHERE actor_id = ? AND at >= ? ORDER BY at", String.class, employee.id(), since);
        assertThat(mine).contains("auth.otp_requested", "auth.login_failed");
        // the request rows carry the status and a masked identifier, never the address in full or any code
        List<Map<String, Object>> requests = new java.util.ArrayList<>(jdbc.queryForList(
                "SELECT path, status, \"after\"::text AS after FROM audit_log WHERE path LIKE '/api/v1/auth/%' AND at >= ? ORDER BY at DESC LIMIT 3", since));
        java.util.Collections.reverse(requests);
        assertThat(requests).hasSize(3);
        assertThat(requests.get(0).get("path")).isEqualTo("/api/v1/auth/otp-requests");
        assertThat(requests.stream().map(r -> r.get("status")).toList()).containsExactly(201, 401, 401);
        assertThat(requests.get(1).get("after").toString()).contains("\"identifier\"").doesNotContain(employee.email());
        assertThat(requests.get(2).get("after").toString()).contains("n***@example.com");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE at >= ? AND (\"after\"::text LIKE '%123456%' OR \"after\"::text LIKE '%000000%')", Integer.class, since)).isZero();
        // a successful sign-in: the semantic row and the request row
        int before = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE actor_id = ? AND action = 'auth.login'", Integer.class, employee.id());
        SignIn.session(mockMvc, employee.email()).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE actor_id = ? AND action = 'auth.login'", Integer.class, employee.id())).isEqualTo(before + 1);
    }

    @Test
    void theSuperAdminSeesEveryBusinessAndTheOwnerOnlyTheirOwn() throws Exception {
        Person person = superAdmin();
        String otherCode = "audit" + TestData.letters(6);
        TestData other = data.inBusiness(otherCode);
        Person ownerA = person(data, ownerRole);
        Person ownerB = person(other, other.role("owner", "dir", "people.view", "people.add", "dept.manage", "roles.manage"));
        mockMvc.perform(write(post("/api/v1/departments")).cookie(ownerA.cookie()).content(dept())).andReturn();
        mockMvc.perform(write(post("/api/v1/departments")).cookie(ownerB.cookie()).content(dept())).andReturn();

        JsonNode all = json.readTree(mockMvc.perform(get("/api/v1/platform/audit").param("periodDays", "All").param("eventGroup", "requests").param("take", "500").cookie(person.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        assertThat(all.get("rows").toString()).contains("Business " + code).contains("Business " + otherCode);
        JsonNode first = all.get("rows").get(0);
        for (String field : List.of("id", "occurredAt", "actor", "action", "entity", "method", "path", "status", "durationMs", "business")) assertThat(first.has(field)).as(field).isTrue();
        assertThat(first.get("occurredAt").asText()).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z");
        assertThat(all.get("businesses").toString()).contains(other.business().toString());

        // filter by business
        JsonNode onlyB = json.readTree(mockMvc.perform(get("/api/v1/platform/audit").param("periodDays", "All").param("businessId", other.business().toString()).cookie(person.cookie()))
                .andReturn().getResponse().getContentAsString()).get("data");
        assertThat(onlyB.get("total").asInt()).isGreaterThan(0);
        for (JsonNode row : onlyB.get("rows")) assertThat(row.get("business").asText()).isEqualTo("Business " + otherCode);

        // the owner's own view is limited to their business, and the platform view is closed to them
        JsonNode mineA = json.readTree(mockMvc.perform(get("/api/v1/audit").param("days", "All").cookie(ownerA.cookie())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data");
        long expected = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE business_id = ?", Long.class, data.business());
        assertThat(mineA.get("total").asLong()).isBetween(expected - 5, expected + 5); // the reads of this very request add a row
        assertThat(mineA.toString()).doesNotContain(other.business().toString());
        mockMvc.perform(get("/api/v1/platform/audit").cookie(ownerA.cookie())).andExpect(status().isForbidden());

        // CSV export of the same filter
        MvcResult csv = mockMvc.perform(get("/api/v1/platform/audit/export").param("days", "All").cookie(person.cookie())).andExpect(status().isOk()).andReturn();
        assertThat(csv.getResponse().getContentType()).startsWith("text/csv");
        assertThat(csv.getResponse().getContentAsString()).startsWith("At (UTC),Business,Actor");
        // viewing the audit log is itself recorded
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE actor_id = ? AND path = '/api/v1/platform/audit'", Integer.class, person.id())).isGreaterThanOrEqualTo(2);
    }
}
