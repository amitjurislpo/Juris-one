package com.jurislpo.jurisone.onboarding;

/** Per-business addresses off (the default): the invitation link is exactly the old {@code app-url + /login}. */
class OnboardingLinkFlagOffTest extends OnboardingLinkTestBase {

    @Override
    String expectedLink(String businessCode) {
        return "http://localhost:3005/login";
    }
}
