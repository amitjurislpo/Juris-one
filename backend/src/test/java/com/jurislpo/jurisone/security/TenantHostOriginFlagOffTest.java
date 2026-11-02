package com.jurislpo.jurisone.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.TestData;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/** Flag off: business-address origins are NOT accepted, unknown hosts are not 404'd and CORS allows only the web app's origin, exactly as before step 6. */
@TestPropertySource(properties = {"jurisone.tenant-host.enabled=false", "jurisone.tenant-host.base-domain=localhost"})
class TenantHostOriginFlagOffTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("corsConfigurationSource") org.springframework.web.cors.CorsConfigurationSource cors;

    @Test
    void onlyTheConfiguredWebOriginPassesAndNothingIsHostChecked() throws Exception {
        String slug = "off-" + TestData.letters(8);
        new TestData(jdbc).inBusiness(slug);
        String host = slug + ".localhost:3005";
        MvcResult tenantOrigin = mockMvc.perform(post("/api/v1/people").header("Host", host).header("Origin", "http://" + host).contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
        assertThat(tenantOrigin.getResponse().getStatus()).isEqualTo(403);
        assertThat(tenantOrigin.getResponse().getContentAsString()).contains("CROSS_SITE_BLOCKED");
        MvcResult webOrigin = mockMvc.perform(post("/api/v1/people").header("Host", "evil.com").header("Origin", "http://localhost:3005").contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn();
        assertThat(webOrigin.getResponse().getStatus()).isEqualTo(401); // not 404: the host is not looked at
        for (String unknown : List.of("evil.com", "127.0.0.1:3005", "nobody.localhost")) {
            assertThat(mockMvc.perform(get("/api/v1/people").header("Host", unknown)).andReturn().getResponse().getStatus()).isEqualTo(401);
        }
        var ownRequest = new org.springframework.mock.web.MockHttpServletRequest("OPTIONS", "/api/v1/people");
        ownRequest.setRequestURI("/api/v1/people");
        ownRequest.addHeader("Host", host);
        ownRequest.addHeader("Origin", "http://" + host);
        assertThat(cors.getCorsConfiguration(ownRequest).checkOrigin("http://" + host)).isNull(); // a business-address origin gets no CORS permission
        assertThat(cors.getCorsConfiguration(ownRequest).checkOrigin("http://localhost:3005")).isEqualTo("http://localhost:3005");
    }
}
