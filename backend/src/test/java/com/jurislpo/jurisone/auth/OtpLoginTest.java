package com.jurislpo.jurisone.auth;

import static com.jurislpo.jurisone.auth.AuthFlowTest.api;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.TestData;
import com.jurislpo.jurisone.service.AuthService;
import com.jurislpo.jurisone.service.RateLimiter;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** Email one-time-code sign-in. The default code is the fixed 000000 (jurisone.otp.fixed-code) until the identity provider is connected. */
@TestPropertySource(properties = {"jurisone.otp.requests-per-ip=10", "jurisone.otp.requests-per-email=5"})
class OtpLoginTest extends IntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired RateLimiter limiter;
    TestData data;
    UUID role;

    @BeforeEach
    void setUp() {
        data = new TestData(jdbc);
        role = data.role("employee", "self", "dir");
        limiter.clear();
    }

    String newEmployee() {
        String code = "JC-" + (100 + (int) (Math.random() * 899_000));
        data.employee(code, code.toLowerCase() + "@example.com", role);
        return code.toLowerCase() + "@example.com";
    }

    String json(String email, String otp) {
        return "{\"identifier\":\"" + email + "\",\"otp\":\"" + otp + "\",\"remember\":true}";
    }

    @Test
    void emailsACodeAndTheCodeSignsYouIn() throws Exception {
        String email = newEmployee();
        mvc.perform(api("/api/auth/otp-requests", "{\"email\":\"" + email.toUpperCase() + "\"}")).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_otps WHERE email = ? AND used_at IS NULL", Integer.class, email)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM email_outbox WHERE \"to\" = ? AND subject LIKE 'Your JurisOne sign-in code%'", Integer.class, email)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT code_hash FROM login_otps WHERE email = ?", String.class, email)).doesNotContain("000000");

        mvc.perform(api("/api/auth/session", json(email, "000000"))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.redirect").value("/home"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_history WHERE identifier = ? AND success", Integer.class, email)).isEqualTo(1);

        // a code works once
        mvc.perform(api("/api/auth/session", json(email, "000000"))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(AuthService.BAD_OTP));
    }

    @Test
    void wrongCodeIsRejectedAndFiveWrongTriesBurnTheCode() throws Exception {
        String email = newEmployee();
        mvc.perform(api("/api/auth/otp-requests", "{\"email\":\"" + email + "\"}")).andExpect(status().isCreated());
        for (int i = 0; i < 5; i++) {
            mvc.perform(api("/api/auth/session", json(email, "111111"))).andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.detail").value(AuthService.BAD_OTP));
        }
        limiter.clear();
        // even the right code no longer works: request a new one
        mvc.perform(api("/api/auth/session", json(email, "000000"))).andExpect(status().isUnauthorized());
        mvc.perform(api("/api/auth/otp-requests", "{\"email\":\"" + email + "\"}")).andExpect(status().isCreated());
        mvc.perform(api("/api/auth/session", json(email, "000000"))).andExpect(status().isCreated());
    }

    @Test
    void aNewCodeReplacesTheOldOne() throws Exception {
        String email = newEmployee();
        mvc.perform(api("/api/auth/otp-requests", "{\"email\":\"" + email + "\"}")).andExpect(status().isCreated());
        mvc.perform(api("/api/auth/otp-requests", "{\"email\":\"" + email + "\"}")).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_otps WHERE email = ? AND used_at IS NULL", Integer.class, email)).isEqualTo(1);
    }

    @Test
    void anExpiredCodeIsRejected() throws Exception {
        String email = newEmployee();
        mvc.perform(api("/api/auth/otp-requests", "{\"email\":\"" + email + "\"}")).andExpect(status().isCreated());
        jdbc.update("UPDATE login_otps SET expires_at = now() - interval '1 minute' WHERE email = ?", email);
        mvc.perform(api("/api/auth/session", json(email, "000000"))).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value(AuthService.BAD_OTP));
    }

    @Test
    void unknownEmailGetsTheSameAnswerAndNoCodeIsStored() throws Exception {
        mvc.perform(api("/api/auth/otp-requests", "{\"email\":\"nobody@example.com\"}")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.startsWith("If that account exists")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_otps WHERE email = 'nobody@example.com'", Integer.class)).isZero();
        mvc.perform(api("/api/auth/session", json("nobody@example.com", "000000"))).andExpect(status().isUnauthorized());
    }

    @Test
    void invalidInputIsRejected() throws Exception {
        mvc.perform(api("/api/auth/otp-requests", "{\"email\":\"\"}")).andExpect(status().isBadRequest());
        mvc.perform(api("/api/auth/otp-requests", "{\"email\":\"not-an-email\"}")).andExpect(status().isBadRequest());
        String email = newEmployee();
        mvc.perform(api("/api/auth/session", json(email, "12ab"))).andExpect(status().isBadRequest());
    }

    @Test
    void disabledAndExitedAccountsGetNoCode() throws Exception {
        String email = newEmployee();
        jdbc.update("UPDATE employees SET portal_access = false WHERE email = ?", email);
        mvc.perform(api("/api/auth/otp-requests", "{\"email\":\"" + email + "\"}")).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_otps WHERE email = ?", Integer.class, email)).isZero();
        // the person is told why no code comes
        assertThat(jdbc.queryForObject("SELECT text FROM email_outbox WHERE \"to\" = ? AND subject = 'Your JurisOne sign-in request'", String.class, email))
                .contains(AuthService.MSG_DISABLED);
        mvc.perform(api("/api/auth/session", json(email, "000000"))).andExpect(status().isUnauthorized());
    }

    @Test
    void anExitedAccountIsToldItCanNoLongerSignIn() throws Exception {
        String email = newEmployee();
        jdbc.update("UPDATE employees SET status = 'EXITED' WHERE email = ?", email);
        mvc.perform(api("/api/auth/otp-requests", "{\"email\":\"" + email + "\"}")).andExpect(status().isCreated());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_otps WHERE email = ?", Integer.class, email)).isZero();
        assertThat(jdbc.queryForObject("SELECT text FROM email_outbox WHERE \"to\" = ? AND subject = 'Your JurisOne sign-in request'", String.class, email))
                .contains(AuthService.MSG_EXITED);
    }

    @Test
    void codeRequestsAreRateLimited() throws Exception {
        String email = newEmployee();
        for (int i = 0; i < 5; i++) mvc.perform(api("/api/auth/otp-requests", "{\"email\":\"" + email + "\"}")).andExpect(status().isCreated());
        mvc.perform(api("/api/auth/otp-requests", "{\"email\":\"" + email + "\"}")).andExpect(status().isTooManyRequests());
    }

    @Test
    void passwordsAreNotAcceptedAnyMore() throws Exception {
        String email = newEmployee();
        mvc.perform(api("/api/auth/session", "{\"identifier\":\"" + email + "\",\"password\":\"anything-at-all-1\"}")).andExpect(status().isBadRequest());
        mvc.perform(api("/api/auth/password-resets", "{\"identifier\":\"" + email + "\"}")).andExpect(status().is4xxClientError());
    }
}
