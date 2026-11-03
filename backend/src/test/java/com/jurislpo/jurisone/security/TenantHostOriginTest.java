package com.jurislpo.jurisone.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.TestData;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Per-business addresses, step 6, flag on: Origin check, CORS and the neutral 404 for hosts that name no business. The web app's configured
 * origin is http://localhost:3005, so tenant origins must be http, port 3005, exactly {@code <slug>.localhost} / {@code admin.localhost}.
 */
@TestPropertySource(properties = {"jurisone.tenant-host.enabled=true", "jurisone.tenant-host.base-domain=localhost"})
class TenantHostOriginTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("corsConfigurationSource") org.springframework.web.cors.CorsConfigurationSource cors;

    String slug, otherSlug, missingSlug;

    @BeforeEach
    void setUp() {
        slug = "oa-" + TestData.letters(8);
        otherSlug = "ob-" + TestData.letters(8);
        new TestData(jdbc).inBusiness(slug);
        new TestData(jdbc).inBusiness(otherSlug);
        missingSlug = "om-" + TestData.letters(8); // no such business
    }

    /** A write to a protected path: the Origin check answers before authentication, so 403 CROSS_SITE_BLOCKED or (past it) 401. */
    MvcResult write(String host, String origin, String... headers) throws Exception {
        MockHttpServletRequestBuilder request = post("/api/v1/people").header("Host", host).contentType(MediaType.APPLICATION_JSON).content("{}");
        if (origin != null) request.header("Origin", origin);
        for (int i = 0; i < headers.length; i += 2) request.header(headers[i], headers[i + 1]);
        return mockMvc.perform(request).andReturn();
    }

    static int status(MvcResult result) {
        return result.getResponse().getStatus();
    }

    boolean isOriginBlocked(MvcResult result) throws Exception {
        return status(result) == 403 && result.getResponse().getContentAsString().contains("CROSS_SITE_BLOCKED");
    }

    int originAudits() {
        return jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'origin.refused'", Integer.class);
    }

    @Test
    void theOriginOfTheRequestsOwnBusinessHostPasses() throws Exception {
        String host = slug + ".localhost:3005";
        assertThat(isOriginBlocked(write(host, "http://" + host))).isFalse();
        assertThat(status(write(host, "http://" + host))).isEqualTo(401); // past the Origin check, then not signed in
        assertThat(isOriginBlocked(write("admin.localhost:3005", "http://admin.localhost:3005"))).isFalse();
        assertThat(isOriginBlocked(write("localhost:3005", "http://localhost:3005"))).isFalse(); // the configured web origin keeps working
    }

    @Test
    void hostileOriginsAreRefusedAndAudited() throws Exception {
        String host = slug + ".localhost:3005";
        String other = otherSlug + ".localhost:3005";
        List<String> origins = List.of(
                "https://evil.com", "http://evil.com:3005",
                "http://" + other,
                "null", "", " ",
                "https://" + host,
                "http://" + slug + ".localhost:3006", "http://" + slug + ".localhost:80", "http://" + slug + ".localhost",
                "http://evil-" + slug + ".localhost:3005", "http://" + slug + ".localhost.evil.com:3005",
                "http://" + slug + ".localhost.:3005", "http://x." + slug + ".localhost:3005",
                "http://" + slug + ".localhost:3005/", "http://" + slug + ".localhost:3005/path", "http://user@" + slug + ".localhost:3005",
                "http://" + slug + ".localhost:3005@evil.com", "http://evil.com#" + slug + ".localhost:3005",
                "http://" + missingSlug + ".localhost:3005",
                "http://" + slug.toUpperCase() + ".LOCALHOST:3005",
                "http://" + slug + ".lоcalhost:3005", "http://ѕ" + slug + ".localhost:3005", "http://xn--" + slug + ".localhost:3005");
        for (String origin : origins) {
            assertThat(isOriginBlocked(write(host, origin))).as("origin [%s]", origin).isTrue();
        }
        assertThat(isOriginBlocked(write(host, null))).isTrue(); // no Origin at all
        assertThat(originAudits()).isGreaterThanOrEqualTo(1); // audited (throttled to one entry per caller per interval)
    }

    @Test
    void theOriginMustEqualTheRealHostHeaderNotAForwardedOne() throws Exception {
        String host = slug + ".localhost:3005";
        String other = otherSlug + ".localhost:3005";
        assertThat(isOriginBlocked(write(host, "http://" + other))).isTrue();
        assertThat(isOriginBlocked(write(host, "http://" + other, "X-Forwarded-Host", other, "Forwarded", "host=" + other, "X-Original-Host", other))).isTrue();
        // the Host is the unknown bare domain on another port: 404 before the Origin check, never accepted
        assertThat(status(write("localhost:9999", "http://" + slug + ".localhost:3005", "X-Forwarded-Host", host))).isEqualTo(404);
        assertThat(status(write("evil.com", "http://evil.com:3005", "X-Forwarded-Host", host))).isEqualTo(404);
        // a Host whose port differs from the Origin's
        assertThat(isOriginBlocked(write(slug + ".localhost:3006", "http://" + slug + ".localhost:3005"))).isTrue();
    }

    @Test
    void anyApiEndpointOnAHostThatNamesNoBusinessAnswersTheSameNeutral404() throws Exception {
        List<String> hosts = List.of("localhost:3005", missingSlug + ".localhost", "127.0.0.1:3005", "evil.com", "a.b.localhost", "[::1]:3005", "evil.com#." + slug + ".localhost");
        String expected = null;
        for (String host : hosts) {
            for (MockHttpServletRequestBuilder request : List.of(get("/api/v1/people"), get("/api/v1/me"), post("/api/v1/people"), get("/api/v1/home"))) {
                MockHttpServletRequestBuilder prepared = request.header("Host", host).header("Origin", "http://localhost:3005").cookie(new Cookie("jo_at", "junk"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}");
                MvcResult result = mockMvc.perform(prepared).andReturn();
                assertThat(status(result)).as(host).isEqualTo(404);
                String body = result.getResponse().getContentAsString();
                assertThat(body).doesNotContain(slug).doesNotContain(missingSlug);
                String shape = body.replaceAll("\"(instance|timestamp|path)\"\\s*:\\s*\"[^\"]*\"", "").replaceAll("\\s", "");
                if (expected == null) expected = shape;
                assertThat(shape).isEqualTo(expected);
            }
        }
        assertThat(status(mockMvc.perform(get("/api/v1/people").header("Host", "localhost:3005").header("X-Forwarded-Host", slug + ".localhost")).andReturn())).isEqualTo(404);
        assertThat(status(mockMvc.perform(get("/actuator/health").header("Host", "evil.com")).andReturn())).isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'auth.host_refused' AND after->>'step' = 'request'", Integer.class)).isPositive();
    }

    @Test
    void aKnownBusinessHostAndTheAdminHostStillReachTheApi() throws Exception {
        assertThat(status(mockMvc.perform(get("/api/v1/people").header("Host", slug + ".localhost:3005")).andReturn())).isEqualTo(401);
        assertThat(status(mockMvc.perform(get("/api/v1/people").header("Host", "admin.localhost:3005")).andReturn())).isEqualTo(401);
    }

    /** The CORS source is asked directly (a browser page of the same address is not a CORS request, so MockMvc preflights would not exercise it). */
    @Test
    void corsAllowsOnlyTheExactOwnBusinessOriginWithCredentials() throws Exception {
        String host = slug + ".localhost:3005";
        var own = cors.getCorsConfiguration(request(host, "http://" + host));
        assertThat(own.getAllowedOrigins()).containsExactly("http://" + host);
        assertThat(own.getAllowCredentials()).isTrue();

        for (String origin : List.of("http://evil.com", "http://" + otherSlug + ".localhost:3005", "https://" + host, "null", "http://evil-" + host, "http://" + host + ".evil.com")) {
            var foreign = cors.getCorsConfiguration(request(host, origin));
            assertThat(foreign.getAllowedOrigins()).as(origin).containsExactly("http://localhost:3005"); // only the web app's own origin, never *, never the foreign one
            assertThat(foreign.checkOrigin(origin)).as(origin).isNull();
        }
        assertThat(cors.getCorsConfiguration(request(host, "http://" + host)).getAllowedOrigins()).doesNotContain("*");
    }

    private static org.springframework.mock.web.MockHttpServletRequest request(String host, String origin) {
        var request = new org.springframework.mock.web.MockHttpServletRequest("OPTIONS", "/api/v1/people");
        request.setRequestURI("/api/v1/people");
        request.addHeader("Host", host);
        request.addHeader("Origin", origin);
        return request;
    }

    @Test
    void securityHeadersStayOnTheResponses() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/people").header("Host", slug + ".localhost:3005")).andReturn();
        assertThat(result.getResponse().getHeader("Content-Security-Policy")).contains("frame-ancestors 'none'").contains("default-src 'none'");
        assertThat(result.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }
}
