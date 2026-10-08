package com.jurislpo.jurisone.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.common.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * CSRF guard (src/lib/api.ts assertSameOrigin): a mutating /api request must carry an Origin whose host equals
 * X-Forwarded-Host, or Host. Together with SameSite=Lax session cookies this is the app's CSRF defence.
 */
@Component
public class OriginCheckFilter extends OncePerRequestFilter {

    static final String MESSAGE = "Cross-site request blocked.";
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

    private final ObjectMapper json;

    public OriginCheckFilter(ObjectMapper json) {
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return SAFE.contains(req.getMethod()) || !req.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String origin = req.getHeader("origin");
        String host = req.getHeader("x-forwarded-host") != null ? req.getHeader("x-forwarded-host") : req.getHeader("host");
        if (origin == null || host == null || !host.equalsIgnoreCase(hostOf(origin))) {
            res.setStatus(HttpServletResponse.SC_FORBIDDEN);
            res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            json.writeValue(res.getOutputStream(), Problems.of(HttpStatus.FORBIDDEN, "CROSS_SITE_BLOCKED", MESSAGE, req.getRequestURI()));
            return;
        }
        chain.doFilter(req, res);
    }

    /** {@code new URL(origin).host}: host plus a non-default port. */
    static String hostOf(String origin) {
        try {
            URI u = URI.create(origin);
            if (u.getHost() == null) return "";
            int port = u.getPort();
            boolean dflt = port == -1 || ("http".equals(u.getScheme()) && port == 80) || ("https".equals(u.getScheme()) && port == 443);
            return dflt ? u.getHost() : u.getHost() + ":" + port;
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
