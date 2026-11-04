package com.jurislpo.jurisone.juristrack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jurislpo.jurisone.IntegrationTest;
import com.jurislpo.jurisone.JurisTrackStub;
import com.jurislpo.jurisone.SignIn;
import com.jurislpo.jurisone.TestData;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.Cookie;
import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Per-business addresses on (step 8): JurisTrack lives at {@code <business address>/juris-track/}. The gateway forwards only a request that came
 * in on the signed-in person's own business address, and the token it sends carries {@code bid} = that business.
 */
@TestPropertySource(properties = {"jurisone.tenant-host.enabled=true", "jurisone.tenant-host.base-domain=localhost", "jurisone.tenant-host.enforce=true"})
class JurisTrackGatewayTenantHostTest extends IntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired JdbcTemplate jdbc;

    String slug, otherSlug, host;
    UUID businessId;
    Cookie employee;

    @BeforeEach
    void setUp() throws Exception {
        slug = "jg-" + TestData.letters(8);
        otherSlug = "jh-" + TestData.letters(8);
        TestData data = new TestData(jdbc).inBusiness(slug);
        new TestData(jdbc).inBusiness(otherSlug);
        businessId = data.business();
        jdbc.update("INSERT INTO business_modules (id, business_id, module, source, starts_on) VALUES (?, ?, 'JURIS_TRACK', 'COMPLIMENTARY', ?)",
                UUID.randomUUID(), businessId, Date.valueOf(LocalDate.now().minusDays(1)));
        String email = "jg." + TestData.letters(8) + "@example.test";
        data.employee("JG-" + TestData.letters(5).toUpperCase(), email, data.role("jt-employee", "self", "dir"));
        host = slug + ".localhost:3005";
        String setCookie = SignIn.session(mockMvc, email, host, "http://" + host).andExpect(status().isCreated()).andReturn().getResponse().getHeader("Set-Cookie");
        Matcher matcher = Pattern.compile("jo_at=([^;]+)").matcher(setCookie);
        assertThat(matcher.find()).isTrue();
        employee = new Cookie("jo_at", matcher.group(1));
        JurisTrackStub.reset();
    }

    @Test
    void aRequestOnTheOwnBusinessAddressIsForwardedWithTheHostBinding() throws Exception {
        mockMvc.perform(get("/api/v1/juris-track/me/time").header("Host", host).cookie(employee)).andExpect(status().isOk());
        assertThat(JurisTrackStub.received()).hasSize(1);
        String authorization = JurisTrackStub.received().get(0).headers().get("authorization");
        SignedJWT token = SignedJWT.parse(authorization.substring("Bearer ".length()));
        assertThat(token.verify(new RSASSAVerifier(JurisTrackStub.publicKey()))).isTrue();
        assertThat(token.getJWTClaimsSet().getStringClaim("bid")).isEqualTo(businessId.toString());
        assertThat(token.getJWTClaimsSet().getStringClaim("businessId")).isEqualTo(businessId.toString());
    }

    @Test
    void aRequestOnAnyOtherAddressIsNeverForwarded() throws Exception {
        for (String otherHost : new String[] {otherSlug + ".localhost:3005", "admin.localhost:3005", "localhost:3005", "nobody-" + TestData.letters(8) + ".localhost:3005", "evil.com",
                slug + ".localhost.evil.com:3005"}) {
            int answered = mockMvc.perform(get("/api/v1/juris-track/me/time").header("Host", otherHost).cookie(employee)).andReturn().getResponse().getStatus();
            assertThat(answered).as(otherHost).isIn(401, 404);
        }
        mockMvc.perform(get("/api/v1/juris-track/me/time").header("Host", host).header("X-Forwarded-Host", otherSlug + ".localhost:3005").cookie(employee)).andExpect(status().isOk());
        assertThat(JurisTrackStub.received()).hasSize(1); // only the one on the own address (the forged forwarding header changed nothing)
    }
}
