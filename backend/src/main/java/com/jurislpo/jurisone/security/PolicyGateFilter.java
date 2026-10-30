package com.jurislpo.jurisone.security;

import static com.jurislpo.jurisone.config.ApiPaths.PREFIX;
import static com.jurislpo.jurisone.config.ApiPaths.PREFIX_SLASH;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.exception.Problems;
import com.jurislpo.jurisone.service.PolicyService;
import com.jurislpo.jurisone.service.SubscriptionService;
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

    static final List<String> OPEN = List.of(PREFIX + "/auth/", PREFIX + "/me", PREFIX + "/shell", PREFIX + "/notifications", PREFIX + "/policies/pending", PREFIX + "/policies/mine", PREFIX + "/audit/page-views");
    static final Pattern OPEN_POLICY = Pattern.compile("^" + PREFIX + "/policies/[^/]+/(acknowledgements|versions/\\d+/file)$");

    private final PolicyService policies;
    private final SubscriptionService subscriptions;
    private final ObjectMapper json;

    public PolicyGateFilter(PolicyService policies, SubscriptionService subscriptions, ObjectMapper json) {
        this.policies = policies;
        this.subscriptions = subscriptions;
        this.json = json;
    }

    private boolean readOnly(CurrentUser user) {
        return user.businessId() != null && subscriptions.isReadOnly(user.businessId());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith(PREFIX_SLASH) || OPEN.stream().anyMatch(uri::startsWith) || OPEN_POLICY.matcher(uri).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        // accepting a policy is a write, so a business whose subscription has ended (read-only) is never asked
        if (authentication != null && authentication.getPrincipal() instanceof CurrentUser currentUser && PolicyService.mustAcknowledge(currentUser) && !readOnly(currentUser) && policies.hasPending(currentUser.id())) {
            response.setStatus(HttpStatus.PRECONDITION_REQUIRED.value());
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            json.writeValue(response.getOutputStream(), Problems.of(HttpStatus.PRECONDITION_REQUIRED, "POLICY_ACK_REQUIRED", MESSAGE, request.getRequestURI()));
            return;
        }
        chain.doFilter(request, response);
    }
}
