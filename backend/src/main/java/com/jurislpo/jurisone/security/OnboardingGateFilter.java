package com.jurislpo.jurisone.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.exception.Problems;
import com.jurislpo.jurisone.service.EmployeeOnboardingService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The hard lock of the onboarding: an employee whose onboarding is not finished (not started, in progress, waiting for HR or sent back)
 * gets 403 {@code onboarding-pending} on every business API. Open: signing in / out / refreshing, who-am-I, the shell, their own onboarding
 * (wizard, bank lookups, files, secrets, submission), reading their notifications, their own profile photo and the page-view log.
 * Employees without an onboarding row (old employees, owners) and the super admin are never locked.
 */
@Component
public class OnboardingGateFilter extends OncePerRequestFilter {

    public static final String TYPE = "onboarding-pending";
    public static final String MESSAGE = "Your onboarding is not complete yet. Finish it, or wait for HR to approve it, before using JurisOne.";

    /** Reachable while locked (prefixes; {@code /api/me} and {@code /api/shell} are exact). */
    static final List<String> OPEN_PREFIXES = List.of("/api/auth/", "/api/onboarding/", "/api/audit/page-views", "/api/profile/photo");
    static final List<String> OPEN_EXACT = List.of("/api/me", "/api/shell", "/api/notifications");

    private final EmployeeOnboardingService onboarding;
    private final ObjectMapper json;

    public OnboardingGateFilter(EmployeeOnboardingService onboarding, ObjectMapper json) {
        this.onboarding = onboarding;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (!uri.startsWith("/api/") || "OPTIONS".equals(request.getMethod())) return true;
        if (OPEN_PREFIXES.stream().anyMatch(uri::startsWith)) return true;
        // reading the notification box is open; changing it is not
        return OPEN_EXACT.contains(uri) && ("GET".equals(request.getMethod()) || !"/api/notifications".equals(uri));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser currentUser && !currentUser.isSuperAdmin() && currentUser.businessId() != null
                && onboarding.summary(currentUser.id()).isRequired()) {
            ProblemDetail problem = Problems.of(HttpStatus.FORBIDDEN, TYPE, MESSAGE, request.getRequestURI());
            problem.setType(URI.create(TYPE));
            problem.setTitle(TYPE);
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            json.writeValue(response.getOutputStream(), problem);
            return;
        }
        chain.doFilter(request, response);
    }
}
