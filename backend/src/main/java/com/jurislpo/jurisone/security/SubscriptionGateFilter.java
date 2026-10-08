package com.jurislpo.jurisone.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.business.SubscriptionService;
import com.jurislpo.jurisone.common.Problems;
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
 * A business can be used only while a subscription covers today. Without one, its users can still sign in and see
 * who they are, but every other business API answers 402 {@code SUBSCRIPTION_INACTIVE} — except the subscription
 * page and the owner's employee export, which stay available. The super admin (no business) is never blocked.
 */
@Component
public class SubscriptionGateFilter extends OncePerRequestFilter {

    public static final String MESSAGE = "Your organisation's subscription has ended. Please contact your administrator.";

    /** Still reachable without an active subscription. */
    static final List<String> OPEN = List.of("/api/auth/", "/api/me", "/api/shell", "/api/notifications", "/api/business/subscription",
            "/api/business/employees/export");

    private final SubscriptionService subscriptions;
    private final ObjectMapper json;

    public SubscriptionGateFilter(SubscriptionService subscriptions, ObjectMapper json) {
        this.subscriptions = subscriptions;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String uri = req.getRequestURI();
        return !uri.startsWith("/api/") || OPEN.stream().anyMatch(uri::startsWith);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        UUID business = TenantContext.current();
        if (business != null && !subscriptions.isActive(business)) {
            res.setStatus(HttpStatus.PAYMENT_REQUIRED.value());
            res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            json.writeValue(res.getOutputStream(), Problems.of(HttpStatus.PAYMENT_REQUIRED, "SUBSCRIPTION_INACTIVE", MESSAGE, req.getRequestURI()));
            return;
        }
        chain.doFilter(req, res);
    }
}
