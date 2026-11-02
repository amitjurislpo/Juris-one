package com.jurislpo.jurisone.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The pure rules of the host binding: who may use which kind of host, and which {@code bid} claim fits. */
class TenantHostGuardTest {

    private static final UUID BUSINESS_A = UUID.randomUUID(), BUSINESS_B = UUID.randomUUID();
    private static final HostBusiness HOST_A = HostBusiness.business(BUSINESS_A, "alpha");

    @Test
    void bindingOffAdmitsEveryone() {
        assertThat(TenantHostGuard.permits(null, BUSINESS_A)).isTrue();
        assertThat(TenantHostGuard.permits(null, null)).isTrue();
        assertThat(TenantHostGuard.permitsClaim(null, null)).isTrue();
    }

    @Test
    void aBusinessHostAdmitsOnlyThatBusiness() {
        assertThat(TenantHostGuard.permits(HOST_A, BUSINESS_A)).isTrue();
        assertThat(TenantHostGuard.permits(HOST_A, BUSINESS_B)).isFalse();
        assertThat(TenantHostGuard.permits(HOST_A, null)).isFalse(); // a platform user
    }

    @Test
    void thePlatformHostAdmitsOnlyPlatformUsers() {
        assertThat(TenantHostGuard.permits(HostBusiness.PLATFORM, null)).isTrue();
        assertThat(TenantHostGuard.permits(HostBusiness.PLATFORM, BUSINESS_A)).isFalse();
    }

    @Test
    void anUnknownHostAdmitsNobody() {
        assertThat(TenantHostGuard.permits(HostBusiness.UNKNOWN, BUSINESS_A)).isFalse();
        assertThat(TenantHostGuard.permits(HostBusiness.UNKNOWN, null)).isFalse();
        assertThat(TenantHostGuard.permitsClaim(HostBusiness.UNKNOWN, TenantHostGuard.PLATFORM_BINDING)).isFalse();
    }

    @Test
    void theClaimMustFitTheHostAndAMissingClaimNeverFits() {
        assertThat(TenantHostGuard.permitsClaim(HOST_A, BUSINESS_A.toString())).isTrue();
        assertThat(TenantHostGuard.permitsClaim(HOST_A, BUSINESS_B.toString())).isFalse();
        assertThat(TenantHostGuard.permitsClaim(HOST_A, TenantHostGuard.PLATFORM_BINDING)).isFalse();
        assertThat(TenantHostGuard.permitsClaim(HOST_A, null)).isFalse();
        assertThat(TenantHostGuard.permitsClaim(HostBusiness.PLATFORM, TenantHostGuard.PLATFORM_BINDING)).isTrue();
        assertThat(TenantHostGuard.permitsClaim(HostBusiness.PLATFORM, BUSINESS_A.toString())).isFalse();
        assertThat(TenantHostGuard.permitsClaim(HostBusiness.PLATFORM, null)).isFalse();
        assertThat(TenantHostGuard.bindingClaim(null)).isEqualTo("platform");
        assertThat(TenantHostGuard.bindingClaim(BUSINESS_A)).isEqualTo(BUSINESS_A.toString());
    }
}
