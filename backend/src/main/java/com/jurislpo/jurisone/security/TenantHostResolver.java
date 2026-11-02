package com.jurislpo.jurisone.security;

import com.jurislpo.jurisone.config.BusinessSlugs;
import com.jurislpo.jurisone.config.JurisOneProperties;
import com.jurislpo.jurisone.repository.BusinessRepository;
import com.jurislpo.jurisone.tenancy.TenantContext;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Turns the host of a request into a {@link HostBusiness}. The host is the one the servlet container read from the real
 * {@code Host} header ({@code request.getServerName()}); forwarding headers are never consulted (and
 * {@code server.forward-headers-strategy=none}). Rules, all strict:
 * <ul>
 *   <li>anything outside printable ASCII is refused before lowercasing (so look-alike letters such as the Kelvin sign cannot become
 *       {@code k}); the host is lowercased, one trailing dot and a numeric port are ignored;</li>
 *   <li>only {@code a-z 0-9 - .} may remain (no {@code @ / \ # ?}, spaces, IPv6 literals);</li>
 *   <li>{@code <admin-label>.<base-domain>} is the platform;</li>
 *   <li>exactly one label in front of {@code <base-domain>} that is a valid, non-reserved slug is looked up in {@code businesses.code}
 *       (cached): found is that business, not found is unknown;</li>
 *   <li>everything else (bare base domain, nested names, IP addresses, other domains, empty host) is unknown.</li>
 * </ul>
 */
@Component
public class TenantHostResolver {

    private static final int MAX_HOST_LENGTH = 253;
    private static final Pattern PORT = Pattern.compile("^[0-9]{1,5}$");
    private static final Pattern ALLOWED_CHARACTERS = Pattern.compile("^[a-z0-9.-]+$");

    private final String baseDomain;
    private final String adminLabel;
    private final SlugLookupCache cache;

    @Autowired
    public TenantHostResolver(JurisOneProperties props, BusinessRepository businesses) {
        this(props.tenantHost().baseDomain(), props.tenantHost().adminLabel(),
                new SlugLookupCache(slug -> TenantContext.call(null, () -> businesses.findIdByCode(slug))));
    }

    TenantHostResolver(String baseDomain, String adminLabel, SlugLookupCache cache) {
        this.baseDomain = normalise(baseDomain);
        this.adminLabel = normalise(adminLabel);
        this.cache = cache;
    }

    /** What the host names; throws only when the database lookup fails (the caller then simply has no answer). */
    public HostBusiness resolve(String serverName) {
        String host = clean(serverName);
        if (host == null || baseDomain.isEmpty() || !host.endsWith("." + baseDomain)) return HostBusiness.UNKNOWN;
        String label = host.substring(0, host.length() - baseDomain.length() - 1);
        if (label.indexOf('.') >= 0) return HostBusiness.UNKNOWN; // nested (a.b.base), empty label or double dot
        if (label.equals(adminLabel)) return HostBusiness.PLATFORM;
        if (BusinessSlugs.problem(label) != null) return HostBusiness.UNKNOWN; // not a possible slug: no database read
        UUID businessId = cache.businessId(label);
        return businessId == null ? HostBusiness.UNKNOWN : HostBusiness.business(businessId, label);
    }

    /** Forgets a slug so the next request reads it again (used when a business is renamed or suspended). */
    void evict(String slug) {
        cache.evict(slug);
    }

    /** The host in lowercase without a trailing dot or port, or null when it is not a plain DNS name. */
    static String clean(String serverName) {
        if (serverName == null || serverName.isEmpty() || serverName.length() > MAX_HOST_LENGTH + 7) return null;
        for (int i = 0; i < serverName.length(); i++) {
            char character = serverName.charAt(i);
            if (character <= 0x20 || character >= 0x7F) return null;
        }
        String host = serverName.toLowerCase(Locale.ROOT);
        int colon = host.indexOf(':');
        if (colon >= 0) {
            if (!PORT.matcher(host.substring(colon + 1)).matches()) return null; // IPv6 literal, a second colon or a non-numeric port
            host = host.substring(0, colon);
        }
        if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        if (host.isEmpty() || host.length() > MAX_HOST_LENGTH || !ALLOWED_CHARACTERS.matcher(host).matches()) return null;
        return host;
    }

    private static String normalise(String value) {
        String text = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        return text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
    }
}
