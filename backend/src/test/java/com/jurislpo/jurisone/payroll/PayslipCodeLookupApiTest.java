package com.jurislpo.jurisone.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/** The payslip route takes an employee code of any business prefix, with or without the old hyphen, and still refuses other people's payslips. */
class PayslipCodeLookupApiTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void anyPrefixAndBothCodeFormsReachThePermissionCheck() throws Exception {
        String suffix = TestData.letters(6);
        TestData data = new TestData(jdbc).inBusiness("payslip-code-" + suffix);
        UUID role = data.role("employee", "self", "dir");
        data.employee("ZQ01", "me." + suffix + "@example.com", role);
        data.employee("ZQ02", "other." + suffix + "@example.com", role);
        String set = SignIn.session(mockMvc, "me." + suffix + "@example.com").andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        Cookie me = new Cookie("jo_at", matcher.group(1));

        // someone else's payslip: refused in both spellings (before the fix a code outside JC-nnn never got this far and answered 404)
        mockMvc.perform(get("/api/v1/payslips/2026-08/ZQ02").cookie(me)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/payslips/2026-08/ZQ-02").cookie(me)).andExpect(status().isForbidden());
        // my own, in both spellings: allowed to ask, and there is no closed payroll yet
        mockMvc.perform(get("/api/v1/payslips/2026-08/ZQ01").cookie(me)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/payslips/2026-08/ZQ-01").cookie(me)).andExpect(status().isNotFound());
        // malformed codes and months never reach the database
        for (String bad : new String[] {"zq01", "ZQ01X-", "ZQ--01", "1234"}) {
            mockMvc.perform(get("/api/v1/payslips/2026-08/" + bad).cookie(me)).andExpect(status().isNotFound());
        }
        mockMvc.perform(get("/api/v1/payslips/August/ZQ01").cookie(me)).andExpect(status().isNotFound());
    }
}
