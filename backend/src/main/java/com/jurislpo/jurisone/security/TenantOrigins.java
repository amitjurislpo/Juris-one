package com.jurislpo.jurisone.security;

import com.jurislpo.jurisone.config.JurisOneProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Per-business addresses, step 6: decides whether a browser {@code Origin} is a web address of this application, for the Origin (CSRF) check
 * and for CORS. With {@code jurisone.tenant-host.enabled=false} nothing is allowed here (the single configured web origin keeps working as before).
 * With it on, an Origin is allowed only when ALL of these hold:
 * <ul>
 *   <li>it is exactly {@code scheme://host[:port]} in printable ASCII (no path, userinfo, query, trailing dot, {@code null}, spaces, unicode / IDN);</li>
 *   <li>the scheme and the effective port are those of the configured web origin ({@code jurisone.frontend-origin}: http and 3005 in dev,
 *       https and 443 above), so a scheme downgrade or another port never passes;</li>
 *   <li>the host is exactly {@code <slug>.<base-domain>} of a real business (looked up in {@code businesses.code}, so a retired or unknown slug fails)
 *       or {@code <admin-label>.<base-domain>} ({@link TenantHostResolver});</li>
 *   <li>the host and port equal those of the request's real {@code Host} header, so a page of one address can only write to its own address.
 *       {@code X-Forwarded-Host} and the other forwarding headers are never read.</li>
 * </ul>
 * Any lookup error means "not allowed".
 */
@Component
public class TenantOrigins {

    private static final Logger log = LoggerFactory.getLogger(TenantOrigins.class);
    private static final Pattern ORIGIN = Pattern.compile("^(https?)://([a-z0-9.-]{1,253})(?::([0-9]{1,5}))?$");

    private final boolean isEnabled;
    private final String scheme;
    private final int port;
    private final TenantHostResolver resolver;

    public TenantOrigins(JurisOneProperties props, TenantHostResolver resolver) {
        this.isEnabled = props.tenantHost().enabled();
        this.resolver = resolver;
        URI web = URI.create(props.frontendOrigin());
        this.scheme = web.getScheme() == null ? "https" : web.getScheme().toLowerCase(Locale.ROOT);
        this.port = web.getPort() == -1 ? defaultPort(scheme) : web.getPort();
    }

    public boolean isEnabled() {
        return isEnabled;
    }

    /** Whether {@code origin} (the raw Origin header) is a business or admin address that is also the request's own Host. */
    public boolean isAllowed(HttpServletRequest request, String origin) {
        if (!isEnabled || origin == null) return false;
        try {
            return check(request.getHeader("Host"), origin);
        } catch (RuntimeException e) {
            log.debug("origin check could not be completed; refused", e);
            return false;
        }
    }

    private boolean check(String hostHeader, String origin) {
        if (hostHeader == null || !isPrintableAscii(origin) || !isPrintableAscii(hostHeader)) return false;
        Matcher matcher = ORIGIN.matcher(origin);
        if (!matcher.matches() || !scheme.equals(matcher.group(1))) return false;
        String originHost = matcher.group(2);
        int originPort = matcher.group(3) == null ? defaultPort(scheme) : Integer.parseInt(matcher.group(3));
        if (originPort != port || originHost.endsWith(".") || originHost.startsWith(".") || originHost.contains("..")) return false;

        String hostName = hostHeader.toLowerCase(Locale.ROOT);
        int hostPort = defaultPort(scheme);
        int colon = hostName.indexOf(':');
        if (colon >= 0) {
            String text = hostName.substring(colon + 1);
            if (text.isEmpty() || text.length() > 5 || !text.chars().allMatch(Character::isDigit)) return false;
            hostPort = Integer.parseInt(text);
            hostName = hostName.substring(0, colon);
        }
        if (!originHost.equals(hostName) || originPort != hostPort) return false;

        HostBusiness named = resolver.resolve(originHost);
        return named.kind() != HostBusiness.Kind.UNKNOWN;
    }

    private static boolean isPrintableAscii(String text) {
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            if (character <= 0x20 || character >= 0x7F) return false;
        }
        return true;
    }

    private static int defaultPort(String scheme) {
        return "https".equals(scheme) ? 443 : 80;
    }
}
