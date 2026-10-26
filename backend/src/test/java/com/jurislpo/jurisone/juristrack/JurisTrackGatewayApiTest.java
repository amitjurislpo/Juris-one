package com.jurislpo.jurisone.juristrack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.JurisTrackStub;
import com.jurislpo.jurisone.JurisTrackStub.Received;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.Cookie;
import java.sql.Date;
import java.time.LocalDate;
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

/** {@code /api/juris-track/**}: the request reaches JurisTrack as the signed-in employee, the answer comes back unchanged. */
class JurisTrackGatewayApiTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    TestData data;
    UUID role;
    static int sequence = 100;

    record Person(UUID id, String email, Cookie cookie) {}

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc).inBusiness("jt-gateway");
        role = data.role("jt-employee", "self", "dir");
        if (jdbc.queryForObject("SELECT count(*) FROM business_modules WHERE business_id = ?", Integer.class, data.business()) == 0) grantModule(data.business());
        JurisTrackStub.reset();
    }

    void grantModule(UUID business) {
        jdbc.update("INSERT INTO business_modules (id, business_id, module, source, starts_on) VALUES (?, ?, 'JURIS_TRACK', 'COMPLIMENTARY', ?)",
                UUID.randomUUID(), business, Date.valueOf(LocalDate.now().minusDays(1)));
    }

    Person person(TestData inBusiness, UUID personRole) throws Exception {
        String code = "JT-" + (++sequence) + (int) (Math.random() * 1000);
        String email = code.toLowerCase() + "@example.com";
        UUID id = inBusiness.employee(code, email, personRole);
        String set = SignIn.session(mockMvc, email).andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        return new Person(id, email, new Cookie("jo_at", matcher.group(1)));
    }

    JWTClaimsSet claimsOf(Received received) throws Exception {
        String authorization = received.headers().get("authorization");
        assertThat(authorization).startsWith("Bearer ");
        SignedJWT token = SignedJWT.parse(authorization.substring("Bearer ".length()));
        assertThat(token.verify(new RSASSAVerifier(JurisTrackStub.publicKey()))).isTrue();
        return token.getJWTClaimsSet();
    }

    @Test
    void theExactPathSaysTheModuleIsActive() throws Exception {
        Person employee = person(data, role);
        mockMvc.perform(get("/api/juris-track").cookie(employee.cookie())).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.module").value("JURIS_TRACK")).andExpect(jsonPath("$.data.status").value("ACTIVE"));
        assertThat(JurisTrackStub.received()).isEmpty();
    }

    @Test
    void methodPathQueryBodyAndContentTypeArriveAndTheBrowserSecretsDoNot() throws Exception {
        Person employee = person(data, role);
        JurisTrackStub.answerWith(201, "application/json", "{\"id\":\"w1\"}");
        mockMvc.perform(write(post(java.net.URI.create("/api/juris-track/workdays/mine?date=2026-11-02&x=a%20b"))).cookie(employee.cookie(), new Cookie("other", "cookie-value"))
                        .header("Authorization", "Bearer browser-token").header("X-Forwarded-For", "203.0.113.7").content("{\"note\":\"start\"}"))
                .andExpect(status().isCreated()).andExpect(content().json("{\"id\":\"w1\"}"));

        Received received = JurisTrackStub.received().get(0);
        assertThat(received.method()).isEqualTo("POST");
        assertThat(received.pathAndQuery()).isEqualTo("/api/workdays/mine?date=2026-11-02&x=a%20b");
        assertThat(received.body()).isEqualTo("{\"note\":\"start\"}");
        assertThat(received.headers().get("content-type")).startsWith("application/json");
        assertThat(received.headers().get("x-request-id")).isNotBlank();
        assertThat(received.headers().get("x-forwarded-for")).startsWith("203.0.113.7, ");
        assertThat(received.headers()).doesNotContainKeys("cookie", "origin");
        assertThat(received.headers().get("authorization")).startsWith("Bearer ").doesNotContain("browser-token");
        assertThat(received.headers().toString()).doesNotContain("jo_at");
    }

    @Test
    void everyMethodIsForwarded() throws Exception {
        Person employee = person(data, role);
        mockMvc.perform(get("/api/juris-track/me/time").cookie(employee.cookie())).andExpect(status().isOk());
        mockMvc.perform(write(put("/api/juris-track/employees/abc/shift")).cookie(employee.cookie()).content("{}")).andExpect(status().isOk());
        mockMvc.perform(write(patch("/api/juris-track/settings")).cookie(employee.cookie()).content("{}")).andExpect(status().isOk());
        mockMvc.perform(write(delete("/api/juris-track/breaks/1")).cookie(employee.cookie())).andExpect(status().isOk());
        assertThat(JurisTrackStub.received()).extracting(Received::method).containsExactly("GET", "PUT", "PATCH", "DELETE");
        assertThat(JurisTrackStub.received()).extracting(Received::pathAndQuery).containsExactly("/api/me/time", "/api/employees/abc/shift", "/api/settings", "/api/breaks/1");
    }

    @Test
    void theTokenSaysWhoTheEmployeeIsAndEachRequestGetsAFreshOne() throws Exception {
        Person manager = person(data, role), employee = person(data, role);
        jdbc.update("UPDATE employees SET reporting_manager_id = ? WHERE id = ?", manager.id(), employee.id());
        String businessName = jdbc.queryForObject("SELECT name FROM businesses WHERE id = ?", String.class, data.business());

        mockMvc.perform(get("/api/juris-track/me/time").cookie(employee.cookie())).andExpect(status().isOk());
        mockMvc.perform(get("/api/juris-track/me/time").cookie(employee.cookie())).andExpect(status().isOk());
        mockMvc.perform(get("/api/juris-track/me/time").cookie(manager.cookie())).andExpect(status().isOk());

        JWTClaimsSet asEmployee = claimsOf(JurisTrackStub.received().get(0)), again = claimsOf(JurisTrackStub.received().get(1)), asManager = claimsOf(JurisTrackStub.received().get(2));
        assertThat(asEmployee.getSubject()).isEqualTo(employee.id().toString());
        assertThat(asEmployee.getStringClaim("email")).isEqualTo(employee.email());
        assertThat(asEmployee.getStringClaim("name")).startsWith("Person JT-");
        assertThat(asEmployee.getStringClaim("businessId")).isEqualTo(data.business().toString());
        assertThat(asEmployee.getStringClaim("businessName")).isEqualTo(businessName);
        assertThat(asEmployee.getStringClaim("managerId")).isEqualTo(manager.id().toString());
        assertThat(asEmployee.getStringClaim("trackRole")).isEqualTo("EMPLOYEE");
        assertThat(asEmployee.getIssuer()).isEqualTo("http://localhost:3005");
        assertThat(asEmployee.getAudience()).containsExactly("juristrack");
        assertThat(asEmployee.getJWTID()).isNotEqualTo(again.getJWTID());
        // the one with an active direct report is the JurisTrack manager
        assertThat(asManager.getStringClaim("trackRole")).isEqualTo("MANAGER");
        assertThat(asManager.getClaims()).doesNotContainKey("managerId");
    }

    @Test
    void jurisTracksAnswersPassThroughUnchanged() throws Exception {
        Person employee = person(data, role);
        String problem = "{\"type\":\"about:blank\",\"title\":\"Conflict\",\"status\":409,\"detail\":\"Day already started.\",\"code\":\"CONFLICT\"}";
        JurisTrackStub.answerWith(409, "application/problem+json", problem);
        mockMvc.perform(write(post("/api/juris-track/me/time-events")).cookie(employee.cookie()).content("{}")).andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json")).andExpect(content().json(problem))
                .andExpect(header().doesNotExist("Set-Cookie"));
        JurisTrackStub.answerWith(404, "application/problem+json", "{\"status\":404}");
        mockMvc.perform(get("/api/juris-track/nothing").cookie(employee.cookie())).andExpect(status().isNotFound());
        JurisTrackStub.answerWith(200, "text/csv", "a,b\n1,2\n");
        mockMvc.perform(get("/api/juris-track/reports/export").cookie(employee.cookie())).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv")).andExpect(content().string("a,b\n1,2\n"));
    }

    @Test
    void aWriteIsOnTheAuditTrailWithoutItsBody() throws Exception {
        Person employee = person(data, role);
        String requestId = mockMvc.perform(write(post("/api/juris-track/me/time-events")).cookie(employee.cookie()).content("{\"secret\":\"do-not-store\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("X-Request-Id");
        assertThat(requestId).isNotBlank();
        assertThat(JurisTrackStub.received().get(0).headers().get("x-request-id")).isEqualTo(requestId);
        String row = jdbc.queryForObject("SELECT action || ' ' || path || ' ' || \"after\"::text FROM audit_log WHERE request_id = ?", String.class, requestId);
        assertThat(row).startsWith("request.POST /api/juris-track/me/time-events").contains("juristrack").doesNotContain("do-not-store");
        assertThat(jdbc.queryForObject("SELECT actor_id FROM audit_log WHERE request_id = ?", UUID.class, requestId)).isEqualTo(employee.id());
    }

    @Test
    void anUnreachableJurisTrackAnswers502AsProblemJson() throws Exception {
        Person employee = person(data, role);
        JurisTrackStub.hangUp(true);
        mockMvc.perform(get("/api/juris-track/me/time").cookie(employee.cookie())).andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json")).andExpect(jsonPath("$.code").value("JURISTRACK_UNAVAILABLE"));
    }

    @Test
    void withoutThePlanOrGrantTheGatewayDoesNotExist() throws Exception {
        TestData other = new TestData(jdbc).inBusiness("jt-no-module");
        Person employee = person(other, other.role("jt-plain", "self", "dir"));
        mockMvc.perform(get("/api/juris-track/me/time").cookie(employee.cookie())).andExpect(status().isNotFound());
        mockMvc.perform(write(post("/api/juris-track/me/time-events")).cookie(employee.cookie()).content("{}")).andExpect(status().isNotFound());
        assertThat(JurisTrackStub.received()).isEmpty();
    }

    @Test
    void whenTheSubscriptionHasEndedTheTimeCanBeReadButNothingCanBeStartedOrChanged() throws Exception {
        TestData ended = new TestData(jdbc).inBusiness("jt-ended" + (++sequence));
        grantModule(ended.business());
        jdbc.update("UPDATE business_subscriptions SET starts_on = current_date - 400, ends_on = current_date - 10 WHERE business_id = ?", ended.business());
        Person worker = person(ended, ended.role("jt-ended-employee", "self", "dir"));

        // reading still works (the card is shown), every write is refused with 402 and never reaches JurisTrack
        mockMvc.perform(get("/api/juris-track/me/time").cookie(worker.cookie())).andExpect(status().isOk());
        for (String eventType : new String[] {"START", "BREAK", "RESUME", "END"}) {
            mockMvc.perform(write(post("/api/juris-track/me/time-events")).cookie(worker.cookie()).content("{\"type\":\"" + eventType + "\"}"))
                    .andExpect(status().isPaymentRequired()).andExpect(jsonPath("$.code").value("SUBSCRIPTION_INACTIVE"));
        }
        // and the Home check-in cannot reach JurisTrack either
        mockMvc.perform(write(post("/api/attendance/punches")).cookie(worker.cookie()).content("{\"expect\":\"IN\"}")).andExpect(status().isPaymentRequired());
        assertThat(JurisTrackStub.received()).extracting(Received::method).containsOnly("GET");
    }

    @Test
    void aUserWithoutThePagePermissionIsRefusedAndASignedOutBrowserIs401() throws Exception {
        Person nobody = person(data, data.role("jt-nothing", "att.all"));
        mockMvc.perform(get("/api/juris-track/me/time").cookie(nobody.cookie())).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/juris-track/me/time")).andExpect(status().isUnauthorized());
        mockMvc.perform(write(post("/api/juris-track/me/time-events")).content("{}")).andExpect(status().isUnauthorized());
        assertThat(JurisTrackStub.received()).isEmpty();
    }

    @Test
    void pathsThatClimbOutOrReachTheServiceEndpointsAreRefused() throws Exception {
        Person employee = person(data, role);
        mockMvc.perform(get("/api/juris-track/%2e%2e/actuator/health").cookie(employee.cookie())).andExpect(status().is4xxClientError());
        mockMvc.perform(write(put("/api/juris-track/integration/employees")).cookie(employee.cookie()).content("{}")).andExpect(status().isNotFound());
        // percent-encoding cannot hide a service path ("%69ntegration" is "integration"), and a huge body never gets buffered
        mockMvc.perform(write(put("/api/juris-track/%69ntegration/employees")).cookie(employee.cookie()).content("{}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(post("/api/juris-track/me/time-events")).cookie(employee.cookie()).content("x".repeat(70 * 1024))).andExpect(status().isPayloadTooLarge());
        assertThat(JurisTrackStub.received()).isEmpty();
    }

    @Test
    void whenJurisTrackRefusesOurTokenTheBrowserGetsA502NotASignOut() throws Exception {
        Person employee = person(data, role);
        JurisTrackStub.answerWith(401, "application/problem+json", "{\"status\":401}");
        mockMvc.perform(get("/api/juris-track/me/time").cookie(employee.cookie())).andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("JURISTRACK_AUTH_FAILED"));
    }
}
