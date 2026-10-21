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

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    TestData data;
    UUID ownerRole, empRole;
    String suffix, code;

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder b) {
        return b.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    @BeforeEach
    void setUp() {
        code = "audit" + TestData.letters(6); // its own business: the roles and rows here must not leak into other tests
        data = new TestData(jdbc).inBusiness(code);
        suffix = UUID.randomUUID().toString().substring(0, 6);
        ownerRole = data.role("owner", "dir", "people.view", "people.add", "dept.manage", "roles.manage", "access.toggle");
        empRole = data.role("employee", "self", "dir");
    }

    Cookie cookieOf(MvcResult r) {
        Matcher m = Pattern.compile("jo_at=([^;]+)").matcher(r.getResponse().getHeader("Set-Cookie"));
        assertThat(m.find()).isTrue();
        return new Cookie("jo_at", m.group(1));
    }

    record Person(UUID id, String email, Cookie cookie) {}

    Person person(TestData d, UUID role) throws Exception {
        String code = "AU-" + UUID.randomUUID().toString().substring(0, 8);
        String email = code.toLowerCase() + "@example.com";
        UUID id = d.employee(code, email, role);
        return new Person(id, email, cookieOf(SignIn.session(mvc, email).andExpect(status().isCreated()).andReturn()));
    }

    Person superAdmin() throws Exception {
        UUID role = jdbc.query("SELECT id FROM roles WHERE key = 'super_admin' AND business_id IS NULL", rs -> rs.next() ? (UUID) rs.getObject(1) : null);
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
        return new Person(id, email, cookieOf(SignIn.session(mvc, email).andExpect(status().isCreated()).andReturn()));
    }

    List<Map<String, Object>> rows(String where, Object... args) {
        return jdbc.queryForList("SELECT * FROM audit_log WHERE " + where + " ORDER BY at", args);
    }

    String dept() {
        return "{\"name\":\"Dept " + UUID.randomUUID().toString().substring(0, 8) + "\"}";
    }

    @Test
    void everyWriteLeavesARowWhateverTheOutcome() throws Exception {
        Person owner = person(data, ownerRole), emp = person(data, empRole);
        Instant t0 = Instant.now();

        // success: the row names the actor and business, how it ended, how long it took, and its id is in the response header
        MvcResult ok = mvc.perform(write(post("/api/departments")).cookie(owner.cookie()).content(dept())).andReturn();
        assertThat(ok.getResponse().getStatus()).isBetween(200, 201);
        String rid = ok.getResponse().getHeader("X-Request-Id");
        Map<String, Object> row = rows("request_id = ?", rid).get(0);
        assertThat(row.get("action")).isEqualTo("request.POST");
        assertThat(row.get("entity")).isEqualTo("api");
        assertThat(row.get("path")).isEqualTo("/api/departments");
        assertThat(row.get("method")).isEqualTo("POST");
        assertThat(row.get("status")).isEqualTo(ok.getResponse().getStatus());
        assertThat(row.get("actor_id")).isEqualTo(owner.id());
        assertThat(row.get("business_id")).isEqualTo(data.business());
        assertThat((Integer) row.get("duration_ms")).isGreaterThanOrEqualTo(0);
        assertThat(row.get("at")).isNotNull();

        // a business rule refuses it (the service rolls back): still recorded
        int bad = mvc.perform(write(post("/api/departments")).cookie(owner.cookie()).content("{\"name\":\"\"}")).andReturn().getResponse().getStatus();
        assertThat(bad).isGreaterThanOrEqualTo(400);
        // no permission: 403
        assertThat(mvc.perform(write(post("/api/departments")).cookie(emp.cookie()).content(dept())).andReturn().getResponse().getStatus()).isEqualTo(403);
        // not signed in: 401, no actor
        assertThat(mvc.perform(write(post("/api/departments")).content(dept())).andReturn().getResponse().getStatus()).isEqualTo(401);
        // a request from another origin is turned away before any controller: recorded too
        assertThat(mvc.perform(post("/api/departments").header("origin", "http://evil.example").header("host", "localhost:3005")
                .contentType(MediaType.APPLICATION_JSON).cookie(owner.cookie()).content(dept())).andReturn().getResponse().getStatus()).isEqualTo(403);

        List<Map<String, Object>> posts = jdbc.queryForList("SELECT status, actor_id FROM audit_log WHERE path = '/api/departments' AND at >= ? ORDER BY at",
                java.sql.Timestamp.from(t0));
        assertThat(posts).hasSize(5);
        assertThat(posts.stream().map(r -> r.get("status")).toList()).containsExactly(ok.getResponse().getStatus(), bad, 403, 401, 403);
        assertThat(posts.get(3).get("actor_id")).isNull();
        assertThat(posts.get(2).get("actor_id")).isEqualTo(emp.id());
    }

    @Test
    void anEndedSubscriptionsRefusalIsRecordedToo() throws Exception {
        TestData ended = data.inBusiness("audit" + TestData.letters(6));
        UUID r = ended.role("owner", "dir", "dept.manage", "roles.manage");
        Person owner = person(ended, r); // sign in while the subscription is still valid
        jdbc.update("UPDATE business_subscriptions SET starts_on = current_date - 400, ends_on = current_date - 10 WHERE business_id = ?", ended.business());
        MvcResult res = mvc.perform(write(post("/api/departments")).cookie(owner.cookie()).content(dept())).andReturn();
        assertThat(res.getResponse().getStatus()).isEqualTo(402);
        Map<String, Object> row = rows("request_id = ?", res.getResponse().getHeader("X-Request-Id")).get(0);
        assertThat(row.get("status")).isEqualTo(402);
        assertThat(row.get("business_id")).isEqualTo(ended.business());
        assertThat(row.get("actor_id")).isEqualTo(owner.id());
    }

    @Test
    void onlySensitiveReadsAreRecorded() throws Exception {
        Person owner = person(data, ownerRole);
        Instant t0 = Instant.now();
        mvc.perform(get("/api/me").cookie(owner.cookie())).andExpect(status().isOk());
        mvc.perform(get("/api/departments").cookie(owner.cookie()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE method = 'GET' AND at >= ? AND path IN ('/api/me', '/api/departments')",
                Integer.class, java.sql.Timestamp.from(t0))).isZero();
        mvc.perform(get("/api/people/" + owner.id()).cookie(owner.cookie())); // recorded whether it is answered or refused
        mvc.perform(get("/api/people/" + owner.id() + "/contact").cookie(owner.cookie()));
        List<Map<String, Object>> reads = jdbc.queryForList("SELECT path, action, actor_id FROM audit_log WHERE method = 'GET' AND at >= ? ORDER BY at", java.sql.Timestamp.from(t0));
        assertThat(reads.stream().map(x -> x.get("path")).toList()).containsExactly("/api/people/" + owner.id(), "/api/people/" + owner.id() + "/contact");
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
        Person emp = person(data, empRole);
        Instant t0 = Instant.now();
        mvc.perform(write(post("/api/audit/page-views")).cookie(emp.cookie()).content("{\"path\":\"/leave?apply=1#x\"}")).andExpect(status().isOk());
        List<Map<String, Object>> v = jdbc.queryForList("SELECT * FROM audit_log WHERE action = 'page.view' AND actor_id = ? AND at >= ?", emp.id(), java.sql.Timestamp.from(t0));
        assertThat(v).hasSize(1);
        assertThat(v.get(0).get("entity")).isEqualTo("page");
        assertThat(v.get(0).get("entity_id")).isEqualTo("/leave");
        assertThat(v.get(0).get("business_id")).isEqualTo(data.business());
        // not a request row on top of it, and bad input is refused
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE path = '/api/audit/page-views'", Integer.class)).isZero();
        mvc.perform(write(post("/api/audit/page-views")).cookie(emp.cookie()).content("{\"path\":\"leave\"}")).andExpect(status().isBadRequest());
        mvc.perform(write(post("/api/audit/page-views")).content("{\"path\":\"/leave\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void signInStepsAreRecordedWithoutSecrets() throws Exception {
        Person emp = person(data, empRole); // signing in already happened once
        Instant t0 = Instant.now();
        mvc.perform(write(post("/api/auth/otp-requests")).content("{\"email\":\"" + emp.email() + "\"}")).andExpect(status().isCreated());
        mvc.perform(write(post("/api/auth/session")).content("{\"identifier\":\"" + emp.email() + "\",\"otp\":\"123456\"}")).andExpect(status().isUnauthorized());
        mvc.perform(write(post("/api/auth/session")).content("{\"identifier\":\"nobody@example.com\",\"otp\":\"123456\"}")).andExpect(status().isUnauthorized());
        java.sql.Timestamp since = java.sql.Timestamp.from(t0.minusSeconds(2));
        List<String> mine = jdbc.queryForList("SELECT action FROM audit_log WHERE actor_id = ? AND at >= ? ORDER BY at", String.class, emp.id(), since);
        assertThat(mine).contains("auth.otp_requested", "auth.login_failed");
        // the request rows carry the status and a masked identifier, never the address in full or any code
        List<Map<String, Object>> req = new java.util.ArrayList<>(jdbc.queryForList(
                "SELECT path, status, \"after\"::text AS after FROM audit_log WHERE path LIKE '/api/auth/%' AND at >= ? ORDER BY at DESC LIMIT 3", since));
        java.util.Collections.reverse(req);
        assertThat(req).hasSize(3);
        assertThat(req.get(0).get("path")).isEqualTo("/api/auth/otp-requests");
        assertThat(req.stream().map(r -> r.get("status")).toList()).containsExactly(201, 401, 401);
        assertThat(req.get(1).get("after").toString()).contains("\"identifier\"").doesNotContain(emp.email());
        assertThat(req.get(2).get("after").toString()).contains("n***@example.com");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE at >= ? AND (\"after\"::text LIKE '%123456%' OR \"after\"::text LIKE '%000000%')", Integer.class, since)).isZero();
        // a successful sign-in: the semantic row and the request row
        int before = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE actor_id = ? AND action = 'auth.login'", Integer.class, emp.id());
        SignIn.session(mvc, emp.email()).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE actor_id = ? AND action = 'auth.login'", Integer.class, emp.id())).isEqualTo(before + 1);
    }

    @Test
    void theSuperAdminSeesEveryBusinessAndTheOwnerOnlyTheirOwn() throws Exception {
        Person sa = superAdmin();
        String otherCode = "audit" + TestData.letters(6);
        TestData other = data.inBusiness(otherCode);
        Person ownerA = person(data, ownerRole);
        Person ownerB = person(other, other.role("owner", "dir", "people.view", "people.add", "dept.manage", "roles.manage"));
        mvc.perform(write(post("/api/departments")).cookie(ownerA.cookie()).content(dept())).andReturn();
        mvc.perform(write(post("/api/departments")).cookie(ownerB.cookie()).content(dept())).andReturn();

        JsonNode all = json.readTree(mvc.perform(get("/api/platform/audit").param("days", "All").param("ev", "requests").param("take", "500").cookie(sa.cookie()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data");
        assertThat(all.get("rows").toString()).contains("Business " + code).contains("Business " + otherCode);
        JsonNode first = all.get("rows").get(0);
        for (String f : List.of("id", "at", "actor", "action", "entity", "method", "path", "status", "durationMs", "business")) assertThat(first.has(f)).as(f).isTrue();
        assertThat(first.get("at").asText()).matches("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z");
        assertThat(all.get("businesses").toString()).contains(other.business().toString());

        // filter by business
        JsonNode onlyB = json.readTree(mvc.perform(get("/api/platform/audit").param("days", "All").param("biz", other.business().toString()).cookie(sa.cookie()))
                .andReturn().getResponse().getContentAsString()).get("data");
        assertThat(onlyB.get("total").asInt()).isGreaterThan(0);
        for (JsonNode r : onlyB.get("rows")) assertThat(r.get("business").asText()).isEqualTo("Business " + otherCode);

        // the owner's own view is limited to their business, and the platform view is closed to them
        JsonNode mineA = json.readTree(mvc.perform(get("/api/audit").param("days", "All").cookie(ownerA.cookie())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).get("data");
        long expected = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE business_id = ?", Long.class, data.business());
        assertThat(mineA.get("total").asLong()).isBetween(expected - 5, expected + 5); // the reads of this very request add a row
        assertThat(mineA.toString()).doesNotContain(other.business().toString());
        mvc.perform(get("/api/platform/audit").cookie(ownerA.cookie())).andExpect(status().isForbidden());

        // CSV export of the same filter
        MvcResult csv = mvc.perform(get("/api/platform/audit/export").param("days", "All").cookie(sa.cookie())).andExpect(status().isOk()).andReturn();
        assertThat(csv.getResponse().getContentType()).startsWith("text/csv");
        assertThat(csv.getResponse().getContentAsString()).startsWith("At (UTC),Business,Actor");
        // viewing the audit log is itself recorded
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE actor_id = ? AND path = '/api/platform/audit'", Integer.class, sa.id())).isGreaterThanOrEqualTo(2);
    }
}
