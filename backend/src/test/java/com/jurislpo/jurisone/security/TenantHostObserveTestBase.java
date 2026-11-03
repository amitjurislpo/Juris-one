package com.jurislpo.jurisone.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Two businesses and a signed-in person of the first, for the host filter tests. */
abstract class TenantHostObserveTestBase extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry meters;
    @Autowired com.jurislpo.jurisone.config.JurisOneProperties props;

    String alphaSlug, betaSlug;
    UUID alphaId, betaId;
    Cookie alphaUser;

    @BeforeEach
    void setUp() throws Exception {
        alphaSlug = "alpha-" + TestData.letters(8);
        betaSlug = "beta-" + TestData.letters(8);
        TestData alpha = new TestData(jdbc).inBusiness(alphaSlug);
        betaId = new TestData(jdbc).inBusiness(betaSlug).business();
        alphaId = alpha.business();
        UUID role = alpha.role("observer", "self", "dir", "people.view");
        String email = "observer." + TestData.letters(8) + "@example.test";
        alpha.employee("OB" + TestData.letters(5).toUpperCase(), email, role);
        String setCookie = SignIn.session(mockMvc, email, alphaSlug + ".localhost:3005", props.tenantHost().enabled() ? "http://" + alphaSlug + ".localhost:3005" : "http://localhost:3005").andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(setCookie);
        assertThat(matcher.find()).isTrue();
        alphaUser = new Cookie("jo_at", matcher.group(1));
    }

    /** One call of a signed-in or anonymous person with the given {@code Host} (and optional forged forwarding headers). */
    MvcResult call(String host, boolean isSignedIn, String... headerNamesAndValues) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/v1/people?search=nobody-" + alphaSlug).header("Host", host);
        for (int i = 0; i < headerNamesAndValues.length; i += 2) request.header(headerNamesAndValues[i], headerNamesAndValues[i + 1]);
        if (isSignedIn) request.cookie(alphaUser);
        return mockMvc.perform(request).andReturn();
    }

    static String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    double count(String kind) {
        var counter = meters.find(TenantHostMismatchReporter.COUNTER_NAME).tag("kind", kind).counter();
        return counter == null ? 0 : counter.count();
    }

    double countAll() {
        return meters.find(TenantHostMismatchReporter.COUNTER_NAME).counters().stream().mapToDouble(counter -> counter.count()).sum();
    }

    HostBusiness hostOf(MvcResult result) {
        return (HostBusiness) result.getRequest().getAttribute(HostBusiness.REQUEST_ATTRIBUTE);
    }
}
