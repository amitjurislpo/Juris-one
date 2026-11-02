package com.jurislpo.jurisone.security;

import static com.jurislpo.jurisone.config.ApiPaths.PREFIX_SLASH;

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
 * Per-business addresses, step 4 (OBSERVE only). With {@code jurisone.tenant-host.enabled=false} it does nothing. With it on it resolves the
 * request's host into a {@link HostBusiness} (request attribute {@link HostBusiness#REQUEST_ATTRIBUTE}) before {@link JwtAuthFilter}, lets the
 * request run exactly as before, and when the chain is done compares the host's business with the signed-in user's
 * ({@link TenantHostMismatchReporter}: counter and rate-limited WARN). It never blocks, rewrites or answers a request, never touches sign-in,
 * cookies, CORS or Origin handling, and any error of its own is logged at DEBUG and swallowed. Enforcement is a later step.
 *
 * <p>The comparison runs after the chain so that it also sees requests that a later filter refused (402, 403); the signed-in user is read
 * from the request attribute {@link AuditTrail#USER_ATTR} that {@link JwtAuthFilter} sets.
 */
@Component
public class TenantHostFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantHostFilter.class);

    private final boolean isEnabled;
    private final TenantHostResolver resolver;
    private final TenantHostMismatchReporter reporter;

    public TenantHostFilter(JurisOneProperties props, TenantHostResolver resolver, TenantHostMismatchReporter reporter) {
        this.isEnabled = props.tenantHost().enabled();
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
