package com.jurislpo.jurisone.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** With the flag off (the default) the filter does nothing: no attribute, no counter. */
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
}
