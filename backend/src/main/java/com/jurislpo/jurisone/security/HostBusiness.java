package com.jurislpo.jurisone.security;

import java.util.UUID;

/**
 * Which business (or the platform) the request's host names. Set as a request attribute by {@link TenantHostFilter} when
 * {@code jurisone.tenant-host.enabled=true}; observed only, nothing decides on it yet.
 *
 * @param kind       what the host is
 * @param businessId the business, only for {@link Kind#BUSINESS}
 * @param slug       the business's short name (its {@code businesses.code}), only for {@link Kind#BUSINESS}
 */
public record HostBusiness(Kind kind, UUID businessId, String slug) {

    /** The request attribute that holds the {@link HostBusiness} of the current request. */
    public static final String REQUEST_ATTRIBUTE = "jurisone.hostBusiness";

    public static final HostBusiness PLATFORM = new HostBusiness(Kind.PLATFORM, null, null);
    public static final HostBusiness UNKNOWN = new HostBusiness(Kind.UNKNOWN, null, null);

    public enum Kind { BUSINESS, PLATFORM, UNKNOWN }

    public static HostBusiness business(UUID businessId, String slug) {
        return new HostBusiness(Kind.BUSINESS, businessId, slug);
    }
}
