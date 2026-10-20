package com.jurislpo.jurisone.auth;

import static com.jurislpo.jurisone.auth.AuthFlowTest.api;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.TestData;
import com.jurislpo.jurisone.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/** The default: sign-in is by emailed code only; the password endpoint is switched off. */
@TestPropertySource(properties = "jurisone.password-login.enabled=false")
class PasswordLoginOffTest extends IntegrationTest {

    static final String PW = "correct-horse-battery-7";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void passwordSignInIsRefusedEvenWithTheRightPassword() throws Exception {
        TestData data = new TestData(jdbc);
        data.employee("JC-777001", "jc-777001@example.com", data.role("employee", "self", "dir"), PW);
        mvc.perform(api("/api/auth/session", "{\"identifier\":\"jc-777001@example.com\",\"password\":\"" + PW + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.detail").value(AuthService.MSG_OTP_ONLY));
    }

    @Test
    void theEmailedCodeStillSignsYouIn() throws Exception {
        TestData data = new TestData(jdbc);
        data.employee("JC-777002", "jc-777002@example.com", data.role("employee", "self", "dir"), PW);
        mvc.perform(api("/api/auth/otp-requests", "{\"email\":\"jc-777002@example.com\"}")).andExpect(status().isCreated());
        mvc.perform(api("/api/auth/session", "{\"identifier\":\"jc-777002@example.com\",\"otp\":\"000000\"}")).andExpect(status().isCreated());
    }
}
