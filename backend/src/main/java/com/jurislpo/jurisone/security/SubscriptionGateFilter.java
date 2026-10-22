package com.jurislpo.jurisone.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.service.SubscriptionService;
import com.jurislpo.jurisone.exception.Problems;
import com.jurislpo.jurisone.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A business can be used only while a subscription covers today.
 * <ul>
 *   <li>Not started yet, or never had one: sign-in is refused (see AuthService); a session that already exists gets 402
 *       {@code SUBSCRIPTION_INACTIVE} on every API except sign-out / refresh, who-am-I and the shell.</li>
 *   <li>Ended: read-only. Every read (and the exports) works; every write answers 402 {@code SUBSCRIPTION_INACTIVE},
 *       except signing out / refreshing the session and marking notifications read.</li>
 * </ul>
 * The super admin and the platform company (no subscription needed) are never blocked.
 */
@Component
public class SubscriptionGateFilter extends OncePerRequestFilter {

    public static final String MESSAGE = "Your organisation's subscription isn't active. Please contact your administrator.";
    public static final String READ_ONLY_MESSAGE = "Your organisation's subscription has ended. You can view and export your data but not change it. "
            + "Please contact your administrator to renew.";

    /** Reachable with no usable subscription at all (not started / none). */
    static final List<String> OPEN = List.of("/api/auth/", "/api/me", "/api/shell",
            "/api/public/onboardings/"); // a candidate link: the service checks that business itself
    /** Still writable once the subscription has ended. */
    static final List<String> WRITABLE_WHEN_ENDED = List.of("/api/auth/", "/api/notifications", "/api/audit/page-views"); // page views are only recorded

    private final SubscriptionService subscriptions;
    private final ObjectMapper json;

    public SubscriptionGateFilter(SubscriptionService subscriptions, ObjectMapper json) {
        this.subscriptions = subscriptions;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith("/api/") || OPEN.stream().anyMatch(uri::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        UUID business = TenantContext.current();
        if (business != null && !subscriptions.isActive(business)) {
            if (subscriptions.isReadOnly(business)) {
                if (isRead(request) || WRITABLE_WHEN_ENDED.stream().anyMatch(request.getRequestURI()::startsWith)) {
                    chain.doFilter(request, response);
                    return;
                }
                refuse(request, response, READ_ONLY_MESSAGE);
                return;
            }
            refuse(request, response, MESSAGE);
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isRead(HttpServletRequest request) {
        String method = request.getMethod();
        return "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.PAYMENT_REQUIRED.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), Problems.of(HttpStatus.PAYMENT_REQUIRED, "SUBSCRIPTION_INACTIVE", message, request.getRequestURI()));
    }
}
