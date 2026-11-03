package com.jurislpo.jurisone.security;

import static com.jurislpo.jurisone.config.ApiPaths.PREFIX_SLASH;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.config.JurisOneProperties;
import com.jurislpo.jurisone.exception.Problems;
import com.jurislpo.jurisone.service.AuditService;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CSRF guard (assertSameOrigin): a mutating /api request must carry an Origin whose host equals
 * the configured web origin ({@code jurisone.frontend-origin}). With {@code jurisone.tenant-host.enabled=true} the Origin of a business address
 * ({@code <slug>.<base-domain>}) or of the admin address is accepted too, but only when it is also this request's real Host ({@link TenantOrigins}).
 * Forwarding headers such as {@code X-Forwarded-Host} are never read. Every refusal is counted and audited (one audit entry per caller per
 * 10 seconds). Together with SameSite=Lax session cookies this is the app's CSRF defence.
 */
@Component
public class OriginCheckFilter extends OncePerRequestFilter {

    static final String MESSAGE = "Cross-site request blocked.";
    public static final String AUDIT_ACTION = "origin.refused";
    public static final String COUNTER_NAME = "jurisone.origin.refused";
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");
    private static final Logger log = LoggerFactory.getLogger(OriginCheckFilter.class);
    private static final long AUDIT_INTERVAL_MILLIS = 10_000L;
    private static final int MAX_TRACKED_CALLERS = 2_000;

    private final ObjectMapper json;
    private final String allowedHost;
    private final TenantOrigins tenantOrigins;
    private final AuditService audit;
    private final MeterRegistry meters;
    private final Map<String, Long> lastAuditedAt = new ConcurrentHashMap<>();

    public OriginCheckFilter(ObjectMapper json, JurisOneProperties props, TenantOrigins tenantOrigins, AuditService audit, MeterRegistry meters) {
        this.json = json;
        this.allowedHost = hostOf(props.frontendOrigin());
        this.tenantOrigins = tenantOrigins;
        this.audit = audit;
        this.meters = meters;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return SAFE.contains(request.getMethod()) || !request.getRequestURI().startsWith(PREFIX_SLASH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String origin = request.getHeader("origin");
        if (origin == null || !(allowedHost.equalsIgnoreCase(hostOf(origin)) || tenantOrigins.isAllowed(request, origin))) {
            recordRefusal(request, origin);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            json.writeValue(response.getOutputStream(), Problems.of(HttpStatus.FORBIDDEN, "CROSS_SITE_BLOCKED", MESSAGE, request.getRequestURI()));
            return;
        }
        chain.doFilter(request, response);
    }

    private void recordRefusal(HttpServletRequest request, String origin) {
        try {
            String reason = origin == null ? "missing" : origin.isBlank() ? "empty" : "not_allowed";
            meters.counter(COUNTER_NAME, "reason", reason).increment();
            String ipAddress = RequestMeta.of(request).ipAddress();
            String caller = ipAddress != null ? ipAddress : String.valueOf(request.getRemoteAddr());
            long now = System.currentTimeMillis();
            Long last = lastAuditedAt.get(caller);
            if (last != null && now - last < AUDIT_INTERVAL_MILLIS) return; // a flood of refusals from one caller is one audit entry per interval
            if (lastAuditedAt.size() >= MAX_TRACKED_CALLERS) lastAuditedAt.clear();
            lastAuditedAt.put(caller, now);
            Map<String, Object> details = new HashMap<>();
            details.put("reason", reason);
            details.put("method", request.getMethod());
            details.put("origin", printable(origin));
            details.put("host", printable(request.getHeader("Host")));
            audit.log(null, AUDIT_ACTION, "request", null, null, details, ipAddress);
        } catch (RuntimeException e) {
            log.warn("could not record a refused origin", e);
        }
    }

    /** Attacker-controlled text for the audit trail: printable ASCII only, at most 120 characters. */
    private static String printable(String text) {
        if (text == null) return null;
        StringBuilder safe = new StringBuilder();
        for (int i = 0; i < Math.min(text.length(), 120); i++) {
            char character = text.charAt(i);
            safe.append(character >= 0x20 && character < 0x7F ? character : '?');
        }
        return safe.toString();
    }

    /** {@code new URL(origin).host}: host plus a non-default port. */
    static String hostOf(String origin) {
        try {
            URI originUri = URI.create(origin);
            if (originUri.getHost() == null) return "";
            int port = originUri.getPort();
            boolean defaultValue = port == -1 || ("http".equals(originUri.getScheme()) && port == 80) || ("https".equals(originUri.getScheme()) && port == 443);
            return defaultValue ? originUri.getHost() : originUri.getHost() + ":" + port;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
