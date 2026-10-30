package com.jurislpo.jurisone.onboarding;

import org.springframework.test.context.TestPropertySource;

/** Per-business addresses on (dev style, base domain localhost): the invitation link uses the business code as the subdomain. */
@TestPropertySource(properties = {"jurisone.tenant-host.enabled=true", "jurisone.tenant-host.base-domain=localhost"})
class OnboardingLinkTenantHostTest extends OnboardingLinkTestBase {

    @Override
    String expectedLink(String businessCode) {
        return "http://" + businessCode + ".localhost:3005/login";
    }
}
