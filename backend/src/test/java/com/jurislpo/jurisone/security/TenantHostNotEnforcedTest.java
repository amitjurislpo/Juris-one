package com.jurislpo.jurisone.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Step 7: with the flag on but {@code enforce} off (dev only; the EnvironmentGuard refuses it in sqe, uat and prd) a request on a host that names
 * no business is not answered with the neutral 404: it is audited and goes on, and the signed-in binding of step 5 still refuses a token on a
 * host that does not fit.
 */
@TestPropertySource(properties = {"jurisone.tenant-host.enabled=true", "jurisone.tenant-host.base-domain=localhost", "jurisone.tenant-host.enforce=false"})
class TenantHostNotEnforcedTest extends TenantHostObserveTestBase {

    @Test
    void anUnknownHostIsAuditedAndLetThroughInsteadOfAnswering404() throws Exception {
        int before = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'auth.host_refused' AND after->>'reason' = 'unknown_host_not_enforced'", Integer.class);
        assertThat(call("localhost:3005", false).getResponse().getStatus()).isEqualTo(401); // not signed in: the normal answer, not 404
        assertThat(call("nobody-" + alphaSlug + ".localhost", false).getResponse().getStatus()).isEqualTo(401);
        int after = jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action = 'auth.host_refused' AND after->>'reason' = 'unknown_host_not_enforced'", Integer.class);
        assertThat(after).isEqualTo(before + 2);
    }

    @Test
    void aTokenOnAHostThatDoesNotFitIsStillRefusedAndAKnownHostWorks() throws Exception {
        assertThat(call("localhost:3005", true).getResponse().getStatus()).isEqualTo(401); // token bound to alpha, unknown host
        assertThat(call(betaSlug + ".localhost", true).getResponse().getStatus()).isEqualTo(401);
        assertThat(call(alphaSlug + ".localhost", true).getResponse().getStatus()).isEqualTo(200);
    }
}
