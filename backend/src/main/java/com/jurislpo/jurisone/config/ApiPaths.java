package com.jurislpo.jurisone.config;

/** The one place that knows where the API lives. Every filter, matcher and regex builds its paths from {@link #PREFIX}. */
public final class ApiPaths {

    /** Version 1 of the API; controllers map under it, the gates (CSRF, policy, subscription, audit) match on it. */
    public static final String PREFIX = "/api/v1";

    /** {@link #PREFIX} followed by a slash, for {@code startsWith} checks. */
    public static final String PREFIX_SLASH = PREFIX + "/";

    private ApiPaths() {
    }
}
