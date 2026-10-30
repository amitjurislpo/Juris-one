package com.jurislpo.jurisone.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import com.jurislpo.jurisone.config.ApiPaths;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Every API path lives under {@code /api/v1}; the old {@code /api} is not served and the gates (CSRF, sign-in, subscription, policy) work on the new prefix. */
class ApiVersionTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping handlerMapping;

    static MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder request) {
        return request.header("origin", "http://localhost:3005").header("host", "localhost:3005").contentType(MediaType.APPLICATION_JSON);
    }

    Cookie signIn(String email) throws Exception {
        String set = SignIn.session(mockMvc, email).andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(set);
        assertThat(matcher.find()).isTrue();
        return new Cookie("jo_at", matcher.group(1));
    }

    @Test
    void theApiPrefixIsVersionOne() {
        assertThat(ApiPaths.PREFIX).isEqualTo("/api/v1");
    }

    @Test
    void everyHandlerMappingStartsWithTheVersionedPrefix() {
        List<String> patterns = handlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(info -> info.getPathPatternsCondition().getPatternValues().stream()).toList();
        assertThat(patterns).hasSizeGreaterThan(100);
        List<String> outside = patterns.stream()
                .filter(pattern -> !pattern.startsWith(ApiPaths.PREFIX_SLASH) && !pattern.startsWith("/actuator") && !pattern.equals("/error")).toList();
        assertThat(outside).as("mappings outside /api/v1/").isEmpty();
        assertThat(patterns).contains("/api/v1/me", "/api/v1/auth/otp-requests", "/api/v1/juris-track");
    }

    @Test
    void theOldPrefixIsNotServed() throws Exception {
        TestData data = new TestData(jdbc).inBusiness("v1-" + TestData.letters(8));
        UUID role = data.role("employee", "self", "dir");
        String email = "v1." + TestData.letters(8) + "@example.com";
        data.employee("V1-" + TestData.letters(6), email, role);
        Cookie cookie = signIn(email);

        mockMvc.perform(get("/api/v1/me").cookie(cookie)).andExpect(status().isOk());
        for (String oldPath : List.of("/api/me", "/api/shell", "/api/people", "/api/leave/requests", "/api/juris-track")) {
            int code = mockMvc.perform(get(oldPath).cookie(cookie)).andReturn().getResponse().getStatus();
            assertThat(code).as("GET " + oldPath).isIn(401, 403, 404);
        }
        int otp = mockMvc.perform(write(post("/api/auth/otp-requests")).content("{\"email\":\"" + email + "\"}")).andReturn().getResponse().getStatus();
        assertThat(otp).as("POST /api/auth/otp-requests").isIn(401, 403, 404);
        int session = mockMvc.perform(write(post("/api/auth/session")).content("{\"identifier\":\"" + email + "\",\"otp\":\"" + SignIn.CODE + "\"}")).andReturn().getResponse().getStatus();
        assertThat(session).as("POST /api/auth/session").isIn(401, 403, 404);
    }

    @Test
    void signedOutCallsGet401AndAWrongOriginIsRefusedOnTheNewPrefix() throws Exception {
        mockMvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/people")).andExpect(status().isUnauthorized());

        TestData data = new TestData(jdbc).inBusiness("v1-" + TestData.letters(8));
        UUID role = data.role("owner", "dir", "roles.manage", "dept.manage");
        String email = "o." + TestData.letters(8) + "@example.com";
        data.employee("VO-" + TestData.letters(6), email, role);
        Cookie cookie = signIn(email);

        String body = "{\"name\":\"Legal " + TestData.letters(5) + "\"}";
        mockMvc.perform(post("/api/v1/departments").cookie(cookie).header("origin", "https://evil.example").header("host", "localhost:3005")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/departments").cookie(cookie).header("host", "localhost:3005")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        assertThat(mockMvc.perform(write(post("/api/v1/departments")).cookie(cookie).content(body)).andReturn().getResponse().getStatus()).isIn(200, 201);
    }

    @Test
    void anEndedSubscriptionIsReadOnlyOnTheNewPrefix() throws Exception {
        TestData data = new TestData(jdbc).inBusiness("v1-" + TestData.letters(8));
        UUID role = data.role("owner", "dir", "people.view", "dept.manage");
        String email = "s." + TestData.letters(8) + "@example.com";
        data.employee("VS-" + TestData.letters(6), email, role);
        Cookie cookie = signIn(email);
        jdbc.update("UPDATE business_subscriptions SET starts_on = current_date - 400, ends_on = current_date - 1 WHERE business_id = ?", data.business());

        mockMvc.perform(get("/api/v1/me").cookie(cookie)).andExpect(status().isOk()).andExpect(jsonPath("$.data.readOnly").value(true));
        mockMvc.perform(get("/api/v1/people").cookie(cookie)).andExpect(status().isOk());
        mockMvc.perform(write(post("/api/v1/departments")).cookie(cookie).content("{\"name\":\"Legal\"}")).andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_INACTIVE"));
    }

    @Test
    void thePolicyGateStillBlocksUntilAcknowledgedOnTheNewPrefix() throws Exception {
        TestData data = new TestData(jdbc).inBusiness("v1-" + TestData.letters(8));
        String suffix = TestData.letters(8);
        UUID hrRole = data.role("hrm", "self", "dir", "people.view", "policies.manage");
        UUID employeeRole = data.role("employee", "self", "dir");
        data.employee("VH-" + suffix, "hr." + suffix + "@example.com", hrRole);
        data.employee("VE-" + suffix, "emp." + suffix + "@example.com", employeeRole);
        Cookie hr = signIn("hr." + suffix + "@example.com");
        Cookie employee = signIn("emp." + suffix + "@example.com");

        String id = json.readTree(mockMvc.perform(write(post("/api/v1/policies")).cookie(hr).content("{\"title\":\"Code\",\"body\":\"Be kind.\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).at("/data/id").asText();
        mockMvc.perform(write(put("/api/v1/policies/" + id + "/status")).cookie(hr).content("{\"status\":\"PUBLISHED\"}")).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/attendance/mine").cookie(employee)).andExpect(status().isPreconditionRequired()).andExpect(jsonPath("$.code").value("POLICY_ACK_REQUIRED"));
        mockMvc.perform(get("/api/v1/policies/pending").cookie(employee)).andExpect(status().isOk());
        mockMvc.perform(write(post("/api/v1/policies/" + id + "/acknowledgements")).cookie(employee).content("{\"version\":1}")).andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/attendance/mine").cookie(employee)).andExpect(status().is(org.hamcrest.Matchers.not(428)));
    }
}
