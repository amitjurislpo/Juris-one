package com.jurislpo.jurisone.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Flag off (the default): sign-in, codes, tokens and refresh ignore the host completely, exactly as before step 5. */
@TestPropertySource(properties = {"jurisone.tenant-host.enabled=false", "jurisone.tenant-host.base-domain=localhost"})
class TenantHostSignInFlagOffTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    private static String cookieValue(MvcResult result, String name) {
        for (String header : result.getResponse().getHeaders("Set-Cookie")) {
            Matcher matcher = Pattern.compile("^" + name + "=([^;]*)").matcher(header);
            if (matcher.find()) return matcher.group(1);
        }
        return null;
    }

    @Test
    void everyHostWorksForSignInTokenAndRefresh() throws Exception {
        String slug = "off-" + TestData.letters(8);
        TestData data = new TestData(jdbc).inBusiness(slug);
        String email = "off." + TestData.letters(8) + "@example.test";
        UUID person = data.employee("O" + TestData.letters(5).toUpperCase(), email, data.role("member", "self", "dir"));

        for (String host : new String[] {"localhost:3005", slug + ".localhost:3005", "someone-else.localhost", "admin.localhost", "evil.example.com"}) {
            MvcResult issued = SignIn.session(mockMvc, email, host).andReturn();
            assertThat(issued.getResponse().getStatus()).as(host).isEqualTo(201);
            Cookie access = new Cookie("jo_at", cookieValue(issued, "jo_at")), refresh = new Cookie("jo_rt", cookieValue(issued, "jo_rt"));
            for (String other : new String[] {"localhost:3005", "x." + slug + ".localhost", "admin.localhost"}) {
                assertThat(mockMvc.perform(get("/api/v1/me").header("host", other).cookie(access)).andReturn().getResponse().getStatus()).as(host + " -> " + other).isEqualTo(200);
            }
            MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/session-refreshes").header("origin", "http://localhost:3005").header("host", "elsewhere.localhost")
                    .contentType(MediaType.APPLICATION_JSON).content("{}").cookie(refresh)).andReturn();
            assertThat(refreshed.getResponse().getStatus()).as(host).isEqualTo(201);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'auth.host_refused' AND actor_id = ?", Integer.class, person)).isZero();
    }
}
