package com.jurislpo.jurisone.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.jurislpo.jurisone.TestData;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/** With the flag off (the default) the filter does nothing: no attribute, no counter, and every host gets the plain host's response. */
@TestPropertySource(properties = {"jurisone.tenant-host.enabled=false", "jurisone.tenant-host.base-domain=localhost"})
class TenantHostFlagOffTest extends TenantHostObserveTestBase {

    @Test
    void nothingIsResolvedOrCounted() throws Exception {
        double before = countAll();
        assertThat(hostOf(call(alphaSlug + ".localhost", true))).isNull();
        assertThat(hostOf(call(betaSlug + ".localhost", true))).isNull();
        assertThat(hostOf(call("admin.localhost", true))).isNull();
        assertThat(hostOf(call("unknown.localhost", false))).isNull();
        assertThat(countAll()).isEqualTo(before);
    }

    /** Flag off only: every host gets the response of the plain host. (With the flag on the host binds the session; see TenantHostSignInTest.) */
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
