package com.jurislpo.jurisone.auth;

import com.jurislpo.jurisone.service.AuthService;
import com.jurislpo.jurisone.service.RateLimiter;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.jurisone.IntegrationTest;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Sign-in, sessions and links through the HTTP API, mirroring tests/e2e/auth.spec.ts at the backend level. */
class AuthFlowTest extends IntegrationTest {

    static final String PW = "correct-horse-battery-7";
    static final String ORIGIN = "http://localhost:3005";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired RateLimiter limiter;
    TestData data;
    UUID employeeRole;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        employeeRole = data.role("employee", "self", "dir");
        limiter.clear();
    }

    /** A POST as the Next.js server sends it: same-origin headers, JSON body. */
    static MockHttpServletRequestBuilder api(String path, String json) {
        return api(post(path), json);
    }

    /** Any write as the Next.js server sends it. */
    static MockHttpServletRequestBuilder api(MockHttpServletRequestBuilder b, String json) {
        return b.header("origin", ORIGIN).header("host", "localhost:3005").header("x-forwarded-for", "10.0.0.7")
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    String code() {
        return "JC-" + (100 + (int) (Math.random() * 899_000));
    }

    /** Test employees' sign-in email. */
    static String mail(String code) {
        return code.toLowerCase() + "@example.com";
    }

    Cookie login(String id, String pw) throws Exception {
        MvcResult r = mvc.perform(api("/api/auth/session", "{\"identifier\":\"" + id + "\",\"password\":\"" + pw + "\",\"remember\":true}"))
                .andExpect(status().isCreated()).andReturn();
        String set = r.getResponse().getHeader("Set-Cookie");
        Matcher m = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(m.find()).isTrue();
        return new Cookie("jo_at", m.group(1));
    }

    @Test
    void signsInByEmailOnlyAndTheSessionAuthenticatesApiCalls() throws Exception {
        String code = code();
        UUID id = data.employee(code, code.toLowerCase() + "@example.com", employeeRole, PW);

        // employee IDs repeat across businesses, so they are not a sign-in name
        mvc.perform(api("/api/auth/session", "{\"identifier\":\"" + code + "\",\"password\":\"" + PW + "\"}")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(AuthService.BAD_LOGIN));

        MvcResult r = mvc.perform(api("/api/auth/session", "{\"identifier\":\"" + mail(code).toUpperCase() + "\",\"password\":\"" + PW + "\",\"remember\":false}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.redirect").value("/home")).andReturn();
        String set = r.getResponse().getHeader("Set-Cookie");
        assertThat(set).contains("HttpOnly").contains("SameSite=Lax").contains("Path=/").doesNotContain("Max-Age"); // browser-session cookie

        Cookie c = login(code.toLowerCase() + "@example.com", PW);
        mvc.perform(get("/api/me").cookie(c)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.user.code").value(code))
                .andExpect(jsonPath("$.data.user.perms[?(@ == 'self')]").exists())
                .andExpect(jsonPath("$.data.landing").value("/home"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_history WHERE employee_id = ? AND success AND reason = 'ok'", Integer.class, id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE actor_id = ? AND action = 'auth.login'", Integer.class, id)).isEqualTo(2);
        // signing in happens outside any business, but the event is filed under the person's business (its admin sees it)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log a JOIN employees e ON e.id = a.actor_id WHERE a.actor_id = ? AND a.action = 'auth.login' "
                + "AND a.business_id = e.business_id", Integer.class, id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT ip FROM sessions WHERE employee_id = ? LIMIT 1", String.class, id)).isEqualTo("10.0.0.7");
    }

    @Test
    void wrongPasswordIsRejectedWithoutRevealingWhichPart() throws Exception {
        String code = code();
        data.employee(code, code.toLowerCase() + "@example.com", employeeRole, PW);
        mvc.perform(api("/api/auth/session", "{\"identifier\":\"" + mail(code) + "\",\"password\":\"nope-nope-1\"}")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED")).andExpect(jsonPath("$.detail").value(AuthService.BAD_LOGIN));
        mvc.perform(api("/api/auth/session", "{\"identifier\":\"JC-000000\",\"password\":\"nope-nope-1\"}")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(AuthService.BAD_LOGIN));
    }

    @Test
    void locksAfterFiveFailures() throws Exception {
        String code = code();
        UUID id = data.employee(code, code.toLowerCase() + "@example.com", employeeRole, PW);
        for (int i = 0; i < 4; i++) mvc.perform(api("/api/auth/session", "{\"identifier\":\"" + mail(code) + "\",\"password\":\"bad-pass-" + i + "\"}"));
        mvc.perform(api("/api/auth/session", "{\"identifier\":\"" + mail(code) + "\",\"password\":\"bad-pass-5\"}"))
                .andExpect(jsonPath("$.detail").value("Too many failed attempts — this account is locked for 15 minutes."));
        mvc.perform(api("/api/auth/session", "{\"identifier\":\"" + mail(code) + "\",\"password\":\"" + PW + "\"}")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("This account is locked after repeated failed sign-ins.")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE actor_id = ? AND action = 'auth.locked'", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void disabledAndExitedAccountsAreBlockedAndLoseTheirSessionsImmediately() throws Exception {
        String code = code();
        UUID id = data.employee(code, code.toLowerCase() + "@example.com", employeeRole, PW);
        Cookie c = login(mail(code), PW);
        jdbc.update("UPDATE employees SET portal_access = false WHERE id = ?", id);
        mvc.perform(get("/api/me").cookie(c)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Your session has ended. Please sign in again."));
        mvc.perform(api("/api/auth/session", "{\"identifier\":\"" + mail(code) + "\",\"password\":\"" + PW + "\"}")).andExpect(jsonPath("$.detail").value(AuthService.MSG_DISABLED));
        jdbc.update("UPDATE employees SET portal_access = true, status = 'EXITED' WHERE id = ?", id);
        mvc.perform(api("/api/auth/session", "{\"identifier\":\"" + mail(code) + "\",\"password\":\"" + PW + "\"}")).andExpect(jsonPath("$.detail").value(AuthService.MSG_EXITED));
    }

    @Test
    void logoutRevokesTheSessionAndClearsTheCookie() throws Exception {
        String code = code();
        data.employee(code, code.toLowerCase() + "@example.com", employeeRole, PW);
        Cookie c = login(mail(code), PW);
        mvc.perform(api(delete("/api/auth/session"), "{}").cookie(c)).andExpect(status().isOk()).andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        mvc.perform(get("/api/me").cookie(c)).andExpect(status().isUnauthorized());
    }

    @Test
    void resetLinkWorksExactlyOnce() throws Exception {
        String code = code();
        UUID id = data.employee(code, code.toLowerCase() + "@example.com", employeeRole, PW);
        Cookie old = login(mail(code), PW);
        mvc.perform(api("/api/auth/password-resets", "{\"identifier\":\"" + mail(code) + "\"}")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.msg").value("If that account exists and is active, a reset link is on its way to the official email."));
        String text = jdbc.queryForObject("SELECT text FROM email_outbox WHERE \"to\" = ? ORDER BY created_at DESC LIMIT 1", String.class, code.toLowerCase() + "@example.com");
        Matcher m = Pattern.compile("/reset/([A-Za-z0-9_-]+)").matcher(text);
        assertThat(m.find()).isTrue();
        String token = m.group(1);

        mvc.perform(get("/api/auth/tokens/RESET/" + token)).andExpect(status().isOk()).andExpect(jsonPath("$.data.code").value(code));
        mvc.perform(api(put("/api/auth/password"), "{\"token\":\"" + token + "\",\"purpose\":\"RESET\",\"password\":\"short\",\"confirm\":\"short\"}"))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.detail").value("Use at least 10 characters."));
        mvc.perform(api(put("/api/auth/password"), "{\"token\":\"" + token + "\",\"purpose\":\"RESET\",\"password\":\"new-password-42\",\"confirm\":\"new-password-42\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.redirect").value("/login?reset=1"));
        mvc.perform(get("/api/auth/tokens/RESET/" + token)).andExpect(status().isNotFound());
        mvc.perform(get("/api/me").cookie(old)).andExpect(status().isUnauthorized()); // all sessions ended
        login(mail(code), "new-password-42");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE actor_id = ? AND action = 'auth.password_reset'", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void crossSiteWritesAreBlocked() throws Exception {
        mvc.perform(post("/api/auth/password-resets").header("origin", "https://evil.example").header("host", "localhost:3005")
                .contentType(MediaType.APPLICATION_JSON).content("{\"identifier\":\"x\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value("Cross-site request blocked."));
        mvc.perform(post("/api/auth/password-resets").contentType(MediaType.APPLICATION_JSON).content("{\"identifier\":\"x\"}")).andExpect(status().isForbidden());
    }

    @Test
    void signedOutGets401ButTheOriginalFileRoutesKeepTheir403() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value("Your session has ended. Please sign in again."));
        mvc.perform(get("/api/people/export")).andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value("Your session has ended. Please sign in again."));
        mvc.perform(get("/api/payslips/2026-08/JC-049")).andExpect(status().isForbidden());
    }
}
