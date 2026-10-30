package com.jurislpo.jurisone.service;

import com.jurislpo.jurisone.config.JurisOneProperties;
import com.jurislpo.jurisone.repository.BusinessRepository;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * The only place that builds a user-facing web address (links in emails and notifications). With
 * {@code jurisone.tenant-host.enabled=false} every link is {@code app-url + path}, exactly as before. With it on, a business link is
 * {@code <scheme>://<business code>.<base-domain>[:port]<path>} (scheme and port come from {@code jurisone.app-url}) and the platform
 * (super admin) link is {@code <scheme>://<admin-label>.<base-domain>[:port]<path>}.
 *
 * <p>The path never decides the host: it is normalised to start with one {@code /}, empty, {@code .} and {@code ..} segments are dropped,
 * and every character outside the unreserved set is percent-encoded (so {@code //evil.com}, {@code https://evil.com}, {@code @}, backslashes,
 * CR and LF stay harmless text inside the path). An optional {@code ?query} and {@code #fragment} are encoded the same way, keeping {@code = &}.
 * Works without a request (jobs): the business is given by id and its code is read from {@code businesses.code} each time.
 */
@Service
public class BusinessUrls {

    private static final Pattern DNS_LABEL = Pattern.compile("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$");
    private static final String HEX = "0123456789ABCDEF";

    private final BusinessRepository businesses;
    private final boolean isTenantHostEnabled;
    private final String appUrl;
    private final String scheme;
    private final String baseDomain;
    private final String adminLabel;
    private final String portSuffix;

    public BusinessUrls(JurisOneProperties props, BusinessRepository businesses) {
        this.businesses = businesses;
        this.appUrl = props.frontendOrigin().replaceAll("/+$", "");
        JurisOneProperties.TenantHost tenantHost = props.tenantHost();
        this.isTenantHostEnabled = tenantHost.enabled();
        if (isTenantHostEnabled) {
            URI uri = URI.create(appUrl);
            if (uri.getScheme() == null || uri.getHost() == null) throw new IllegalStateException("jurisone.app-url must be an absolute URL when jurisone.tenant-host.enabled=true");
            this.scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            this.portSuffix = uri.getPort() == -1 ? "" : ":" + uri.getPort();
            this.baseDomain = tenantHost.baseDomain().trim().toLowerCase(Locale.ROOT);
            this.adminLabel = tenantHost.adminLabel().trim().toLowerCase(Locale.ROOT);
            if (baseDomain.isEmpty() || baseDomain.contains("/") || baseDomain.contains(":") || baseDomain.contains("*")) throw new IllegalStateException("jurisone.tenant-host.base-domain is not a plain domain name");
            if (!DNS_LABEL.matcher(adminLabel).matches()) throw new IllegalStateException("jurisone.tenant-host.admin-label is not a DNS label");
        } else {
            this.scheme = null;
            this.portSuffix = "";
            this.baseDomain = null;
            this.adminLabel = null;
        }
    }

    /** A link to {@code path} on the business's own address (its code is read by id, so jobs without a request work too). */
    public String forBusiness(UUID businessId, String path) {
        if (!isTenantHostEnabled) return appUrl + normalisePath(path);
        if (businessId == null) throw new IllegalArgumentException("A business id is needed to build a business address");
        return forSlug(businesses.findCode(businessId), path);
    }

    /** A link to {@code path} on the address of the business with this code (slug). */
    public String forSlug(String slug, String path) {
        if (!isTenantHostEnabled) return appUrl + normalisePath(path);
        String label = slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT);
        if (!DNS_LABEL.matcher(label).matches()) throw new IllegalArgumentException("The business code cannot be used as a host name");
        return scheme + "://" + label + "." + baseDomain + portSuffix + normalisePath(path);
    }

    /** A link to {@code path} on the platform (super admin) address. */
    public String platformUrl(String path) {
        if (!isTenantHostEnabled) return appUrl + normalisePath(path);
        return scheme + "://" + adminLabel + "." + baseDomain + portSuffix + normalisePath(path);
    }

    /** {@code /a/b?x=1#top} from whatever was given: one leading slash, safe segments, encoded query and fragment. */
    static String normalisePath(String path) {
        String rest = path == null ? "" : path;
        String fragment = null;
        String query = null;
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            fragment = rest.substring(hash + 1);
            rest = rest.substring(0, hash);
        }
        int question = rest.indexOf('?');
        if (question >= 0) {
            query = rest.substring(question + 1);
            rest = rest.substring(0, question);
        }
        StringBuilder out = new StringBuilder();
        for (String segment : rest.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) continue;
            out.append('/').append(encode(segment, ""));
        }
        if (out.length() == 0) out.append('/');
        if (query != null) out.append('?').append(encode(query, "=&"));
        if (fragment != null) out.append('#').append(encode(fragment, "=&"));
        return out.toString();
    }

    /** Keeps letters, digits and {@code - . _ ~} plus {@code extraSafe}; every other byte becomes {@code %XX} (UTF-8). */
    private static String encode(String text, String extraSafe) {
        StringBuilder out = new StringBuilder();
        for (byte value : text.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (value & 0xff);
            boolean isSafe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '.' || c == '_' || c == '~' || extraSafe.indexOf(c) >= 0;
            if (isSafe && c < 0x80) out.append(c);
            else out.append('%').append(HEX.charAt((value >> 4) & 0xf)).append(HEX.charAt(value & 0xf));
        }
        return out.toString();
    }
}
