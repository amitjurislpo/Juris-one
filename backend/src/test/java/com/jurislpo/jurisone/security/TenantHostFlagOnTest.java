package com.jurislpo.jurisone.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;

import com.jurislpo.jurisone.TestData;
import com.jurislpo.jurisone.repository.BusinessRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MvcResult;

/** With the flag on (base domain localhost) the host is resolved and mismatches are counted; tokens bound to another host are refused (step 5). */
@TestPropertySource(properties = {"jurisone.tenant-host.enabled=true", "jurisone.tenant-host.base-domain=localhost"})
class TenantHostFlagOnTest extends TenantHostObserveTestBase {

    @MockitoSpyBean @Autowired BusinessRepository businessRepository;

    @Test
    void theRequestAttributeHoldsWhatTheHostNames() throws Exception {
        assertThat(hostOf(call(alphaSlug + ".localhost:3005", true))).isEqualTo(HostBusiness.business(alphaId, alphaSlug));
        assertThat(hostOf(call(alphaSlug.toUpperCase() + ".localhost.", false))).isEqualTo(HostBusiness.business(alphaId, alphaSlug));
        assertThat(hostOf(call(betaSlug + ".localhost", true))).isEqualTo(HostBusiness.business(betaId, betaSlug));
        assertThat(hostOf(call("admin.localhost:3005", true))).isEqualTo(HostBusiness.PLATFORM);
        assertThat(hostOf(call("nobody-" + TestData.letters(8) + ".localhost", true))).isEqualTo(HostBusiness.UNKNOWN);
        assertThat(hostOf(call("localhost:3005", true))).isEqualTo(HostBusiness.UNKNOWN);
        assertThat(hostOf(call("a.b.localhost", true))).isEqualTo(HostBusiness.UNKNOWN);
        assertThat(hostOf(call("127.0.0.1:3005", true))).isEqualTo(HostBusiness.UNKNOWN);
    }

    @Test
    void forwardingHeadersNeverChangeTheHost() throws Exception {
        MvcResult forged = call("localhost:3005", true, "X-Forwarded-Host", alphaSlug + ".localhost", "Forwarded", "host=" + alphaSlug + ".localhost", "X-Original-Host", alphaSlug + ".localhost");
        assertThat(hostOf(forged)).isEqualTo(HostBusiness.UNKNOWN);
        MvcResult forgedOnOtherBusiness = call(betaSlug + ".localhost", true, "X-Forwarded-Host", alphaSlug + ".localhost");
        assertThat(hostOf(forgedOnOtherBusiness)).isEqualTo(HostBusiness.business(betaId, betaSlug));
    }

    @Test
    void mismatchesAreCountedAndATokenOnTheWrongHostIsRefused() throws Exception {
        double anonymous = count("anonymous_on_unknown_host");
        double total = countAll();

        assertThat(call(alphaSlug + ".localhost", true).getResponse().getStatus()).isEqualTo(200); // the user's own business: fine
        call(alphaSlug + ".localhost", false); // not signed in on a business host: fine
        call("admin.localhost", false); // not signed in on the platform host: fine
        assertThat(countAll()).isEqualTo(total);

        // the signed-in person on another business's or the platform's host is signed out (401); an unknown host answers the neutral 404 (step 6)
        assertThat(call(betaSlug + ".localhost", true).getResponse().getStatus()).isEqualTo(401);
        assertThat(call("admin.localhost", true).getResponse().getStatus()).isEqualTo(401);
        assertThat(call("nobody-" + TestData.letters(8) + ".localhost", true).getResponse().getStatus()).isEqualTo(404);
        call("localhost:3005", false, "X-Forwarded-Host", alphaSlug + ".localhost"); // a forged header does not make it a known host
        assertThat(count("anonymous_on_unknown_host")).isEqualTo(anonymous + 2); // the refused unknown-host token counts as anonymous, plus this call
    }

    @Test
    void aFailingLookupNeverFailsTheRequestAndFailsClosed() throws Exception {
        String failingSlug = "failing-" + TestData.letters(8);
        doThrow(new IllegalStateException("database down")).when(businessRepository).findIdByCode(failingSlug);
        double total = countAll();
        MvcResult failed = call(failingSlug + ".localhost", true);
        MvcResult plain = call("localhost:3005", true);
        assertThat(failed.getResponse().getStatus()).isEqualTo(404); // no resolved host: fails closed with the neutral 404, it does not crash
        assertThat(body(failed)).isEqualTo(body(plain));
        assertThat(hostOf(failed)).isNull();
        assertThat(countAll()).isEqualTo(total + 1); // only the plain call (unknown host, now signed out) counted
    }
}
