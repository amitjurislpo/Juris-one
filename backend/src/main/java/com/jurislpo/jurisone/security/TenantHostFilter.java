package com.jurislpo.jurisone.security;

import static com.jurislpo.jurisone.config.ApiPaths.PREFIX;
import static com.jurislpo.jurisone.config.ApiPaths.PREFIX_SLASH;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.common.RequestMeta;
import com.jurislpo.jurisone.exception.Problems;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

import com.jurislpo.jurisone.config.JurisOneProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-business addresses, steps 4 and 6. With {@code jurisone.tenant-host.enabled=false} it does nothing. With it on it resolves the
 * request's host into a {@link HostBusiness} (request attribute {@link HostBusiness#REQUEST_ATTRIBUTE}) before {@link OriginCheckFilter} and
 * {@link JwtAuthFilter}, and when the chain is done compares the host's business with the signed-in user's ({@link TenantHostMismatchReporter}:
 * counter and rate-limited WARN).
 *
 * <p>Step 6: a request for any {@code /api/v1} endpoint whose real Host names no business and is not the admin address (unknown or retired
 * slug, bare domain, IP address, other domain, unresolvable) gets the same neutral 404 as sign-in does, before the Origin check and before
 * authentication, and is audited ({@code auth.host_refused}, step {@code request}). The three sign-in endpoints answer that themselves
 * (with their own audit step), so they pass through here. Health checks are not under {@code /api/v1} and stay reachable. Any lookup
 * failure of this filter fails closed on {@code /api/v1} (404) while binding is on.
 *
 * <p>Step 7: that refusal applies only with {@code jurisone.tenant-host.enforce=true} (required by the {@code EnvironmentGuard} in sqe, uat and prd).
 * With enforce off the unknown host is audited ({@code unknown_host_not_enforced}) and the request goes on unchanged.
 *
 * <p>The comparison runs after the chain so that it also sees requests that a later filter refused (402, 403); the signed-in user is read
 * from the request attribute {@link AuditTrail#USER_ATTR} that {@link JwtAuthFilter} sets.
 */
@Component
public class TenantHostFilter extends OncePerRequestFilter {

    /** The sign-in endpoints refuse an unknown host themselves (own audit step), with the same neutral 404. */
    private static final Set<String> SIGN_IN_PATHS = Set.of(PREFIX + "/auth/session", PREFIX + "/auth/session-refreshes", PREFIX + "/auth/otp-requests");

    private static final Logger log = LoggerFactory.getLogger(TenantHostFilter.class);

    private final boolean isEnabled;
    private final boolean isEnforced;
    private final TenantHostResolver resolver;
    private final TenantHostMismatchReporter reporter;
    private final TenantHostGuard guard;
    private final ObjectMapper json;

    public TenantHostFilter(JurisOneProperties props, TenantHostResolver resolver, TenantHostMismatchReporter reporter, TenantHostGuard guard, ObjectMapper json) {
        this.guard = guard;
        this.json = json;
        this.isEnabled = props.tenantHost().enabled();
        this.isEnforced = props.tenantHost().enforce();
        this.resolver = resolver;
        this.reporter = reporter;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !isEnabled;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        HostBusiness host = null;
        try {
            host = resolver.resolve(request.getServerName()); // the Host header as the container read it, never X-Forwarded-Host
            request.setAttribute(HostBusiness.REQUEST_ATTRIBUTE, host);
        } catch (RuntimeException e) {
            log.debug("tenant host lookup failed; the request goes on", e);
        }
        if (request.getRequestURI().startsWith(PREFIX_SLASH) && !SIGN_IN_PATHS.contains(request.getRequestURI())
                && (host == null || host.kind() == HostBusiness.Kind.UNKNOWN)) {
            guard.refused(null, "request", isEnforced ? "unknown_host" : "unknown_host_not_enforced", HostBusiness.UNKNOWN, RequestMeta.of(request).ipAddress());
            if (!isEnforced) { // observe only: audited above, the request goes on as before
                try {
                    chain.doFilter(request, response);
                } finally {
                    observe(request, host);
                }
                return;
            }
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            json.writeValue(response.getOutputStream(), Problems.of(HttpStatus.NOT_FOUND, Problems.NOT_FOUND, "Not found.", request.getRequestURI()));
            observe(request, host);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            observe(request, host);
        }
    }

    private void observe(HttpServletRequest request, HostBusiness host) {
        if (host == null || !request.getRequestURI().startsWith(PREFIX_SLASH)) return; // health checks and the like are not observed
        try {
            Object user = request.getAttribute(AuditTrail.USER_ATTR);
            reporter.observe(host, user instanceof CurrentUser currentUser ? currentUser : null);
        } catch (RuntimeException e) {
            log.debug("tenant host observation failed", e);
        }
    }
}
