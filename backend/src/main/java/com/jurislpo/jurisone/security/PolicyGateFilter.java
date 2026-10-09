package com.jurislpo.jurisone.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.exception.Problems;
import com.jurislpo.jurisone.service.PolicyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Until a user has accepted the current version of every published policy that needs it, every business API answers
 * 428 {@code POLICY_ACK_REQUIRED} — except signing in / out, who-am-I, the shell, notifications and reading and accepting
 * the policies themselves. Owners and the super admin are never asked.
 */
@Component
public class PolicyGateFilter extends OncePerRequestFilter {

    public static final String MESSAGE = "Please read and accept the company policies first.";

    static final List<String> OPEN = List.of("/api/auth/", "/api/me", "/api/shell", "/api/notifications", "/api/policies/pending", "/api/policies/mine",
            "/api/onboarding/"); // a candidate's link (checked by the service)
    static final Pattern OPEN_POLICY = Pattern.compile("^/api/policies/[^/]+/(acknowledgements|versions/\\d+/file)$");

    private final PolicyService policies;
    private final ObjectMapper json;

    public PolicyGateFilter(PolicyService policies, ObjectMapper json) {
        this.policies = policies;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        String uri = req.getRequestURI();
        return !uri.startsWith("/api/") || OPEN.stream().anyMatch(uri::startsWith) || OPEN_POLICY.matcher(uri).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a != null && a.getPrincipal() instanceof CurrentUser u && PolicyService.mustAcknowledge(u) && policies.hasPending(u.id())) {
            res.setStatus(HttpStatus.PRECONDITION_REQUIRED.value());
            res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            json.writeValue(res.getOutputStream(), Problems.of(HttpStatus.PRECONDITION_REQUIRED, "POLICY_ACK_REQUIRED", MESSAGE, req.getRequestURI()));
            return;
        }
        chain.doFilter(req, res);
    }
}
