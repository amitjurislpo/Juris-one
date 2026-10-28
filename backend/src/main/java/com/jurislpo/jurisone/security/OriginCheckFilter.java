package com.jurislpo.jurisone.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jurislpo.jurisone.config.JurisOneProperties;
import com.jurislpo.jurisone.exception.Problems;
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
 * CSRF guard (assertSameOrigin): a mutating /api request must carry an Origin whose host equals
 * the configured web origin ({@code jurisone.frontend-origin}); client-supplied Host headers are not trusted. Together with SameSite=Lax session cookies this is the app's CSRF defence.
 */
@Component
public class OriginCheckFilter extends OncePerRequestFilter {

    static final String MESSAGE = "Cross-site request blocked.";
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");

    private final ObjectMapper json;
    private final String allowedHost;

    public OriginCheckFilter(ObjectMapper json, JurisOneProperties props) {
        this.json = json;
        this.allowedHost = hostOf(props.frontendOrigin());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return SAFE.contains(request.getMethod()) || !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        String origin = request.getHeader("origin");
        if (origin == null || !allowedHost.equalsIgnoreCase(hostOf(origin))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            json.writeValue(response.getOutputStream(), Problems.of(HttpStatus.FORBIDDEN, "CROSS_SITE_BLOCKED", MESSAGE, request.getRequestURI()));
            return;
        }
        chain.doFilter(request, response);
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
