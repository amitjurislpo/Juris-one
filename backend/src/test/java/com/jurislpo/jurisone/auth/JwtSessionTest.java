package com.jurislpo.jurisone.auth;

import com.jurislpo.jurisone.service.RateLimiter;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
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

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired JwtService jwtService;
    @Autowired RateLimiter limiter;
    TestData data;
    UUID role;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        role = data.role("jwt-employee", "self", "dir");
        limiter.clear();
    }

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    record Signed(UUID id, Cookie accessCookie, Cookie refreshCookie) {}

    static String cookie(MockHttpServletResponse response, String name) {
        for (String header : response.getHeaders("Set-Cookie")) {
            Matcher matcher = Pattern.compile("^" + name + "=([^;]*)").matcher(header);
            if (matcher.find()) return matcher.group(1);
        }
        return null;
    }

    Signed signIn() throws Exception {
        String code = "JC-J" + (int) (Math.random() * 1_000_000);
        UUID id = data.employee(code, code.toLowerCase() + "@example.com", role);
        MockHttpServletResponse response = SignIn.session(mockMvc, code.toLowerCase() + "@example.com")
                .andExpect(status().isCreated()).andReturn().getResponse();
        List<String> headers = response.getHeaders("Set-Cookie");
        assertThat(headers).anyMatch(h -> h.startsWith("jo_at=") && h.contains("HttpOnly") && h.contains("SameSite=Lax"));
        assertThat(headers).anyMatch(h -> h.startsWith("jo_rt=") && h.contains("HttpOnly"));
        return new Signed(id, new Cookie("jo_at", cookie(response, "jo_at")), new Cookie("jo_rt", cookie(response, "jo_rt")));
    }

    @Test
    void theAccessTokenIsASignedFiveMinuteJwt() throws Exception {
        Signed signed = signIn();
        String[] parts = signed.accessCookie().getValue().split("\\.");
        assertThat(parts).hasSize(3);
        String payload = new String(java.util.Base64.getUrlDecoder().decode(parts[1]));
        assertThat(payload).contains("\"sub\":\"" + signed.id() + "\"").contains("\"sid\"").contains("\"iss\":\"jurisone\"");
        JwtService.AccessClaims claims = jwtService.verify(signed.accessCookie().getValue());
        assertThat(claims.employeeId()).isEqualTo(signed.id());
        mockMvc.perform(get("/api/me").cookie(signed.accessCookie())).andExpect(status().isOk());
    }

    @Test
    void anExpiredOrForgedTokenIsRejectedWithProblemJson() throws Exception {
        Signed signed = signIn();
        String sessionId = jdbc.queryForObject("SELECT id FROM sessions WHERE employee_id = ?", String.class, signed.id());
        String expiredToken = jwtService.issue(signed.id(), sessionId, Instant.now().minusSeconds(6 * 60));
        mockMvc.perform(get("/api/me").cookie(new Cookie("jo_at", expiredToken))).andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"))
                .andExpect(jsonPath("$.detail").value("Your session has ended. Please sign in again."))
                .andExpect(jsonPath("$.status").value(401));

        String forged = signed.accessCookie().getValue().substring(0, signed.accessCookie().getValue().lastIndexOf('.') + 1) + "AAAA";
        mockMvc.perform(get("/api/me").cookie(new Cookie("jo_at", forged))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void refreshRotatesAndAReusedRefreshTokenRevokesTheSession() throws Exception {
        Signed signed = signIn();
        MockHttpServletResponse response = mockMvc.perform(write(post("/api/auth/session-refreshes")).cookie(signed.refreshCookie())).andExpect(status().isCreated()).andReturn().getResponse();
        String refreshedAccessToken = cookie(response, "jo_at"), refreshedRefreshToken = cookie(response, "jo_rt");
        assertThat(refreshedRefreshToken).isNotEqualTo(signed.refreshCookie().getValue());
        mockMvc.perform(get("/api/me").cookie(new Cookie("jo_at", refreshedAccessToken))).andExpect(status().isOk());

        // the old refresh token was used — presenting it again means it was copied
        mockMvc.perform(write(post("/api/auth/session-refreshes")).cookie(signed.refreshCookie())).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get("/api/me").cookie(new Cookie("jo_at", refreshedAccessToken))).andExpect(status().isUnauthorized());
        mockMvc.perform(write(post("/api/auth/session-refreshes")).cookie(new Cookie("jo_rt", refreshedRefreshToken))).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT revoked_reason FROM sessions WHERE employee_id = ?", String.class, signed.id())).isEqualTo("refresh_reuse");
    }

    @Test
    void disablingAccessStopsTheNextRequestAndRefresh() throws Exception {
        Signed signed = signIn();
        mockMvc.perform(get("/api/me").cookie(signed.accessCookie())).andExpect(status().isOk());
        jdbc.update("UPDATE employees SET portal_access = false WHERE id = ?", signed.id());
        mockMvc.perform(get("/api/me").cookie(signed.accessCookie())).andExpect(status().isUnauthorized());
        mockMvc.perform(write(post("/api/auth/session-refreshes")).cookie(signed.refreshCookie())).andExpect(status().isUnauthorized());
    }

    @Test
    void signOutRevokesTheSessionEvenWithOnlyTheRefreshCookie() throws Exception {
        Signed signed = signIn();
        mockMvc.perform(write(delete("/api/auth/session")).cookie(signed.refreshCookie())).andExpect(status().isOk());
        mockMvc.perform(get("/api/me").cookie(signed.accessCookie())).andExpect(status().isUnauthorized());
        mockMvc.perform(write(post("/api/auth/session-refreshes")).cookie(signed.refreshCookie())).andExpect(status().isUnauthorized());
    }

    @Test
    void errorsAreProblemDetails() throws Exception {
        Signed signed = signIn();
        mockMvc.perform(get("/api/people/not-a-uuid").cookie(signed.accessCookie())).andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("FORBIDDEN")).andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.instance").value("/api/people/not-a-uuid"));
        mockMvc.perform(write(post("/api/leave/requests")).cookie(signed.accessCookie()).content("{nope")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(post("/api/leave/requests").cookie(signed.accessCookie()).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CROSS_SITE_BLOCKED"));
    }
}
