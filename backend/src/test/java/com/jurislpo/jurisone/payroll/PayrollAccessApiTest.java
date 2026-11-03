package com.jurislpo.jurisone.payroll;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Who may change payroll: {@code pay.view} edits a month's adjustments (by design, "View and edit payroll"), only {@code pay.close} closes it,
 * a person without either gets 403, and hostile input or another business's employee is refused.
 */
class PayrollAccessApiTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    Cookie signIn(TestData data, UUID role, String code) throws Exception {
        String email = code.toLowerCase() + "." + UUID.randomUUID().toString().substring(0, 6) + "@payroll.test";
        data.employee(code, email, role);
        String set = SignIn.session(mockMvc, email).andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        matcher.find();
        return new Cookie("jo_at", matcher.group(1));
    }

    static final String ADJUSTMENT = "{\"lopDays\":1,\"bonus\":100,\"otherDeduction\":0,\"note\":\"x\"}";

    @Test
    void writesNeedThePermissionAndClosingNeedsPayClose() throws Exception {
        TestData data = new TestData(jdbc).inBusiness("payroll-access-" + UUID.randomUUID().toString().substring(0, 6));
        Cookie employee = signIn(data, data.role("plain", "self"), "PE" + (int) (Math.random() * 100_000));
        Cookie viewer = signIn(data, data.role("finance-view", "self", "pay.view"), "PV" + (int) (Math.random() * 100_000));
        String path = "/api/v1/payroll/2031-01/adjustments/" + UUID.randomUUID();

        mockMvc.perform(write(put(path)).cookie(employee).content(ADJUSTMENT)).andExpect(status().isForbidden());
        mockMvc.perform(write(delete(path)).cookie(employee)).andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/payroll/export?month=2031-01&type=register").cookie(employee)).andExpect(status().isForbidden());
        mockMvc.perform(write(put("/api/v1/payroll/2031-01/status")).cookie(employee).content("{\"status\":\"CLOSED\"}")).andExpect(status().isForbidden());
        // pay.view is not pay.close
        mockMvc.perform(write(put("/api/v1/payroll/2031-01/status")).cookie(viewer).content("{\"status\":\"CLOSED\"}")).andExpect(status().isForbidden());
        // no cookie at all
        mockMvc.perform(write(put(path)).content(ADJUSTMENT)).andExpect(status().isUnauthorized());
    }

    @Test
    void hostileInputAndOtherBusinessEmployeesAreRefused() throws Exception {
        TestData alpha = new TestData(jdbc).inBusiness("payroll-alpha-" + UUID.randomUUID().toString().substring(0, 6));
        TestData beta = new TestData(jdbc).inBusiness("payroll-beta-" + UUID.randomUUID().toString().substring(0, 6));
        Cookie alphaFinance = signIn(alpha, alpha.role("finance", "self", "pay.view"), "FA" + (int) (Math.random() * 100_000));
        UUID betaEmployee = beta.employee("BE" + (int) (Math.random() * 100_000), "be" + UUID.randomUUID().toString().substring(0, 6) + "@payroll.test", beta.role("plain", "self"));

        // an employee of another business is not found from here
        mockMvc.perform(write(put("/api/v1/payroll/2031-01/adjustments/" + betaEmployee)).cookie(alphaFinance).content(ADJUSTMENT)).andExpect(status().is4xxClientError());
        // malformed ids and months, out-of-range and oversized values, SQL text
        mockMvc.perform(write(put("/api/v1/payroll/2031-01/adjustments/not-a-uuid")).cookie(alphaFinance).content(ADJUSTMENT)).andExpect(status().isBadRequest());
        mockMvc.perform(write(put("/api/v1/payroll/2031-13/adjustments/" + betaEmployee)).cookie(alphaFinance).content(ADJUSTMENT)).andExpect(status().is4xxClientError());
        mockMvc.perform(write(put("/api/v1/payroll/x'%3BDROP%20TABLE%20employees--/adjustments/" + betaEmployee)).cookie(alphaFinance).content(ADJUSTMENT)).andExpect(status().is4xxClientError());
        mockMvc.perform(write(put("/api/v1/payroll/2031-01/adjustments/" + betaEmployee)).cookie(alphaFinance)
                .content("{\"lopDays\":99,\"bonus\":1,\"otherDeduction\":0,\"note\":\"x\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(write(put("/api/v1/payroll/2031-01/adjustments/" + betaEmployee)).cookie(alphaFinance)
                .content("{\"lopDays\":1,\"bonus\":1,\"otherDeduction\":0,\"note\":\"" + "<script>".repeat(40) + "\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/payroll/export?month=2031-01&type=' OR 1=1--").cookie(alphaFinance)).andExpect(status().isBadRequest());
    }
}
