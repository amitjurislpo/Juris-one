package com.jurislpo.jurisone.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The host filter only observes: with the flag on or off, a request gets exactly the response it would get without the filter,
 * whichever host it comes from (tested against the same request sent with the plain {@code localhost:3005} host).
 */
abstract class TenantHostObserveTestBase extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry meters;

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
        String setCookie = SignIn.session(mockMvc, email).andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
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

    @Test
    void everyHostGetsTheSameResponseAsThePlainHost() throws Exception {
        for (boolean isSignedIn : new boolean[] {true, false}) {
            MvcResult baseline = call("localhost:3005", isSignedIn);
            assertThat(baseline.getResponse().getStatus()).isEqualTo(isSignedIn ? 200 : 401);
            List<MvcResult> others = List.of(
                    call(alphaSlug + ".localhost:3005", isSignedIn),
                    call(betaSlug + ".localhost:3005", isSignedIn),
                    call(alphaSlug.toUpperCase() + ".LOCALHOST.", isSignedIn),
                    call("admin.localhost:3005", isSignedIn),
                    call("nobody-" + TestData.letters(8) + ".localhost", isSignedIn),
                    call("a.b.localhost", isSignedIn),
                    call("localhost", isSignedIn),
                    call("127.0.0.1:3005", isSignedIn),
                    call("[::1]:3005", isSignedIn),
                    call("evil.com#." + alphaSlug + ".localhost", isSignedIn),
                    call("localhost:3005", isSignedIn, "X-Forwarded-Host", alphaSlug + ".localhost"),
                    call(betaSlug + ".localhost", isSignedIn, "X-Forwarded-Host", alphaSlug + ".localhost", "Forwarded", "host=" + alphaSlug + ".localhost", "X-Original-Host", alphaSlug + ".localhost"));
            for (MvcResult other : others) {
                assertThat(other.getResponse().getStatus()).isEqualTo(baseline.getResponse().getStatus());
                assertThat(body(other)).isEqualTo(body(baseline));
                assertThat(other.getResponse().getHeaderNames()).containsExactlyInAnyOrderElementsOf(baseline.getResponse().getHeaderNames());
            }
        }
    }
}
