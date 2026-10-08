package com.jurislpo.jurisone.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.TestData;
import com.jurislpo.jurisone.security.JwtService;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** JWT access tokens (5 min) + rotating refresh tokens; revocation on the next request; problem+json errors. */
class JwtSessionTest extends IntegrationTest {

    static final String PW = "correct-horse-battery-7";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwt;
    @Autowired RateLimiter limiter;
    TestData data;
    UUID role;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        role = data.role("jwt-employee", "self", "dir");
        limiter.clear();
    }

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder b) {
        return b.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    record Signed(UUID id, Cookie at, Cookie rt) {}

    static String cookie(MockHttpServletResponse res, String name) {
        for (String h : res.getHeaders("Set-Cookie")) {
            Matcher m = Pattern.compile("^" + name + "=([^;]*)").matcher(h);
            if (m.find()) return m.group(1);
        }
        return null;
    }

    Signed signIn() throws Exception {
        String c = "JC-J" + (int) (Math.random() * 1_000_000);
        UUID id = data.employee(c, c.toLowerCase() + "@example.com", role, PW);
        MockHttpServletResponse res = mvc.perform(write(post("/api/auth/session")).content("{\"identifier\":\"" + c.toLowerCase() + "@example.com\",\"password\":\"" + PW + "\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse();
        List<String> headers = res.getHeaders("Set-Cookie");
        assertThat(headers).anyMatch(h -> h.startsWith("jo_at=") && h.contains("HttpOnly") && h.contains("SameSite=Lax"));
        assertThat(headers).anyMatch(h -> h.startsWith("jo_rt=") && h.contains("HttpOnly"));
        return new Signed(id, new Cookie("jo_at", cookie(res, "jo_at")), new Cookie("jo_rt", cookie(res, "jo_rt")));
    }

    @Test
    void theAccessTokenIsASignedFiveMinuteJwt() throws Exception {
        Signed s = signIn();
        String[] parts = s.at().getValue().split("\\.");
        assertThat(parts).hasSize(3);
        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
        assertThat(payload).contains("\"sub\":\"" + s.id() + "\"").contains("\"sid\"").contains("\"iss\":\"jurisone\"");
        JwtService.AccessClaims claims = jwt.verify(s.at().getValue());
        assertThat(claims.employeeId()).isEqualTo(s.id());
        mvc.perform(get("/api/me").cookie(s.at())).andExpect(status().isOk());
    }

    @Test
    void anExpiredOrForgedTokenIsRejectedWithProblemJson() throws Exception {
        Signed s = signIn();
        String sid = jdbc.queryForObject("SELECT id FROM sessions WHERE employee_id = ?", String.class, s.id());
        String old = jwt.issue(s.id(), sid, Instant.now().minusSeconds(6 * 60));
        mvc.perform(get("/api/me").cookie(new Cookie("jo_at", old))).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"))
                .andExpect(jsonPath("$.detail").value("Your session has ended. Please sign in again."))
                .andExpect(jsonPath("$.status").value(401));

        String forged = s.at().getValue().substring(0, s.at().getValue().lastIndexOf('.') + 1) + "AAAA";
        mvc.perform(get("/api/me").cookie(new Cookie("jo_at", forged))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void refreshRotatesAndAReusedRefreshTokenRevokesTheSession() throws Exception {
        Signed s = signIn();
        MockHttpServletResponse r1 = mvc.perform(write(post("/api/auth/session/refresh")).cookie(s.rt())).andExpect(status().isOk()).andReturn().getResponse();
        String at2 = cookie(r1, "jo_at"), rt2 = cookie(r1, "jo_rt");
        assertThat(rt2).isNotEqualTo(s.rt().getValue());
        mvc.perform(get("/api/me").cookie(new Cookie("jo_at", at2))).andExpect(status().isOk());

        // the old refresh token was used — presenting it again means it was copied
        mvc.perform(write(post("/api/auth/session/refresh")).cookie(s.rt())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/me").cookie(new Cookie("jo_at", at2))).andExpect(status().isUnauthorized());
        mvc.perform(write(post("/api/auth/session/refresh")).cookie(new Cookie("jo_rt", rt2))).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT revoked_reason FROM sessions WHERE employee_id = ?", String.class, s.id())).isEqualTo("refresh_reuse");
    }

    @Test
    void disablingAccessStopsTheNextRequestAndRefresh() throws Exception {
        Signed s = signIn();
        mvc.perform(get("/api/me").cookie(s.at())).andExpect(status().isOk());
        jdbc.update("UPDATE employees SET portal_access = false WHERE id = ?", s.id());
        mvc.perform(get("/api/me").cookie(s.at())).andExpect(status().isUnauthorized());
        mvc.perform(write(post("/api/auth/session/refresh")).cookie(s.rt())).andExpect(status().isUnauthorized());
    }

    @Test
    void signOutRevokesTheSessionEvenWithOnlyTheRefreshCookie() throws Exception {
        Signed s = signIn();
        mvc.perform(write(delete("/api/auth/session")).cookie(s.rt())).andExpect(status().isOk());
        mvc.perform(get("/api/me").cookie(s.at())).andExpect(status().isUnauthorized());
        mvc.perform(write(post("/api/auth/session/refresh")).cookie(s.rt())).andExpect(status().isUnauthorized());
    }

    @Test
    void errorsAreProblemDetails() throws Exception {
        Signed s = signIn();
        mvc.perform(get("/api/people/not-a-uuid").cookie(s.at())).andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("FORBIDDEN")).andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.instance").value("/api/people/not-a-uuid"));
        mvc.perform(write(post("/api/leave/requests")).cookie(s.at()).content("{nope")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/leave/requests").cookie(s.at()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CROSS_SITE_BLOCKED"));
    }
}
