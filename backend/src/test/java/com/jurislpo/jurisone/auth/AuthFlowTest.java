package com.jurislpo.jurisone.auth;

import com.jurislpo.jurisone.service.AuthService;
import com.jurislpo.jurisone.service.RateLimiter;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
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

/** Sign-in by emailed code and sessions through the HTTP API, mirroring tests/e2e/auth.spec.ts at the backend level. */
class AuthFlowTest extends IntegrationTest {

    static final String ORIGIN = "http://localhost:3005";

    @Autowired MockMvc mockMvc;
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

    /** A POST as the web server sends it: same-origin headers, JSON body. */
    static MockHttpServletRequestBuilder api(String path, String json) {
        return api(post(path), json);
    }

    /** Any write as the web server sends it. */
    static MockHttpServletRequestBuilder api(MockHttpServletRequestBuilder request, String json) {
        return request.header("origin", ORIGIN).header("host", "localhost:3005").header("x-forwarded-for", "10.0.0.7")
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    String code() {
        return "JC-" + (100 + (int) (Math.random() * 899_000));
    }

    /** Test employees' sign-in email. */
    static String mail(String code) {
        return code.toLowerCase() + "@example.com";
    }

    void requestCode(String email) throws Exception {
        mockMvc.perform(api("/api/auth/otp-requests", "{\"email\":\"" + email + "\"}")).andExpect(status().isCreated());
    }

    /** Asks for a code and signs in with it. */
    Cookie login(String email) throws Exception {
        requestCode(email);
        MvcResult result = mockMvc.perform(api("/api/auth/session", "{\"identifier\":\"" + email + "\",\"otp\":\"" + SignIn.CODE + "\",\"remember\":true}"))
                .andExpect(status().isCreated()).andReturn();
        String set = result.getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        return new Cookie("jo_at", matcher.group(1));
    }

    @Test
    void signsInByEmailOnlyAndTheSessionAuthenticatesApiCalls() throws Exception {
        String code = code();
        UUID id = data.employee(code, mail(code), employeeRole);

        // employee IDs repeat across businesses, so they are not a sign-in name
        mockMvc.perform(api("/api/auth/session", "{\"identifier\":\"" + code + "\",\"otp\":\"" + SignIn.CODE + "\"}")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(AuthService.BAD_OTP));

        requestCode(mail(code));
        MvcResult result = mockMvc.perform(api("/api/auth/session", "{\"identifier\":\"" + mail(code).toUpperCase() + "\",\"otp\":\"" + SignIn.CODE + "\",\"remember\":false}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.redirect").value("/home")).andReturn();
        String set = result.getResponse().getHeader("Set-Cookie");
        assertThat(set).contains("HttpOnly").contains("SameSite=Lax").contains("Path=/").doesNotContain("Max-Age"); // browser-session cookie

        Cookie cookie = login(mail(code));
        mockMvc.perform(get("/api/me").cookie(cookie)).andExpect(status().isOk())
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
    void accountIsLockedAfterRepeatedWrongCodesEvenForTheRightCodeAndIsAudited() throws Exception {
        String code = code();
        UUID id = data.employee(code, mail(code), employeeRole);
        requestCode(mail(code));
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(api("/api/auth/session", "{\"identifier\":\"" + mail(code) + "\",\"otp\":\"11111" + i + "\"}")).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.detail").value(AuthService.BAD_OTP));
        }
        assertThat(jdbc.queryForObject("SELECT locked_until > now() FROM users WHERE employee_id = ?", Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE actor_id = ? AND action = 'auth.account_locked'", Integer.class, id)).isEqualTo(1);
        requestCode(mail(code));
        mockMvc.perform(api("/api/auth/session", "{\"identifier\":\"" + mail(code) + "\",\"otp\":\"" + SignIn.CODE + "\"}")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.startsWith("This account is locked")));
        // the lock ends: the right code works again and clears the count
        jdbc.update("UPDATE users SET locked_until = now() - interval '1 minute' WHERE employee_id = ?", id);
        mockMvc.perform(api("/api/auth/session", "{\"identifier\":\"" + mail(code) + "\",\"otp\":\"" + SignIn.CODE + "\"}")).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT failed_attempts FROM users WHERE employee_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void responsesCarryTheSecurityHeaders() throws Exception {
        mockMvc.perform(get("/api/me")).andExpect(status().isUnauthorized())
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString("frame-ancestors 'none'")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }

    @Test
    void wrongCodeIsRejectedWithoutRevealingWhetherTheAccountExists() throws Exception {
        String code = code();
        data.employee(code, mail(code), employeeRole);
        requestCode(mail(code));
        mockMvc.perform(api("/api/auth/session", "{\"identifier\":\"" + mail(code) + "\",\"otp\":\"111111\"}")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED")).andExpect(jsonPath("$.detail").value(AuthService.BAD_OTP));
        mockMvc.perform(api("/api/auth/session", "{\"identifier\":\"nobody@example.com\",\"otp\":\"111111\"}")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(AuthService.BAD_OTP));
    }

    @Test
    void disabledAndExitedAccountsAreBlockedAndLoseTheirSessionsImmediately() throws Exception {
        String code = code();
        UUID id = data.employee(code, mail(code), employeeRole);
        Cookie cookie = login(mail(code));
        jdbc.update("UPDATE employees SET portal_access = false WHERE id = ?", id);
        mockMvc.perform(get("/api/me").cookie(cookie)).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Your session has ended. Please sign in again."));
        // a code asked for while the account was still active stops working once access is removed
        jdbc.update("UPDATE employees SET portal_access = true WHERE id = ?", id);
        requestCode(mail(code));
        jdbc.update("UPDATE employees SET portal_access = false WHERE id = ?", id);
        mockMvc.perform(api("/api/auth/session", "{\"identifier\":\"" + mail(code) + "\",\"otp\":\"" + SignIn.CODE + "\"}")).andExpect(jsonPath("$.detail").value(AuthService.MSG_DISABLED));
        // while access is off no code is sent at all; the person is told by email
        requestCode(mail(code));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE \"to\" = ? AND subject = 'Your JurisOne sign-in request'", Integer.class, mail(code))).isEqualTo(1);
        jdbc.update("UPDATE employees SET portal_access = true WHERE id = ?", id);
        requestCode(mail(code));
        jdbc.update("UPDATE employees SET status = 'EXITED' WHERE id = ?", id);
        mockMvc.perform(api("/api/auth/session", "{\"identifier\":\"" + mail(code) + "\",\"otp\":\"" + SignIn.CODE + "\"}")).andExpect(jsonPath("$.detail").value(AuthService.MSG_EXITED));
    }

    @Test
    void logoutRevokesTheSessionAndClearsTheCookie() throws Exception {
        String code = code();
        data.employee(code, mail(code), employeeRole);
        Cookie cookie = login(mail(code));
        mockMvc.perform(api(delete("/api/auth/session"), "{}").cookie(cookie)).andExpect(status().isOk()).andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("Max-Age=0")));
        mockMvc.perform(get("/api/me").cookie(cookie)).andExpect(status().isUnauthorized());
    }

    @Test
    void crossSiteWritesAreBlocked() throws Exception {
        mockMvc.perform(post("/api/auth/otp-requests").header("origin", "https://evil.example").header("host", "localhost:3005")
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"x@example.com\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value("Cross-site request blocked."));
        mockMvc.perform(post("/api/auth/otp-requests").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"x@example.com\"}")).andExpect(status().isForbidden());
    }

    @Test
    void signedOutGets401ButTheOriginalFileRoutesKeepTheir403() throws Exception {
        mockMvc.perform(get("/api/me")).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.detail").value("Your session has ended. Please sign in again."));
        mockMvc.perform(get("/api/people/export")).andExpect(status().isForbidden()).andExpect(jsonPath("$.detail").value("Your session has ended. Please sign in again."));
        mockMvc.perform(get("/api/payslips/2026-08/JC-049")).andExpect(status().isForbidden());
    }
}
